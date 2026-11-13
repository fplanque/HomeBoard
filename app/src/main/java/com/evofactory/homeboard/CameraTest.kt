package com.evofactory.homeboard

import android.media.MediaCodec
import android.media.MediaCodecList
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Base64
import androidx.annotation.OptIn
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.ParsableBitArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.container.NalUnitUtil
import androidx.media3.extractor.AacUtil
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.net.ConnectException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.log10
import kotlin.random.Random

private const val TAG = "CameraTest"
private const val CONNECT_TIMEOUT_MS = 3_000
private const val ANSWER_TIMEOUT_MS = 5_000
private const val SAMPLE_MS = 4_000L
private const val SAMPLE_READ_TIMEOUT_MS = 500

val GoodColor = Color(0xFF81C995)
private val WarnColor = Color(0xFFFDD663)

internal enum class Mark { Info, Good, Warn, Bad }

internal class ReportLine(val label: String, val value: String, val mark: Mark, val indent: Boolean)

/** What one stream's test found so far, line by line */
internal class StreamTestReport(val title: String) {
    val lines = mutableStateListOf<ReportLine>()
    var running by mutableStateOf(false)
}

/**
 * Tests the streams as entered, saved or not: talks RTSP to each camera itself, without a player,
 * so it never takes one of the TV's decoders. Up and down scroll the report; "Test again" reruns it.
 */
@Composable
fun CameraTestDialog(name: String, url: String, substreamUrl: String, useAudio: Boolean, useSubstreamAudio: Boolean, onDismiss: () -> Unit) {
    val reports = remember {
        listOfNotNull(StreamTestReport("Main Stream"), StreamTestReport("Sub Stream").takeIf { substreamUrl.isNotBlank() })
    }
    var run by remember { mutableIntStateOf(0) }
    val running = reports.any { it.running }
    LaunchedEffect(run) {
        reports.forEach { it.lines.clear() }
        reports.forEachIndexed { index, report ->
            report.running = true
            try {
                if (index == 0) testStream(url, isMain = true, useAudio, report) else testStream(substreamUrl, isMain = false, useSubstreamAudio, report)
            } finally {
                report.running = false
            }
            // Without the camera's address, which the log never has
            AppLog.i(
                TAG,
                "${report.title}:\n" + report.lines.joinToString("\n") {
                    "  ${if (it.indent) "  " else ""}${it.label}: ${it.value.replace(Regex("""rtsps?://\S+"""), "(address)")}"
                },
            )
        }
    }
    val reportFocus = remember { FocusRequester() }
    TopDialog(
        title = "Test ${name.ifBlank { "camera" }}",
        onDismiss = onDismiss,
        fullWidth = true,
        atTop = true,
        buttons = listOf(DialogButton("Test again", enabled = !running) { run++ }),
        contentFocus = { reportFocus },
    ) {
        val scroll = rememberScrollState()
        val scope = rememberCoroutineScope()
        val height = LocalConfiguration.current.screenHeightDp.dp - 150.dp
        Box(
            modifier = Modifier
                .heightIn(max = height)
                .focusRequester(reportFocus)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    val step = when (event.key) {
                        Key.DirectionDown -> if (scroll.value < scroll.maxValue) 240f else return@onPreviewKeyEvent false
                        Key.DirectionUp -> if (scroll.value > 0) -240f else return@onPreviewKeyEvent false
                        else -> return@onPreviewKeyEvent false
                    }
                    scope.launch { scroll.animateScrollBy(step) }
                    true
                }
                .ringToTop()
                .focusable()
                .scrollIndicators(scroll),
        ) {
            Column(Modifier.verticalScroll(scroll).padding(horizontal = 12.dp)) {
                reports.forEach { report ->
                    Text(
                        report.title + if (report.running) " · testing…" else "",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                    )
                    if (report.lines.isEmpty() && !report.running) Text("Waiting…", style = MaterialTheme.typography.bodySmall)
                    report.lines.forEach { ReportRow(it) }
                }
            }
        }
        LaunchedEffect(Unit) { reportFocus.requestFocus() }
    }
}

@Composable
private fun ReportRow(line: ReportLine) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(start = if (line.indent) 24.dp else 0.dp, top = 2.dp, bottom = 2.dp)) {
        Text(
            line.label,
            style = MaterialTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
            modifier = Modifier.weight(0.28f),
        )
        Text(
            line.value,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = when (line.mark) {
                Mark.Info -> colors.onSurface
                Mark.Good -> GoodColor
                Mark.Warn -> WarnColor
                Mark.Bad -> ErrorColor
            },
            modifier = Modifier.weight(0.72f),
        )
    }
}

// One track as the camera describes it
private class TestTrack(val number: Int, val media: String, val payload: String, val encoding: String, val clockRate: Int, val channels: Int, val format: String, val control: String?) {
    val label get() = "Track $number"
    val parameters: Map<String, String> = format.split(';').mapNotNull { part ->
        part.trim().takeIf { it.isNotEmpty() }?.let { it.substringBefore('=').trim().lowercase() to it.substringAfter('=', "").trim() }
    }.toMap()
}

private fun parseTracks(sdp: String): List<TestTrack> = sdpSections(sdp).drop(1).mapIndexed { index, lines ->
    val words = lines.first().trim().split(' ')
    val media = words.first().removePrefix("m=")
    val payload = words.getOrNull(3).orEmpty()
    val rtpmap = lines.firstNotNullOfOrNull { attributeValue(it, "rtpmap", payload) }
    val format = lines.firstNotNullOfOrNull { attributeValue(it, "fmtp", payload) }.orEmpty()
    val control = lines.firstOrNull { it.startsWith("a=control:") }?.substringAfter("a=control:")?.trim()
    val (encoding, clock, channels) = rtpmap?.split('/')?.let {
        Triple(it[0].trim().uppercase(), it.getOrNull(1)?.trim()?.toIntOrNull() ?: 0, it.getOrNull(2)?.trim()?.toIntOrNull() ?: 1)
    } ?: when (payload) {
        // Static payload types, which need no rtpmap
        "0" -> Triple("PCMU", 8000, 1)
        "8" -> Triple("PCMA", 8000, 1)
        "26" -> Triple("JPEG", 90000, 1)
        else -> Triple("type $payload", 0, 1)
    }
    TestTrack(index + 1, media, payload, encoding, clock, channels, format, control)
}

