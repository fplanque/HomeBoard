package com.evofactory.homeboard

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetSocketAddress
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

private const val TAG = "Network"
private const val DETAILS_REFRESH_MS = 1_000L
private const val NO_SIGNAL_RSSI = -127
private const val LOOPBACK = "127.0.0.1"
private const val ADB_PROBE_TIMEOUT_MS = 500
private const val ADB_PROBE_INTERVAL_MS = 5_000L
private const val WIRELESS_DEBUGGING_SERVICE = "_adb-tls-connect._tcp"

enum class NetworkKind(val label: String, val icon: String, val brokenIcon: String) {
    Ethernet("Ethernet", "ethernet", "ethernet-off"),
    Wifi("Wi-Fi", "wifi", "wifi-off"),
    Other("Network", "lan", "lan-disconnect"),
}

/**
 * The TV's connection. [problem] says why it can't be used: on the link ([linkUp] false) or with its
 * addresses. Without a network, [kind] is the last one used.
 */
data class NetworkStatus(
    val kind: NetworkKind,
    val problem: String?,
    val linkUp: Boolean,
    val link: List<Pair<String, String>>,
    val addresses: List<Pair<String, String>>,
)

/** This network's IPv4 addresses, including a self-assigned one */
fun ConnectivityManager.ipv4Addresses(network: Network): List<Inet4Address> =
    getLinkProperties(network)?.linkAddresses.orEmpty()
        .map { it.address }
        .filterIsInstance<Inet4Address>()
        .filter { !it.isLoopbackAddress && !it.isAnyLocalAddress }

/**
 * Asks Android for the default network. While Android blocks this app's network access (Doze, in
 * standby) it answers that there's none, so only for while the launcher is on screen.
 */
fun Context.readNetworkStatus(): NetworkStatus {
    val connectivity = getSystemService(ConnectivityManager::class.java)
    val network = connectivity.activeNetwork
    return networkStatus(network?.let(connectivity::getNetworkCapabilities), network?.let(connectivity::getLinkProperties))
}

/** The default network's status from what Android says about it; without [capabilities] or [link], there's none */
fun Context.networkStatus(capabilities: NetworkCapabilities?, link: LinkProperties?): NetworkStatus {
    val prefs = LauncherPrefs(this)
    if (capabilities == null || link == null) return noNetworkStatus(prefs)

    val kind = when {
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkKind.Ethernet
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkKind.Wifi
        else -> NetworkKind.Other
    }
    if (prefs.lastNetworkKind != kind.name) prefs.lastNetworkKind = kind.name

    val ipv4 = link.linkAddresses.filter { it.address is Inet4Address && !it.address.isLoopbackAddress }
    val usable = ipv4.firstOrNull { !it.address.isLinkLocalAddress }
    val ipv6 = link.linkAddresses.map { it.address }.filterIsInstance<Inet6Address>()
        .firstOrNull { !it.isLinkLocalAddress && !it.isLoopbackAddress }
    val problem = when {
        usable != null || ipv6 != null -> null
        ipv4.isNotEmpty() -> "Self-assigned address: no answer from DHCP"
        else -> "Connected, but no IP address"
    }
    val shown = usable ?: ipv4.firstOrNull()
    val linkDetails = buildList {
        // The interface name is the vendor's choice (eth0, eth1, a USB adapter's), and doesn't decide the type
        link.interfaceName?.let { add("Interface" to it) }
        if (kind == NetworkKind.Wifi) addAll(wifiDetails())
    }
    val addresses = buildList {
        add("IP address" to (shown?.address?.hostAddress ?: "None"))
        shown?.let { add("Subnet mask" to subnetMask(it.prefixLength)) }
        gateway(link)?.let { add("Gateway" to it) }
        link.dnsServers.mapNotNull { it.hostAddress }.takeIf { it.isNotEmpty() }?.let { add("DNS servers" to it.joinToString(", ")) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            link.dhcpServerAddress?.hostAddress?.let { add("DHCP server" to it) }
        }
        ipv6?.hostAddress?.let { add("IPv6 address" to it) }
        val internet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        add("Internet" to if (internet) "Yes" else "Not confirmed")
    }
    return NetworkStatus(kind, problem, linkUp = true, linkDetails, addresses)
}

