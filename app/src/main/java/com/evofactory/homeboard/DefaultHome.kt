package com.evofactory.homeboard

import android.app.Activity
import android.app.role.RoleManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text

/**
 * Whether Android can make this app the TV's default home app, and if not, why.
 *
 * Android picks the activity for Home by the priority its intent filter declares before it looks
 * at the user's choice: when the first home screen outranks the second, it opens without a chooser,
 * whatever the default home app is (the HOME role from Android 10, the "Always" choice before).
 * Only privileged system apps may declare a priority above 0, and the stock TV launchers declare 2,
 * so on those TVs no request can work, even where Android lets the app ask (Kitchen TV, 2026-11-06).
 */
class DefaultHome(
    /** Home opens this app */
    val isDefault: Boolean,
    /** Why this app can't become the default, or null when it can (or already is) */
    val blocked: Blocked?,
    /** The priority of the home screen that opens whatever is chosen, when [blocked] is [Blocked.Outranked] */
    val topPriority: Int,
    val ownPriority: Int,
    /** Android 10 and later, with the home app role: the app can ask for it */
    val roleAvailable: Boolean,
    /** This app is chosen as the default home app (Android 10 and later) */
    val roleHeld: Boolean,
    /** The TV's Settings have a real screen that chooses the home app */
    val homeSettings: Boolean,
    /** For the log: every home screen with its priority, highest first */
    val candidates: List<String>,
) {
    enum class Blocked {
        /** A home screen declares a higher priority than every other, so Android always opens it */
        Outranked,

        /** Android 10 or later, but the home app role is switched off on this TV */
        NoRole,

        /** Before Android 10: another home app was chosen with "Always", and Settings has no screen to change it */
        ChosenAlways,
    }

    val canRequest get() = !isDefault && blocked == null

    override fun toString() =
        "default=$isDefault blocked=$blocked own=$ownPriority top=$topPriority role=$roleAvailable held=$roleHeld " +
            "homeSettings=$homeSettings candidates=$candidates"
}

private val homeIntent get() = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)

fun Context.defaultHome(): DefaultHome {
    val pm = packageManager
    // As Android ranks them for Home: priority, then preferred order (all match DEFAULT here)
    val candidates = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
        .sortedWith(compareByDescending<ResolveInfo> { it.priority }.thenByDescending { it.preferredOrder })
    val own = candidates.firstOrNull { it.activityInfo.packageName == packageName }
    val first = candidates.firstOrNull()
    val second = candidates.getOrNull(1)
    // Android's own rule (chooseBestActivity): a first that ranks above the second wins outright
    val outranked = first != null && first.activityInfo.packageName != packageName &&
        (second == null || first.priority > second.priority || first.preferredOrder > second.preferredOrder)
    val resolved = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
    val isDefault = resolved?.packageName == packageName
    val roles = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) getSystemService(RoleManager::class.java) else null
    val roleAvailable = roles?.isRoleAvailable(RoleManager.ROLE_HOME) == true
    val roleHeld = roleAvailable && roles?.isRoleHeld(RoleManager.ROLE_HOME) == true
    val homeSettings = hasSettingsScreen(Settings.ACTION_HOME_SETTINGS)
    val blocked = when {
        isDefault -> null
        outranked -> DefaultHome.Blocked.Outranked
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> if (roleAvailable) null else DefaultHome.Blocked.NoRole
        // Before Android 10, Home shows the chooser unless an app was chosen with "Always" ("android" is the chooser)
        !homeSettings && resolved != null && resolved.packageName != "android" -> DefaultHome.Blocked.ChosenAlways
        else -> null
    }
    return DefaultHome(
        isDefault = isDefault,
        blocked = blocked,
        topPriority = first?.priority ?: 0,
        ownPriority = own?.priority ?: 0,
        roleAvailable = roleAvailable,
        roleHeld = roleHeld,
        homeSettings = homeSettings,
        candidates = candidates.map { "${it.activityInfo.packageName}/${it.activityInfo.name} p=${it.priority}" },
    )
}

/**
 * What asks Android to make this app the default home app: the role request from Android 10,
 * before that the Home app screen of Settings, or Home itself, which then shows the chooser.
 */
private fun Context.defaultHomeRequest(): Intent = when {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
        getSystemService(RoleManager::class.java).createRequestRoleIntent(RoleManager.ROLE_HOME)
    hasSettingsScreen(Settings.ACTION_HOME_SETTINGS) -> Intent(Settings.ACTION_HOME_SETTINGS)
    else -> homeIntent
}

/** Which explanation the dialog shows */
private enum class HomeDialog { State, Declined, NoScreen }

/**
 * The first row of HomeBoard settings: OK asks Android to make this app the default home app where
 * that can work, and otherwise opens a dialog that says exactly why it can't, or that it already is.
 */
