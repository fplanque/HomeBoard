package com.evofactory.homeboard

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Inet4Address
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Locale
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

private const val TAG = "HomeBoard"
private const val PEER_APP = "homeboard-peer"
private const val PEER_PORT = 47811
private const val ANNOUNCE_INTERVAL_MS = 1_000L
private const val PEER_TIMEOUT_MS = 3_500L
private const val LISTEN_TIMEOUT_MS = 250
private const val CONNECT_TIMEOUT_MS = 3_000
private const val REPLY_TIMEOUT_MS = 10_000
private const val RECONNECT_DELAY_MS = 1_000L
private const val MAX_HANDSHAKE_BYTES = 1024
private const val MAX_SETTINGS_BYTES = 4 * 1024 * 1024
private const val GONE = "gone"
private const val ACK_OK = "ok"
private const val ACK_BAD = "bad"

// Typing another device's address connects to it here, over TCP: an iPad can't broadcast without an
// entitlement Apple has to grant, and broadcasts don't cross networks. The next ports if it's taken.
private const val DIRECT_PORT_FIRST = 47811
private const val DIRECT_PORT_LAST = 47819

/** The TV exporting lists the TVs importing, and the other way round */
enum class PeerRole(val wire: String, val other: String) {
    Export("export", "import"),
    Import("import", "export"),
}

/** Another TV on the network with its export or import screen open */
data class Peer(val id: String, val name: String, val address: InetAddress, val port: Int, val choseUs: Boolean)

private fun tvName(context: Context) =
    Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)?.takeIf { it.isNotBlank() } ?: Build.MODEL

/** This TV's IPv4 addresses, for the other device to type */
private fun ownAddresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
}.getOrDefault(emptyList()).distinct()

/** The direct port, or the next free one; null if none is */
private fun openDirectServer(): ServerSocket? = (DIRECT_PORT_FIRST..DIRECT_PORT_LAST).firstNotNullOfOrNull { port ->
    runCatching {
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(port))
            soTimeout = LISTEN_TIMEOUT_MS
        }
    }.getOrNull()
}

/** "host" or "host:port", the direct port by default; null if it isn't one */
fun parseDeviceAddress(text: String): Pair<String, Int>? {
    val trimmed = text.trim()
    val host = trimmed.substringBefore(':')
    val port = if (':' in trimmed) trimmed.substringAfter(':').toIntOrNull() ?: return null else DIRECT_PORT_FIRST
    if (host.isEmpty() || host.any { it.isWhitespace() } || port !in 1..65535) return null
    return host to port
}

/** The other device has the same screen open: both export, or both import */
class SameModeException(val peerName: String) : IOException("Same mode")

/** Where this TV stands while typing another device's address */
data class DirectCallState(val address: String, val peerName: String? = null, val error: String? = null)

/** Every address that reaches the other TVs on the networks this TV is on */
private fun broadcastAddresses(): List<InetAddress> {
    val found = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { network -> network.interfaceAddresses.mapNotNull { it.broadcast } }
    }.getOrDefault(emptyList())
    return (found + InetAddress.getByAddress(byteArrayOf(-1, -1, -1, -1))).distinct()
}

/**
 * While an export or import screen is open: says so on the network every second and keeps [peers]
 * up to date, so TVs appear and leave as their screens open and close. Two TVs connect once each has
 * chosen the other. The TV importing only accepts a connection from the TV it chose.
 *
 * It also listens on the direct port, for a TV or iPad that types this TV's address: those show in
 * [requests] until accepted, and [callAddress] does the same the other way. Either way ends in [direct].
 */
class PeerLink(context: Context, private val role: PeerRole) {
    private val appContext = context.applicationContext
    private val id = UUID.randomUUID().toString()
    val name: String = tvName(appContext)

    var peers by mutableStateOf<List<Peer>>(emptyList())
        private set
    var chosenId by mutableStateOf<String?>(null)
    /** The connection from the chosen TV, on the importing side */
    var incoming by mutableStateOf<PeerSession?>(null)
        private set
    /** What the chosen TV sent, on the importing side */
    var received by mutableStateOf<SettingsFile?>(null)
        private set