/** Android doesn't say whether a cable is plugged in, and Ethernet without an address looks the same */
private fun Context.noNetworkStatus(prefs: LauncherPrefs): NetworkStatus {
    val wifiOn = getSystemService(WifiManager::class.java)?.isWifiEnabled == true
    val kind = NetworkKind.entries.firstOrNull { it.name == prefs.lastNetworkKind } ?: NetworkKind.Other
    val problem = when {
        kind == NetworkKind.Ethernet -> "No cable connected, or no answer from the network"
        kind == NetworkKind.Wifi && !wifiOn -> "Wi-Fi is off"
        kind == NetworkKind.Wifi -> "Not connected to a Wi-Fi network"
        else -> "Not connected to a network"
    }
    return NetworkStatus(kind, problem, linkUp = false, listOf("Wi-Fi" to if (wifiOn) "On" else "Off"), emptyList())
}

/** HomeBoard's version, its build number (the version code) and when it was built */
private fun Context.buildText(): String {
    val built = Date(BuildConfig.BUILD_TIME)
    val time = "${DateFormat.getMediumDateFormat(this).format(built)} ${DateFormat.getTimeFormat(this).format(built)}"
    return "HomeBoard ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}), built $time"
}

/** When the TV last started: now less the time since boot, which includes time in standby */
private fun bootTimeMillis(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

private fun Context.bootTimeText(now: Long): String {
    val locale = Locale.getDefault()
    val skeleton = if (DateFormat.is24HourFormat(this)) "EEEdMMMHHmm" else "EEEdMMMhmma"
    val format = SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale)
    val boot = bootTimeMillis()
    return "${format.format(Date(boot))}, ${elapsedText(now - boot)} ago"
}

/** Android hides the network's name from apps without the location permission */
@Suppress("DEPRECATION")
private fun Context.wifiDetails(): List<Pair<String, String>> {
    val info = getSystemService(WifiManager::class.java)?.connectionInfo ?: return emptyList()
    return buildList {
        if (info.rssi > NO_SIGNAL_RSSI) add("Signal" to "${info.rssi} dBm")
        if (info.linkSpeed > 0) add("Link speed" to "${info.linkSpeed} Mbps")
        if (info.frequency > 0) add("Frequency" to "${info.frequency} MHz")
    }
}

private fun gateway(link: LinkProperties): String? =
    link.routes.filter { it.isDefaultRoute && it.hasGateway() }
        .let { routes -> routes.firstOrNull { it.gateway is Inet4Address } ?: routes.firstOrNull() }
        ?.gateway?.hostAddress

private fun subnetMask(prefixLength: Int): String {
    val mask = if (prefixLength == 0) 0 else -1 shl (32 - prefixLength)
    return listOf(24, 16, 8, 0).joinToString(".") { (mask ushr it and 255).toString() }
}

/**
 * Top bar button: Ethernet or Wi-Fi, crossed out and in red when the TV has no network or no address.
 * No internet doesn't count. Follows changes live, also in the background, where it logs them.
 */
@Composable
fun NetworkButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var status by remember { mutableStateOf(context.readNetworkStatus()) }
    var focused by remember { mutableStateOf(false) }
    DisposableEffect(context) {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        // Only from what the callbacks bring. Asking Android instead answers "no network" while it blocks this app's
        // network access, as in Doze, and nothing calls back when that ends: in standby, the Kitchen TV stayed
        // crossed out from 06:19 to 16:55 with Ethernet up all along (2026-11-06)
        val callback = object : ConnectivityManager.NetworkCallback() {
            private var network: Network? = null
            private var capabilities: NetworkCapabilities? = null
            private var link: LinkProperties? = null
            private var blocked: Boolean? = null

            private fun update() {
                val new = context.networkStatus(capabilities, link)
                if (new.kind != status.kind || new.problem != status.problem) {
                    AppLog.i(TAG, "${new.kind.label}: ${new.problem ?: "connected"}")
                }
                status = new
            }

            // Its capabilities and link properties follow at once
            override fun onAvailable(network: Network) {
                if (network == this.network) return
                this.network = network
                capabilities = null
                link = null
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                if (network != this.network) return
                this.capabilities = capabilities
                if (link != null) update()
            }

            override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) {
                if (network != this.network) return
                this.link = link
                if (capabilities != null) update()
            }

            override fun onLost(network: Network) {
                if (network != this.network) return
                this.network = null
                capabilities = null
                link = null
                update()
            }

            // Android 10+. The network is still there: only this app can't use it for now
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) {
                if (blocked != this.blocked && (blocked || this.blocked != null)) {
                    AppLog.i(TAG, if (blocked) "Android blocks HomeBoard's network access (e.g. Doze in standby)" else "Network access unblocked")
                }
                this.blocked = blocked
            }
        }
        connectivity.registerDefaultNetworkCallback(callback, Handler(Looper.getMainLooper()))
        onDispose { connectivity.unregisterNetworkCallback(callback) }
    }
    val broken = status.problem != null
    val name = if (broken) "${status.kind.label}: no connection" else status.kind.label
    val colors = MaterialTheme.colorScheme
    Box(modifier.size(SettingsButtonSize)) {
        Surface(
            onClick = onClick,
            modifier = Modifier
                .fillMaxSize()
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = name },
            shape = ClickableSurfaceDefaults.shape(CircleShape),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = colors.surfaceVariant,
                contentColor = if (broken) ErrorColor else colors.onSurfaceVariant,
                focusedContainerColor = colors.onSurface,
                focusedContentColor = colors.inverseOnSurface,
            ),
        ) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MdiIcon(if (broken) status.kind.brokenIcon else status.kind.icon, color = LocalContentColor.current)
            }
        }
        if (focused) TopBarButtonLabel(name)
    }
}

