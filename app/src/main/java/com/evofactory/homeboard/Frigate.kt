package com.evofactory.homeboard

import android.os.Build
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.IOException
import java.net.InetAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import org.conscrypt.Conscrypt
import javax.net.ssl.SSLException
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509ExtendedTrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.ResponseBody
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

private const val FRIGATE_PORT = 8971
private const val LOGIN_PATH = "/api/login"
private const val SOCKET_PATH = "/ws"
private const val SOCKET_PING_MS = 30_000L
private const val ALERTS_SHOWN = 50
const val ALERTS_WITHIN_DAYS = 7
private const val THUMBNAILS_KEPT = ALERTS_SHOWN * 2

// Recordings are saved in pieces of about 10 seconds: an alert younger than this may have none yet
private const val VIDEO_EXPECTED_WITHIN_S = 60

// Long enough after an alert for its last recordings to have been saved
private const val VIDEO_SETTLED_AFTER_S = 300

private const val FRIGATE_CONNECT_TIMEOUT_MS = 10_000L

// Frigate puts a clip together before sending it, which takes a while for a long one
private const val FRIGATE_READ_TIMEOUT_MS = 30_000L

/**
 * Can't reach or log in to Frigate. With [stopRetrying], trying again won't help until the settings
 * change. With [unreachable] nothing got through, as when the TV's network is still coming back
 * after standby, so trying again soon costs nothing.
 */
class FrigateException(message: String, val stopRetrying: Boolean = false, val unreachable: Boolean = false, cause: Throwable? = null) : Exception(message, cause) {
    init {
        AppLog.w("Frigate", message, cause)
    }
}

/**
 * Where Frigate is and how the TV logs in. Frigate has no refresh token, so the [password] is kept
 * to log in again when the login expires. [certPin]: the certificate seen when connecting from the
 * settings, since Frigate's is self-signed and nothing else can vouch for it. The settings may hold
 * what was typed before a connection ever worked: [isSet] is false until it's safe to use, as an
 * https:// server without a pin would be sent the password whatever certificate it shows, which
 * only [trustsFirstCertificate] allows, once.
 */
data class FrigateServer(val url: String = "", val username: String = "", val password: String = "", val certPin: String = "") {
    val isSet get() = normalizeFrigateUrl(url) == url && username.isNotEmpty() && password.isNotEmpty() &&
        (certPin.isNotEmpty() || url.startsWith("http://"))

    /** The password is known, e.g. imported, but this TV never connected: the first connection accepts the certificate */
    val trustsFirstCertificate get() = !isSet && normalizeFrigateUrl(url) == url && username.isNotEmpty() && password.isNotEmpty()
}

/** Android 9 has no TLS 1.3, which Frigate's HTTPS port can insist on: Conscrypt brings it */
private fun frigateSslContext(): SSLContext =
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) SSLContext.getInstance("TLSv1.3", Conscrypt.newProvider())
    else SSLContext.getInstance("TLS")

