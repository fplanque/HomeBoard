package com.evofactory.homeboard

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Button
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Switch
import androidx.tv.material3.SwitchDefaults
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * HomeBoard settings: optionally take over from the TV's home screen, in either way, both or neither.
 * Either needs the accessibility service turned on in the TV's settings too.
 */
@Composable
fun LauncherSettingsDialog(
    catchHomeKey: Boolean,
    reopenAfterHome: Boolean,
    autoTurnedOff: Boolean,
    onCatchHomeKeyChange: (Boolean) -> Unit,
    onReopenAfterHomeChange: (Boolean) -> Unit,
    onExport: () -> Unit,
    onImport: () -> Unit,
    blackBackground: Boolean,
    onBlackBackgroundChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val replaceHome = catchHomeKey || reopenAfterHome
    val context = LocalContext.current
    val serviceName = stringResource(R.string.home_override_label)
    val serviceOn = rememberHomeOverrideServiceOn()
    var advanced by remember { mutableStateOf(false) }
    val advancedOption = remember { FocusRequester() }
    val toggle = remember { FocusRequester() }
    // The item last on, to come back to from the top row
    val items = remember { ListFocus() }
    val itemKeys = if (advanced) listOf("hours-", "hours+") else listOf("default", "catch", "reopen", "accessibility", "gray", "black", "export", "import")
    var defaultHome by remember { mutableStateOf(context.defaultHome()) }
    val prefs = remember { LauncherPrefs(context) }
    // An option turned on, once its explanation is accepted
    var pendingOption by remember { mutableStateOf<(() -> Unit)?>(null) }

    fun turnOn(option: () -> Unit) {
        if (prefs.homeOverrideAccepted) option() else pendingOption = { option() }
    }

    val running = HomeOverrideService.running
    // Some TVs can't open Accessibility settings directly: then the main settings, with the way there
    val accessibilityScreen = remember { context.hasSettingsScreen(Settings.ACTION_ACCESSIBILITY_SETTINGS) }
    val accessibility = if (accessibilityScreen) "Accessibility settings" else "Settings → Device Preferences → Accessibility"
    val status = when {
        // The setting comes first: while it's off, the helper not running is no surprise
        replaceHome && !serviceOn ->
            "Home opens the TV's own home screen until you turn on “$serviceName” in $accessibility."
        replaceHome && running ->
            (if (catchHomeKey) "Home brings you here." else "The TV's own home screen brings you here when it opens.") +
                " If this launcher fails to start a few times in a row, this switches itself off."
        replaceHome ->
            "“$serviceName” is on in $accessibility, but Android isn't running it, so Home opens " +
                "the TV's own home screen. Turn it off and on again there; if it still doesn't run, restart the TV."
        autoTurnedOff -> "Switched off automatically: this launcher failed to start several times in a row."
        defaultHome.isDefault -> "Off: Home opens this launcher anyway, as the TV's default home app."
        serviceOn ->
            "Off. “$serviceName” is still on in $accessibility but does nothing; you can turn it off there."
        else -> "Off: Home opens the TV's own home screen."
    }

    TopDialog(
        title = "HomeBoard settings",
        onDismiss = onDismiss,
        tabs = listOf(
            DialogTab("Launcher", selected = !advanced) { advanced = false },
            DialogTab("Advanced", selected = advanced) { advanced = true },
        ),
        buttons = listOf(DialogButton("Restart app") { context.restartApp() }),
        contentFocus = { items.current(itemKeys) },
    ) {
        LaunchedEffect(Unit) { toggle.requestFocus() }
        if (advanced) {
            LauncherAdvancedSettings(
                firstModifier = Modifier.focusRequester(advancedOption).then(items.row("hours-")).ringToTop(right = false),
                lastModifier = items.row("hours+").ringToTop(left = false),
            )
            return@TopDialog
        }
        // First: where Android allows it, the one way that needs no Accessibility helper
        DefaultHomeOption(
            state = defaultHome,
            onStateChange = { defaultHome = it },
            modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp).focusRequester(toggle).then(items.row("default")).ringToTop(),
        )
        MenuDivider()
        HomeOption(
            title = "Catch the Home button",
            description = "Opens at once. May stop the volume from changing continuously while you hold a volume button.",
            checked = catchHomeKey,
            onClick = {
                if (catchHomeKey) onCatchHomeKeyChange(false) else turnOn { onCatchHomeKeyChange(true) }
            },
            modifier = Modifier.padding(horizontal = 12.dp).then(items.row("catch")).ringToTop(),
        )
        HomeOption(
            title = "Reopen after the TV's home screen",
            description = "Also works when leaving an app or after a restart. The TV's home screen shows first, for several seconds on some TVs.",
            checked = reopenAfterHome,
            onClick = {
                if (reopenAfterHome) onReopenAfterHomeChange(false) else turnOn { onReopenAfterHomeChange(true) }
            },
            modifier = Modifier.padding(horizontal = 12.dp).then(items.row("reopen")).ringToTop(),
        )
        if (replaceHome) {
            Row(
                modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val ok = serviceOn && running
                Box(Modifier.size(10.dp).background(if (ok) RunningColor else ErrorColor, CircleShape))
                Text(
                    when {
                        !serviceOn -> "Accessibility helper: turned off"
                        running -> "Home button helper: running"
                        else -> "Home button helper: not running"
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
            }
        }
        Text(status, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
        Button(
            onClick = { context.openSystemSettings(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, bottom = 8.dp).then(items.row("accessibility")).ringToTop(),
        ) {
            Text(if (accessibilityScreen) "Open Accessibility settings" else "Open TV settings")
        }
        MenuDivider()
        // Only the screen's background: cards and panels keep their grey
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Background", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { onBlackBackgroundChange(false) }, modifier = items.row("gray").ringToTop(right = false)) {
                Text(if (blackBackground) "Gray" else "✓ Gray")
            }
            OutlinedButton(onClick = { onBlackBackgroundChange(true) }, modifier = items.row("black").ringToTop(left = false)) {
                Text(if (blackBackground) "✓ Black" else "Black")
            }
        }
        MenuDivider()
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = onExport, modifier = items.row("export").ringToTop(right = false)) { Text("Export settings") }
            OutlinedButton(onClick = onImport, modifier = items.row("import").ringToTop(left = false)) { Text("Import settings") }
            Spacer(Modifier.weight(1f))
            BuildInfo()
        }
    }

    pendingOption?.let { option ->
        HomeOverrideConsentDialog(
            onAccept = {
                prefs.homeOverrideAccepted = true
                pendingOption = null
                option()
            },
            onDecline = { pendingOption = null },
        )
    }
}