/** Whether something answers on this port of the TV itself */
private fun isPortOpen(port: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress(LOOPBACK, port), ADB_PROBE_TIMEOUT_MS) }
    true
} catch (e: IOException) {
    false
}

/**
 * A system property, through the `getprop` command. Empty both when it isn't set and when Android
 * hides it from apps; null if `getprop` couldn't run.
 */
private fun systemProperty(name: String): String? = try {
    val process = ProcessBuilder("getprop", name).redirectErrorStream(true).start()
    process.inputStream.bufferedReader().use { it.readText().trim() }.also { process.waitFor() }
} catch (e: IOException) {
    AppLog.w(TAG, "Couldn't read $name", e)
    null
}

private fun propertyText(value: String?): String = when {
    value == null -> "Couldn't read"
    value.isEmpty() -> "Empty"
    else -> value
}

private val refusedSettings: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

/**
 * A setting from Settings.Global, or Settings.Secure if it isn't there. Android 12+ refuses most
 * settings it doesn't document to apps, which reads as "Couldn't read".
 */
private fun Context.settingText(name: String): String {
    var refused = false
    fun read(get: () -> String?): String? = try {
        get()
    } catch (e: SecurityException) {
        // Read every few seconds while the screen is open: log it once
        if (refusedSettings.add(name)) AppLog.w(TAG, "Couldn't read setting $name: ${e.message}")
        refused = true
        null
    }
    return read { Settings.Global.getString(contentResolver, name) }
        ?: read { Settings.Secure.getString(contentResolver, name) }
        ?: if (refused) "Couldn't read" else "Not set"
}

/**
 * Sony names the TV's Bluetooth after its model number (e.g. KD-75XE9005); the user can rename it.
 * Android 14 refuses it to apps targeting SDK 32 or later, like this one; Android 9 allows it.
 */
private const val BLUETOOTH_NAME_SETTING = "bluetooth_name"

/** Netflix's device group, which includes the model year on Sony TVs (e.g. SONYANDROIDTV2017) */
private const val NETFLIX_MODEL_GROUP = "ro.vendor.nrdp.modelgroup"

/** The model number on newer Sony TVs (e.g. K-77XR80); the Living Room TV's Android 9 doesn't have it */
private const val HARDWARE_SKU = "ro.boot.product.hardware.sku"

private class TvInfo(val bluetoothName: String, val netflixModelGroup: String?, val hardwareSku: String?)

private fun Context.readTvInfo() = TvInfo(
    bluetoothName = settingText(BLUETOOTH_NAME_SETTING),
    netflixModelGroup = systemProperty(NETFLIX_MODEL_GROUP)?.takeIf { it.isNotEmpty() },
    hardwareSku = systemProperty(HARDWARE_SKU)?.takeIf { it.isNotEmpty() },
)

/** `adb tcpip` sets the first two; wireless debugging the last two (Android 11+) */
private const val ADB_TCP_PORT = "service.adb.tcp.port"
private const val ADB_TCP_PORT_PERSIST = "persist.adb.tcp.port"
private const val ADB_TLS_PORT = "service.adb.tls.port"
private const val ADB_WIFI_SETTING = "adb_wifi_enabled"

/** adb's properties as read, and whether each port they name answers */
private class AdbInfo(
    val tcpPort: String?,
    val tcpPortPersist: String?,
    val tlsPort: String?,
    val wifiEnabled: String,
    val answering: Map<Int, Boolean>,
)

