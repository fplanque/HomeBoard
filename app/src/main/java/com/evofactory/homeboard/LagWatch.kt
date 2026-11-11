package com.evofactory.homeboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * How far behind live a camera's picture is. Over TCP nothing is lost: a slow network delays
 * everything after it, and the player, which plays at normal speed by the camera's timestamps, keeps
 * showing a moving picture that has fallen behind. (Its catch-up speed only drains what has already
 * arrived; it can't see what's still stuck on the way.)
 *
 * The player's position follows the camera's clock. Now minus the position is meaningless by itself,
 * but while the picture is live it stays nearly constant: the smallest value seen is the baseline,
 * and anything above it is lag. A pause to buffer, or frames arriving late, raise it.
 *
 * The camera's clock and the TV's drift apart by up to about 100 ppm, so the baseline may creep up at
 * that rate (0.36 s an hour); real lag grows far faster. A position that jumps by more than a few
 * seconds between checks starts over instead of reading as lag. The iPad app measures the same thing
 * from the camera's timestamps as frames arrive (HomeBoard-iOS LagMeter.swift).
 */
class LagMeter {
    private var lastPositionMs: Long? = null
    private var lastNowMs = 0L
    private var baselineMs: Double? = null

    /** Seconds behind live, given the player's position and the time now (SystemClock.elapsedRealtime) */
    fun add(positionMs: Long, nowMs: Long): Float {
        val last = lastPositionMs
        if (last != null && abs(positionMs - last) > MAX_JUMP_MS) baselineMs = null
        val sinceLast = if (last == null) 0L else max(0L, nowMs - lastNowMs)
        lastPositionMs = positionMs
        lastNowMs = nowMs
        val offset = (nowMs - positionMs).toDouble()
        val baseline = baselineMs?.let { min(offset, it + MAX_DRIFT * sinceLast) } ?: offset
        baselineMs = baseline
        return ((offset - baseline) / 1000).toFloat()
    }

    /** For a new player, which starts live */
    fun reset() {
        lastPositionMs = null
        baselineMs = null
    }

    companion object {
        private const val MAX_DRIFT = 100e-6
        private const val MAX_JUMP_MS = 5_000L

        /** Shown from this far behind, held for two checks; hidden again below [HIDE_S] */
        const val SHOW_S = 1f
        const val HIDE_S = 0.5f
    }
}

// The design tokens' "warn"
private val LagColor = Color(0xFFFDD663)

/** Over a live picture that has fallen behind: how far, in seconds */
@Composable
fun LagLabel(seconds: Float, compact: Boolean, modifier: Modifier = Modifier) {
    Text(
        "Lag: " + String.format(Locale.ROOT, if (seconds < 10) "%.1f" else "%.0f", seconds) + " s",
        style = if (compact) MaterialTheme.typography.labelMedium else MaterialTheme.typography.titleSmall,
        color = Color.Black,
        modifier = modifier
            .background(LagColor, RoundedCornerShape(50))
            .padding(horizontal = if (compact) 6.dp else 10.dp, vertical = if (compact) 2.dp else 4.dp),
    )
}
