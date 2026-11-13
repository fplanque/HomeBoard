package com.evofactory.homeboard

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import kotlin.math.roundToInt

// The panel next to the camera (Home Assistant or the other cameras) is at least this wide; it
// also gets whatever width the camera leaves
private val SidePanelWidth = 260.dp

// App tiles from this 0-based slot on have the side panel above them, the earlier ones the camera
private const val FIRST_SLOT_UNDER_PANEL = 3

class MainActivity : ComponentActivity() {
    private val prefs by lazy { LauncherPrefs(this) }
    private var installed by mutableStateOf<List<InstalledApp>?>(null)
    private var order by mutableStateOf(emptyList<String>())
    private var hidden by mutableStateOf(emptyList<String>())
    private var showHidden by mutableStateOf(false)
    private var pingOpen by mutableStateOf(false)
    private var networkStatusOpen by mutableStateOf(false)
    private var camerasOpen by mutableStateOf(false)
    private var cameras by mutableStateOf(emptyList<Camera>())
    private var cameraGridLive by mutableStateOf(true)
    private var clipsShown by mutableStateOf(FrigateKind.entries.toList())
    private var blackBackground by mutableStateOf(false)
    // What the Clips tab shows
    private var clipsKind by mutableStateOf(FrigateKind.Alert)
    private var cameraGridMaxHardware by mutableIntStateOf(MAX_HARDWARE_DEFAULT)
    private var minimizeVideoStreams by mutableStateOf(false)
    private var selectedCamera by mutableStateOf("")
    private var tab by mutableStateOf(HomeTab.Home)
    private var haDialogOpen by mutableStateOf(false)
    private var haServer by mutableStateOf(HaServer(""))
    // Everything on the Assistant tab in its order, and the actions also in the top bar
    private var haEntities by mutableStateOf(emptyList<String>())
    private var haTopBar by mutableStateOf(emptyList<String>())
    // The Home Assistant dashboard the Assistant tab shows instead, if any
    private var haDisplay by mutableStateOf(HaDisplay())
    private var frigate by mutableStateOf(FrigateServer())
    private var frigateOpen by mutableStateOf(false)
    // The changed certificate whose dialog was closed: it doesn't open again for that one
    private var dismissedCertificate by mutableStateOf<String?>(null)
    private var launcherSettingsOpen by mutableStateOf(false)
    private var stuckPlayersOpen by mutableStateOf(false)
    private var replaceHome by mutableStateOf(false)
    private var catchHomeKey by mutableStateOf(false)
    private var reopenAfterHome by mutableStateOf(false)
    private var exportOpen by mutableStateOf(false)
    private var importOpen by mutableStateOf(false)
    private var watchNext by mutableStateOf(emptyList<WatchItem>())
    private var watchNextReadAt by mutableStateOf(0L)
    private var hiddenWatching by mutableStateOf(emptyList<String>())