/** The version and build number, and the date and time of the build: three small lines, as tall as a button */
@Composable
private fun BuildInfo() {
    val context = LocalContext.current
    val built = remember { Date(BuildConfig.BUILD_TIME) }
    Column(horizontalAlignment = Alignment.End) {
        listOf(
            "Version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
            DateFormat.getMediumDateFormat(context).format(built),
            DateFormat.getTimeFormat(context).format(built),
        ).forEach {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp, lineHeight = 13.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The app's log: how many hours it keeps, and what they take up */
@Composable
private fun LauncherAdvancedSettings(
    firstModifier: Modifier,
    lastModifier: Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { LauncherPrefs(context) }
    var hours by remember { mutableStateOf(prefs.logHoursKept) }
    var size by remember { mutableStateOf<Long?>(null) }

    fun setHours(value: Int) {
        hours = value.coerceIn(LOG_HOURS_MIN, LOG_HOURS_MAX)
        prefs.logHoursKept = hours
        AppLog.keepHours(hours)
    }

    LaunchedEffect(Unit) {
        while (true) {
            size = withContext(Dispatchers.IO) { AppLog.sizeBytes() }
            delay(LOG_SIZE_REFRESH_MS)
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Hours of log to keep", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.weight(1f))
        OutlinedButton(onClick = { setHours(hours - 1) }, modifier = firstModifier) { Text("−") }
        Text("$hours", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = { setHours(hours + 1) }, modifier = lastModifier) { Text("+") }
    }
    Text(
        "Log size: ${size?.let { Formatter.formatShortFileSize(context, it) } ?: "…"}",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(12.dp),
    )
}

/** Says what the accessibility service does, before either option that needs it is turned on. Declining leaves it off. */
@Composable
private fun HomeOverrideConsentDialog(onAccept: () -> Unit, onDecline: () -> Unit) {
    val serviceName = stringResource(R.string.home_override_label)
    val accept = remember { FocusRequester() }
    TopDialog(
        title = "Use the Accessibility service?",
        onDismiss = onDecline,
        buttons = listOf(DialogButton("Accept", primary = true, focus = accept, onClick = onAccept)),
    ) {
        Text(
            "To take over from the TV's home screen, this app uses Android's Accessibility service, “$serviceName”. " +
                "With these options on, it sees your remote's button presses and which app's window opens, " +
                "only to open this launcher when you press Home or the TV's home screen appears. " +
                "It reads nothing else on screen, and it doesn't save or send any of this anywhere.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(12.dp),
        )
        Text(
            "You'll also need to turn it on in the TV's Accessibility settings. You can turn it off at any time.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        LaunchedEffect(Unit) { accept.requestFocus() }
    }
}

/**
 * Starts the launcher over in a new process, which frees whatever it holds, e.g. stuck video decoders.
 * Killed rather than exited: exiting runs cleanup code, which a stuck thread could hold up.
 */
fun Context.restartApp() {
    startActivity(Intent.makeRestartActivityTask(ComponentName(this, MainActivity::class.java)))
    Process.killProcess(Process.myPid())
}

@Composable
fun HomeOption(title: String, description: String, checked: Boolean, onClick: () -> Unit, modifier: Modifier) {
    ListItem(
        selected = false,
        onClick = onClick,
        headlineContent = { Text(title) },
        supportingContent = { Text(description) },
        trailingContent = {
            Switch(
                checked = checked,
                onCheckedChange = null,
                // Neutral: reads on the dialog and on the light background of the selected row
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.White,
                    checkedTrackColor = SwitchOnTrack,
                    checkedBorderColor = SwitchOnTrack,
                    uncheckedThumbColor = SwitchOff,
                    uncheckedTrackColor = Color.Transparent,
                    uncheckedBorderColor = SwitchOff,
                ),
            )
        },
        modifier = modifier,
    )
}

/** Whether the service is on in the TV's Accessibility settings; follows changes to the setting */
@Composable
private fun rememberHomeOverrideServiceOn(): Boolean {
    val context = LocalContext.current
    var on by remember { mutableStateOf(context.isHomeOverrideServiceOn()) }
    DisposableEffect(context) {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                on = context.isHomeOverrideServiceOn()
            }
        }
        val setting = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        context.contentResolver.registerContentObserver(setting, false, observer)
        on = context.isHomeOverrideServiceOn()
        onDispose { context.contentResolver.unregisterContentObserver(observer) }
    }
    return on
}

/**
 * For the top bar, only when Home is set to open this launcher: shows that the accessibility helper
 * is turned off or, if it's on, that Android isn't running it. Either way Home opens the TV's own
 * home screen. Opens HomeBoard settings.
 */
@Composable
fun HomeHelperWarning(replaceHome: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val serviceOn = rememberHomeOverrideServiceOn()
    // The helper connects a moment after the launcher starts: no warning flash meanwhile
    var settled by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(HELPER_START_GRACE_MS)
        settled = true
    }
    if (!replaceHome || !settled || (serviceOn && HomeOverrideService.running)) return
    TopBarWarning(
        // Short: it shares the top bar with the action buttons. HomeBoard settings has the details.
        if (serviceOn) "Helper not running" else "Helper turned off",
        onClick = onClick,
        modifier = modifier,
    )
}

private const val HELPER_START_GRACE_MS = 3_000L
private const val LOG_SIZE_REFRESH_MS = 5_000L

private val RunningColor = Color(0xFF7BD88F)
private val SwitchOnTrack = Color(0xFF5A595F)
private val SwitchOff = Color(0xFF8E8C92)