    /** The port another device types after this TV's address, once listening; null if no port was free */
    var directPort by mutableStateOf<Int?>(null)
        private set
    /** This TV's IPv4 addresses, for the other device to type */
    val addresses: List<String> = ownAddresses()
    /** Devices that typed this TV's address, waiting to be accepted */
    var requests by mutableStateOf<List<PeerSession.DirectRequest>>(emptyList())
        private set
    /** Where it stands while this TV connects to a typed address */
    var call by mutableStateOf<DirectCallState?>(null)
        private set
    private var calling: PeerSession.DirectCall? = null
    /** A connection by address, once accepted on the other side or this one */
    var direct by mutableStateOf<PeerSession?>(null)
        private set

    private fun hello() = JSONObject().put("app", PEER_APP).put("id", id).put("name", name).put("mode", role.wire)

    /** The chosen TV, once it has chosen this one too */
    val connectedPeer: Peer? get() = peers.firstOrNull { it.id == chosenId && it.choseUs }

    /** Runs until cancelled */
    suspend fun run() = withContext(Dispatchers.IO) {
        // Without this, some Wi-Fi chips drop what's broadcast
        val wifi = appContext.getSystemService(WifiManager::class.java)
        val lock = wifi?.createMulticastLock("homeboard-peers")?.apply {
            setReferenceCounted(false)
            acquire()
        }
        val udp = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = LISTEN_TIMEOUT_MS
            bind(InetSocketAddress(PEER_PORT))
        }
        val server = ServerSocket(0).apply { soTimeout = LISTEN_TIMEOUT_MS }
        val directServer = openDirectServer()
        directPort = directServer?.localPort
        if (directServer == null) AppLog.w(TAG, "No free port for connections by address")
        try {
            if (directServer != null) launch { serveDirect(directServer) }
            if (role == PeerRole.Import) {
                // Settings come once a connection by address is accepted
                launch { snapshotFlow { direct }.collectLatest { session -> if (session != null) receiveDirect(session) } }
            }
            // Starts over when the choice changes, so the other TV hears of it right away
            launch {
                snapshotFlow { chosenId }.collectLatest {
                    while (true) {
                        announce(udp, role.wire, server.localPort)
                        delay(ANNOUNCE_INTERVAL_MS)
                    }
                }
            }
            if (role == PeerRole.Import) {
                launch { serve(server) }
                // Reading from the other TV blocks: closing is what ends it
                launch {
                    try {
                        awaitCancellation()
                    } finally {
                        incoming?.close()
                    }
                }
            }
            listen(udp)
        } finally {
            announce(udp, GONE, 0)
            udp.close()
            server.close()
            directServer?.close()
            requests.forEach { it.close() }
            calling?.close()
            direct?.close()
            lock?.release()
        }
    }

    private fun announce(udp: DatagramSocket, mode: String, port: Int) {
        val message = JSONObject()
            .put("app", PEER_APP)
            .put("id", id)
            .put("name", name)
            .put("mode", mode)
            .put("port", port)
            .put("chosen", chosenId.orEmpty())
            .toString()
            .toByteArray()
        broadcastAddresses().forEach { address ->
            runCatching { udp.send(DatagramPacket(message, message.size, address, PEER_PORT)) }
        }
    }

    private suspend fun listen(udp: DatagramSocket) {
        val heard = mutableMapOf<String, Pair<Peer, Long>>()
        val buffer = ByteArray(MAX_HANDSHAKE_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                val packet = DatagramPacket(buffer, buffer.size)
                udp.receive(packet)
                val json = runCatching { JSONObject(String(packet.data, 0, packet.length)) }.getOrNull()
                val peerId = json?.optString("id").orEmpty()
                if (json?.optString("app") == PEER_APP && peerId.isNotEmpty() && peerId != id) {
                    if (json.optString("mode") == role.other) {
                        heard[peerId] = Peer(
                            id = peerId,
                            name = json.optString("name").take(MAX_NAME_LENGTH).ifEmpty { "TV" },
                            address = packet.address,
                            port = json.optInt("port"),
                            choseUs = json.optString("chosen") == id,
                        ) to System.currentTimeMillis()
                    } else {
                        heard.remove(peerId)
                    }
                }
            } catch (_: SocketTimeoutException) {
                // Nothing heard: still time to drop TVs that went silent
            }
            val now = System.currentTimeMillis()
            heard.values.removeAll { (_, seen) -> now - seen > PEER_TIMEOUT_MS }
            peers = heard.values.map { it.first }.sortedWith(compareBy({ it.name.lowercase() }, { it.id }))
        }
    }

    private suspend fun serve(server: ServerSocket) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                // Closed with the screen: the end, not a failure
                if (server.isClosed) return else throw e
            }
            val peer = peers.firstOrNull { it.id == chosenId && it.address == socket.inetAddress }
            val session = peer?.let { runCatching { PeerSession.accept(socket, it) }.getOrNull() }
            if (session == null) {
                socket.close()
                continue
            }
            incoming = session
            try {
                currentCoroutineContext().ensureActive()
                val settings = settingsFromJson(String(session.receive()))
                session.acknowledge(settings != null)
                if (settings != null) received = settings
            } catch (e: IOException) {
                AppLog.i(TAG, "Connection to the other TV closed: ${e.javaClass.simpleName}")
            } finally {
                session.close()
                incoming = null
            }
        }
    }

    /** Answers each device that typed this TV's address, and lists it until accepted */
    private suspend fun serveDirect(server: ServerSocket) = coroutineScope {
        while (true) {
            currentCoroutineContext().ensureActive()
            val socket = try {
                server.accept()
            } catch (_: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                // Closed with the screen: the end, not a failure
                if (server.isClosed) return@coroutineScope else throw e
            }
            launch {
                val request = try {
                    PeerSession.answer(socket, hello(), role)
                } catch (e: IOException) {
                    AppLog.i(TAG, "Connection by address from ${socket.inetAddress.hostAddress} failed: ${e.javaClass.simpleName}")
                    socket.close()
                    null
                }
                if (request != null) {
                    AppLog.i(TAG, "${request.peer.name} at ${request.peer.address.hostAddress} wants to connect")
                    requests = requests.filterNot { it.peer.id == request.peer.id } + request
                }
            }
        }
    }

    /** Accepts a device that typed this TV's address. Blocks: from Dispatchers.IO. */
    fun accept(request: PeerSession.DirectRequest) {
        requests = requests - request
        direct?.close()
        direct = runCatching { request.accept() }
            .onFailure { AppLog.w(TAG, "Can't connect to ${request.peer.name}", it) }
            .getOrNull()
    }

    fun decline(request: PeerSession.DirectRequest) {
        requests = requests - request
        request.close()
    }

    /** Connects to a typed address and waits until the other device accepts. Blocks: from Dispatchers.IO. */
    fun callAddress(text: String) {
        val (host, port) = parseDeviceAddress(text) ?: run {
            call = DirectCallState(text, error = "That isn't an address: type it like 192.168.1.20, or 192.168.1.20:47812")
            return
        }
        calling?.close()
        call = DirectCallState(text)
        val attempt = try {
            PeerSession.call(host, port, hello(), role)
        } catch (e: SameModeException) {
            val other = if (role == PeerRole.Export) "Export settings" else "Import settings"
            val wanted = if (role == PeerRole.Export) "Import settings" else "Export settings"
            call = DirectCallState(text, e.peerName, "${e.peerName} has $other open too: open $wanted there")
            return
        } catch (e: IOException) {
            AppLog.w(TAG, "Can't connect to $text", e)
            call = DirectCallState(text, error = "Couldn't reach $text. Is HomeBoard's ${if (role == PeerRole.Export) "Import" else "Export"} settings open there?")
            return
        }
        calling = attempt
        call = DirectCallState(text, attempt.peer.name)
        try {
            val session = attempt.awaitAccept()
            direct?.close()
            direct = session
            call = null
        } catch (e: IOException) {
            // Cancelled here, or declined or closed there
            if (calling === attempt && call != null) call = DirectCallState(text, attempt.peer.name, "${attempt.peer.name} closed the connection")
        } finally {
            if (calling === attempt) calling = null
        }
    }

    fun cancelCall() {
        call = null
        calling?.close()
        calling = null
    }

    /** Ends the connection by address, e.g. when its dialog closes */
    fun closeDirect() {
        direct?.close()
        direct = null
    }

    private fun receiveDirect(session: PeerSession) {
        try {
            val settings = settingsFromJson(String(session.receive()))
            session.acknowledge(settings != null)
            if (settings != null) received = settings
        } catch (e: IOException) {
            AppLog.i(TAG, "Connection to ${session.peer.name} closed: ${e.javaClass.simpleName}")
        } finally {
            session.close()
            if (direct === session) direct = null
        }
    }

    /** From the TV exporting to the TV importing */
    fun connect(peer: Peer): PeerSession = PeerSession.connect(id, peer)

    companion object {
        const val MAX_NAME_LENGTH = 60
    }
}