private fun Context.readAdbInfo(): AdbInfo {
    val tcp = systemProperty(ADB_TCP_PORT)
    val persist = systemProperty(ADB_TCP_PORT_PERSIST)
    val tls = systemProperty(ADB_TLS_PORT)
    val ports = listOf(tcp, persist, tls).mapNotNull { it?.toIntOrNull() }.filter { it > 0 }.distinct()
    return AdbInfo(tcp, persist, tls, settingText(ADB_WIFI_SETTING), ports.associateWith(::isPortOpen))
}

/** A port property's value, and whether that port answers */
private fun AdbInfo.portText(value: String?): String {
    val port = value?.toIntOrNull()?.takeIf { it > 0 } ?: return propertyText(value)
    return when (answering[port]) {
        true -> "$port, answering"
        false -> "$port, not answering"
        null -> "$port"
    }
}

/**
 * The port wireless debugging is on right now, which changes each time. Android 11+ announces it on
 * the local network, along with every other TV's: only this TV's own addresses count.
 */
@Composable
private fun wirelessDebuggingPort(): Int? {
    val context = LocalContext.current
    var port by remember { mutableStateOf<Int?>(null) }
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
    DisposableEffect(context) {
        val nsd = context.getSystemService(NsdManager::class.java)
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        val main = Handler(Looper.getMainLooper())
        val waiting = ArrayDeque<NsdServiceInfo>()
        var resolving = false
        var portName: String? = null

        // Only one can resolve at a time
        fun resolveNext() {
            if (resolving) return
            val service = waiting.removeFirstOrNull() ?: return
            resolving = true
            @Suppress("DEPRECATION")
            nsd.resolveService(service, object : NsdManager.ResolveListener {
                override fun onServiceResolved(info: NsdServiceInfo) {
                    main.post {
                        resolving = false
                        val ours = connectivity.activeNetwork?.let { network ->
                            connectivity.getLinkProperties(network)?.linkAddresses.orEmpty().any { it.address == info.host }
                        } == true
                        if (ours) {
                            portName = info.serviceName
                            port = info.port
                        }
                        resolveNext()
                    }
                }

                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    AppLog.w(TAG, "Couldn't resolve ${info.serviceName}: error $errorCode")
                    main.post {
                        resolving = false
                        resolveNext()
                    }
                }
            })
        }

        val discovery = object : NsdManager.DiscoveryListener {
            override fun onServiceFound(service: NsdServiceInfo) {
                main.post {
                    waiting += service
                    resolveNext()
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                main.post {
                    if (service.serviceName == portName) {
                        portName = null
                        port = null
                    }
                }
            }

            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w(TAG, "Couldn't look for wireless debugging: error $errorCode")
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                AppLog.w(TAG, "Couldn't stop looking for wireless debugging: error $errorCode")
            }
        }
        nsd.discoverServices(WIRELESS_DEBUGGING_SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery)
        onDispose {
            runCatching { nsd.stopServiceDiscovery(discovery) }
                .onFailure { AppLog.w(TAG, "Couldn't stop looking for wireless debugging", it) }
        }
    }
    return port
}