// The encodings Media3's RTSP player takes
private val Media3Encodings = setOf(
    "H264", "H265", "MP4V-ES", "H263-1998", "H263-2000", "VP8", "VP9",
    "MPEG4-GENERIC", "MP4A-LATM", "PCMU", "PCMA", "L8", "L16", "OPUS", "AC3", "AMR", "AMR-WB",
)

private fun mimeOf(encoding: String) = when (encoding) {
    "H264" -> "video/avc"
    "H265" -> "video/hevc"
    "MP4V-ES" -> "video/mp4v-es"
    "VP8" -> "video/x-vnd.on2.vp8"
    "VP9" -> "video/x-vnd.on2.vp9"
    "MPEG4-GENERIC", "MP4A-LATM" -> "audio/mp4a-latm"
    "PCMU" -> "audio/g711-mlaw"
    "PCMA" -> "audio/g711-alaw"
    "OPUS" -> "audio/opus"
    "AC3" -> "audio/ac3"
    else -> null
}

// Reports through the main thread, which owns the report's state; masks any login a value carries
private class Reporter(private val report: StreamTestReport) {
    suspend fun line(label: String, value: String, mark: Mark = Mark.Info, indent: Boolean = false) = withContext(Dispatchers.Main) {
        report.lines += ReportLine(label, maskSecrets(value), mark, indent)
    }
}

private fun maskSecrets(text: String) = text
    .replace(Regex("""(rtsps?://)[^/@\s]*@"""), "$1***@")
    .replace(Regex("""(?i)\b(user(name)?|pass(word)?|pwd|token|auth|key)=[^&\s;]*"""), "$1=***")

@OptIn(UnstableApi::class)
internal suspend fun testStream(url: String, isMain: Boolean, useAudio: Boolean, report: StreamTestReport) = withContext(Dispatchers.IO) {
    val r = Reporter(report)
    val uri = Uri.parse(url.trim())
    val host = uri.host.orEmpty()
    val port = if (uri.port > 0) uri.port else 554
    val bareUrl = uri.buildUpon().encodedAuthority(if (uri.port > 0) "$host:$port" else host).build().toString()
    r.line("Address", bareUrl)
    val userInfo = uri.userInfo
    val user = userInfo?.substringBefore(':')
    val password = userInfo?.substringAfter(':', "")
    r.line(
        "Login",
        when {
            user == null -> "none in the address"
            password.isNullOrEmpty() -> "user name in the address, no password"
            else -> "user name and password in the address"
        },
    )
    if (uri.scheme.equals("rtsps", ignoreCase = true)) {
        r.line("TLS", "rtsps:// isn't tested here", Mark.Warn)
        return@withContext
    }

    // Name lookup
    var started = SystemClock.elapsedRealtime()
    val addresses = try {
        InetAddress.getAllByName(host)
    } catch (e: UnknownHostException) {
        r.line("Name lookup", "$host: no such name on this network", Mark.Bad)
        return@withContext
    }
    val literal = host.all { it.isDigit() || it == '.' } || host.contains(':')
    r.line(
        "Name lookup",
        if (literal) "not needed, an IP address" else addresses.joinToString { it.hostAddress.orEmpty() } + " · ${SystemClock.elapsedRealtime() - started} ms",
    )
    val address = addresses.firstOrNull { it is Inet4Address } ?: addresses.first()

    // Connection
    val socket = Socket()
    coroutineContext[Job]?.invokeOnCompletion { runCatching { socket.close() } }
    started = SystemClock.elapsedRealtime()
    try {
        socket.connect(InetSocketAddress(address, port), CONNECT_TIMEOUT_MS)
    } catch (e: Exception) {
        r.line(
            "Connect",
            when (e) {
                is SocketTimeoutException -> "no answer on TCP port $port within ${CONNECT_TIMEOUT_MS / 1000} s"
                is ConnectException -> "TCP port $port refused: nothing listens there"
                is NoRouteToHostException -> "no route to ${address.hostAddress}"
                else -> e.toString()
            },
            Mark.Bad,
        )
        return@withContext
    }
    r.line("Connect", "TCP port $port in ${SystemClock.elapsedRealtime() - started} ms, from ${socket.localAddress.hostAddress}", Mark.Good)
    socket.soTimeout = ANSWER_TIMEOUT_MS
    val rtsp = RtspTalk(socket, user?.let(Uri::decode), password?.let(Uri::decode))

    try {
        // What it offers
        val options = rtsp.request("OPTIONS", bareUrl)
        r.line("OPTIONS", options.summary, if (options.code == 200) Mark.Good else Mark.Warn)
        (options.header("server") ?: options.header("user-agent"))?.let { r.line("Server", it) }
        options.header("public")?.let { r.line("Requests", it.replace(Regex("""\s*,\s*"""), " ")) }

        // Its description of the stream
        val describe = rtsp.request("DESCRIBE", bareUrl, mapOf("Accept" to "application/sdp"))
        rtsp.challenge?.let { r.line("Login asked", it) }
        if (describe.code != 200) {
            r.line("DESCRIBE", describe.summary + explainCode(describe.code, user != null), Mark.Bad)
            return@withContext
        }
        r.line("DESCRIBE", describe.summary + (rtsp.challenge?.let { ", logged in" } ?: ""), Mark.Good)
        val sdp = String(describe.body, Charsets.UTF_8)
        val base = describe.header("content-base") ?: describe.header("content-location") ?: bareUrl
        r.line("Description", "${describe.body.size} bytes · ${describe.header("content-type") ?: "no type"}")
        describe.header("content-base")?.let { r.line("Content-Base", it) }
        sdp.lineSequence().firstOrNull { it.startsWith("s=") }?.substring(2)?.trim()?.takeIf { it.isNotEmpty() }?.let { r.line("Session name", it) }
        sdp.lineSequence().firstOrNull { it.startsWith("a=tool:") }?.substringAfter(':')?.trim()?.let { r.line("Made by", it) }
        val tracks = parseTracks(sdp)
        val described = sdpTracks(sdp)
        r.line("Tracks", if (tracks.isEmpty()) "none" else tracks.joinToString { "${it.media} ${it.encoding}" }, if (tracks.none { it.media == "video" }) Mark.Bad else Mark.Info)

        tracks.forEachIndexed { index, track -> reportTrack(r, track, described.getOrNull(index)?.described ?: true, isMain, useAudio) }

        // A few seconds of the stream itself
        sample(r, rtsp, tracks, base)
    } catch (e: SocketTimeoutException) {
        r.line("Stopped", "the camera stopped answering for ${ANSWER_TIMEOUT_MS / 1000} s", Mark.Bad)
    } catch (e: EOFException) {
        r.line("Stopped", "the camera closed the connection", Mark.Bad)
    } catch (e: Exception) {
        if (coroutineContext[Job]?.isActive == false) throw e
        r.line("Stopped", e.toString(), Mark.Bad)
    } finally {
        runCatching { socket.close() }
    }
}