/** Something Frigate flagged for review, with severity "alert". [endTime] is null while it's going on. */
class FrigateAlert(
    val id: String,
    val camera: String,
    val startTime: Double,
    val endTime: Double?,
    private val thumbPath: String,
    val objects: List<String>,
) {
    /** Where Frigate serves the thumbnail; null if the path isn't under its clips folder */
    val thumbnailPath: String? get() = thumbPath.takeIf { "/clips/" in it }?.let { "/clips/" + it.substringAfter("/clips/") }

    /** front_door → Front door */
    val cameraName: String get() = camera.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

/**
 * Address as typed → base URL, e.g. "192.168.1.30" → "https://192.168.1.30:8971": https:// is
 * assumed without a scheme, and Frigate's logged-in port without a port either. Null if invalid.
 */
fun normalizeFrigateUrl(input: String): String? {
    val text = input.trim()
    if (text.isEmpty()) return null
    val withScheme = when {
        "://" in text -> text
        ':' in text -> "https://$text"
        else -> "https://${text.trimEnd('/')}:$FRIGATE_PORT"
    }
    // Only the server: anything copied from the browser after it (e.g. /review) goes
    val url = withScheme.toHttpUrlOrNull() ?: return null
    return url.newBuilder().encodedPath("/").query(null).fragment(null).build().toString().trimEnd('/')
}

/**
 * Accepts a certificate the system trusts for the address used, as with a real one (e.g. Let's
 * Encrypt): it may be renewed at any time. Otherwise only the one pinned, since Frigate's own is
 * self-signed and nothing else can vouch for it; with no pin, any, as the first connection is made
 * from the settings by someone who knows this is their server. It remembers what it saw for saving
 * as the pin, and a certificate refused for not being the pinned one in [offered].
 */
private class PinningTrustManager(private val pin: String) : X509ExtendedTrustManager() {
    @Volatile var seen = ""
    @Volatile var changed = false
    @Volatile var offered = ""

    private val system: X509TrustManager? = runCatching {
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(null as KeyStore?) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
    }.onFailure { AppLog.w("Frigate", "No system trust store", it) }.getOrNull()

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?, socket: Socket?) =
        check(chain, (socket as? SSLSocket)?.handshakeSession?.peerHost) {
            (system as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, socket) ?: system?.checkServerTrusted(chain, authType)
        }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?, engine: SSLEngine?) =
        check(chain, engine?.handshakeSession?.peerHost ?: engine?.peerHost) {
            (system as? X509ExtendedTrustManager)?.checkServerTrusted(chain, authType, engine) ?: system?.checkServerTrusted(chain, authType)
        }

    override fun checkServerTrusted(chain: Array<out X509Certificate>, authType: String?) =
        check(chain, null) { system?.checkServerTrusted(chain, authType) }

    private fun check(chain: Array<out X509Certificate>, host: String?, systemCheck: () -> Unit) {
        val certificate = chain.firstOrNull() ?: throw CertificateException("No certificate")
        val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        val trusted = pin.isEmpty() || fingerprint == pin ||
            (system != null && host != null && certificateNames(certificate, host) && runCatching(systemCheck).isSuccess)
        if (!trusted) {
            offered = fingerprint
            changed = true
            throw CertificateException("Not the certificate this TV knows, and not one the system trusts for $host")
        }
        seen = fingerprint
    }

    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?) = throw CertificateException()
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?, socket: Socket?) = throw CertificateException()
    override fun checkClientTrusted(chain: Array<out X509Certificate>, authType: String?, engine: SSLEngine?) = throw CertificateException()

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Whether [certificate] is for [host], by its subject alternative names: a DNS name, with a wildcard for one leftmost label, or an IP address */
internal fun certificateNames(certificate: X509Certificate, host: String): Boolean {
    val names = runCatching { certificate.subjectAlternativeNames }.getOrNull() ?: return false
    val wanted = host.trimEnd('.').lowercase()
    val isAddress = wanted.contains(':') || wanted.all { it.isDigit() || it == '.' }
    return names.any { entry ->
        val type = entry.getOrNull(0) as? Int
        val name = (entry.getOrNull(1) as? String)?.trimEnd('.')?.lowercase() ?: return@any false
        when {
            isAddress -> type == 7 && runCatching { InetAddress.getByName(name) == InetAddress.getByName(wanted) }.getOrDefault(false)
            type != 2 -> false
            name.startsWith("*.") -> {
                val suffix = name.substring(1)
                wanted.endsWith(suffix) && wanted.length > suffix.length && '.' !in wanted.substring(0, wanted.length - suffix.length)
            }
            else -> name == wanted
        }
    }
}

/** Talks to one Frigate: logs in when it's asked to, including for the video player, which shares [http]. */
class FrigateClient(private val server: FrigateServer) {
    private val trust = PinningTrustManager(server.certPin)
    private val thumbnails = LruCache<String, ImageBitmap>(THUMBNAILS_KEPT)
    private val videoFound = LruCache<String, Boolean>(THUMBNAILS_KEPT)
    private val loginLock = Any()

    @Volatile private var token: String? = null

    /** The certificate of the last connection, to save as the server's [FrigateServer.certPin] */
    val seenCertPin: String get() = trust.seen