/** The connection in detail, kept current while open */
@Composable
fun NetworkStatusDialog(frigate: FrigateFeed?, homeAssistant: HaConnection?, onOpenPing: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val status by produceState(context.readNetworkStatus(), context) {
        while (true) {
            value = context.readNetworkStatus()
            delay(DETAILS_REFRESH_MS)
        }
    }
    val adb by produceState<AdbInfo?>(null, context) {
        while (true) {
            value = withContext(Dispatchers.IO) { context.readAdbInfo() }
            delay(ADB_PROBE_INTERVAL_MS)
        }
    }
    val tv by produceState<TvInfo?>(null, context) {
        value = withContext(Dispatchers.IO) { context.readTvInfo() }
    }
    val wirelessPort = wirelessDebuggingPort()
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    val ping = remember { FocusRequester() }
    // Wireless debugging has no screen of its own to open: it's inside Developer options
    val hasDeveloperOptions = remember(context) { context.hasSettingsScreen(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS) }
    TopDialog(
        title = "Network status",
        subtitle = remember(context) { context.buildText() },
        onDismiss = onDismiss,
        fullWidth = true,
        atTop = true,
        buttons = listOfNotNull(
            DialogButton("Ping utility", focus = ping, onClick = onOpenPing),
            DialogButton("Wi-Fi settings") { context.openSystemSettings(Settings.ACTION_WIFI_SETTINGS) },
            DialogButton("Developer options") {
                context.openSystemSettings(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
            }.takeIf { hasDeveloperOptions },
        ),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f)) {
                NetworkSection("TV")
                NetworkRow("Model", "${Build.MANUFACTURER.replaceFirstChar(Char::uppercase)} ${Build.MODEL}")
                NetworkRow("Bluetooth name", tv?.bluetoothName ?: "Checking…")
                tv?.hardwareSku?.let { NetworkRow("Hardware SKU", it) }
                tv?.netflixModelGroup?.let { NetworkRow("Netflix model group", it) }
                NetworkRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
                NetworkRow("Last reboot", context.bootTimeText(now))

                frigate?.let {
                    NetworkSection("Frigate")
                    FrigateRows(it, now)
                }

                homeAssistant?.let {
                    NetworkSection("Home Assistant")
                    HomeAssistantRows(it, now)
                }

                NetworkSection("ADB")
                val checking = "Checking…"
                NetworkRow(ADB_TCP_PORT, adb?.portText(adb?.tcpPort) ?: checking)
                NetworkRow(ADB_TCP_PORT_PERSIST, adb?.portText(adb?.tcpPortPersist) ?: checking)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    NetworkRow(ADB_TLS_PORT, adb?.portText(adb?.tlsPort) ?: checking)
                    NetworkRow(ADB_WIFI_SETTING, adb?.wifiEnabled ?: checking)
                    NetworkRow("Wireless debugging", wirelessPort?.let { "Port $it, from mDNS" } ?: "Not found on mDNS")
                }
            }
            Column(Modifier.weight(1f)) {
                NetworkSection("Layer 2: link")
                NetworkRow("Type", status.kind.label)
                val linkProblem = status.problem.takeIf { !status.linkUp }
                NetworkRow("Status", linkProblem ?: "Up", if (linkProblem != null) ErrorColor else Color.Unspecified)
                status.link.forEach { (label, value) -> NetworkRow(label, value) }

                NetworkSection("Layer 3: IP")
                if (status.linkUp) {
                    NetworkRow("Status", status.problem ?: "Connected", if (status.problem != null) ErrorColor else Color.Unspecified)
                    status.addresses.forEach { (label, value) -> NetworkRow(label, value) }
                } else {
                    NetworkRow("Status", "No link", ErrorColor)
                }
            }
        }
        LaunchedEffect(Unit) { ping.requestFocus() }
    }
}

@Composable
private fun FrigateRows(feed: FrigateFeed, now: Long) {
    val lastQuery = feed.lastQueryAt
    val failed = feed.failedQueries
    NetworkRow(
        "Last query",
        when {
            lastQuery != null -> "${elapsedText(now - lastQuery)} ago, $failed failed since"
            failed > 0 -> "None worked yet, $failed failed"
            else -> "None yet"
        },
        if (failed > 0) ErrorColor else Color.Unspecified,
    )
    val connectedAt = feed.socketConnectedAt
    val problem = feed.socketProblem
    NetworkRow(
        "WebSocket",
        when {
            connectedAt != null -> "Connected for ${elapsedText(now - connectedAt)}"
            problem != null -> "Not connected: $problem"
            lastQuery != null -> "Connecting…"
            else -> "Waiting for a query to work"
        },
        if (connectedAt == null && problem != null) ErrorColor else Color.Unspecified,
    )
}

@Composable
private fun HomeAssistantRows(connection: HaConnection, now: Long) {
    val connectedAt = connection.connectedAt
    val problem = connection.problem
    NetworkRow(
        "WebSocket",
        when {
            connectedAt != null -> "Connected for ${elapsedText(now - connectedAt)}"
            connection.status == HaStatus.TokenRejected -> "Login refused, waiting for a new one"
            problem != null -> "Not connected: $problem"
            else -> "Connecting…"
        },
        if (connectedAt == null && problem != null) ErrorColor else Color.Unspecified,
    )
    val failed = connection.failedConnections
    NetworkRow(
        "Failed connections",
        if (connectedAt != null) "$failed since connected" else "$failed in a row",
        if (failed > 0) ErrorColor else Color.Unspecified,
    )
}

private fun elapsedText(ms: Long): String {
    val seconds = (ms / 1000).coerceAtLeast(0)
    return when {
        seconds < 60 -> "$seconds s"
        seconds < 3600 -> "${seconds / 60} min"
        seconds < 86400 -> "${seconds / 3600} h ${seconds % 3600 / 60} min"
        else -> "${seconds / 86400} d ${seconds % 86400 / 3600} h"
    }
}

@Composable
private fun NetworkSection(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 1.dp),
    )
}

@Composable
private fun NetworkRow(label: String, value: String, color: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 1.dp)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(NetworkLabelWidth),
        )
        Text(value, style = MaterialTheme.typography.bodyMedium, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private val NetworkLabelWidth = 150.dp
