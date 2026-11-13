package com.evofactory.homeboard

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * Remembers the order chosen with "Move" and which apps are hidden, across restarts.
 * Stored in shared_prefs/launcher.xml in the app's private data on the TV.
 */
class LauncherPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    /** Package names of visible apps, in row order */
    var order: List<String>
        get() = readList("order")
        set(value) = writeList("order", value)

    /** Package names of hidden apps, in the order they were hidden */
    var hidden: List<String>
        get() = readList("hidden_list").ifEmpty {
            // Earlier versions stored an unordered set
            prefs.getStringSet("hidden", null)?.sorted() ?: emptyList()
        }
        set(value) = prefs.edit {
            putString("hidden_list", value.joinToString("\n"))
            remove("hidden")
        }

    /** Shows hidden from Continue watching, see [WatchItem.hideKey] */
    var hiddenWatching: List<String>
        get() = readList("hidden_watching")
        set(value) = writeList("hidden_watching", value)

    /** Last address entered in the Ping utility */
    var pingHost: String
        get() = prefs.getString("ping_host", null) ?: ""
        set(value) = prefs.edit { putString("ping_host", value) }

    /** The [NetworkKind] last connected, for which icon to cross out without a network */
    var lastNetworkKind: String
        get() = prefs.getString("last_network_kind", null) ?: ""
        set(value) = prefs.edit { putString("last_network_kind", value) }

    /** Cameras, in the order chosen; their addresses may include the camera's login */
    var cameras: List<Camera>
        get() = prefs.getString("cameras", null)?.let(::camerasFromJson)
            // Earlier versions had a single camera
            ?: prefs.getString("camera_url", null)?.takeIf { it.isNotEmpty() }?.let { url ->
                listOf(Camera(id = "camera1", name = "Camera", url = url, useTcp = prefs.getBoolean("camera_tcp", true)))
            }
            ?: emptyList()
        set(value) = prefs.edit {
            putString("cameras", camerasToJson(value))
            remove("camera_url")
            remove("camera_tcp")
        }

    /** Id of the camera shown on the home screen */
    var selectedCamera: String
        get() = prefs.getString("camera_selected", null) ?: ""
        set(value) = prefs.edit { putString("camera_selected", value) }

    /** Home Assistant address, e.g. http://192.168.1.20:8123; empty = not set up */
    var haUrl: String
        get() = prefs.getString("ha_url", null) ?: ""
        set(value) = prefs.edit { putString("ha_url", value) }

    /** Home Assistant long-lived access token, when logging in with one */
    var haToken: String
        get() = prefs.getString("ha_token", null) ?: ""
        set(value) = prefs.edit { putString("ha_token", value) }

    /** Home Assistant refresh token, when logging in with a username and password (never stored) */
    var haRefreshToken: String
        get() = prefs.getString("ha_refresh_token", null) ?: ""
        set(value) = prefs.edit { putString("ha_refresh_token", value) }

    /** Username of that login, to show it again in the settings */
    var haUsername: String
        get() = prefs.getString("ha_username", null) ?: ""
        set(value) = prefs.edit { putString("ha_username", value) }

    /**
     * Everything from Home Assistant on the Assistant tab, in its order there: weather, sensors,
     * scenes, scripts and buttons. The sensors and weather also show next to the camera.
     */
    val haEntities: List<String>
        get() = if (prefs.contains("ha_top_bar")) readList("ha_entities") else readList("ha_entities").filterNot(::isHaAction) + oldHaActions()

    /** The scenes, scripts and buttons also in the top bar, at most [MAX_HA_ACTIONS] */
    val haTopBar: List<String>
        get() = if (prefs.contains("ha_top_bar")) readList("ha_top_bar") else oldHaActions().take(MAX_HA_ACTIONS)

    /** What the Assistant tab and the Home tab's panel show of Home Assistant */
    var haDisplay: HaDisplay
        get() = HaDisplay(
            dashboard = prefs.getString("ha_dashboard", null),
            bottomRow = prefs.getBoolean("ha_bottom_row", false),
            sectionColumns = prefs.getBoolean("ha_section_columns", false),
            homeDashboard = prefs.getString("ha_home_dashboard", null),
            homeColumns = prefs.getBoolean("ha_home_columns", true),
        )
        set(value) = prefs.edit {
            if (value.dashboard == null) remove("ha_dashboard") else putString("ha_dashboard", value.dashboard)
            putBoolean("ha_bottom_row", value.bottomRow)
            putBoolean("ha_section_columns", value.sectionColumns)
            if (value.homeDashboard == null) remove("ha_home_dashboard") else putString("ha_home_dashboard", value.homeDashboard)
            putBoolean("ha_home_columns", value.homeColumns)
            // The bottom row's first version, which only hid the alerts row
            remove("ha_alerts_row")
        }

    // Together: before the top bar was a choice of its own, the actions were in another list
    fun saveHaChoice(entities: List<String>, topBar: List<String>) {
        writeList("ha_entities", entities)
        writeList("ha_top_bar", topBar)
        prefs.edit { remove("ha_actions") }
    }

    // Earlier versions kept the top bar's actions in "ha_actions", and before that with the sensors
    private fun oldHaActions() = if (prefs.contains("ha_actions")) readList("ha_actions") else readList("ha_entities").filter(::isHaAction)

    /** Frigate's address and login; the password is kept, see [FrigateServer] */
    var frigate: FrigateServer
        get() = FrigateServer(
            prefs.getString("frigate_url", null) ?: "",
            prefs.getString("frigate_username", null) ?: "",
            prefs.getString("frigate_password", null) ?: "",
            prefs.getString("frigate_cert_pin", null) ?: "",
        )
        set(value) = prefs.edit {
            putString("frigate_url", value.url)
            putString("frigate_username", value.username)
            putString("frigate_password", value.password)
            putString("frigate_cert_pin", value.certPin)
        }

    // Earlier versions had one switch, which did what both of these do. Both off by default.
    private val replaceHomeBefore get() = prefs.getBoolean("replace_home", false)

    /** Catch the Home button so the TV's home screen never opens. May stop a held volume button from repeating. */
    var catchHomeKey: Boolean
        get() = prefs.getBoolean("catch_home_key", replaceHomeBefore)
        set(value) = prefs.edit { putBoolean("catch_home_key", value) }

    /** Reopen this launcher whenever the TV's own home screen opens */
    var reopenAfterHome: Boolean
        get() = prefs.getBoolean("reopen_after_home", replaceHomeBefore)
        set(value) = prefs.edit { putBoolean("reopen_after_home", value) }

    /** Either way of taking over from the TV's home screen is on (see HomeOverrideService) */
    val replaceHome: Boolean get() = catchHomeKey || reopenAfterHome

    fun stopReplacingHome() {
        catchHomeKey = false
        reopenAfterHome = false
    }

    /** The Cameras tab plays substreams live; otherwise it shows still pictures */
    var cameraGridLive: Boolean
        get() = prefs.getBoolean("camera_grid_live", true)
        set(value) = prefs.edit { putBoolean("camera_grid_live", value) }

    /**
     * How many of the Cameras tab's live tiles use the TV's hardware video pipeline; the next ones are drawn
     * by the GPU in the app's window. The Kitchen TV's pipeline has 3 video windows and left some tiles
     * black with 8 (2026-11-06).
     */
    var cameraGridMaxHardware: Int
        get() = prefs.getInt("camera_grid_max_hardware", MAX_HARDWARE_DEFAULT).coerceIn(0, MAX_HARDWARE_LIMIT)
        set(value) = prefs.edit { putInt("camera_grid_max_hardware", value) }

    /** Black behind everything instead of the dark grey; cards and panels keep their grey */
    var blackBackground: Boolean
        get() = prefs.getBoolean("black_background", false)
        set(value) = prefs.edit { putBoolean("black_background", value) }

    /** Which lists the Clips tab offers (Frigate dialog): alerts, detections, saved clips. None hides the tab. */
    var clipsShown: List<FrigateKind>
        get() = FrigateKind.entries.filter { prefs.getBoolean("clips_${it.api}", true) }
        set(value) = prefs.edit { FrigateKind.entries.forEach { putBoolean("clips_${it.api}", it in value) } }

    /** Labels every video with how it's drawn, HW (the TV's video pipeline) or GPU, and shows the free memory in the top bar */
    var showDebugInfo: Boolean
        get() = prefs.getBoolean("show_debug_info", false)
        set(value) = prefs.edit { putBoolean("show_debug_info", value) }

    /**
     * For older TVs: the cameras stop while a clip plays, and the clip waits until their players have
     * released. Decoders open one at a time.
     * Otherwise videos play side by side, and open together.
     */
    var minimizeVideoStreams: Boolean
        get() = prefs.getBoolean("minimize_video_streams", false)
        set(value) = prefs.edit { putBoolean("minimize_video_streams", value) }

    /** How long the screensaver shows each camera, one of [SCREENSAVER_SECONDS] */
    var screensaverSeconds: Int
        get() = prefs.getInt("screensaver_seconds", SCREENSAVER_SECONDS_DEFAULT).takeIf { it in SCREENSAVER_SECONDS } ?: SCREENSAVER_SECONDS_DEFAULT
        set(value) = prefs.edit { putInt("screensaver_seconds", value) }

    /** Ids of the cameras the screensaver leaves out; new cameras show */
    var screensaverHiddenCameras: List<String>
        get() = readList("screensaver_hidden_cameras")
        set(value) = writeList("screensaver_hidden_cameras", value)

    /** The screensaver shows a camera's status while it connects; off, only once something fails */
    var screensaverShowProgress: Boolean
        get() = prefs.getBoolean("screensaver_show_progress", false)
        set(value) = prefs.edit { putBoolean("screensaver_show_progress", value) }

    /** How many hours of the app's log to keep, see [AppLog] */
    var logHoursKept: Int
        get() = prefs.getInt("log_hours_kept", LOG_HOURS_DEFAULT).coerceIn(LOG_HOURS_MIN, LOG_HOURS_MAX)
        set(value) = prefs.edit { putInt("log_hours_kept", value) }

    /** The user accepted what the home override does, asked the first time they turn on either option */
    var homeOverrideAccepted: Boolean
        get() = prefs.getBoolean("home_override_accepted", false)
        set(value) = prefs.edit { putBoolean("home_override_accepted", value) }

    /** Set when the crash guard turned replaceHome off, so HomeBoard settings can say why */
    var replaceHomeAutoOff: Boolean
        get() = prefs.getBoolean("replace_home_auto_off", false)
        set(value) = prefs.edit { putBoolean("replace_home_auto_off", value) }

    /**
     * Crash guard: launches by the home override since the launcher last started properly, counted
     * from [overrideLaunchesSince]. Written to disk at once, since a crash follows a failed launch.
     */
    var overrideLaunches: Int
        get() = prefs.getInt("override_launches", 0)
        set(value) = prefs.edit(commit = true) { putInt("override_launches", value) }

    var overrideLaunchesSince: Long
        get() = prefs.getLong("override_launches_since", 0)
        set(value) = prefs.edit(commit = true) { putLong("override_launches_since", value) }

    /** The listener is only weakly held: keep a reference to it */
    fun addChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.registerOnSharedPreferenceChangeListener(listener)

    fun removeChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener) =
        prefs.unregisterOnSharedPreferenceChangeListener(listener)

    /** The apps found last time */
    var installedApps: List<InstalledApp>
        get() = runCatching { installedAppsFromJson(prefs.getString("installed_apps", null).orEmpty()) }.getOrDefault(emptyList())
        set(value) = prefs.edit { putString("installed_apps", installedAppsToJson(value)) }

    private fun readList(key: String): List<String> =
        prefs.getString(key, null)?.split('\n')?.filter { it.isNotEmpty() } ?: emptyList()

    private fun writeList(key: String, value: List<String>) = prefs.edit { putString(key, value.joinToString("\n")) }
}

/** Applies the saved order and drops hidden apps; apps not ordered yet (e.g. newly installed) go last. */
fun arrange(apps: List<InstalledApp>, order: List<String>, hidden: List<String>): List<InstalledApp> {
    val rank = order.withIndex().associate { (index, key) -> key to index }
    val hiddenKeys = hidden.toSet()
    // sortedBy is stable, so unranked apps keep their alphabetical order
    return apps.filter { it.key !in hiddenKeys }.sortedBy { rank[it.key] ?: Int.MAX_VALUE }
}
