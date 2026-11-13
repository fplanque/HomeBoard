package com.evofactory.homeboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme

/**
 * Says a list in a dialog goes on past what shows: a chevron at the bottom pointing down while
 * there's more below, and one at the top pointing up once the top has scrolled out of view, each
 * over a fade from the dialog's colour. Only a sign, nothing to select: the remote scrolls the list
 * as the selection moves.
 */
fun Modifier.scrollIndicators(state: ScrollableState): Modifier = composed {
    val below by animateFloatAsState(if (state.canScrollForward) 1f else 0f, label = "moreBelow")
    val above by animateFloatAsState(if (state.canScrollBackward) 1f else 0f, label = "moreAbove")
    val chevron = MaterialTheme.colorScheme.onSurface
    drawWithContent {
        drawContent()
        if (above > 0f) edge(above, top = true, chevron)
        if (below > 0f) edge(below, top = false, chevron)
    }
}

private fun DrawScope.edge(shown: Float, top: Boolean, chevron: androidx.compose.ui.graphics.Color) {
    val dialog = AppColorScheme.surfaceVariant
    val fade = 32.dp.toPx()
    val y = if (top) 0f else size.height - fade
    val solid = dialog.copy(alpha = 0.95f * shown)
    val clear = dialog.copy(alpha = 0f)
    drawRect(
        Brush.verticalGradient(if (top) listOf(solid, clear) else listOf(clear, solid), startY = y, endY = y + fade),
        topLeft = Offset(0f, y),
        size = Size(size.width, fade),
    )
    // A wide, flat chevron, pointing out of the list
    val half = 14.dp.toPx()
    val rise = 6.dp.toPx()
    val tipY = if (top) 6.dp.toPx() else size.height - 6.dp.toPx()
    val armY = if (top) tipY + rise else tipY - rise
    val cx = size.width / 2
    val color = chevron.copy(alpha = shown)
    val stroke = 3.dp.toPx()
    drawLine(color, Offset(cx - half, armY), Offset(cx, tipY), stroke, StrokeCap.Round)
    drawLine(color, Offset(cx, tipY), Offset(cx + half, armY), stroke, StrokeCap.Round)
}