/**
 * One encrypted connection between two TVs. A key is agreed for this connection only, with
 * nothing sent readable. [code] comes from the key, and both TVs only show the same one if nobody
 * sits in between.
 */
class PeerSession private constructor(
    private val socket: Socket,
    private val key: SecretKey,
    val code: String,
    val peer: Peer,
) : Closeable {
    private val input = DataInputStream(socket.getInputStream())
    private val output = DataOutputStream(socket.getOutputStream())

    /** True once the other TV has read the settings */
    fun send(settings: String): Boolean {
        writeFrame(output, seal(settings.toByteArray(), "settings"))
        return String(open(readFrame(input, MAX_HANDSHAKE_BYTES), "ack")) == ACK_OK
    }

    /** Waits for the settings, however long the other TV takes to choose them */
    fun receive(): ByteArray {
        socket.soTimeout = 0
        val settings = open(readFrame(input, MAX_SETTINGS_BYTES), "settings")
        socket.soTimeout = REPLY_TIMEOUT_MS
        return settings
    }

    fun acknowledge(ok: Boolean) = writeFrame(output, seal((if (ok) ACK_OK else ACK_BAD).toByteArray(), "ack"))

    override fun close() {
        runCatching { socket.close() }
    }

    private fun seal(plain: ByteArray, label: String): ByteArray {
        val nonce = ByteArray(NONCE_BYTES).also { SecureRandom().nextBytes(it) }
        val cipher = cipher(Cipher.ENCRYPT_MODE, nonce, label)
        return nonce + cipher.doFinal(plain)
    }

    private fun open(sealed: ByteArray, label: String): ByteArray = try {
        cipher(Cipher.DECRYPT_MODE, sealed.copyOf(NONCE_BYTES), label).doFinal(sealed, NONCE_BYTES, sealed.size - NONCE_BYTES)
    } catch (e: GeneralSecurityException) {
        throw IOException("Can't decrypt", e)
    }

    private fun cipher(mode: Int, nonce: ByteArray, label: String) = Cipher.getInstance("AES/GCM/NoPadding").apply {
        init(mode, key, GCMParameterSpec(TAG_BITS, nonce))
        updateAAD(label.toByteArray())
    }

    /** This TV typed the other device's address, which hasn't accepted yet */
    class DirectCall internal constructor(
        private val socket: Socket,
        private val input: DataInputStream,
        private val keys: java.security.KeyPair,
        val peer: Peer,
        private val role: PeerRole,
    ) : Closeable {
        /** Waits as long as the other device's user takes; closing ends it */
        fun awaitAccept(): PeerSession {
            socket.soTimeout = 0
            val theirKey = readFrame(input, MAX_HANDSHAKE_BYTES)
            socket.soTimeout = REPLY_TIMEOUT_MS
            return deriveByRole(socket, keys, theirKey, role, peer)
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    /** A device that typed this TV's address, waiting for this TV's user to accept */
    class DirectRequest internal constructor(
        private val socket: Socket,
        val peer: Peer,
        private val theirKey: ByteArray,
        private val role: PeerRole,
    ) : Closeable {
        fun accept(): PeerSession {
            val keys = newKeys()
            writeFrame(DataOutputStream(socket.getOutputStream()), keys.public.encoded)
            return deriveByRole(socket, keys, theirKey, role, peer)
        }

        override fun close() {
            runCatching { socket.close() }
        }
    }

    companion object {
        private const val NONCE_BYTES = 12
        private const val TAG_BITS = 128

        fun connect(ownId: String, peer: Peer): PeerSession {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(peer.address, peer.port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = REPLY_TIMEOUT_MS
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                val keys = newKeys()
                writeFrame(output, ownId.toByteArray())
                writeFrame(output, keys.public.encoded)
                val theirKey = readFrame(input, MAX_HANDSHAKE_BYTES)
                return derive(socket, keys.private, theirKey, keys.public.encoded, theirKey, peer)
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }

        private class Hello(val id: String, val name: String, val mode: String)

        /** Null if it isn't HomeBoard */
        private fun readHello(input: DataInputStream): Hello? {
            val json = runCatching { JSONObject(String(readFrame(input, MAX_HANDSHAKE_BYTES))) }.getOrNull() ?: return null
            val id = json.optString("id")
            if (json.optString("app") != PEER_APP || id.isEmpty()) return null
            return Hello(id, json.optString("name").take(PeerLink.MAX_NAME_LENGTH).ifEmpty { "TV" }, json.optString("mode"))
        }

        /**
         * Typing another device's address: sends this side's hello and key, and reads the other's hello.
         * Its key only comes once its user accepts, see [DirectCall.awaitAccept].
         */
        fun call(host: String, port: Int, hello: JSONObject, role: PeerRole): DirectCall {
            val socket = Socket()
            try {
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
                socket.soTimeout = REPLY_TIMEOUT_MS
                val input = DataInputStream(socket.getInputStream())
                val output = DataOutputStream(socket.getOutputStream())
                val keys = newKeys()
                writeFrame(output, hello.toString().toByteArray())
                writeFrame(output, keys.public.encoded)
                val theirs = readHello(input) ?: throw IOException("Not HomeBoard")
                if (theirs.mode != role.other) throw SameModeException(theirs.name)
                val peer = Peer(theirs.id, theirs.name, socket.inetAddress, port, choseUs = true)
                return DirectCall(socket, input, keys, peer, role)
            } catch (e: Exception) {
                socket.close()
                throw e
            }
        }

        /** A device typed this TV's address: reads its hello and key and answers with this hello. Null if it isn't HomeBoard or has the same screen open. */
        fun answer(socket: Socket, hello: JSONObject, role: PeerRole): DirectRequest? {
            socket.soTimeout = REPLY_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val theirs = readHello(input)
            if (theirs == null) {
                socket.close()
                return null
            }
            val theirKey = readFrame(input, MAX_HANDSHAKE_BYTES)
            writeFrame(output, hello.toString().toByteArray())
            if (theirs.mode != role.other) {
                socket.close()
                return null
            }
            return DirectRequest(socket, Peer(theirs.id, theirs.name, socket.inetAddress, socket.port, choseUs = true), theirKey, role)
        }

        /** Exporter and importer by role, whoever typed the address */
        private fun deriveByRole(socket: Socket, keys: java.security.KeyPair, theirKey: ByteArray, role: PeerRole, peer: Peer) =
            if (role == PeerRole.Export) derive(socket, keys.private, theirKey, keys.public.encoded, theirKey, peer)
            else derive(socket, keys.private, theirKey, theirKey, keys.public.encoded, peer)

        /** Null if it isn't the TV chosen */
        fun accept(socket: Socket, peer: Peer): PeerSession? {
            socket.soTimeout = REPLY_TIMEOUT_MS
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            val theirId = String(readFrame(input, MAX_HANDSHAKE_BYTES))
            val theirKey = readFrame(input, MAX_HANDSHAKE_BYTES)
            if (theirId != peer.id) return null
            val keys = newKeys()
            writeFrame(output, keys.public.encoded)
            return derive(socket, keys.private, theirKey, theirKey, keys.public.encoded, peer)
        }

        private fun newKeys() = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()

        private fun derive(
            socket: Socket,
            ownPrivate: PrivateKey,
            theirPublic: ByteArray,
            exporterPublic: ByteArray,
            importerPublic: ByteArray,
            peer: Peer,
        ): PeerSession {
            val secret = KeyAgreement.getInstance("ECDH").run {
                init(ownPrivate)
                doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(theirPublic)), true)
                generateSecret()
            }
            fun digest(label: String) = MessageDigest.getInstance("SHA-256").run {
                update("$PEER_APP:$label".toByteArray())
                update(secret)
                update(exporterPublic)
                digest(importerPublic)
            }
            val code = (ByteBuffer.wrap(digest("code")).int and Int.MAX_VALUE) % 10_000
            return PeerSession(socket, SecretKeySpec(digest("key"), "AES"), "%04d".format(Locale.ROOT, code), peer)
        }
    }
}

private fun writeFrame(output: DataOutputStream, bytes: ByteArray) {
    output.writeInt(bytes.size)
    output.write(bytes)
    output.flush()
}

private fun readFrame(input: DataInputStream, maxBytes: Int): ByteArray {
    val size = input.readInt()
    if (size !in 0..maxBytes) throw IOException("Bad frame size")
    return ByteArray(size).also { input.readFully(it) }
}

@Composable
private fun rememberPeerLink(role: PeerRole): PeerLink {
    val context = LocalContext.current
    val link = remember { PeerLink(context, role) }
    LaunchedEffect(link) {
        try {
            link.run()
        } catch (e: IOException) {
            AppLog.w(TAG, "Can't look for other TVs: ${e.javaClass.simpleName}")
            Toast.makeText(context, "Can't look for other TVs on the network", Toast.LENGTH_LONG).show()
        }
    }
    return link
}

/** This TV's address as the other device types it: the port only when it isn't the usual one */
private fun PeerLink.ownAddressText(): String? {
    val port = directPort ?: return null
    val suffix = if (port == DIRECT_PORT_FIRST) "" else ":$port"
    return addresses.joinToString("  or  ") { it + suffix }.ifEmpty { null }
}

/**
 * The other TVs in the opposite mode, changing as they come and go. Pressing one chooses it. Above
 * them, devices that typed this TV's address, and this TV's own attempt at a typed address.
 */
@Composable
private fun PeerList(
    title: String,
    link: PeerLink,
    hint: String,
    fileLabel: String,
    onFile: () -> Unit,
    onDismiss: () -> Unit,
) {
    val peers = link.peers
    val requests = link.requests
    val call = link.call
    val keys = listOfNotNull(CALL_ROW.takeIf { call != null }) + requests.map { REQUEST_ROW + it.peer.id } + peers.map { it.id }
    val rows = remember { ListFocus() }
    val fileButton = remember { FocusRequester() }
    val close = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var typing by remember { mutableStateOf(false) }
    val ownAddress = link.ownAddressText()

    TopDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = listOf(
            DialogButton("Connect by address") { typing = true },
            DialogButton(fileLabel, focus = fileButton, onClick = onFile),
        ),
        contentFocus = { rows.currentOrNull(keys) },
        closeFocus = close,
    ) {
        // In the content: the top row has no room left for it with two buttons
        Text(
            if (ownAddress != null) "This TV is ${link.name}, at $ownAddress" else "This TV is ${link.name}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 4.dp),
        )
        if (keys.isEmpty()) {
            Text(
                hint + if (ownAddress != null) " From an iPad, or a TV on another network, type this TV's address there, or choose Connect by address." else "",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(12.dp),
            )
        }
        LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp)) {
            if (call != null) {
                item(key = CALL_ROW) {
                    ListItem(
                        selected = false,
                        onClick = link::cancelCall,
                        headlineContent = { Text(call.peerName ?: call.address) },
                        supportingContent = {
                            Text(
                                when {
                                    call.error != null -> call.error
                                    call.peerName != null -> "Waiting for ${call.peerName} to accept. Select to cancel."
                                    else -> "Connecting to ${call.address}… Select to cancel."
                                },
                            )
                        },
                        leadingContent = { MdiIcon(if (call.error != null) "mdi:alert-circle-outline" else "mdi:lan-pending", size = 24.dp) },
                        modifier = rows.row(CALL_ROW).ringToTop(),
                    )
                }
            }
            items(requests, key = { REQUEST_ROW + it.peer.id }) { request ->
                ListItem(
                    selected = false,
                    onClick = { scope.launch(Dispatchers.IO) { link.accept(request) } },
                    headlineContent = { Text(request.peer.name) },
                    supportingContent = { Text("${request.peer.address.hostAddress} wants to connect. Select it to accept.") },
                    leadingContent = { MdiIcon("mdi:lan-connect", size = 24.dp) },
                    modifier = rows.row(REQUEST_ROW + request.peer.id).ringToTop(),
                )
            }
            items(peers, key = { it.id }) { peer ->
                val chosen = peer.id == link.chosenId
                ListItem(
                    selected = false,
                    onClick = { link.chosenId = if (chosen) null else peer.id },
                    headlineContent = { Text(peer.name) },
                    supportingContent = {
                        Text(
                            when {
                                chosen -> "Waiting for this TV to select ${link.name}"
                                peer.choseUs -> "Wants to connect. Select it to accept."
                                else -> peer.address.hostAddress.orEmpty()
                            },
                        )
                    },
                    leadingContent = { MdiIcon(if (chosen) "mdi:check" else "mdi:television", size = 24.dp) },
                    modifier = rows.row(peer.id).ringToTop(),
                )
            }
        }
        // To the list as soon as there's something in it, and off it when the last one leaves
        LaunchedEffect(keys.isEmpty()) {
            runCatching { if (keys.isEmpty()) close.requestFocus() else rows.current(keys).requestFocus() }
        }
    }
    if (typing) {
        AddressDialog(
            onConnect = { address ->
                typing = false
                scope.launch(Dispatchers.IO) { link.callAddress(address) }
            },
            onDismiss = { typing = false },
        )
    }
}

