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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

// Saved clips in a case whose name starts with this, in any case, aren't shown: the user's way to keep
// clips off the family TV
private const val PRIVATE_CASE_PREFIX = "private"

// Frigate's default pre_capture and post_capture, when its configuration can't be read
private const val DEFAULT_CAPTURE_S = 5.0

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

/** The Clips tab's lists. [api]: the review severity, or "export" for the clips saved in Frigate. */
enum class FrigateKind(val api: String, val title: String) {
    Alert("alert", "Alerts"),
    Detection("detection", "Detections"),

    /** Frigate's exports, shown as "Saved" */
    Export("export", "Saved"),
}

/**
 * A clip Frigate can give: a review item, alert or detection, whose [endTime] is null while it's
 * going on, or an export, saved in Frigate as a file of its own ([videoPath]), with its [name] and
 * the [caseName] it's filed in.
 */
class FrigateAlert(
    val id: String,
    val camera: String,
    val startTime: Double,
    val endTime: Double?,
    private val thumbPath: String,
    val objects: List<String>,
    val kind: FrigateKind = FrigateKind.Alert,
    val videoPath: String? = null,
    val name: String? = null,
    val caseName: String? = null,
) {
    /** Where Frigate serves the thumbnail; null if the path isn't under its clips folder */
    val thumbnailPath: String? get() = thumbPath.takeIf { "/clips/" in it }?.let { "/clips/" + it.substringAfter("/clips/") }

    /** front_door → Front door */
    val cameraName: String get() = camera.replace('_', ' ').replaceFirstChar { it.uppercase() }

    /** e.g. "Front door · person, car", or an export's name */
    val title: String
        get() = name?.takeIf { it.isNotEmpty() }
            ?: listOf(cameraName, objects.joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" · ")
}

/** Seconds of recording Frigate keeps before and after an alert or detection: its `pre_capture` and `post_capture` */
data class FrigateCapture(val pre: Double, val post: Double)

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

    /** Frigate's version as it gives it, e.g. 0.18.2-7a5bd5f; throws [FrigateException] */
    suspend fun version(): String = get("/api/version") { it.string().trim() }

    /** The latest alerts, newest first; throws [FrigateException] */
    suspend fun alerts(): List<FrigateAlert> = reviews(FrigateKind.Alert)

    /** The latest review items of one severity, alert or detection, newest first; throws [FrigateException] */
    suspend fun reviews(kind: FrigateKind): List<FrigateAlert> {
        // Without a start, Frigate only gives the last 24 hours
        val after = System.currentTimeMillis() / 1000 - ALERTS_WITHIN_DAYS * 24 * 3600L
        return get("/api/review?severity=${kind.api}&limit=$ALERTS_SHOWN&after=$after") { body ->
            try {
                val array = JSONArray(body.string())
                List(array.length()) { parseAlert(array.getJSONObject(it), kind) }.filterNotNull().sortedByDescending { it.startTime }
            } catch (_: JSONException) {
                throw FrigateException("Frigate's list of ${kind.api}s wasn't the JSON list it should be")
            }
        }
    }

    /**
     * The clips saved in Frigate (its exports): first those in no case, then each case's, the case
     * with the newest clip first, newest first within each. Those still being made are left out, and
     * so are those in a case whose name starts with "Private". Throws [FrigateException].
     */
    suspend fun exports(): List<FrigateAlert> {
        val array = get("/api/exports") { body ->
            try {
                JSONArray(body.string())
            } catch (_: JSONException) {
                throw FrigateException("Frigate's list of exports wasn't the JSON list it should be")
            }
        }
        // Frigate before 0.18 has no cases: then every clip is in none
        val caseNames = try {
            get("/api/cases") { body ->
                val cases = JSONArray(body.string())
                (0 until cases.length()).mapNotNull { cases.optJSONObject(it) }
                    .associate { it.optString("id") to it.optString("name") }
            }
        } catch (e: FrigateException) {
            AppLog.i("Frigate", "No export cases from Frigate: saved clips aren't grouped")
            emptyMap()
        } catch (_: JSONException) {
            emptyMap()
        }
        var hidden = 0
        val clips = (0 until array.length()).mapNotNull { index ->
            val json = array.optJSONObject(index) ?: return@mapNotNull null
            if (json.optBoolean("in_progress")) return@mapNotNull null
            val video = json.optString("video_path").takeIf { "/exports/" in it } ?: return@mapNotNull null
            val date = seconds(json, "date") ?: return@mapNotNull null
            // The case's id, or the case itself
            val case = json.opt("export_case") ?: json.opt("export_case_id")
            val caseName = when (case) {
                is JSONObject -> case.optString("name").ifEmpty { caseNames[case.optString("id")] }
                is String -> caseNames[case]
                is Number -> caseNames[case.toString()]
                else -> null
            }?.takeIf { it.isNotEmpty() }
            if (caseName != null && caseName.lowercase().startsWith(PRIVATE_CASE_PREFIX)) {
                hidden++
                return@mapNotNull null
            }
            FrigateAlert(
                id = "export-${json.optString("id")}",
                camera = json.optString("camera"),
                startTime = date,
                endTime = date,
                thumbPath = json.optString("thumb_path"),
                objects = emptyList(),
                kind = FrigateKind.Export,
                videoPath = "/exports/" + video.substringAfter("/exports/"),
                name = json.optString("name").takeIf { it.isNotEmpty() },
                caseName = caseName,
            )
        }
        if (hidden > 0) AppLog.i("Frigate", "$hidden saved clips left out: their case's name starts with \"Private\"")
        // Newest first, then grouped by case, in the order of each case's newest clip, those in none first
        val newest = clips.sortedByDescending { it.startTime }
        val order = newest.map { it.caseName }.distinct().sortedBy { it != null }
        return order.flatMap { name -> newest.filter { it.caseName == name } }
    }

    // Each camera's capture times, read once from Frigate's configuration, by "alert/driveway"
    private var captureTimes: Map<String, FrigateCapture>? = null
    private val captureLock = Mutex()

    /**
     * Reads each camera's `record.alerts` and `record.detections` `pre_capture` and `post_capture`
     * from Frigate's configuration, once; an account that can't read it gets Frigate's default, 5 s.
     */
    suspend fun loadCaptureTimes(): Map<String, FrigateCapture> = captureLock.withLock {
        captureTimes ?: try {
            get("/api/config") { body -> parseCaptureTimes(JSONObject(body.string())) }
        } catch (e: FrigateException) {
            AppLog.w("Frigate", "Couldn't read Frigate's configuration for its pre_capture and post_capture: clips get Frigate's default, 5 s before and after")
            emptyMap()
        } catch (e: JSONException) {
            AppLog.w("Frigate", "Frigate's configuration isn't JSON: clips get the default pre_capture and post_capture", e)
            emptyMap()
        }.also { captureTimes = it }
    }

    private suspend fun capture(alert: FrigateAlert): FrigateCapture = when (alert.kind) {
        FrigateKind.Export -> FrigateCapture(0.0, 0.0)
        else -> loadCaptureTimes()["${alert.kind.api}/${alert.camera}"] ?: FrigateCapture(DEFAULT_CAPTURE_S, DEFAULT_CAPTURE_S)
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

    /**
     * An export's own file, or what was recorded during the alert or detection, with the recording
     * Frigate keeps before and after it, never past now. Frigate's review times are the activity
     * alone, so a clip cut to them starts and ends too tight.
     */
    suspend fun clipUrl(alert: FrigateAlert): String {
        alert.videoPath?.let { return server.url + it }
        val padding = capture(alert)
        val now = System.currentTimeMillis() / 1000.0
        val start = floor(alert.startTime - padding.pre).toLong()
        val end = ceil(alert.endTime?.let { minOf(it + padding.post, now) } ?: now).toLong()
        return "${server.url}/api/${alert.camera}/start/$start/end/$end/clip.mp4"
    }

    private fun clipRange(alert: FrigateAlert): Pair<Long, Long> =
        floor(alert.startTime).toLong() to ceil(alert.endTime ?: (System.currentTimeMillis() / 1000.0)).toLong()
}