    // Home override's crash guard: the launcher counts as started once it has stayed up a moment
    private val confirmStarted = Runnable {
        running = true
        if (prefs.overrideLaunches != 0) prefs.overrideLaunches = 0
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Last time's list: the tiles and the selection are there from the first frame
        installed = prefs.installedApps.ifEmpty { null }
        order = prefs.order
        hidden = prefs.hidden
        cameras = prefs.cameras
        cameraGridLive = prefs.cameraGridLive
        clipsShown = prefs.clipsShown
        blackBackground = prefs.blackBackground
        cameraGridMaxHardware = prefs.cameraGridMaxHardware
        DebugOverlay.shown = prefs.showDebugInfo
        minimizeVideoStreams = prefs.minimizeVideoStreams
        selectedCamera = prefs.selectedCamera
        haServer = HaServer(prefs.haUrl, prefs.haToken, prefs.haRefreshToken)
        haEntities = prefs.haEntities
        haTopBar = prefs.haTopBar
        haDisplay = prefs.haDisplay
        frigate = prefs.frigate
        hiddenWatching = prefs.hiddenWatching
        // Android stops asking by itself after it's been refused. Answering comes back to onResume.
        if (!canReadWatchNext()) requestPermissions(arrayOf(READ_TV_LISTINGS), 0)
        setContent {
            MaterialTheme(colorScheme = AppColorScheme) {
                // HomeBoard settings › Background
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    colors = if (blackBackground) SurfaceDefaults.colors(containerColor = Color.Black) else SurfaceDefaults.colors(),
                ) {
                    val all = installed
                    val visible = remember(all, order, hidden) { all?.let { arrange(it, order, hidden) } }
                    val hiddenApps = remember(all, hidden) {
                        all?.let { apps -> hidden.mapNotNull { key -> apps.find { it.key == key } } }
                    }
                    val camera = cameras.find { it.id == selectedCamera } ?: cameras.firstOrNull()
                    val mainCameraFocus = remember { FocusRequester() }
                    val haSensorsFocus = remember { FocusRequester() }
                    val networkFocus = remember { FocusRequester() }
                    val cogFocus = remember { FocusRequester() }
                    val secondTabFocus = remember { FocusRequester() }
                    val homeTabFocus = remember { FocusRequester() }
                    val alertsTabFocus = remember { FocusRequester() }
                    val assistantTabFocus = remember { FocusRequester() }
                    var topBarSelected by remember { mutableStateOf(false) }
                    var homeTabSelected by remember { mutableStateOf(false) }
                    var firstTileSelected by remember { mutableStateOf(false) }
                    var goToFirstTile by remember { mutableIntStateOf(0) }
                    // The alerts, apps and Continue Watching at the bottom: off on the Assistant tab
                    // unless chosen in the Home Assistant dialog. Off, they slide in while the
                    // selection is on them, reached with Down from the cards.
                    val bottomRow = tab != HomeTab.Assistant || haDisplay.bottomRow
                    var bottomRevealed by remember { mutableStateOf(false) }
                    val bottomShown = bottomRow || bottomRevealed
                    val bottomFraction by animateFloatAsState(if (bottomShown) 1f else 0f, label = "bottom rows")
                    // Back never leaves the launcher, it moves the selection: the top bar to Home,
                    // anything else to the first tile, and the first tile to the cog. Without the
                    // tiles, anything below the top bar goes to the Assistant tab.
                    BackHandler {
                        when {
                            topBarSelected && !homeTabSelected -> runCatching { homeTabFocus.requestFocus() }
                            !bottomShown -> if (!topBarSelected) runCatching { assistantTabFocus.requestFocus() }
                            firstTileSelected -> runCatching { cogFocus.requestFocus() }
                            else -> goToFirstTile++
                        }
                    }
                    // The top bar's, in their order on the Assistant tab, which has them all
                    val actions = remember(haEntities, haTopBar) { haEntities.filter { it in haTopBar }.take(MAX_HA_ACTIONS) }
                    val haConnection = rememberHaConnection(haServer, haEntities, haDisplay.dashboards)
                    val sideContent = haServer.isSet
                    // A dashboard in columns on the Home tab: the camera and the columns scroll sideways together
                    val homeStrip = sideContent && haDisplay.homeDashboard != null && haDisplay.homeColumns
                    // Picked in the Cameras tab, showing full screen
                    var fullScreenCamera by remember { mutableStateOf<Camera?>(null) }
                    val continueItems = remember(watchNext, watchNextReadAt, hiddenWatching, visible) {
                        watchRow(watchNext, watchNextReadAt, visible.orEmpty().map { it.key }, hiddenWatching)
                    }
                    val appRowFocus = remember { FocusRequester() }
                    val continueFocus = remember { FocusRequester() }
                    var continueActive by remember { mutableStateOf(false) }
                    val frigateFeed = rememberFrigateFeed(frigate, onCertificateAccepted = { saveFrigate(frigate.copy(certPin = it)) })
                    val alertsRowFocus = remember { FocusRequester() }
                    // From reaching the row until the remote is back on the tiles, wherever it goes meanwhile
                    var alertsRowShown by remember { mutableStateOf(false) }
                    // Not on the Alerts tab, nor on the Assistant tab unless chosen in the Home Assistant dialog
                    val alertsRowFeed = frigateFeed?.takeIf { tab != HomeTab.Alerts && bottomRow }
                    val hasAlertsRow = alertsRowFeed != null && alertsRowFeed.rowAlerts.isNotEmpty()
                    LaunchedEffect(hasAlertsRow) { if (!hasAlertsRow) alertsRowShown = false }
                    var tilesRowSelected by remember { mutableStateOf(false) }
                    var otherTileSelected by remember { mutableStateOf(false) }
                    var noAlertsSelected by remember { mutableStateOf(false) }
                    // Before Frigate is set up, on the Home tab, the tile offers to set it up
                    val frigateSetUpTile = frigateFeed == null && tab == HomeTab.Home
                    val showNoAlertsTile = (frigateFeed?.noRecentAlerts == true && bottomRow) || frigateSetUpTile
                    // Worked out before the tile is removed, when focus is still known to be on it
                    val lostNoAlertsTile = remember(showNoAlertsTile) { !showNoAlertsTile && noAlertsSelected }
                    val alertsExist = frigateFeed != null && frigateFeed.rowAlerts.isNotEmpty()
                    // Above the rows, the row of alerts is what shows below
                    var middleSelected by remember { mutableStateOf(false) }
                    LaunchedEffect(hasAlertsRow, topBarSelected, middleSelected) {
                        if (hasAlertsRow && (topBarSelected || middleSelected)) alertsRowShown = true
                    }
                    // When alerts arrive or the Cameras tab opens, the selection on the first tile goes to the row
                    var alertsRowDue by remember { mutableStateOf(false) }
                    LaunchedEffect(alertsExist) { if (alertsExist) alertsRowDue = true }
                    LaunchedEffect(tab) { if (tab == HomeTab.Cameras) alertsRowDue = true }
                    LaunchedEffect(showNoAlertsTile) {
                        if (lostNoAlertsTile && (!alertsExist || tab == HomeTab.Alerts)) goToFirstTile++
                    }
                    LaunchedEffect(alertsRowDue, hasAlertsRow) {
                        if (!alertsRowDue || !hasAlertsRow) return@LaunchedEffect
                        // Not from another tile or the row below: those are in use
                        if (!otherTileSelected && !continueActive && (tilesRowSelected || lostNoAlertsTile)) {
                            alertsRowShown = true
                            withFrameNanos { } // let the row lay out, so its first card is focusable
                            runCatching { alertsRowFocus.requestFocus() }
                        }
                        alertsRowDue = false
                    }
                    // Where up goes from the bottom rows, from the item on screen in this 0-based slot
                    fun aboveRows(slot: Int) = when {
                        // What's above moves sideways: the nearest card or the camera
                        tab == HomeTab.Home && homeStrip -> FocusRequester.Default
                        slot >= FIRST_SLOT_UNDER_PANEL && tab == HomeTab.Home && haServer.isSet -> haSensorsFocus
                        slot < FIRST_SLOT_UNDER_PANEL && tab == HomeTab.Home && camera != null -> mainCameraFocus
                        else -> FocusRequester.Default
                    }
                    // A number button from the Alerts or Assistant tab: the selection goes to the camera
                    var mainCameraDue by remember { mutableStateOf(false) }
                    LaunchedEffect(mainCameraDue, tab) {
                        if (!mainCameraDue || tab != HomeTab.Home) return@LaunchedEffect
                        withFrameNanos { } // let the Home tab lay out, so the camera is focusable
                        runCatching { mainCameraFocus.requestFocus() }
                        mainCameraDue = false
                    }
                    // A number button on the Cameras tab: the selection goes to that camera
                    var gridCameraDue by remember { mutableStateOf<String?>(null) }
                    // The Cameras tab's camera the selection was last on: channel up and down go on from it
                    var gridFocusedCamera by remember { mutableStateOf<String?>(null) }
                    // The colour buttons open the tabs, with the selection on the tab. A number button picks
                    // a camera: selected on the Cameras tab, as the Home tab's camera everywhere else. The
                    // dot button selects the cog.
                    fun onRemoteButton(event: KeyEvent): Boolean {
                        if (isSettingsButton(event)) {
                            if (event.type == KeyEventType.KeyDown) runCatching { cogFocus.requestFocus() }
                            return true
                        }
                        colorButtonTab(event)?.let { pressed ->
                            // The yellow button does nothing while the Clips tab is turned off
                            if (pressed == HomeTab.Alerts && clipsShown.isEmpty()) return true
                            if (event.type == KeyEventType.KeyDown) {
                                tab = pressed
                                val tabFocus = when (pressed) {
                                    HomeTab.Home -> homeTabFocus
                                    HomeTab.Cameras -> secondTabFocus
                                    HomeTab.Alerts -> alertsTabFocus
                                    HomeTab.Assistant -> assistantTabFocus
                                }
                                runCatching { tabFocus.requestFocus() }
                            }
                            return true
                        }
                        // Channel up and down: the next or previous camera, on the Home tab wherever the
                        // selection is, and on the Cameras tab the selection moves to it
                        channelStep(event)?.let { step ->
                            if (tab != HomeTab.Home && tab != HomeTab.Cameras) return false
                            if (event.type == KeyEventType.KeyDown) {
                                if (tab == HomeTab.Cameras) {
                                    steppedCamera(cameras, gridFocusedCamera, step)?.let { gridCameraDue = it.id }
                                } else {
                                    steppedCamera(cameras, camera?.id, step)?.let(::selectCamera)
                                }
                            }
                            return true
                        }
                        val index = cameraButtonIndex(event) ?: return false
                        val picked = cameras.getOrNull(index)
                        if (event.type == KeyEventType.KeyDown && picked != null) {
                            if (tab == HomeTab.Cameras) {
                                gridCameraDue = picked.id
                            } else {
                                selectCamera(picked)
                                if (tab != HomeTab.Home) {
                                    tab = HomeTab.Home
                                    mainCameraDue = true
                                }
                            }
                        }
                        return true
                    }
                    Column(modifier = Modifier.fillMaxSize().onPreviewKeyEvent(::onRemoteButton)) {
                        HomeTopBar(
                            tab = tab,
                            onTabChange = { tab = it },
                            secondTabFocus = secondTabFocus,
                            homeTabFocus = homeTabFocus,
                            alertsTabFocus = alertsTabFocus,
                            assistantTabFocus = assistantTabFocus,
                            onHomeTabSelected = { homeTabSelected = it },
                            // Part of the debug info
                            showFreeMemory = DebugOverlay.shown,
                            tabs = HomeTab.entries.filter { it != HomeTab.Alerts || clipsShown.isNotEmpty() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = EdgePadding, top = TopPadding, end = EdgePadding)
                                .onFocusChanged { topBarSelected = it.hasFocus },
                            actions = {
                                // These get what the action buttons leave
                                StuckPlayersWarning(
                                    onClick = { stuckPlayersOpen = true },
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                HomeHelperWarning(
                                    replaceHome,
                                    onClick = { launcherSettingsOpen = true },
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (haServer.isSet) HaActionButtons(haConnection, actions)
                                NetworkButton(
                                    onClick = { networkStatusOpen = true },
                                    modifier = Modifier.focusRequester(networkFocus),
                                )
                            },
                        ) {
                            SettingsButton(
                                showHidden = showHidden,
                                hiddenCount = hiddenApps?.size ?: 0,
                                onShowHiddenChange = { showHidden = it },
                                onOpenCameras = { camerasOpen = true },
                                onOpenHomeAssistant = { haDialogOpen = true },
                                onOpenFrigate = { frigateOpen = true },
                                onOpenLauncherSettings = { launcherSettingsOpen = true },
                                showDebugInfo = DebugOverlay.shown,
                                onShowDebugInfoChange = {
                                    DebugOverlay.shown = it
                                    prefs.showDebugInfo = it
                                },
                                modifier = Modifier.focusRequester(cogFocus),
                            )
                        }
                        if (tab == HomeTab.Cameras) {
                            CameraGrid(
                                cameras = cameras,
                                // Like the main camera: a clip opened from the alerts row may need its decoder,
                                // and so may a camera shown full screen
                                playSubstreams = cameraGridLive &&
                                    !(minimizeVideoStreams && (frigateFeed?.playing != null || fullScreenCamera != null)),
                                minimizeStreams = minimizeVideoStreams,
                                maxHardware = cameraGridMaxHardware,
                                spareDecoder = fullScreenCamera != null,
                                onSelect = { fullScreenCamera = it },
                                onSetUp = { camerasOpen = true },
                                selectCamera = gridCameraDue,
                                onCameraSelected = { gridCameraDue = null },
                                onCameraFocused = { gridFocusedCamera = it },
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = EdgePadding).onFocusChanged { middleSelected = it.hasFocus },
                            )
                        } else if (tab == HomeTab.Alerts) {
                            FrigateClipsTab(
                                feed = frigateFeed,
                                kinds = clipsShown,
                                // The one chosen, unless the Frigate dialog has hidden it since
                                kind = clipsKind.takeIf { it in clipsShown } ?: clipsShown.firstOrNull() ?: FrigateKind.Alert,
                                onKindChange = { clipsKind = it },
                                tabFocus = alertsTabFocus,
                                // A changed certificate: its dialog again
                                onSetUp = { if (frigateFeed?.changedCertificate != null) dismissedCertificate = null else frigateOpen = true },
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = EdgePadding),
                            )
                        } else if (tab == HomeTab.Assistant) {
                            HaAssistantTab(
                                connection = haConnection,
                                homeAssistantSet = haServer.isSet,
                                entityIds = haEntities,
                                dashboard = haDisplay.dashboard,
                                tabFocus = assistantTabFocus,
                                onSetUp = { haDialogOpen = true },
                                sectionColumns = haDisplay.sectionColumns,
                                toScreenEdge = !bottomShown,
                                modifier = Modifier.weight(1f).fillMaxWidth().padding(top = EdgePadding).onFocusChanged { middleSelected = it.hasFocus },
                            )
                        } else {
                            // The camera, stopped before a clip opens, so the clip can have its decoder
                            @Composable
                            fun CameraBox(width: Dp) {
                                Box(modifier = Modifier.width(width).fillMaxHeight()) {
                                    if (camera != null && !(minimizeVideoStreams && frigateFeed?.playing != null)) {
                                        // Restarted when the panel appears or goes: the playing video
                                        // doesn't follow the camera's new size otherwise
                                        key(sideContent) {
                                            MainCamera(
                                                camera = camera,
                                                cameras = cameras,
                                                minimizeStreams = minimizeVideoStreams,
                                                onSelect = ::selectCamera,
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .aspectRatio(16f / 9f)
                                                    .focusRequester(mainCameraFocus)
                                                    .focusProperties { up = secondTabFocus },
                                            )
                                        }
                                    }
                                }
                            }
                            val homeEntities = remember(haEntities) { haEntities.filterNot(::isHaAction) }
                            if (homeStrip) {
                                // A dashboard in columns: the camera and the columns in one row that
                                // scrolls sideways to the screen's right edge, the camera leaving on
                                // the left as Right brings in more columns
                                BoxWithConstraints(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(top = EdgePadding)
                                        .onFocusChanged { middleSelected = it.hasFocus },
                                ) {
                                    // As large as when next to the panel
                                    val cameraWidth = minOf(maxHeight * 16f / 9f, maxWidth - EdgePadding * 3 - SidePanelWidth)
                                    HaAssistantTab(
                                        connection = haConnection,
                                        homeAssistantSet = haServer.isSet,
                                        entityIds = homeEntities,
                                        dashboard = haDisplay.homeDashboard,
                                        // The button next to the cog
                                        tabFocus = networkFocus,
                                        onSetUp = { haDialogOpen = true },
                                        sectionColumns = true,
                                        leading = LeadingItem(cameraWidth, cameraWidth * 9f / 16f) { CameraBox(cameraWidth) },
                                        columnsFocus = haSensorsFocus,
                                        modifier = Modifier.fillMaxSize(),
                                    )
                                }
                            } else {
                                // The camera, as large as fits above the app row, and Home Assistant
                                // next to it, which gets all the width the camera leaves
                                BoxWithConstraints(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth()
                                        .padding(start = EdgePadding, top = EdgePadding, end = EdgePadding)
                                        .onFocusChanged { middleSelected = it.hasFocus },
                                ) {
                                    // Before Home Assistant is set up, the panel's outline keeps its place
                                    val cameraWidth = minOf(maxHeight * 16f / 9f, maxWidth - SidePanelWidth - EdgePadding)
                                    Row(horizontalArrangement = Arrangement.spacedBy(EdgePadding)) {
                                        CameraBox(cameraWidth)
                                        if (sideContent && haDisplay.homeDashboard != null) {
                                            // A dashboard chosen for the Home tab: its sections, card by card
                                            HaAssistantTab(
                                                connection = haConnection,
                                                homeAssistantSet = haServer.isSet,
                                                entityIds = homeEntities,
                                                dashboard = haDisplay.homeDashboard,
                                                // The button next to the cog
                                                tabFocus = networkFocus,
                                                onSetUp = { haDialogOpen = true },
                                                inset = 0.dp,
                                                modifier = Modifier.weight(1f).fillMaxHeight().focusRequester(haSensorsFocus),
                                            )
                                        } else if (sideContent) {
                                            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                                                HomeAssistantPanel(
                                                    connection = haConnection,
                                                    entityIds = homeEntities,
                                                    sensorsFocus = haSensorsFocus,
                                                    // The button next to the cog
                                                    upFocus = networkFocus,
                                                    onLogIn = { haDialogOpen = true },
                                                    onOpen = {
                                                        tab = HomeTab.Assistant
                                                        runCatching { assistantTabFocus.requestFocus() }
                                                    },
                                                    modifier = Modifier.fillMaxSize(),
                                                )
                                            }
                                        } else {
                                            HomeAssistantSetUpOutline(
                                                buttonFocus = haSensorsFocus,
                                                upFocus = networkFocus,
                                                onSetUp = { haDialogOpen = true },
                                                modifier = Modifier.weight(1f).fillMaxHeight(),
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        // Hidden on the Assistant tab unless chosen or the selection is on them: its
                        // cards then take the whole height. They slide up from the screen's edge,
                        // placed below it while hidden, where only Down finds them.
                        Column(
                            Modifier
                                .onFocusChanged { if (!bottomRow) bottomRevealed = it.hasFocus }
                                .clipToBounds()
                                .layout { measurable, constraints ->
                                    val placeable = measurable.measure(constraints.copy(minHeight = 0, maxHeight = Constraints.Infinity))
                                    layout(placeable.width, (placeable.height * bottomFraction).roundToInt()) { placeable.place(0, 0) }
                                },
                        ) {
                            // The tiles' grow room counts towards the margins above and below the row
                            Spacer(Modifier.height(EdgePadding - TileGrowRoom))
                            SlidingRows(
                                revealed = continueActive,
                                aboveRevealed = alertsRowShown && hasAlertsRow,
                                above = {
                                    if (alertsRowFeed != null) {
                                        FrigateAlertsRow(
                                            feed = alertsRowFeed,
                                            shown = alertsRowShown,
                                            firstCardFocus = alertsRowFocus,
                                            upFocus = ::aboveRows,
                                            downFocus = appRowFocus,
                                            onEnter = { alertsRowShown = true },
                                            onLeave = { goToFirstTile++ },
                                        )
                                    }
                                },
                                first = {
                                    AppRowSpace(Modifier.onFocusChanged { tilesRowSelected = it.hasFocus }) {
                                        if (visible != null && hiddenApps != null) AppRow(
                                            apps = visible,
                                            hiddenApps = hiddenApps,
                                            showHidden = showHidden,
                                            onShowHiddenChange = { showHidden = it },
                                            onHide = { app ->
                                                // Hidden apps leave the row order and queue up at the end
                                                saveOrder(visible.map { it.key } - app.key)
                                                saveHidden(hidden + app.key)
                                            },
                                            onUnhide = { app ->
                                                // Back at the end of the visible apps
                                                saveOrder(visible.map { it.key } + app.key)
                                                saveHidden(hidden - app.key)
                                            },
                                            onReorder = { list -> saveOrder(list.map { it.key }) },
                                            upFocus = { slot ->
                                                when {
                                                    hasAlertsRow -> alertsRowFocus
                                                    else -> aboveRows(slot)
                                                }
                                            },
                                            downFocus = if (continueItems.isNotEmpty()) continueFocus else FocusRequester.Cancel,
                                            rowFocus = appRowFocus,
                                            goToFirstTile = goToFirstTile,
                                            onFirstTileSelected = { firstTileSelected = it },
                                            onOtherTileSelected = { otherTileSelected = it },
                                            showNoAlertsTile = showNoAlertsTile,
                                            noAlertsLabel = if (frigateSetUpTile) "Set up Frigate" else "No Recent Alerts",
                                            onNoAlertsSelected = { noAlertsSelected = it },
                                            onNoAlertsClick = click@{
                                                if (frigateSetUpTile) {
                                                    frigateOpen = true
                                                    return@click
                                                }
                                                // The Clips tab on its alerts, or its first list, unless it's turned off
                                                val kind = FrigateKind.Alert.takeIf { it in clipsShown } ?: clipsShown.firstOrNull()
                                                if (kind != null) {
                                                    clipsKind = kind
                                                    tab = HomeTab.Alerts
                                                    runCatching { alertsTabFocus.requestFocus() }
                                                }
                                            },
                                            modifier = Modifier.onFocusChanged { if (it.hasFocus) alertsRowShown = false },
                                        ) else AppRowPlaceholder()
                                    }
                                },
                                second = {
                                    ContinueWatchingRow(
                                        items = continueItems,
                                        apps = all.orEmpty(),
                                        firstCardFocus = continueFocus,
                                        upFocus = appRowFocus,
                                        onActiveChange = { continueActive = it },
                                        onHide = {
                                            hiddenWatching = hiddenWatching + it.hideKey
                                            prefs.hiddenWatching = hiddenWatching
                                        },
                                        onLeave = { goToFirstTile++ },
                                    )
                                },
                            )
                            Spacer(Modifier.height(EdgePadding - TileGrowRoom))
                        }
                    }
                    frigateFeed?.let { FrigateClipPlayer(it, minimizeVideoStreams) }
                    fullScreenCamera?.let {
                        FullScreenCamera(
                            it,
                            minimizeVideoStreams,
                            onCameraButton = { index -> cameras.getOrNull(index)?.let { picked -> fullScreenCamera = picked } },
                            onChannel = { step -> steppedCamera(cameras, it.id, step)?.let { picked -> fullScreenCamera = picked } },
                            onClose = { fullScreenCamera = null },
                        )
                    }
                    if (camerasOpen) {
                        CamerasDialog(
                            cameras = cameras,
                            onChange = ::saveCameras,
                            playSubstreams = cameraGridLive,
                            onPlaySubstreamsChange = {
                                cameraGridLive = it
                                prefs.cameraGridLive = it
                            },
                            maxHardware = cameraGridMaxHardware,
                            onMaxHardwareChange = {
                                cameraGridMaxHardware = it
                                prefs.cameraGridMaxHardware = it
                            },
                            minimizeStreams = minimizeVideoStreams,
                            onMinimizeStreamsChange = {
                                minimizeVideoStreams = it
                                prefs.minimizeVideoStreams = it
                            },
                            onDismiss = { camerasOpen = false },
                        )
                    }
                    if (haDialogOpen) {
                        HomeAssistantDialog(
                            initialServer = haServer,
                            initialUsername = remember { prefs.haUsername },
                            initialEntities = haEntities,
                            initialTopBar = haTopBar,
                            initialDisplay = haDisplay,
                            loginRejected = haConnection.status == HaStatus.TokenRejected,
                            onSave = { server, username, entities, topBar, display ->
                                // A replaced username-and-password login is ended in Home Assistant
                                if (haServer.refreshToken != server.refreshToken) revokeHaLogin(haServer)
                                haServer = server
                                prefs.haUrl = server.url
                                prefs.haToken = server.token
                                prefs.haRefreshToken = server.refreshToken
                                prefs.haUsername = username
                                saveHaChoice(entities, topBar)
                                haDisplay = display
                                prefs.haDisplay = display
                            },
                            onLogOut = ::logOutOfHa,
                            onDiscardLogin = ::revokeHaLogin,
                            onDismiss = { haDialogOpen = false },
                        )
                    }
                    val changedCertificate = frigateFeed?.changedCertificate
                    if (changedCertificate != null && changedCertificate != dismissedCertificate && !frigateOpen) {
                        FrigateCertificateDialog(
                            server = frigate,
                            offered = changedCertificate,
                            onAccept = { saveFrigate(frigate.copy(certPin = changedCertificate)) },
                            onLogIn = { frigateOpen = true },
                            onDismiss = { dismissedCertificate = changedCertificate },
                        )
                    }
                    if (frigateOpen) {
                        FrigateDialog(
                            initial = frigate,
                            feed = frigateFeed,
                            onSave = ::saveFrigate,
                            clipsShown = clipsShown,
                            onClipsShownChange = {
                                clipsShown = it
                                prefs.clipsShown = it
                                if (it.isEmpty() && tab == HomeTab.Alerts) tab = HomeTab.Home
                            },
                            onDismiss = { frigateOpen = false },
                        )
                    }
                    if (launcherSettingsOpen) {
                        LauncherSettingsDialog(
                            catchHomeKey = catchHomeKey,
                            reopenAfterHome = reopenAfterHome,
                            autoTurnedOff = remember { prefs.replaceHomeAutoOff },
                            onCatchHomeKeyChange = { on ->
                                prefs.catchHomeKey = on
                                homeOverrideChanged()
                            },
                            onReopenAfterHomeChange = { on ->
                                prefs.reopenAfterHome = on
                                homeOverrideChanged()
                            },
                            onExport = { exportOpen = true },
                            onImport = { importOpen = true },
                            blackBackground = blackBackground,
                            onBlackBackgroundChange = {
                                blackBackground = it
                                prefs.blackBackground = it
                            },
                            onDismiss = { launcherSettingsOpen = false },
                        )
                    }
                    if (stuckPlayersOpen) {
                        StuckPlayersDialog(onDismiss = { stuckPlayersOpen = false })
                    }
                    if (exportOpen) {
                        ExportDialog(
                            file = SettingsFile(
                                cameras = cameras,
                                login = haServer.takeIf { it.isSet },
                                username = remember { prefs.haUsername },
                                sensors = haEntities.filterNot(::isHaAction).map { NamedEntity(it, haConnection.entities[it]?.name ?: it) },
                                actions = haEntities.filter(::isHaAction).map { NamedEntity(it, haConnection.entities[it]?.name ?: it) },
                                order = haEntities,
                                topBar = haTopBar,
                                frigate = frigate.takeIf { it.isSet },
                                appTiles = visible.orEmpty().map { NamedEntity(it.key, it.label) },
                            ),
                            onDismiss = { exportOpen = false },
                        )
                    }
                    if (importOpen) {
                        ImportDialog(onImport = ::applyImport, onDismiss = { importOpen = false })
                    }
                    if (networkStatusOpen) {
                        NetworkStatusDialog(
                            frigate = frigateFeed,
                            // It only connects with something to show
                            homeAssistant = haConnection.takeIf { haServer.isSet && haEntities.isNotEmpty() },
                            onOpenPing = { pingOpen = true },
                            onDismiss = { networkStatusOpen = false },
                        )
                    }
                    if (pingOpen) {
                        PingDialog(
                            initialHost = remember { prefs.pingHost },
                            onHostEntered = { prefs.pingHost = it },
                            onDismiss = { pingOpen = false },
                        )
                    }
                }
            }
        }
    }

    /** Ends the login; the address, username and chosen entities stay for logging in again */
    private fun logOutOfHa() {
        revokeHaLogin(haServer)
        haServer = HaServer(haServer.url)
        prefs.haToken = ""
        prefs.haRefreshToken = ""
    }

    private fun revokeHaLogin(server: HaServer) {
        lifecycleScope.launch { haRevoke(server) }
    }

    /** Adds what's in the file. A camera whose name is already taken gets "(imported)" after its name. */
    private fun applyImport(file: SettingsFile) {
        // The same camera, by its exact addresses, only takes the imported name. Each camera here
        // matches one in the file; any more copies in the file aren't added.
        val sameUrls = { a: Camera, b: Camera -> a.url == b.url && a.substreamUrl == b.substreamUrl }
        val renamed = mutableMapOf<String, String>()
        val new = mutableListOf<Camera>()
        file.cameras.forEach { camera ->
            val same = cameras.filter { sameUrls(it, camera) }
            val free = same.firstOrNull { it.id !in renamed }
            when {
                free != null -> renamed[free.id] = camera.name
                same.isEmpty() && new.none { sameUrls(it, camera) } -> new += camera
            }
        }
        val names = cameras.filter { it.id !in renamed }.map { it.name }.toMutableSet()
        fun uniqueName(wanted: String): String {
            var name = wanted
            while (name in names) name += " (imported)"
            names += name
            return name
        }
        val merged = cameras.map { camera -> renamed[camera.id]?.let { camera.copy(name = uniqueName(it)) } ?: camera } +
            new.map { it.copy(id = UUID.randomUUID().toString(), name = uniqueName(it.name)) }
        if (merged != cameras) saveCameras(merged)
        file.login?.let { server ->
            // A replaced username-and-password login is ended in Home Assistant
            if (haServer.refreshToken != server.refreshToken) revokeHaLogin(haServer)
            haServer = server
            prefs.haUrl = server.url
            prefs.haToken = server.token
            prefs.haRefreshToken = server.refreshToken
            prefs.haUsername = file.username
        }
        // Files from earlier versions: the sensors, then the actions, the first ones in the top bar
        val fileActions = file.actions.map { it.id }
        val imported = file.order ?: (file.sensors.map { it.id } + fileActions)
        val entities = (haEntities + imported).distinct()
        saveHaChoice(entities, (haTopBar + (file.topBar ?: fileActions.take(MAX_HA_ACTIONS))).distinct().filter { it in entities }.take(MAX_HA_ACTIONS))
        file.frigate?.let(::saveFrigate)
        // The row becomes the apps in the file that are on this TV; the others can be unhidden later
        val apps = installed.orEmpty().map { it.key }
        val shown = file.appTiles.map { it.id }.filter { it in apps }
        if (shown.isNotEmpty()) {
            saveOrder(shown)
            saveHidden((hidden + apps).distinct() - shown.toSet())
        }
        Toast.makeText(this, "Settings imported", Toast.LENGTH_SHORT).show()
    }

    private fun saveHaChoice(entities: List<String>, topBar: List<String>) {
        haEntities = entities
        haTopBar = topBar
        prefs.saveHaChoice(entities, topBar)
    }

    private fun saveFrigate(value: FrigateServer) {
        frigate = value
        prefs.frigate = value
    }

    private fun selectCamera(camera: Camera) {
        selectedCamera = camera.id
        prefs.selectedCamera = camera.id
    }

    private fun saveCameras(value: List<Camera>) {
        cameras = value
        prefs.cameras = value
        pruneCameraSnapshots(this, value.flatMap { listOf(it.url, it.substreamUrl) })
    }

    private fun saveOrder(value: List<String>) {
        order = value
        prefs.order = value
    }

    private fun saveHidden(value: List<String>) {
        hidden = value
        prefs.hidden = value
    }

    private fun homeOverrideChanged() {
        prefs.replaceHomeAutoOff = false
        updateReplaceHome()
    }

    private fun updateReplaceHome() {
        replaceHome = prefs.replaceHome
        catchHomeKey = prefs.catchHomeKey
        reopenAfterHome = prefs.reopenAfterHome
    }

    override fun onResume() {
        super.onResume()
        // The home override may have switched itself off meanwhile
        updateReplaceHome()
        window.decorView.postDelayed(confirmStarted, 2_000)
        // Refresh in the background (apps may have been installed or removed meanwhile);
        // the current row stays on screen until the new list is ready
        lifecycleScope.launch {
            val apps = withContext(Dispatchers.IO) { loadInstalledApps(this@MainActivity) }
            if (apps != installed) prefs.installedApps = apps
            installed = apps
        }
        // Shows watched meanwhile
        if (canReadWatchNext()) {
            lifecycleScope.launch {
                watchNext = withContext(Dispatchers.IO) { loadWatchNext(this@MainActivity) }
                watchNextReadAt = System.currentTimeMillis()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        window.decorView.removeCallbacks(confirmStarted)
    }

    override fun onDestroy() {
        super.onDestroy()
        running = false
    }

    override fun onStart() {
        // Before the cameras start, which happens just after this: the TV may be on its way to standby
        ScreenState.refresh(this)
        super.onStart()
    }

    override fun onStop() {
        super.onStop()
        // Hidden apps are only revealed until you leave the launcher
        showHidden = false
    }

    companion object {
        /** Started properly and still alive; a crash takes the whole process, so this resets */
        @Volatile
        var running = false
            private set
    }
}