@Composable
fun DefaultHomeOption(state: DefaultHome, onStateChange: (DefaultHome) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<HomeDialog?>(null) }
    LifecycleResumeEffect(context) {
        onStateChange(context.defaultHome())
        onPauseOrDispose { }
    }
    val request = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val now = context.defaultHome()
        onStateChange(now)
        AppLog.i(TAG, "Request returned ${result.resultCode}: $now")
        // Only the role request answers; the screens before Android 10 come back canceled whatever was chosen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && result.resultCode != Activity.RESULT_OK && !now.isDefault) {
            dialog = HomeDialog.Declined
        }
    }
    ListItem(
        selected = false,
        onClick = {
            val now = context.defaultHome()
            onStateChange(now)
            AppLog.i(TAG, "Default home: $now")
            if (!now.canRequest) {
                dialog = HomeDialog.State
                return@ListItem
            }
            try {
                request.launch(context.defaultHomeRequest())
            } catch (e: ActivityNotFoundException) {
                AppLog.w(TAG, "No screen to ask for the default home app", e)
                dialog = HomeDialog.NoScreen
            }
        },
        headlineContent = { Text(if (state.isDefault) "HomeBoard is the default home app" else "Make HomeBoard the default home app") },
        supportingContent = {
            Text(
                when {
                    state.isDefault -> "Home opens it at once, without the Accessibility helper."
                    state.canRequest -> "Then Home opens it at once, without the Accessibility helper. Android asks you first."
                    state.blocked == DefaultHome.Blocked.Outranked ->
                        "Not possible on this TV: Android always opens the TV's own home screen. OK says why."
                    state.blocked == DefaultHome.Blocked.NoRole ->
                        "Not possible on this TV: its Android has no default home app setting. OK says why."
                    else -> "Another home app was chosen with “Always”. OK says what to do."
                },
            )
        },
        modifier = modifier,
    )
    dialog?.let { DefaultHomeDialog(state, it, onDismiss = { dialog = null }) }
}

@Composable
private fun DefaultHomeDialog(state: DefaultHome, which: HomeDialog, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val close = remember { FocusRequester() }
    val action = remember { FocusRequester() }
    val options = "To have Home open HomeBoard anyway, turn on “Catch the Home button” or “Reopen after the TV's " +
        "home screen” in HomeBoard settings. They use the Accessibility helper."
    val paragraphs = when {
        which == HomeDialog.Declined -> listOfNotNull(
            "Android didn't make HomeBoard the default home app. Once its question has been declined twice, " +
                "Android stops asking and declines at once.",
            if (state.homeSettings) "You can still choose HomeBoard in the TV's home app setting." else null,
        )
        which == HomeDialog.NoScreen -> listOf(
            "This TV's Android has the default home app setting, but no screen to ask for it.",
            options,
        )
        state.isDefault -> listOfNotNull(
            "HomeBoard is this TV's default home app, so Home opens it at once, without the Accessibility helper.",
            if (state.homeSettings) "To choose another, open the TV's home app setting." else null,
        )
        state.blocked == DefaultHome.Blocked.Outranked -> listOfNotNull(
            "Android can't make HomeBoard this TV's default home app.",
            "When Home is pressed, Android first compares the priority each home screen declares. If one has a higher " +
                "priority than all the others, Android opens it at once, without asking and without looking at which " +
                "home app was chosen as the default. Here the TV's own home screen declares priority ${state.topPriority}, " +
                "and HomeBoard ${state.ownPriority}. Android only lets apps that come with the TV's system declare a " +
                "priority above 0, so no app installed afterwards can outrank it.",
            when {
                state.roleHeld ->
                    "HomeBoard is already chosen as the default home app, but Home still opens the TV's own home screen."
                state.roleAvailable ->
                    "This TV has Android's default home app setting, and would accept HomeBoard there, but Home " +
                        "would still open the TV's own home screen."
                Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ->
                    "This TV also runs Android ${Build.VERSION.RELEASE}, which has no way for an app to ask to become " +
                        "the default home app: that came with Android 10." +
                        (if (state.homeSettings) "" else " Its Settings have no home app screen either.")
                else -> "This TV's Android also has its default home app setting switched off."
            },
            options,
        )
        state.blocked == DefaultHome.Blocked.NoRole -> listOf(
            "Android can't make HomeBoard this TV's default home app: this TV's Android has its default home app " +
                "setting switched off, so no app can ask for it.",
            options,
        )
        else -> listOf(
            "Another home app was chosen with “Always”, so Android opens it without asking, and this TV's Settings " +
                "have no home app screen to change that.",
            "In Settings → Apps, open that app and choose Clear defaults. Then press Home and choose HomeBoard, with “Always”.",
        )
    }
    val button = when {
        which == HomeDialog.State && state.blocked == DefaultHome.Blocked.ChosenAlways ->
            DialogButton("Open Apps settings", primary = true, focus = action) {
                context.openSystemSettings(Settings.ACTION_APPLICATION_SETTINGS)
                onDismiss()
            }
        state.homeSettings && (state.isDefault || which == HomeDialog.Declined) ->
            DialogButton("Open home app setting", primary = true, focus = action) {
                context.openSystemSettings(Settings.ACTION_HOME_SETTINGS)
                onDismiss()
            }
        else -> null
    }
    TopDialog(
        title = "Default home app",
        onDismiss = onDismiss,
        buttons = listOfNotNull(button),
        closeFocus = close,
    ) {
        paragraphs.forEachIndexed { index, text ->
            Text(
                text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 12.dp, top = if (index == 0) 12.dp else 0.dp, end = 12.dp, bottom = 12.dp),
            )
        }
        LaunchedEffect(Unit) { (if (button != null) action else close).requestFocus() }
    }
}

private const val TAG = "DefaultHome"