/** Frigate's /api/config gives each camera's settings with the global ones already applied */
private fun parseCaptureTimes(root: JSONObject): Map<String, FrigateCapture> {
    fun record(section: JSONObject?, kind: String) = section?.optJSONObject("record")?.optJSONObject(kind)
    fun value(key: String, vararg sections: JSONObject?): Double =
        sections.firstNotNullOfOrNull { section ->
            section?.optDouble(key)?.takeIf { !it.isNaN() && it >= 0 }?.coerceAtMost(300.0)
        } ?: DEFAULT_CAPTURE_S
    val cameras = root.optJSONObject("cameras") ?: JSONObject()
    val times = mutableMapOf<String, FrigateCapture>()
    for (kind in listOf("alerts", "detections")) {
        val global = record(root, kind)
        for (name in cameras.keys()) {
            val own = record(cameras.optJSONObject(name), kind)
            times["${kind.dropLast(1)}/$name"] = FrigateCapture(value("pre_capture", own, global), value("post_capture", own, global))
        }
    }
    AppLog.i("Frigate", "Clips padded per camera: " + times.toSortedMap().entries.joinToString("; ") { (key, capture) ->
        "$key ${capture.pre.toInt()} s before, ${capture.post.toInt()} s after"
    })
    return times
}

private fun parseAlert(json: JSONObject, kind: FrigateKind = FrigateKind.Alert): FrigateAlert? {
    val data = json.optJSONObject("data") ?: json.optString("data").let { runCatching { JSONObject(it) }.getOrNull() }
    val objects = data?.optJSONArray("objects")
    return FrigateAlert(
        id = json.getString("id"),
        camera = json.getString("camera"),
        startTime = seconds(json, "start_time") ?: return null,
        endTime = seconds(json, "end_time"),
        thumbPath = json.optString("thumb_path"),
        objects = List(objects?.length() ?: 0) { objects!!.optString(it).removeSuffix("-verified") }.filter { it.isNotEmpty() }.distinct(),
        kind = kind,
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