private fun explainCode(code: Int, hasLogin: Boolean) = when (code) {
    401 -> if (hasLogin) ": the camera refused this login" else ": the camera wants a login, put it in the address"
    403 -> ": access to this stream is refused"
    404 -> ": no stream at this path"
    else -> ""
}

@OptIn(UnstableApi::class)
private suspend fun reportTrack(r: Reporter, track: TestTrack, described: Boolean, isMain: Boolean, useAudio: Boolean) {
    r.line(track.label, "${track.media} ${track.encoding}" + (if (track.clockRate > 0) "/${track.clockRate}" else "") + (if (track.channels > 1) "/${track.channels}" else ""))
    r.line("Payload", "type ${track.payload}" + (track.control?.let { " · control $it" } ?: ""), indent = true)
    if (track.format.isNotBlank()) r.line("Format", track.parameters.entries.joinToString(" ") { (k, v) -> if (v.length > 24) "$k=(${v.length} chars)" else "$k=$v" }, indent = true)
    var width = 0
    var height = 0
    when (track.encoding) {
        "H264" -> {
            track.parameters["profile-level-id"]?.let { r.line("Profile id", "${it.uppercase()}: ${h264ProfileLevel(it)}", indent = true) }
            track.parameters["packetization-mode"]?.let { r.line("Packetization", "mode $it" + if (it == "0") " (one NAL unit per packet)" else " (fragmented and aggregated NAL units)", indent = true) }
            val sets = track.parameters["sprop-parameter-sets"]?.split(',')?.mapNotNull { decodeBase64(it) }.orEmpty()
            sets.firstOrNull { it.isNotEmpty() && it[0].toInt() and 0x1F == 7 }?.let { sps ->
                runCatching { NalUnitUtil.parseSpsNalUnit(sps, 0, sps.size) }.getOrNull()?.let {
                    width = it.width
                    height = it.height
                    r.line("SPS", h264SpsSummary(it, sps.size), indent = true)
                }
            }
            sets.firstOrNull { it.isNotEmpty() && it[0].toInt() and 0x1F == 8 }?.let { r.line("PPS", "${it.size} bytes", indent = true) }
        }
        "H265" -> {
            listOf("sprop-vps", "sprop-pps").forEach { key ->
                track.parameters[key]?.let { decodeBase64(it) }?.let { r.line(key.removePrefix("sprop-").uppercase(), "${it.size} bytes", indent = true) }
            }
            track.parameters["sprop-sps"]?.let { decodeBase64(it) }?.let { sps ->
                runCatching { NalUnitUtil.parseH265SpsNalUnit(sps, 0, sps.size, null) }.getOrNull()?.let {
                    width = it.width
                    height = it.height
                    r.line("SPS", h265SpsSummary(it, sps.size), indent = true)
                }
            }
        }
        "MPEG4-GENERIC", "MP4A-LATM" -> track.parameters["config"]?.let { r.line("AAC config", "${it.uppercase()}: ${aacConfig(it, track.encoding == "MP4A-LATM")}", indent = true) }
    }
    when {
        track.encoding !in Media3Encodings -> r.line("Media3", "can't play ${track.encoding}" + if (track.media == "video") ": no picture from this stream" else "", if (track.media == "video") Mark.Bad else Mark.Warn, indent = true)
        !described -> r.line(
            "Media3",
            "format details missing" + when (track.encoding) {
                "H264" -> " (sprop-parameter-sets)"
                "H265" -> " (sprop-vps/sps/pps)"
                else -> " (a=fmtp)"
            } + ": Media3 refuses the whole stream" + when {
                track.media != "audio" -> ""
                useAudio -> ". Turn off \"Use audio track\""
                else -> ", but the app takes this track out"
            },
            if (track.media == "audio" && !useAudio) Mark.Warn else Mark.Bad,
            indent = true,
        )
        else -> r.line("Media3", "format described, playable", Mark.Good, indent = true)
    }
    mimeOf(track.encoding)?.let { mime -> decoderLine(mime, width, height) }?.let { (text, mark) -> r.line("Decoder here", text, mark, indent = true) }
    when (track.media) {
        "video" -> r.line("In the app", if (isMain) "Home tab and full screen" else "Cameras tab tiles", indent = true)
        "audio" -> r.line(
            "In the app",
            when {
                !useAudio -> "taken out: \"Use audio track\" is off"
                isMain -> "plays muted on the Home tab, heard in full screen"
                else -> "not played: substreams have no sound"
            },
            indent = true,
        )
        else -> r.line("In the app", "not played", indent = true)
    }
}

private fun decodeBase64(text: String) = runCatching { Base64.decode(text.trim(), Base64.DEFAULT) }.getOrNull()

// constraint_set1 makes Baseline "Constrained", as most cameras' substreams are
private fun h264ProfileName(idc: Int, constraints: Int = 0) = when (idc) {
    66 -> if (constraints and 0x40 != 0) "Constrained Baseline" else "Baseline"
    77 -> "Main"
    88 -> "Extended"
    100 -> "High"
    110 -> "High 10"
    122 -> "High 4:2:2"
    244 -> "High 4:4:4"
    else -> "profile $idc"
}

private fun h264ProfileLevel(id: String): String {
    val value = id.toIntOrNull(16) ?: return "unreadable"
    val level = value and 0xFF
    return "${h264ProfileName(value shr 16, (value shr 8) and 0xFF)}, level ${level / 10}.${level % 10}"
}

