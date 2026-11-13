package com.evofactory.homeboard

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// The row sits this much higher than the tab's content: right under the top bar's tabs
private val SubTabRowLift = EdgePadding

// Shorter than the top bar's tabs, so the two rows sit close
private val SubTabRowHeight = 32.dp

/**
 * A row of names just under the top bar's tabs, choosing what the open tab shows: a dashboard's
 * views on the Assistant tab, alerts, detections or saved clips on the Clips tab. [below]: the room
 * between it and what it chooses, so the selection can't land on the wrong one by mistake.
 */
@Composable
fun SubTabRow(
    titles: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    inset: Dp,
    below: Dp = 4.dp,
    itemModifier: (Int) -> Modifier = { Modifier },
) {
    Row(
        Modifier
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val lift = SubTabRowLift.roundToPx()
                layout(placeable.width, placeable.height - lift) { placeable.place(0, -lift) }
            }
            .padding(start = inset, end = inset, bottom = below)
            .height(SubTabRowHeight),
    ) {
        titles.forEachIndexed { index, title ->
            TabButton(title, selected = index == selected, onSelect = { onSelect(index) }, secondary = true, modifier = itemModifier(index))
        }
    }
}