private const val CALL_ROW = "call"
private const val REQUEST_ROW = "request:"

/** The other device's address, as it shows it: e.g. 10.77.77.20, or 10.77.77.20:47812 */
@Composable
private fun AddressDialog(onConnect: (String) -> Unit, onDismiss: () -> Unit) {
    var address by remember { mutableStateOf("") }
    val field = remember { FocusRequester() }
    val valid = parseDeviceAddress(address) != null
    TopDialog(
        title = "Connect by address",
        onDismiss = onDismiss,
        buttons = listOf(DialogButton("Connect", enabled = valid, primary = true) { onConnect(address.trim()) }),
        contentFocus = { field },
        hint = "The other TV or iPad shows its address on its Export or Import settings screen",
    ) {
        TvTextField(
            value = address,
            onValueChange = { address = it },
            placeholder = "IP address, e.g. 10.77.77.20",
            keyboardType = KeyboardType.Uri,
            imeAction = ImeAction.Go,
            onImeAction = { if (valid) onConnect(address.trim()) },
            modifier = Modifier.fillMaxWidth().padding(12.dp).focusRequester(field).ringToTop(),
        )
        LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
    }
}

@Composable
private fun CodeLine(session: PeerSession) {
    Text(
        "Both screens should show the code ${session.code}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(12.dp),
    )
}