@OptIn(UnstableApi::class)
private fun h264SpsSummary(sps: NalUnitUtil.SpsData, size: Int) =
    "${sps.width}×${sps.height} · ${h264ProfileName(sps.profileIdc, sps.constraintsFlagsAndReservedZero2Bits)} ${sps.levelIdc / 10}.${sps.levelIdc % 10} · " +
        "${8 + sps.bitDepthLumaMinus8}-bit · ${if (sps.frameMbsOnlyFlag) "progressive" else "interlaced"} · " +
        "${sps.maxNumRefFrames} ref frames · $size bytes"

@OptIn(UnstableApi::class)
private fun h265SpsSummary(sps: NalUnitUtil.H265SpsData, size: Int): String {
    val ptl = sps.profileTierLevel ?: return "${sps.width}×${sps.height} · ${8 + sps.bitDepthLumaMinus8}-bit · $size bytes"
    val profile = when (ptl.generalProfileIdc) {
        1 -> "Main"
        2 -> "Main 10"
        3 -> "Main Still Picture"
        4 -> "Range extensions"
        else -> "profile ${ptl.generalProfileIdc}"
    }
    val chroma = when (sps.chromaFormatIdc) {
        0 -> "4:0:0"
        1 -> "4:2:0"
        2 -> "4:2:2"
        else -> "4:4:4"
    }
    return "${sps.width}×${sps.height} · $profile, level ${"%.1f".format(ptl.generalLevelIdc / 30.0)} " +
        "${if (ptl.generalTierFlag) "High" else "Main"} tier · ${8 + sps.bitDepthLumaMinus8}-bit $chroma · $size bytes"
}

private val AacRates = listOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

// AudioSpecificConfig; LATM's StreamMuxConfig carries it 15 bits in
private fun aacConfig(hex: String, latm: Boolean): String {
    val bytes = hex.chunked(2).mapNotNull { it.toIntOrNull(16) }
    val bits = bytes.joinToString("") { it.toString(2).padStart(8, '0') }
    val start = if (latm) 15 else 0
    if (bits.length < start + 13) return "unreadable"
    val type = bits.substring(start, start + 5).toInt(2)
    val rate = bits.substring(start + 5, start + 9).toInt(2)
    val channels = bits.substring(start + 9, start + 13).toInt(2)
    val name = when (type) {
        1 -> "AAC Main"
        2 -> "AAC-LC"
        4 -> "AAC LTP"
        5 -> "HE-AAC (SBR)"
        29 -> "HE-AAC v2 (PS)"
        39 -> "AAC-ELD"
        else -> "object type $type"
    }
    val hz = AacRates.getOrNull(rate)?.let { "${it / 1000.0} kHz".replace(".0 ", " ") } ?: "rate index $rate"
    val layout = when (channels) {
        1 -> "mono"
        2 -> "stereo"
        else -> "$channels channels"
    }
    return "$name · $hz · $layout"
}

// The decoder players pick first for this type, and whether it takes this size
private fun decoderLine(mime: String, width: Int, height: Int): Pair<String, Mark> {
    val info = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.firstOrNull { !it.isEncoder && mime in it.supportedTypes }
        ?: return "none for $mime on this TV" to Mark.Bad
    val caps = info.getCapabilitiesForType(mime)
    val hardware = if (Build.VERSION.SDK_INT >= 29) info.isHardwareAccelerated else !info.name.startsWith("OMX.google.") && !info.name.startsWith("c2.android.")
    val size = caps.videoCapabilities?.takeIf { width > 0 && height > 0 }?.let { video ->
        if (video.isSizeSupported(width, height)) " · ${width}×$height supported" else " · ${width}×$height NOT supported"
    }.orEmpty()
    return "${info.name} · ${if (hardware) "hardware" else "software"} · up to ${caps.maxSupportedInstances} at once$size" to
        if (size.contains("NOT")) Mark.Bad else Mark.Info
}

// What a few seconds of one track brought
private class TrackSample(val track: TestTrack) {
    var packets = 0
    var padded = 0
    var bytes = 0L
    var lost = 0
    private var lastSeq = -1
    private var lastRawTs = 0L
    private var ts = 0L
    var firstTs = -1L
    var lastTs = -1L
    var frames = 0
    var firstAt = 0L
    var lastAt = 0L
    val nalTypes = mutableSetOf<Int>()
    val keyframes = mutableListOf<Long>()
    var inBandSps: ByteArray? = null
    val aac = if (track.encoding == "MPEG4-GENERIC") AacFraming(track) else null

    fun add(packet: ByteArray, now: Long) {
        if (packet.size < 12 || packet[0].toInt() and 0xC0 != 0x80) return
        val first = packets == 0
        packets += 1
        if (first) firstAt = now
        lastAt = now
        val seq = u16(packet, 2)
        if (lastSeq >= 0) {
            val gap = (seq - lastSeq - 1) and 0xFFFF
            if (gap in 1..1000) lost += gap
        }
        lastSeq = seq
        val raw = u32(packet, 4)
        ts = if (first) 0 else ts + (raw - lastRawTs).toInt()
        lastRawTs = raw
        if (first) firstTs = ts
        if (ts != lastTs) frames += 1
        lastTs = ts
        var offset = 12 + (packet[0].toInt() and 0x0F) * 4
        if (packet[0].toInt() and 0x10 != 0 && packet.size >= offset + 4) offset += 4 + u16(packet, offset + 2) * 4
        if (packet[0].toInt() and 0x20 != 0) padded += 1
        val end = packet.size - if (packet[0].toInt() and 0x20 != 0) packet.last().toInt() and 0xFF else 0
        if (offset >= end) return
        bytes += end - offset
        val payload = packet.copyOfRange(offset, end)
        aac?.add(payload, marker = packet[1].toInt() and 0x80 != 0)
        when (track.encoding) {
            "H264" -> h264Units(payload)
            "H265" -> h265Units(payload)
            else -> emptyList()
        }.forEach { (type, unit) ->
            nalTypes += type
            val key = if (track.encoding == "H264") type == 5 else type in 16..21
            if (key && keyframes.lastOrNull() != ts) keyframes += ts
            val sps = if (track.encoding == "H264") type == 7 else type == 33
            if (sps && unit != null && inBandSps == null) inBandSps = unit
        }
    }

