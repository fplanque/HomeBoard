package com.evofactory.homeboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.service.dreams.DreamService
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import kotlinx.coroutines.delay

private const val TAG = "Screensaver"

/** How long each camera shows, in seconds: the steps of − and + in the Cameras dialog */
val SCREENSAVER_SECONDS = listOf(5, 10, 15, 20, 30, 45, 60, 90, 120, 180, 300)
const val SCREENSAVER_SECONDS_DEFAULT = 15

/** How long the TV waits without input before its screensaver, in seconds: the steps of − and + in the Screensaver tab */
private val SCREENSAVER_DELAYS = listOf(60, 120, 180, 300, 600, 900, 1200, 1800, 3600, 7200)

// A camera with no picture by then is skipped, so one that's down doesn't hold up the others
private const val NOT_LIVE_SKIP_MS = 20_000L

/**
 * The TV's screensaver, once chosen in Android's settings: the cameras full screen, one after the
 * other, in the camera list's order. Each shows for the time set in the Cameras dialog, counted from
 * its first picture. No sound. Any button ends it.
 *
 * In the launcher's process, so its players count towards the TV's decoders: with "Minimize
 * simultaneous video streams" on, each camera waits for the one before to let go of its decoder.
 * Not an activity, so it gives Compose a lifecycle of its own: started while dreaming.
 */
class CameraScreensaver : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = false
        isFullscreen = true
        isScreenBright = true
        val prefs = LauncherPrefs(this)
        val hidden = prefs.screensaverHiddenCameras.toSet()
        val cameras = prefs.cameras.filter { it.id !in hidden }
        val seconds = prefs.screensaverSeconds
        val minimizeStreams = prefs.minimizeVideoStreams
        val showProgress = prefs.screensaverShowProgress
        // The launcher may not have run in this process yet
        DebugOverlay.shown = prefs.showDebugInfo
        AppLog.i(TAG, "Showing ${cameras.size} cameras, $seconds s each")
        setContentView(
            ComposeView(this).apply {
                setViewTreeLifecycleOwner(this@CameraScreensaver)
                setViewTreeSavedStateRegistryOwner(this@CameraScreensaver)
                setContent {
                    MaterialTheme(colorScheme = AppColorScheme) {
                        CameraRotation(cameras, seconds, minimizeStreams, showProgress)
                    }
                }
            },
        )
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    override fun onDreamingStopped() {
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
        AppLog.i(TAG, "Stopped")
        super.onDreamingStopped()
    }

    override fun onDestroy() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }
}