/** Choose a TV in import mode, then what to push to it. */
@Composable
fun ExportToTv(file: SettingsFile, onSaveToFile: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val link = rememberPeerLink(PeerRole.Export)
    val peer = link.connectedPeer
    var session by remember { mutableStateOf<PeerSession?>(null) }
    var sending by remember { mutableStateOf(false) }

    LaunchedEffect(peer?.id) {
        if (peer == null) return@LaunchedEffect
        var connected: PeerSession? = null
        try {
            while (connected == null) {
                connected = withContext(Dispatchers.IO) { runCatching { link.connect(peer) }.onFailure { AppLog.w(TAG, "Can't connect to the other TV", it) }.getOrNull() }
                if (connected == null) delay(RECONNECT_DELAY_MS)
            }
            session = connected
            awaitCancellation()
        } finally {
            connected?.close()
            session = null
        }
    }

    val current = session ?: link.direct
    if (current == null) {
        PeerList(
            title = "Export settings",
            link = link,
            hint = "Looking for other TVs. Open HomeBoard settings > Import settings on the other TV and it appears here.",
            fileLabel = "Save to a file",
            onFile = onSaveToFile,
            onDismiss = onDismiss,
        )
    } else {
        ChecklistDialog(
            title = "Send to ${current.peer.name}",
            confirmLabel = "Send",
            file = file,
            onConfirm = { chosen ->
                if (!sending) {
                    sending = true
                    scope.launch {
                        val sent = withContext(Dispatchers.IO) { runCatching { current.send(chosen.toJson()) }.onFailure { AppLog.w(TAG, "Can't send to the other TV", it) }.getOrDefault(false) }
                        sending = false
                        if (sent) {
                            Toast.makeText(context, "Settings sent to ${current.peer.name}", Toast.LENGTH_LONG).show()
                            onDismiss()
                        } else {
                            Toast.makeText(context, "Couldn't send the settings to ${current.peer.name}", Toast.LENGTH_LONG).show()
                            link.chosenId = null
                            withContext(Dispatchers.IO) { link.closeDirect() }
                        }
                    }
                }
            },
            onDismiss = {
                link.chosenId = null
                scope.launch(Dispatchers.IO) { link.closeDirect() }
            },
            above = { CodeLine(current) },
        )
    }
}