    /** A certificate refused for not being the one pinned, nor one the system trusts: to accept, or not */
    val changedCertPin: String? get() = trust.offered.takeIf { trust.changed }

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(FRIGATE_CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(FRIGATE_READ_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .sslSocketFactory(
            frigateSslContext().apply { init(null, arrayOf(trust), SecureRandom()) }.socketFactory,
            trust,
        )
        // A self-signed certificate rarely names the address it's reached at: the pin identifies it.
        // The trust manager checks the names of one the system trusts
        .hostnameVerifier { _, _ -> true }
        .addInterceptor { chain ->
            val current = token
            val request = chain.request()
            chain.proceed(
                if (current == null || request.url.encodedPath == LOGIN_PATH) request
                else request.newBuilder().header("Authorization", "Bearer $current").build(),
            )
        }
        // A login lasts 24 hours by default. Whenever there's none or it's expired, Frigate answers
        // 401: log in, once, and send the request again
        .authenticator { _, response ->
            if (response.priorResponse != null || response.request.url.encodedPath == LOGIN_PATH) return@authenticator null
            val sent = response.request.header("Authorization")
            val fresh = synchronized(loginLock) {
                // Another request may have logged in meanwhile
                token?.takeIf { "Bearer $it" != sent } ?: logIn().also { token = it }
            }
            fresh?.let { response.request.newBuilder().header("Authorization", "Bearer $it").build() }
        }
        .build()

    /** The token, or null if the username or password is refused. It only comes as a cookie. */
    private fun logIn(): String? {
        val body = JSONObject().put("user", server.username).put("password", server.password).toString()
        val request = Request.Builder()
            .url(server.url + LOGIN_PATH)
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 400 || response.code == 401) return null
            if (response.code == 429) throw IOException("too many failed logins, try again later")
            if (!response.isSuccessful) throw IOException("its login answered HTTP ${response.code}")
            // The cookie's name can be configured; its value is a JSON web token, with its 3 parts
            return response.headers("Set-Cookie")
                .map { it.substringBefore(';').substringAfter('=') }
                .firstOrNull { value -> value.count { it == '.' } == 2 }
                ?: throw IOException("its login gave no token")
        }
    }