    // (type, the whole unit when it's all in this packet)
    private fun h264Units(p: ByteArray): List<Pair<Int, ByteArray?>> = when (val type = p[0].toInt() and 0x1F) {
        24 -> aggregated(p, 1).map { (it[0].toInt() and 0x1F) to it }
        28 -> if (p.size > 1 && p[1].toInt() and 0x80 != 0) listOf((p[1].toInt() and 0x1F) to null) else emptyList()
        else -> listOf(type to p)
    }

    private fun h265Units(p: ByteArray): List<Pair<Int, ByteArray?>> {
        if (p.size < 2) return emptyList()
        return when (val type = (p[0].toInt() shr 1) and 0x3F) {
            48 -> aggregated(p, 2).map { ((it[0].toInt() shr 1) and 0x3F) to it }
            49 -> if (p.size > 2 && p[2].toInt() and 0x80 != 0) listOf((p[2].toInt() and 0x3F) to null) else emptyList()
            else -> listOf(type to p)
        }
    }

    // Units packed one after another, each after its 16-bit size
    private fun aggregated(p: ByteArray, start: Int): List<ByteArray> {
        val units = mutableListOf<ByteArray>()
        var i = start
        while (i + 2 < p.size) {
            val size = u16(p, i)
            if (size == 0 || i + 2 + size > p.size) break
            units += p.copyOfRange(i + 2, i + 2 + size)
            i += 2 + size
        }
        return units
    }
}

/**
 * An AAC track's packets (RFC 3640): how the camera frames them, and the frames cut two ways. As
 * Media3 1.11 cuts them: AU headers of 13 + 3 bits (6 + 2 in AAC-lbr) whatever the description says,
 * and with one AU header the last AU-size bytes once the marker bit is set. As the AU headers place
 * them: each AU's size, one after another past the headers.
 */
@OptIn(UnstableApi::class)
private class AacFraming(track: TestTrack) {
    private val lowBitrate = track.parameters["mode"].equals("AAC-lbr", ignoreCase = true)
    private val sizeBits = track.parameters["sizelength"]?.toIntOrNull() ?: if (lowBitrate) 6 else 13
    private val indexBits = track.parameters["indexlength"]?.toIntOrNull() ?: if (lowBitrate) 2 else 3
    private val deltaBits = track.parameters["indexdeltalength"]?.toIntOrNull() ?: indexBits
    private val media3SizeBits = if (lowBitrate) 6 else 13
    private val media3HeaderBits = media3SizeBits + if (lowBitrate) 2 else 3

    var packets = 0
    var markers = 0
    // AU headers in a packet → packets
    val auCounts = sortedMapOf<Int, Int>()
    // Bytes past the AU headers minus the AU sizes → packets
    val leftovers = sortedMapOf<Int, Int>()
    val firstPayloads = mutableListOf<String>()
    val media3Frames = mutableListOf<ByteArray>()
    val headerFrames = mutableListOf<ByteArray>()

    // What Media3 writes to its sample queue: a sample is the last AU-size bytes written
    private val written = ByteArrayOutputStream()

    fun add(payload: ByteArray, marker: Boolean) {
        if (payload.size < 2) return
        packets += 1
        if (marker) markers += 1
        if (firstPayloads.size < 3) firstPayloads += hex(payload, 12)
        val headersBits = u16(payload, 0)
        val dataStart = 2 + (headersBits + 7) / 8
        if (dataStart > payload.size) return
        val bits = ParsableBitArray(payload)

        bits.setPosition(16)
        val sizes = mutableListOf<Int>()
        var used = 0
        while (used + sizeBits + (if (sizes.isEmpty()) indexBits else deltaBits) <= headersBits) {
            sizes += bits.readBits(sizeBits)
            val index = if (sizes.size == 1) indexBits else deltaBits
            bits.skipBits(index)
            used += sizeBits + index
        }
        auCounts.merge(sizes.size, 1, Int::plus)
        leftovers.merge(payload.size - dataStart - sizes.sum(), 1, Int::plus)
        var at = dataStart
        sizes.forEach { size ->
            if (at + size <= payload.size) headerFrames += payload.copyOfRange(at, at + size)
            at += size
        }

        bits.setPosition(16)
        val count = headersBits / media3HeaderBits
        if (count == 1) {
            val size = bits.readBits(media3SizeBits)
            written.write(payload, 2, payload.size - 2)
            if (marker) {
                val all = written.toByteArray()
                if (size <= all.size) media3Frames += all.copyOfRange(all.size - size, all.size)
            }
        } else {
            var from = dataStart
            repeat(count) {
                val size = bits.readBits(media3SizeBits)
                bits.skipBits(media3HeaderBits - media3SizeBits)
                if (from + size <= payload.size) media3Frames += payload.copyOfRange(from, from + size)
                from += size
            }
        }
    }
}

private fun hex(bytes: ByteArray, count: Int) = bytes.take(count).joinToString(" ") { "%02X".format(it) } + if (bytes.size > count) " …" else ""

// Starts with an ADTS header, the framing of .aac files, which RTP's AAC leaves out
private fun isAdts(frame: ByteArray) = frame.size > 7 && frame[0] == 0xFF.toByte() && frame[1].toInt() and 0xF6 == 0xF0

private fun withoutAdts(frame: ByteArray) = if (isAdts(frame)) frame.copyOfRange(if (frame[1].toInt() and 1 == 1) 7 else 9, frame.size) else frame

private class DecodeResult(val frames: Int, val outputs: Int, val silent: Int, val peak: Int)

/**
 * Decodes AAC frames on this TV's decoder with the AudioSpecificConfig [config]. Its software decoder
 * puts silence in place of a frame it rejects, so all-zero output means rejected frames (or a camera
 * sending pure silence).
 */
