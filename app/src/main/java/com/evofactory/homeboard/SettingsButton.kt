package com.evofactory.homeboard

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/** The cog's size: the top row leaves room for it */
val SettingsButtonSize = 40.dp

/** Cog at the right of the top bar; opens the launcher's settings menu */
@Composable
fun SettingsButton(
    showHidden: Boolean,
    hiddenCount: Int,
    onShowHiddenChange: (Boolean) -> Unit,
    onOpenCameras: () -> Unit,
    onOpenHomeAssistant: () -> Unit,
    onOpenFrigate: () -> Unit,
    onOpenLauncherSettings: () -> Unit,
    showDebugInfo: Boolean,
    onShowDebugInfoChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var menuOpen by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }

    Box(modifier = modifier) {
        IconButton(
            onClick = { menuOpen = true },
            modifier = Modifier.size(SettingsButtonSize).onFocusChanged { focused = it.isFocused },
            colors = IconButtonDefaults.colors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        ) {
            Icon(painter = painterResource(R.drawable.ic_settings), contentDescription = "Settings")
        }
        if (focused) TopBarButtonLabel("Settings")
        if (menuOpen) {
            ContextMenu(
                // The cog says what it is
                title = null,
                actions = listOf(
                    // The system Settings app has no tile in the row, so it's reachable from here
                    MenuAction(if (context.isGoogleTv()) "Google TV settings" else "Android TV settings") {
                        context.openSystemSettings()
                    },
                    MenuAction("HomeBoard settings", onOpenLauncherSettings),
                    MenuAction("Cameras", onOpenCameras),
                    MenuAction("Home Assistant", onOpenHomeAssistant),
                    MenuAction("Frigate", onOpenFrigate),
                    showHiddenAction(showHidden, hiddenCount) { onShowHiddenChange(!showHidden) },
                    // Labels each video HW (the TV's video pipeline) or GPU, and shows the free memory in the top bar
                    MenuAction(if (showDebugInfo) "Hide debug info" else "Display debug info") { onShowDebugInfoChange(!showDebugInfo) },
                ),
                onDismiss = { menuOpen = false },
                placement = MenuPlacement.BelowAtRightMargin,
            )
        }
    }
}

/** Google TV rather than plain Android TV: Google TV devices declare this feature */
private fun Context.isGoogleTv() = packageManager.hasSystemFeature("com.google.android.feature.AMATI_EXPERIENCE")

/**
 * The name of a round top bar button, for inside a box of the button's size while the remote is on
 * it. Takes no room in the bar: centred under the button, in the margin, however long the name.
 */
@Composable
fun TopBarButtonLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.layout { measurable, _ ->
            val label = measurable.measure(Constraints(maxWidth = TopBarLabelMaxWidth.roundToPx()))
            val button = SettingsButtonSize.roundToPx()
            layout(0, 0) {
                label.place((button - label.width) / 2, button + TopBarLabelGap.roundToPx())
            }
        },
    )
}

private val TopBarLabelMaxWidth = 200.dp

// Clears the button, which grows a little while selected
private val TopBarLabelGap = 4.dp

/** Opens a screen of the TV's Settings app, or its main screen if this TV doesn't have that one. */
fun Context.openSystemSettings(action: String = Settings.ACTION_SETTINGS) {
    try {
        startActivity(Intent(if (hasSettingsScreen(action)) action else Settings.ACTION_SETTINGS))
    } catch (e: ActivityNotFoundException) {
        AppLog.w("Settings", "No screen for $action", e)
        startActivity(Intent(Settings.ACTION_SETTINGS))
    }
}

/**
 * Whether the TV's Settings app has this screen. Some TVs answer screens they lack with a
 * placeholder that only says no app can open it, e.g. Sony for Accessibility, or with an empty
 * screen of their own, e.g. Sony's `EmptyStubActivity` for Home app on the Living Room TV.
 */
fun Context.hasSettingsScreen(action: String): Boolean {
    val handler = packageManager.resolveActivity(Intent(action), PackageManager.MATCH_DEFAULT_ONLY) ?: return false
    return !handler.activityInfo.packageName.endsWith(".frameworkpackagestubs") &&
        !handler.activityInfo.name.endsWith(".EmptyStubActivity")
}