@Composable
private fun CameraRotation(cameras: List<Camera>, seconds: Int, minimizeStreams: Boolean, showProgress: Boolean) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameras.isEmpty()) {
            Text(
                // None set up, or all turned off for the screensaver
                "No cameras to show: add them or turn them on in HomeBoard's Cameras settings",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }
        var index by remember { mutableIntStateOf(0) }
        var live by remember { mutableStateOf(false) }
        val camera = cameras[index]
        LaunchedEffect(index, live) {
            if (cameras.size < 2) return@LaunchedEffect
            delay(if (live) seconds * 1_000L else NOT_LIVE_SKIP_MS)
            if (!live) AppLog.i(TAG, "No picture from camera ${index + 1} after $NOT_LIVE_SKIP_MS ms, on to the next")
            live = false
            index = (index + 1) % cameras.size
        }
        CameraView(
            url = camera.url,
            useTcp = camera.useTcp,
            modifier = Modifier.fillMaxSize(),
            minimizeStreams = minimizeStreams,
            // No sound: never full screen in CameraView's sense
            waitForOtherPlayers = minimizeStreams,
            onLiveChange = { live = it },
            showProgress = showProgress,
            rounded = false,
            stretch = camera.stretch,
        )
        Text(
            camera.name,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(24.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** What Android's settings say about the screensaver. Null: Android didn't let the app read it. */
private class ScreensaverStatus(
    val enabled: Boolean?,
    // The chosen screensaver, null if none
    val chosen: ComponentName?,
    val readable: Boolean,
    // How long without input before it starts
    val startsAfterMs: Int?,
    // Whether the app may change that: the TV's "Modify system settings" permission
    val canChangeDelay: Boolean,
)

// Hidden settings: their names aren't in the SDK, but apps may read them
private const val SCREENSAVER_ENABLED = "screensaver_enabled"
private const val SCREENSAVER_COMPONENTS = "screensaver_components"
private const val SCREENSAVER_DEFAULT_COMPONENT = "screensaver_default_component"

private fun Context.readScreensaverStatus(): ScreensaverStatus {
    val resolver = contentResolver
    val (enabled, chosen, readable) = try {
        val enabled = Settings.Secure.getString(resolver, SCREENSAVER_ENABLED)?.let { it == "1" }
        // Comma-separated: the first one is the one shown. None chosen: Android shows the TV's default.
        fun first(key: String) = Settings.Secure.getString(resolver, key)
            ?.split(',')?.firstOrNull { it.isNotBlank() }?.let(ComponentName::unflattenFromString)
        val chosen = first(SCREENSAVER_COMPONENTS) ?: first(SCREENSAVER_DEFAULT_COMPONENT)
        Triple(enabled, chosen, true)
    } catch (e: SecurityException) {
        AppLog.w(TAG, "Can't read the screensaver settings", e)
        Triple(null, null, false)
    }
    val startsAfter = runCatching { Settings.System.getInt(resolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()
    return ScreensaverStatus(enabled, chosen, readable, startsAfter, Settings.System.canWrite(this))
}

/** Follows changes to the settings, e.g. made in Android's screensaver settings while the dialog stays open */
@Composable
private fun rememberScreensaverStatus(): ScreensaverStatus {
    val context = LocalContext.current
    var status by remember { mutableStateOf(context.readScreensaverStatus()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                status = context.readScreensaverStatus()
            }
        }
        val settings = listOf(
            Settings.Secure.getUriFor(SCREENSAVER_ENABLED),
            Settings.Secure.getUriFor(SCREENSAVER_COMPONENTS),
            Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT),
        )
        settings.forEach { context.contentResolver.registerContentObserver(it, false, observer) }
        status = context.readScreensaverStatus()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    // The permission isn't a setting, so nothing calls back: read it again on coming back from the TV's settings
    LifecycleResumeEffect(context) {
        status = context.readScreensaverStatus()
        onPauseOrDispose { }
    }
    return status
}

private const val TV_SETTINGS = "com.android.tv.settings"
private const val GOOGLE_TV_AMBIENT = "com.google.android.tv.settings.ambient"

private fun Context.opens(intent: Intent) = packageManager.resolveActivity(intent, 0)?.activityInfo
    ?.let { it.exported && !it.packageName.endsWith(".frameworkpackagestubs") } == true

/**
 * Google TV's Settings, which can't choose another screensaver: its Ambient mode is only Google's,
 * and its screen saver screen is switched off (Kitchen TV and emulator, 2026-11-04).
 */
fun Context.isGoogleTvSettings() = opens(Intent(GOOGLE_TV_AMBIENT))

/**
 * The screen of the TV's Settings that chooses the screensaver, or null if there's none to open.
 * Few TVs answer [Settings.ACTION_DREAM_SETTINGS], but their Settings app often lets other apps open
 * its screen saver screen by name.
 */
fun Context.screensaverScreen(): Intent? {
    if (isGoogleTvSettings()) return null
    return listOf(
        Intent(Settings.ACTION_DREAM_SETTINGS),
        // Sony's Android TV, e.g. Android 9
        Intent().setClassName(TV_SETTINGS, "com.sony.dtv.settings.device.display.daydream.DaydreamActivity"),
        // Android TV's own
        Intent().setClassName(TV_SETTINGS, "com.android.tv.settings.device.display.daydream.DaydreamActivity"),
    ).firstOrNull(::opens)
}

/** The TV's screen that lets HomeBoard change its settings ("Modify system settings"), or null if it has none */
private fun Context.writeSettingsScreen(): Intent? =
    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:$packageName")).takeIf(::opens)

/** Sets how long the TV waits without input before its screensaver. The TV's settings then show it too. */
private fun Context.setScreensaverDelay(seconds: Int) {
    try {
        Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, seconds * 1_000)
        AppLog.i(TAG, "Screensaver starts after $seconds s without input")
    } catch (e: Exception) {
        // e.g. the permission was taken back meanwhile
        AppLog.w(TAG, "Couldn't change when the screensaver starts", e)
    }
}

/** The screensaver screen if the TV has one to open, otherwise the main screen of its Settings */
fun Context.openScreensaverSettings() {
    val screen = screensaverScreen() ?: return openSystemSettings()
    try {
        startActivity(screen.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        AppLog.w(TAG, "Couldn't open the screensaver settings", e)
        openSystemSettings()
    }
}

/**
 * The Cameras dialog's Screensaver tab: how long each camera shows, then whether HomeBoard is the
 * TV's screensaver. Choosing it is up to Android's settings, opened from the dialog's top row. Last,
 * how long the TV waits without input before it starts, which HomeBoard can change once the TV allows it.
 */
@OptIn(ExperimentalTvMaterial3Api::class) // FilterChip
@Composable
fun ScreensaverSettings(
    seconds: Int,
    onSecondsChange: (Int) -> Unit,
    showProgress: Boolean,
    onShowProgressChange: (Boolean) -> Unit,
    cameras: List<Camera>,
    hiddenCameras: List<String>,
    onHiddenCamerasChange: (List<String>) -> Unit,
    firstModifier: Modifier,
    lastModifier: Modifier,
    progressModifier: Modifier,
    // For each camera's switch, by its index
    cameraModifier: (Int) -> Modifier,
    // − and + for when it starts, or the button that allows changing it in their place
    shorterDelayModifier: Modifier,
    longerDelayModifier: Modifier,
    allowDelayModifier: Modifier,
) {
    val context = LocalContext.current
    val status = rememberScreensaverStatus()
    val ours = remember { ComponentName(context, CameraScreensaver::class.java) }
    val hasScreen = remember { context.screensaverScreen() != null }
    val googleTv = remember { context.isGoogleTvSettings() }
    val step = SCREENSAVER_SECONDS.indexOf(seconds).takeIf { it >= 0 } ?: SCREENSAVER_SECONDS.indexOf(SCREENSAVER_SECONDS_DEFAULT)

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Time on each camera", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = { onSecondsChange(SCREENSAVER_SECONDS[(step - 1).coerceAtLeast(0)]) }, modifier = firstModifier) { Text("−") }
        Text(formatSeconds(seconds), style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { onSecondsChange(SCREENSAVER_SECONDS[(step + 1).coerceAtMost(SCREENSAVER_SECONDS.lastIndex)]) }, modifier = lastModifier) {
            Text("+")
        }
    }
    Text(
        "Counted from each camera's first picture. The cameras below show in the list's order, without sound.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 12.dp),
    )
    // Side by side: a list of switches wouldn't leave room for the rest
    FlowRow(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        cameras.forEachIndexed { index, camera ->
            val shown = camera.id !in hiddenCameras
            FilterChip(
                selected = shown,
                onClick = { onHiddenCamerasChange(if (shown) hiddenCameras + camera.id else hiddenCameras - camera.id) },
                leadingIcon = { MdiIcon(if (shown) "mdi:check" else "mdi:close", size = 18.dp, color = LocalContentColor.current) },
                modifier = cameraModifier(index),
            ) { Text(camera.name) }
        }
    }
    HomeOption(
        title = "Display connection progress",
        description = "While a camera connects. Off: only a problem shows, e.g. a camera that doesn't answer.",
        checked = showProgress,
        onClick = { onShowProgressChange(!showProgress) },
        modifier = progressModifier.padding(top = 8.dp),
    )
    MenuDivider()

    val isOurs = status.chosen == ours
    val state = when {
        !status.readable -> "This TV doesn't let apps see which screensaver is chosen."
        status.enabled == false -> "The TV's screensaver is turned off."
        isOurs -> "HomeBoard is the TV's screensaver."
        status.chosen == null -> "The TV has no screensaver chosen."
        else -> context.screensaverName(status.chosen)?.let { "The TV's screensaver is $it, not HomeBoard." }
            ?: "The TV's screensaver is set to ${status.chosen.packageName}, which isn't installed."
    }
    Text(
        state,
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp),
    )
    if (status.readable && (!isOurs || status.enabled == false)) {
        // Some TVs only offer their own screensavers in their settings
        val commands = listOfNotNull(
            "adb shell settings put secure $SCREENSAVER_COMPONENTS ${ours.flattenToShortString()}".takeIf { !isOurs },
            "adb shell settings put secure $SCREENSAVER_ENABLED 1".takeIf { status.enabled == false },
        )
        Text(
            (
                if (hasScreen) {
                    "Choose HomeBoard in the TV's screensaver settings, with the button above. If it isn't offered there, a computer can choose it over ADB:\n"
                } else if (googleTv) {
                    "Google TV's Settings can't choose another screensaver, but a computer can over ADB:\n"
                } else {
                    // e.g. Sony's Android 9: the screen is there, but has no link to it
                    "Choose HomeBoard in the TV's Settings, with the button above, where it sets the screensaver. If it isn't offered there, a computer can choose it over ADB:\n"
                }
                ) + commands.joinToString("\n"),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp),
        )
    }
    status.startsAfterMs?.let {
        ScreensaverDelay(it, status.canChangeDelay, shorterDelayModifier, longerDelayModifier, allowDelayModifier)
    }
    Spacer(Modifier.padding(6.dp))
}

/**
 * How long the TV waits without input before its screensaver (`screen_off_timeout`), the TV's own
 * setting, also its screensaver settings' "When to start". Changing it needs the TV's "Modify system
 * settings" permission, which only the user can give: Allow opens that screen.
 */
@Composable
private fun ScreensaverDelay(
    startsAfterMs: Int,
    canChange: Boolean,
    shorterModifier: Modifier,
    longerModifier: Modifier,
    allowModifier: Modifier,
) {
    val context = LocalContext.current
    val allowScreen = remember { context.writeSettingsScreen() }
    // Android TV's "Never"
    val never = startsAfterMs <= 0 || startsAfterMs == Int.MAX_VALUE
    val seconds = startsAfterMs / 1_000
    // Once allowed, the selection goes from Allow, which leaves, to −
    val shorter = remember { FocusRequester() }
    var allowPressed by remember { mutableStateOf(false) }
    LaunchedEffect(canChange) {
        if (!canChange || !allowPressed) return@LaunchedEffect
        allowPressed = false
        withFrameNanos { }
        runCatching { shorter.requestFocus() }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Starts after", style = MaterialTheme.typography.titleSmall)
            Text(
                "Without input. The TV's own setting, whichever screensaver it shows.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        val value = if (never) "Never" else formatSeconds(seconds)
        if (canChange) {
            OutlinedButton(
                onClick = { (if (never) SCREENSAVER_DELAYS.last() else SCREENSAVER_DELAYS.lastOrNull { it < seconds })?.let(context::setScreensaverDelay) },
                modifier = Modifier.focusRequester(shorter).then(shorterModifier),
            ) { Text("−") }
            Text(value, style = MaterialTheme.typography.titleMedium)
            OutlinedButton(
                onClick = { SCREENSAVER_DELAYS.firstOrNull { !never && it > seconds }?.let(context::setScreensaverDelay) },
                modifier = longerModifier,
            ) { Text("+") }
        } else {
            Text(value, style = MaterialTheme.typography.titleMedium)
            if (allowScreen != null) {
                OutlinedButton(
                    onClick = {
                        allowPressed = true
                        try {
                            context.startActivity(Intent(allowScreen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        } catch (e: Exception) {
                            AppLog.w(TAG, "Couldn't open the screen that allows changing system settings", e)
                        }
                    },
                    modifier = allowModifier,
                ) { Text("Allow") }
            }
        }
    }
    if (!canChange) {
        Text(
            if (allowScreen != null) {
                "To change it here, HomeBoard needs the TV's permission to modify system settings: Allow opens it, where you turn it on for HomeBoard."
            } else {
                "To change it here, HomeBoard needs the TV's permission to modify system settings, which a computer can give over ADB:\n" +
                    "adb shell appops set ${context.packageName} WRITE_SETTINGS allow"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp, top = 4.dp, end = 12.dp),
        )
    }
}

/** The screensaver's name as Android's settings show it, e.g. "Ambient mode", or its app's; null if it isn't installed */
private fun Context.screensaverName(component: ComponentName): String? = try {
    packageManager.getServiceInfo(component, 0).loadLabel(packageManager).toString()
} catch (e: PackageManager.NameNotFoundException) {
    null
}

private fun formatSeconds(seconds: Int) = when {
    seconds < 60 -> "$seconds s"
    seconds % 3600 == 0 -> "${seconds / 3600} h"
    seconds % 60 == 0 -> "${seconds / 60} min"
    else -> "${seconds / 60} min ${seconds % 60} s"
}