private fun decodeAac(frames: List<ByteArray>, config: ByteArray, rate: Int, channels: Int): DecodeResult? {
    if (frames.isEmpty() || rate <= 0) return null
    val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
        setByteBuffer("csd-0", ByteBuffer.wrap(config))
    }
    val codec = runCatching { MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) }.getOrNull() ?: return null
    var outputs = 0
    var silent = 0
    var peak = 0
    try {
        codec.configure(format, null, null, 0)
        codec.start()
        val info = MediaCodec.BufferInfo()
        var queued = 0
        var ended = false
        val frameUs = 1_024_000_000L / rate
        val deadline = SystemClock.elapsedRealtime() + 3_000
        while (SystemClock.elapsedRealtime() < deadline) {
            if (!ended) {
                val input = codec.dequeueInputBuffer(10_000)
                if (input >= 0) {
                    if (queued < frames.size) {
                        val frame = frames[queued]
                        codec.getInputBuffer(input)?.apply { clear(); put(frame) }
                        codec.queueInputBuffer(input, 0, frame.size, queued * frameUs, 0)
                        queued += 1
                    } else {
                        codec.queueInputBuffer(input, 0, 0, queued * frameUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        ended = true
                    }
                }
            }
            val output = codec.dequeueOutputBuffer(info, 10_000)
            if (output < 0) continue
            if (info.size > 0) {
                outputs += 1
                val pcm = codec.getOutputBuffer(output)?.apply { position(info.offset); limit(info.offset + info.size) }
                    ?.order(ByteOrder.nativeOrder())?.asShortBuffer()
                var loudest = 0
                while (pcm != null && pcm.hasRemaining()) loudest = maxOf(loudest, abs(pcm.get().toInt()))
                if (loudest == 0) silent += 1
                peak = maxOf(peak, loudest)
            }
            codec.releaseOutputBuffer(output, false)
            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
        }
    } catch (e: Exception) {
        AppLog.w(TAG, "AAC decode test failed", e)
        return null
    } finally {
        runCatching { codec.stop() }
        codec.release()
    }
    return DecodeResult(frames.size, outputs, silent, peak)
}

private suspend fun reportDecode(r: Reporter, label: String, result: DecodeResult?) {
    if (result == null) {
        r.line(label, "couldn't run this TV's AAC decoder", Mark.Warn, indent = true)
        return
    }
    val level = if (result.peak > 0) " · peak %.0f dBFS".format(20 * log10(result.peak / 32768.0)) else ""
    val sound = result.outputs - result.silent
    r.line(
        label,
        "${result.frames} frames in, ${result.outputs} out · " + when {
            result.outputs == 0 -> "no sound came out"
            result.silent == 0 -> "all with sound$level"
            sound == 0 -> "all silent: this TV's decoder rejects them"
            else -> "$sound with sound, ${result.silent} silent$level"
        },
        when {
            result.outputs > 0 && result.silent == 0 -> Mark.Good
            sound > result.silent -> Mark.Warn
            else -> Mark.Bad
        },
        indent = true,
    )
}

@OptIn(UnstableApi::class)
private suspend fun reportAac(r: Reporter, track: TestTrack, aac: AacFraming) {
    if (aac.packets == 0) return
    fun counts(map: Map<Int, Int>, name: (Int) -> String) = map.entries.joinToString(", ") { (key, packets) ->
        name(key) + if (map.size > 1) " in $packets" else ""
    }
    r.line("AU headers", counts(aac.auCounts) { if (it == 1) "1 frame per packet" else "$it frames per packet" }, if (0 in aac.auCounts) Mark.Bad else Mark.Info, indent = true)
    val leftover = counts(aac.leftovers) { bytes ->
        when {
            bytes == 0 -> "sizes match the packets"
            bytes > 0 -> "$bytes bytes more than the sizes say"
            else -> "${-bytes} bytes fewer than the sizes say"
        }
    }
    r.line("AU sizes", leftover, if (aac.leftovers.keys == setOf(0)) Mark.Info else Mark.Warn, indent = true)
    r.line(
        "Marker bit",
        if (aac.markers == aac.packets) "on every packet" else "on ${aac.markers} of ${aac.packets} packets: Media3 only takes a frame from a marked one",
        if (aac.markers == aac.packets) Mark.Info else Mark.Warn,
        indent = true,
    )
    aac.firstPayloads.forEachIndexed { i, bytes -> r.line(if (i == 0) "First packets" else "", bytes, indent = true) }
    val adts = aac.headerFrames.count(::isAdts)
    if (adts > 0) r.line("ADTS", "$adts of ${aac.headerFrames.size} frames start with an ADTS header, which RTP leaves out", Mark.Warn, indent = true)
    aac.media3Frames.take(3).forEachIndexed { i, frame ->
        r.line(if (i == 0) "Frames as Media3" else "", "${frame.size} bytes · ${hex(frame, 10)}", indent = true)
    }

    val channels = track.channels.coerceAtLeast(1)
    // Media3 builds its own AAC-LC config from the clock rate and channels, ignoring the camera's
    val media3Config = AacUtil.buildAacLcAudioSpecificConfig(track.clockRate, channels)
    reportDecode(r, "Decoded here", decodeAac(aac.media3Frames, media3Config, track.clockRate, channels))
    val sameCut = aac.headerFrames.size == aac.media3Frames.size && aac.headerFrames.indices.all { aac.headerFrames[it].contentEquals(aac.media3Frames[it]) }
    if (!sameCut) {
        aac.headerFrames.take(3).forEachIndexed { i, frame ->
            r.line(if (i == 0) "Frames by headers" else "", "${frame.size} bytes · ${hex(frame, 10)}", indent = true)
        }
        reportDecode(r, "Cut by headers", decodeAac(aac.headerFrames, media3Config, track.clockRate, channels))
    }
    if (adts > 0) reportDecode(r, "Without ADTS", decodeAac(aac.headerFrames.map(::withoutAdts), media3Config, track.clockRate, channels))
    val cameraConfig = track.parameters["config"]?.let { hexBytes(it) }
    if (cameraConfig != null && !cameraConfig.contentEquals(media3Config)) {
        reportDecode(r, "Camera's config", decodeAac(aac.headerFrames.map(::withoutAdts), cameraConfig, track.clockRate, channels))
    }
}

private fun hexBytes(text: String) = text.takeIf { it.length % 2 == 0 }?.chunked(2)?.map { it.toIntOrNull(16)?.toByte() ?: return null }?.toByteArray()

private fun u16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)

private fun u32(b: ByteArray, i: Int) = (u16(b, i).toLong() shl 16) or u16(b, i + 2).toLong()