/** Choose a TV in export mode, then wait for what it pushes. */
@Composable
fun ImportFromTv(onImport: (SettingsFile) -> Unit, onOpenFile: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val link = rememberPeerLink(PeerRole.Import)
    val peer = link.connectedPeer
    val session = link.incoming ?: link.direct
    val scope = rememberCoroutineScope()

    // The other TV left or changed its mind
    LaunchedEffect(peer?.id) {
        if (peer == null) withContext(Dispatchers.IO) { link.incoming?.close() }
    }
    LaunchedEffect(link.received) {
        link.received?.let {
            onImport(it)
            Toast.makeText(context, "Settings imported", Toast.LENGTH_LONG).show()
            onDismiss()
        }
    }

    if (session == null) {
        PeerList(
            title = "Import settings",
            link = link,
            hint = "Looking for other TVs. Open HomeBoard settings > Export settings on the other TV and it appears here.",
            fileLabel = "Open a file",
            onFile = onOpenFile,
            onDismiss = onDismiss,
        )
    } else {
        val cancel = remember { FocusRequester() }
        TopDialog(
            title = "Import from ${session.peer.name}",
            onDismiss = {
                link.chosenId = null
                scope.launch(Dispatchers.IO) { link.closeDirect() }
            },
            closeFocus = cancel,
        ) {
            CodeLine(session)
            Text(
                "Waiting for ${session.peer.name} to choose what to send.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
            )
            LaunchedEffect(Unit) { runCatching { cancel.requestFocus() } }
        }
        // Cancelling here closes the connection, which the sending TV sees through its list
        LaunchedEffect(link.chosenId) {
            if (session !== link.direct && link.chosenId != session.peer.id) withContext(Dispatchers.IO) { session.close() }
        }
    }
}