    private suspend fun <T> get(path: String, read: (ResponseBody) -> T): T = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(server.url + path).build()).execute().use { response ->
                when {
                    response.code == 401 -> throw FrigateException("Frigate refused the login: check the username and password", stopRetrying = true)
                    !response.isSuccessful -> throw FrigateException("Frigate at ${server.url} answered HTTP ${response.code}")
                }
                read(response.body)
            }
        } catch (e: IOException) {
            throw FrigateException(failureText(e), stopRetrying = trust.changed, unreachable = !trust.changed && isUnreachable(e), cause = e)
        }
    }

    private fun isUnreachable(e: IOException) = e is UnknownHostException || e is SocketException || e is SocketTimeoutException

    private fun failureText(e: IOException): String = when {
        trust.changed -> "Frigate's certificate changed: accept the new one only if you know it was renewed or replaced."
        e is UnknownHostException -> "Unknown address: ${server.url}"
        isUnreachable(e) -> "Can't reach Frigate at ${server.url}"
        e is SSLException -> "HTTPS problem: ${e.message}"
        else -> "Problem with Frigate: ${e.message ?: e.javaClass.simpleName}"
    }

    /**
     * Calls [onAlertChanged] whenever Frigate says an alert started, changed or ended, on one of
     * OkHttp's threads, until the connection is lost. Returns what went wrong.
     */
    suspend fun watchAlerts(onConnected: () -> Unit, onAlertChanged: () -> Unit): String {
        val lost = CompletableDeferred<String>()
        val socket = http.newBuilder().pingInterval(SOCKET_PING_MS, TimeUnit.MILLISECONDS).build().newWebSocket(
            Request.Builder().url(server.url + SOCKET_PATH).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) = onConnected()

                override fun onMessage(webSocket: WebSocket, text: String) {
                    if (isAlertChange(text)) onAlertChanged()
                }

                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                    lost.complete("Frigate closed it ($code)")
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    lost.complete(response?.let { "HTTP ${it.code}" } ?: t.message ?: t.javaClass.simpleName)
                }
            },
        )
        try {
            return lost.await()
        } finally {
            socket.cancel()
        }
    }

    /** The latest alerts, newest first; throws [FrigateException] */
    suspend fun alerts(): List<FrigateAlert> {
        // Without a start, Frigate only gives the last 24 hours
        val after = System.currentTimeMillis() / 1000 - ALERTS_WITHIN_DAYS * 24 * 3600L
        return get("/api/review?severity=alert&limit=$ALERTS_SHOWN&after=$after") { body ->
            try {
                val array = JSONArray(body.string())
                List(array.length()) { parseAlert(array.getJSONObject(it)) }.filterNotNull().sortedByDescending { it.startTime }
            } catch (_: JSONException) {
                throw FrigateException("Unexpected reply from Frigate")
            }
        }
    }

    /** Kept in memory while the list refreshes; null if there's none to show */
    suspend fun thumbnail(alert: FrigateAlert): ImageBitmap? {
        val path = alert.thumbnailPath ?: return null
        thumbnails.get(path)?.let { return it }
        return try {
            get(path) { body -> BitmapFactory.decodeStream(body.byteStream())?.asImageBitmap() }?.also { thumbnails.put(path, it) }
        } catch (_: FrigateException) {
            null
        }
    }

    /**
     * Whether Frigate has recordings for the alert's clip; null if it can't be told yet, including
     * just after the alert started, when the first ones aren't saved yet. The answer is kept once
     * it won't change: found, or still none long after the end. Only throws [FrigateException] when
     * asking about the next alerts is pointless. Files gone from the disk still count: Frigate only looks at its database.
     */
    suspend fun hasVideo(alert: FrigateAlert): Boolean? {
        videoFound.get(alert.id)?.let { return it }
        val (start, end) = clipRange(alert)
        val found = try {
            get("/api/${alert.camera}/recordings?after=$start&before=$end") { body -> JSONArray(body.string()).length() > 0 }
        } catch (e: FrigateException) {
            // Unreachable: the next alerts would each wait out the timeout, holding up the refresh
            if (e.stopRetrying || e.unreachable) throw e
            return null
        } catch (e: JSONException) {
            AppLog.w("Frigate", "Unexpected recordings reply for ${alert.id}", e)
            return null
        }
        val now = System.currentTimeMillis() / 1000.0
        if (!found && now - alert.startTime < VIDEO_EXPECTED_WITHIN_S) return null
        if (found || (alert.endTime != null && now - alert.endTime > VIDEO_SETTLED_AFTER_S)) videoFound.put(alert.id, found)
        return found
    }

    /** What was recorded during the alert, or until now if it's still going on */
    fun clipUrl(alert: FrigateAlert): String {
        val (start, end) = clipRange(alert)
        return "${server.url}/api/${alert.camera}/start/$start/end/$end/clip.mp4"
    }

    private fun clipRange(alert: FrigateAlert): Pair<Long, Long> =
        floor(alert.startTime).toLong() to ceil(alert.endTime ?: (System.currentTimeMillis() / 1000.0)).toLong()
}

private fun parseAlert(json: JSONObject): FrigateAlert? {
    val data = json.optJSONObject("data") ?: json.optString("data").let { runCatching { JSONObject(it) }.getOrNull() }
    val objects = data?.optJSONArray("objects")
    return FrigateAlert(
        id = json.getString("id"),
        camera = json.getString("camera"),
        startTime = seconds(json, "start_time") ?: return null,
        endTime = seconds(json, "end_time"),
        thumbPath = json.optString("thumb_path"),
        objects = List(objects?.length() ?: 0) { objects!!.optString(it).removeSuffix("-verified") }.filter { it.isNotEmpty() }.distinct(),
    )
}

/** Most of what comes through the socket is about other things. A review that can't be read may be an alert. */
private fun isAlertChange(message: String): Boolean {
    if ("reviews" !in message) return false
    return runCatching {
        val root = JSONObject(message)
        if (root.optString("topic") != "reviews") return false
        JSONObject(root.optString("payload")).optJSONObject("after")?.optString("severity") != "detection"
    }.getOrDefault(true)
}

/** A time in seconds; Frigate gives a number, though its API description says a date */
private fun seconds(json: JSONObject, key: String): Double? {
    if (json.isNull(key)) return null
    json.optDouble(key).takeUnless { it.isNaN() }?.let { return it }
    return try {
        OffsetDateTime.parse(json.optString(key)).toEpochSecond().toDouble()
    } catch (_: DateTimeParseException) {
        null
    }
}