@OptIn(UnstableApi::class)
private suspend fun sample(r: Reporter, rtsp: RtspTalk, tracks: List<TestTrack>, base: String) {
    val samples = mutableMapOf<Int, TrackSample>()
    for (track in tracks.filter { it.media == "video" || it.media == "audio" }) {
        val control = track.control
        val trackUrl = when {
            control == null || control == "*" -> base
            control.startsWith("rtsp://", ignoreCase = true) || control.startsWith("rtsps://", ignoreCase = true) -> control
            else -> base.trimEnd('/') + "/" + control
        }
        val channel = samples.size * 2
        val setup = rtsp.request("SETUP", trackUrl, mapOf("Transport" to "RTP/AVP/TCP;unicast;interleaved=$channel-${channel + 1}"))
        if (setup.code != 200) {
            r.line("SETUP ${track.label}", setup.summary + if (setup.code == 461) ": refuses RTP over TCP, the sample needs it" else "", Mark.Warn)
            continue
        }
        setup.header("session")?.let { rtsp.session = it.substringBefore(';').trim() }
        val transport = setup.header("transport").orEmpty()
        val actual = Regex("""interleaved=(\d+)""").find(transport)?.groupValues?.get(1)?.toIntOrNull() ?: channel
        val timeout = setup.header("session")?.let { Regex("""timeout=(\d+)""").find(it)?.groupValues?.get(1) }
        r.line("SETUP ${track.label}", setup.summary + " · channels $actual-${actual + 1}" + (timeout?.let { " · session timeout $it s" } ?: ""), Mark.Good)
        samples[actual] = TrackSample(track)
    }
    if (samples.isEmpty()) return
    val play = rtsp.request("PLAY", base, mapOf("Range" to "npt=0.000-"))
    if (play.code != 200) {
        r.line("PLAY", play.summary, Mark.Bad)
        return
    }
    r.line("PLAY", play.summary + (play.header("range")?.let { " · $it" } ?: ""), Mark.Good)
    val playedAt = SystemClock.elapsedRealtime()
    rtsp.sampling = true
    while (SystemClock.elapsedRealtime() - playedAt < SAMPLE_MS) {
        val frame = try {
            rtsp.readInterleaved()
        } catch (e: SocketTimeoutException) {
            null
        } ?: continue
        samples[frame.first]?.add(frame.second, SystemClock.elapsedRealtime())
    }
    rtsp.sampling = false
    runCatching { rtsp.request("TEARDOWN", base) }

    r.line("Sample", "${SAMPLE_MS / 1000} s over RTSP's TCP connection")
    samples.values.forEach { s ->
        val track = s.track
        val label = "${track.label} ${track.media}"
        if (s.packets == 0) {
            r.line(
                label,
                "no packets in ${SAMPLE_MS / 1000} s" + if (track.media == "audio") ": the camera describes audio but sends none" else "",
                Mark.Bad,
            )
            return@forEach
        }
        val seconds = (s.lastAt - s.firstAt).coerceAtLeast(1) / 1000.0
        val rate = s.bytes * 8 / seconds
        val bitrate = if (rate >= 1_000_000) "%.2f Mbit/s".format(rate / 1_000_000) else "%.0f kbit/s".format(rate / 1000)
        r.line(label, "${s.packets} packets · $bitrate · first after ${s.firstAt - playedAt} ms", Mark.Good)
        if (s.lost > 0) r.line("Lost", "${s.lost} packets (sequence gaps)", Mark.Warn, indent = true)
        if (s.padded > 0) {
            r.line(
                "Padding",
                "on ${s.padded} of ${s.packets} packets: the app takes it out over TCP only, so this camera needs TCP",
                Mark.Warn,
                indent = true,
            )
        }
        s.aac?.let { reportAac(r, track, it) }
        if (track.media != "video" || track.clockRate <= 0) return@forEach
        val span = (s.lastTs - s.firstTs).toDouble() / track.clockRate
        if (s.frames > 1 && span > 0) r.line("Frame rate", "%.1f fps (%d frames in %.2f s of stream time)".format((s.frames - 1) / span, s.frames, span), indent = true)
        r.line(
            "Keyframes",
            when (s.keyframes.size) {
                0 -> "none in ${SAMPLE_MS / 1000} s: a picture can take that long to start"
                1 -> "1 in ${SAMPLE_MS / 1000} s: further apart than the sample"
                else -> "every %.2f s".format((s.keyframes.last() - s.keyframes.first()).toDouble() / track.clockRate / (s.keyframes.size - 1))
            },
            if (s.keyframes.isEmpty()) Mark.Warn else Mark.Info,
            indent = true,
        )
        val sets = if (track.encoding == "H264") listOf(7 to "SPS", 8 to "PPS") else listOf(32 to "VPS", 33 to "SPS", 34 to "PPS")
        val inStream = sets.filter { it.first in s.nalTypes }.map { it.second }
        r.line("In the stream", if (inStream.isEmpty()) "no parameter sets seen" else inStream.joinToString(" ") + " seen", indent = true)
        if (!track.format.contains("sprop") && inStream.isNotEmpty()) {
            r.line("Note", "the camera sends its parameter sets in the stream only: Media3 needs them in the description", Mark.Warn, indent = true)
        }
        s.inBandSps?.let { sps ->
            val summary = runCatching {
                if (track.encoding == "H264") h264SpsSummary(NalUnitUtil.parseSpsNalUnit(sps, 0, sps.size), sps.size)
                else h265SpsSummary(NalUnitUtil.parseH265SpsNalUnit(sps, 0, sps.size, null), sps.size)
            }.getOrNull()
            summary?.let { r.line("SPS in stream", it, indent = true) }
        }
        val names = s.nalTypes.sorted().joinToString(" ")
        r.line("NAL types", names, indent = true)
    }
}

private class RtspAnswer(val code: Int, val status: String, private val headers: Map<String, String>, val body: ByteArray, val ms: Long) {
    fun header(name: String) = headers[name.lowercase()]

    val summary get() = "$status · $ms ms"
}

/** A bare RTSP conversation: requests one at a time, with Basic or Digest login when asked */
private class RtspTalk(socket: Socket, private val user: String?, private val password: String?) {
    private val input = BufferedInputStream(socket.getInputStream())
    private val output = socket.getOutputStream()
    private val socket = socket
    private var cseq = 0
    private var scheme: String? = null
    private var params: Map<String, String> = emptyMap()
    private var nonceCount = 0
    var session: String? = null

    /** The login the camera asked for, e.g. Digest, realm "IP Camera" */
    var challenge: String? = null

    /** Reading media: short timeouts, so the sample ends on time */
    var sampling = false
        set(value) {
            field = value
            socket.soTimeout = if (value) SAMPLE_READ_TIMEOUT_MS else ANSWER_TIMEOUT_MS
        }

    fun request(method: String, url: String, headers: Map<String, String> = emptyMap()): RtspAnswer {
        val answer = send(method, url, headers)
        if (answer.code != 401 || user == null || scheme != null) return answer
        val offers = answer.header("www-authenticate").orEmpty().split('\n')
        val offer = offers.firstOrNull { it.trim().startsWith("Digest", ignoreCase = true) }
            ?: offers.firstOrNull { it.trim().startsWith("Basic", ignoreCase = true) }
            ?: return answer
        scheme = offer.trim().substringBefore(' ').lowercase()
        params = Regex("""(\w+)\s*=\s*(?:"([^"]*)"|([^,\s]*))""").findAll(offer).associate { it.groupValues[1].lowercase() to it.groupValues[2].ifEmpty { it.groupValues[3] } }
        challenge = "${scheme!!.replaceFirstChar { it.uppercase() }}" + (params["realm"]?.let { ", realm \"$it\"" } ?: "") +
            (params["qop"]?.let { ", qop $it" } ?: "") + (params["algorithm"]?.let { ", $it" } ?: "")
        return send(method, url, headers)
    }

    private fun send(method: String, url: String, headers: Map<String, String>): RtspAnswer {
        val started = SystemClock.elapsedRealtime()
        cseq += 1
        val text = buildString {
            append("$method $url RTSP/1.0\r\nCSeq: $cseq\r\nUser-Agent: HomeBoard camera test\r\n")
            authorization(method, url)?.let { append("Authorization: $it\r\n") }
            session?.let { append("Session: $it\r\n") }
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("\r\n")
        }
        output.write(text.toByteArray(Charsets.ISO_8859_1))
        output.flush()
        while (true) {
            // Media still arriving, e.g. before TEARDOWN's answer
            if (peek() == '$'.code) {
                skipInterleaved()
                continue
            }
            val status = readLine()
            if (status.isEmpty()) continue
            val answerHeaders = mutableMapOf<String, String>()
            while (true) {
                val line = readLine()
                if (line.isEmpty()) break
                val name = line.substringBefore(':').trim().lowercase()
                val value = line.substringAfter(':', "").trim()
                answerHeaders[name] = answerHeaders[name]?.let { "$it\n$value" } ?: value
            }
            val body = readBytes(answerHeaders["content-length"]?.toIntOrNull() ?: 0)
            val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: 0
            return RtspAnswer(code, status.substringAfter(' ').trim(), answerHeaders, body, SystemClock.elapsedRealtime() - started)
        }
    }

    // RFC 2617, as cameras use it for RTSP
    private fun authorization(method: String, url: String): String? = when (scheme) {
        "basic" -> "Basic " + Base64.encodeToString("$user:${password.orEmpty()}".toByteArray(), Base64.NO_WRAP)
        "digest" -> {
            val realm = params["realm"].orEmpty()
            val nonce = params["nonce"].orEmpty()
            val ha1 = md5("$user:$realm:${password.orEmpty()}")
            val ha2 = md5("$method:$url")
            val qop = params["qop"]?.split(',')?.map { it.trim() }?.firstOrNull { it == "auth" }
            buildString {
                append("Digest username=\"$user\", realm=\"$realm\", nonce=\"$nonce\", uri=\"$url\"")
                if (qop != null) {
                    nonceCount += 1
                    val nc = "%08x".format(nonceCount)
                    val cnonce = Random.nextBytes(8).joinToString("") { "%02x".format(it) }
                    append(", response=\"${md5("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")}\", qop=$qop, nc=$nc, cnonce=\"$cnonce\"")
                } else {
                    append(", response=\"${md5("$ha1:$nonce:$ha2")}\"")
                }
                params["opaque"]?.let { append(", opaque=\"$it\"") }
                params["algorithm"]?.let { append(", algorithm=$it") }
            }
        }
        else -> null
    }

    /** One media packet: its channel and bytes; null for anything else, e.g. an RTSP message from the camera */
    fun readInterleaved(): Pair<Int, ByteArray>? {
        if (peek() != '$'.code) {
            // e.g. the camera's own requests or keep-alive answers
            while (readLine().isNotEmpty()) Unit
            return null
        }
        // The rest of the packet is on its way: a short timeout would cut it and lose track of the stream
        socket.soTimeout = ANSWER_TIMEOUT_MS
        try {
            input.read()
            val channel = input.read()
            val length = (input.read() shl 8) or input.read()
            return channel to readBytes(length)
        } finally {
            if (sampling) socket.soTimeout = SAMPLE_READ_TIMEOUT_MS
        }
    }

    private fun skipInterleaved() {
        input.read()
        input.read()
        val length = (input.read() shl 8) or input.read()
        readBytes(length)
    }

    private fun peek(): Int {
        input.mark(1)
        val c = input.read()
        input.reset()
        if (c == -1) throw EOFException()
        return c
    }

    private fun readLine(): String {
        val line = ByteArrayOutputStream()
        while (true) {
            val c = input.read()
            if (c == -1) throw EOFException()
            if (c == '\n'.code) break
            if (c != '\r'.code) line.write(c)
        }
        return line.toString(Charsets.ISO_8859_1.name())
    }

    private fun readBytes(length: Int): ByteArray = ByteArray(length).also { readFully(input, it) }
}

private fun readFully(input: InputStream, buffer: ByteArray) {
    var read = 0
    while (read < buffer.size) {
        val count = input.read(buffer, read, buffer.size - read)
        if (count == -1) throw EOFException()
        read += count
    }
}

private fun md5(text: String) = MessageDigest.getInstance("MD5").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
