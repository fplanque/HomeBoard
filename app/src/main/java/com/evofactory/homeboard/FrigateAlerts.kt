package com.evofactory.homeboard

import android.text.format.DateUtils
import android.view.SurfaceView
import android.view.ViewGroup
import androidx.annotation.OptIn
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

// How often the list refreshes while it's on screen
private const val ALERTS_REFRESH_MS = 30_000L
private const val SOCKET_REFRESH_GAP_MS = 1_000L
private const val SOCKET_FIRST_RETRY_MS = 5_000L
private const val SOCKET_LAST_RETRY_MS = 300_000L

// How soon to try again when Frigate can't be reached
private const val ALERTS_UNREACHABLE_RETRY_MS = 5_000L

private const val CLIP_PROGRESS_INTERVAL_MS = 200L
private val CLIP_PROGRESS_HEIGHT = 6.dp

/**
 * The latest Frigate alerts, for the Alerts tab and the row above the app tiles. Keeps refreshing
 * while the launcher is on screen, so up from the tiles knows whether there's anything to show.
 * After a failed refresh the last list stays, with what went wrong in [error].
 */
class FrigateFeed(val client: FrigateClient) {
    var alerts by mutableStateOf<List<FrigateAlert>?>(null)
    var error by mutableStateOf<String?>(null)

    // Ids of the alerts Frigate has no recordings for
    var noVideo by mutableStateOf(emptySet<String>())

    // Refused login or changed certificate: only the settings can fix it
    var settingsNeeded by mutableStateOf(false)

    // A certificate refused for not being the one this TV knows: the dialog offers to accept it
    var changedCertificate by mutableStateOf<String?>(null)
    var playing by mutableStateOf<FrigateAlert?>(null)

    // For the network status: when a query last worked, and how many failed since
    var lastQueryAt by mutableStateOf<Long?>(null)
    var failedQueries by mutableIntStateOf(0)

    // The live connection: when it was made, or else why there's none
    var socketConnectedAt by mutableStateOf<Long?>(null)
    var socketProblem by mutableStateOf<String?>(null)

    // Goes up when the socket says an alert changed, to refresh without waiting
    val refreshRequests = MutableStateFlow(0)

    // The alert watched last, for the list on screen to select once the player has closed
    var returnTo by mutableStateOf<String?>(null)
}

/** Null while Frigate isn't set up */
@Composable
fun rememberFrigateFeed(server: FrigateServer, onCertificateAccepted: (String) -> Unit): FrigateFeed? {
    if (!server.isSet && !server.trustsFirstCertificate) return null
    val feed = remember(server) { FrigateFeed(FrigateClient(server)) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(feed, feed.playing == null) {
        if (feed.playing != null) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                var nextTry = ALERTS_REFRESH_MS
                val requested = feed.refreshRequests.value
                try {
                    val latest = feed.client.alerts()
                    feed.alerts = latest
                    feed.error = null
                    feed.lastQueryAt = System.currentTimeMillis()
                    feed.failedQueries = 0
                    if (server.certPin.isEmpty() && feed.client.seenCertPin.isNotEmpty()) onCertificateAccepted(feed.client.seenCertPin)
                    feed.settingsNeeded = false
                    // One at a time: if the login stops working, it's only tried once
                    for (alert in latest) {
                        // A newer list is waiting
                        if (feed.refreshRequests.value != requested) break
                        when (feed.client.hasVideo(alert)) {
                            true -> feed.noVideo -= alert.id
                            false -> feed.noVideo += alert.id
                            null -> Unit
                        }
                    }
                } catch (e: FrigateException) {
                    feed.error = e.message
                    feed.failedQueries++
                    feed.settingsNeeded = e.stopRetrying
                    feed.changedCertificate = feed.client.changedCertPin
                    // Asking again with a refused login could get it locked out
                    if (e.stopRetrying) break
                    if (e.unreachable) nextTry = ALERTS_UNREACHABLE_RETRY_MS
                }
                val requestedNow = withTimeoutOrNull(nextTry) { feed.refreshRequests.first { it != requested } }
                // Changes come in bursts while an alert goes on
                if (requestedNow != null) delay(SOCKET_REFRESH_GAP_MS)
            }
        }
    }

    // Only once a query has worked: it has logged in, so a refused login isn't tried again here
    val canConnect = feed.lastQueryAt != null && !feed.settingsNeeded
    LaunchedEffect(feed, canConnect) {
        if (!canConnect) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            var wait = SOCKET_FIRST_RETRY_MS
            try {
                while (true) {
                    val problem = feed.client.watchAlerts(
                        onConnected = {
                            wait = SOCKET_FIRST_RETRY_MS
                            feed.socketConnectedAt = System.currentTimeMillis()
                            feed.socketProblem = null
                            AppLog.i("Frigate", "Live connection made")
                            // Anything that happened while it was down
                            feed.refreshRequests.update { it + 1 }
                        },
                        onAlertChanged = { feed.refreshRequests.update { it + 1 } },
                    )
                    feed.socketConnectedAt = null
                    feed.socketProblem = problem
                    AppLog.w("Frigate", "Live connection lost: $problem")
                    delay(wait)
                    wait = (wait * 2).coerceAtMost(SOCKET_LAST_RETRY_MS)
                }
            } finally {
                feed.socketConnectedAt = null
            }
        }
    }
    return feed
}

/** The latest Frigate alerts on two rows that scroll sideways, newest first. OK plays an alert's clip. */
@Composable
fun FrigateAlertsGrid(feed: FrigateFeed?, onSetUp: () -> Unit, modifier: Modifier = Modifier) {
    if (feed == null) {
        SettingsPrompt(null, "Set up Frigate", onSetUp, modifier)
        return
    }
    val shown = feed.alerts
    val problem = feed.error
    when {
        feed.settingsNeeded -> SettingsPrompt(problem, if (feed.changedCertificate != null) "Review the certificate" else "Open Frigate settings", onSetUp, modifier)
        shown == null -> AlertsMessage(problem ?: "Loading alerts…", modifier, isError = problem != null)
        shown.isEmpty() && problem == null -> AlertsMessage("No alerts in the last $ALERTS_WITHIN_DAYS days", modifier)
        else -> Column(modifier = modifier) {
            if (problem != null) {
                Text(
                    problem,
                    style = MaterialTheme.typography.bodySmall,
                    color = ErrorColor,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = EdgePadding, end = EdgePadding, bottom = PanelItemSpacing),
                )
            }
            TwoRowGrid(
                items = shown,
                key = { it.id },
                onSelect = { feed.playing = it },
                selectKey = feed.returnTo,
                onSelected = { feed.returnTo = null },
                modifier = Modifier.weight(1f).fillMaxWidth(),
            ) { alert -> AlertTile(feed.client, alert, hasNoVideo = alert.id in feed.noVideo) }
        }
    }
}

/** The clip picked in the grid or the row, full screen. With [minimizeStreams], it waits for the cameras' players to release first. */
@Composable
fun FrigateClipPlayer(feed: FrigateFeed, minimizeStreams: Boolean) {
    feed.playing?.let { alert ->
        val alerts = feed.alerts.orEmpty()
        val index = alerts.indexOfFirst { it.id == alert.id }
        ClipPlayer(
            client = feed.client,
            alert = alert,
            // Newest first, as in the row and the grid
            newer = alerts.getOrNull(index - 1).takeIf { index >= 0 },
            older = alerts.getOrNull(index + 1).takeIf { index >= 0 },
            minimizeStreams = minimizeStreams,
            onOpen = { feed.playing = it },
            onDismiss = {
                feed.returnTo = alert.id
                feed.playing = null
            },
        )
    }
}

/** What the row above the app tiles shows; empty if there's nothing to go up to */
val FrigateFeed.rowAlerts: List<FrigateAlert> get() = if (settingsNeeded) emptyList() else alerts.orEmpty()

/** Frigate answered and has nothing: not while it's unreachable, when there's no knowing */
val FrigateFeed.noRecentAlerts: Boolean get() = alerts?.isEmpty() == true && error == null && !settingsNeeded

/** The same alerts as the Alerts tab, in one row the size of the app tiles. */
@kotlin.OptIn(ExperimentalFoundationApi::class)
@Composable
fun FrigateAlertsRow(
    feed: FrigateFeed,
    // On screen, whether or not the remote is on it
    shown: Boolean,
    // Up from an app tile comes here: the first card, the newest alert
    firstCardFocus: FocusRequester,
    // Where up goes from the card on screen in this 0-based slot
    upFocus: (slot: Int) -> FocusRequester,
    // Down from any card: the app tiles, which wait below this row while it shows
    downFocus: FocusRequester,
    onEnter: () -> Unit,
    // Go back to the app tiles: Back, or nothing left in the row
    onLeave: () -> Unit,
) {
    val items = feed.rowAlerts
    val listState = rememberLazyListState()
    val requesters = remember { mutableMapOf<String, FocusRequester>() }
    var focusedId by remember { mutableStateOf<String?>(null) }
    var focusedIndex by remember { mutableIntStateOf(0) }
    var pendingFocus by remember { mutableStateOf<String?>(null) }

    // Starts from the newest next time, including one that arrived while hidden
    val newestId = items.firstOrNull()?.id
    LaunchedEffect(shown, newestId) { if (!shown) listState.scrollToItem(0) }
    // On screen, the row would keep to the card it starts on, with a new alert out of sight before it
    val wasAtStart = remember(newestId) {
        Snapshot.withoutReadObservation { listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset == 0 }
    }
    LaunchedEffect(newestId) { if (wasAtStart) listState.scrollToItem(0) }

    // Worked out before the card is removed, when focus is still known to be on it
    val lostFocus = remember(items) { focusedId != null && items.none { it.id == focusedId } }
    LaunchedEffect(items) {
        if (!lostFocus) return@LaunchedEffect
        val next = items.getOrNull(focusedIndex) ?: items.lastOrNull()
        if (next != null) pendingFocus = next.id else onLeave()
    }

    LaunchedEffect(feed.returnTo) {
        val id = feed.returnTo ?: return@LaunchedEffect
        val index = items.indexOfFirst { it.id == id }
        if (index >= 0) {
            if (listState.layoutInfo.visibleItemsInfo.none { it.key == id }) {
                listState.scrollToItem((index - LastFocusSlot).coerceAtLeast(0))
            }
            pendingFocus = id
        }
        feed.returnTo = null
    }

    LaunchedEffect(pendingFocus, items) {
        val id = pendingFocus ?: return@LaunchedEffect
        withFrameNanos { } // let the row lay out, so the card is composed and focusable
        runCatching { requesters[id]?.requestFocus() }
        pendingFocus = null
    }

    if (items.isEmpty()) return

    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val cardWidth = tileWidth(maxWidth)
        val firstPositionPx = with(LocalDensity.current) { (EdgePadding + (cardWidth + TileSpacing) * FocusSlot).toPx() }
        val lastPositionPx = with(LocalDensity.current) { (EdgePadding + (cardWidth + TileSpacing) * LastFocusSlot).toPx() }
        val scrollSpec = remember(firstPositionPx, lastPositionPx) { scrollOutsideOf(firstPositionPx, lastPositionPx) }

        CompositionLocalProvider(LocalBringIntoViewSpec provides scrollSpec) {
            LazyRow(
                state = listState,
                modifier = Modifier
                    // Coming back down from the camera or the side panel lands on the card that was left
                    .focusRestorer()
                    .onPreviewKeyEvent { event ->
                        if (event.key != Key.Back && event.key != Key.Escape) return@onPreviewKeyEvent false
                        if (event.type == KeyEventType.KeyUp) onLeave()
                        true
                    },
                // The same height as the app tiles, so the rows take turns in one space
                contentPadding = PaddingValues(horizontal = EdgePadding, vertical = TileGrowRoom),
                horizontalArrangement = Arrangement.spacedBy(TileSpacing),
            ) {
                itemsIndexed(items, key = { _, alert -> alert.id }) { index, alert ->
                    val requester = requesters.getOrPut(alert.id) { FocusRequester() }
                    Card(
                        onClick = { feed.playing = alert },
                        modifier = Modifier
                            .width(cardWidth)
                            .aspectRatio(16f / 9f)
                            .then(if (index == 0) Modifier.focusRequester(firstCardFocus) else Modifier)
                            .focusRequester(requester)
                            .focusProperties {
                                up = upFocus(listState.slotOnScreen(alert.id))
                                down = downFocus
                                if (index == 0) left = FocusRequester.Cancel
                                if (index == items.lastIndex) right = FocusRequester.Cancel
                            }
                            .onFocusChanged { state ->
                                if (state.hasFocus) {
                                    focusedId = alert.id
                                    focusedIndex = index
                                    onEnter()
                                } else if (focusedId == alert.id) {
                                    focusedId = null
                                }
                            },
                    ) {
                        AlertTile(feed.client, alert, hasNoVideo = alert.id in feed.noVideo)
                    }
                }
            }
        }
    }
}

/** A button to a tab's settings in the middle of the tab, under what went wrong, if anything */
@Composable
fun SettingsPrompt(
    problem: String?,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier,
    buttonModifier: Modifier = Modifier,
) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (problem != null) {
                Text(
                    problem,
                    style = MaterialTheme.typography.bodyMedium,
                    color = ErrorColor,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(start = EdgePadding, end = EdgePadding, bottom = EdgePadding),
                )
            }
            Button(onClick = onClick, modifier = buttonModifier) { Text(label) }
        }
    }
}

@Composable
private fun AlertsMessage(text: String, modifier: Modifier, isError: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isError) ErrorColor else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = EdgePadding),
    )
}

@Composable
private fun AlertTile(client: FrigateClient, alert: FrigateAlert, hasNoVideo: Boolean) {
    val thumbnail by produceState<ImageBitmap?>(null, alert.id) { value = client.thumbnail(alert) }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        thumbnail?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
        // On a shade at the bottom, readable over any picture
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
                // Long text stops before the red X
                .padding(start = 10.dp, end = if (hasNoVideo) 36.dp else 10.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Text(
                listOf(alert.cameraName, alert.objects.joinToString(", ")).filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                alertTime(alert),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (hasNoVideo) NotLiveMark(Modifier.align(Alignment.BottomEnd).padding(6.dp))
    }
}

/** e.g. "14:32" today, "Oct 30, 14:32" before; in the TV's language and 12 or 24-hour clock */
@Composable
private fun alertTime(alert: FrigateAlert): String {
    val context = LocalContext.current
    val millis = (alert.startTime * 1000).toLong()
    val flags = DateUtils.FORMAT_SHOW_TIME or
        if (DateUtils.isToday(millis)) 0 else DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH
    val time = DateUtils.formatDateTime(context, millis, flags)
    return if (alert.endTime == null) "$time · going on" else time
}

/** An alert's clip, full screen, with its sound if it was recorded. OK pauses, left opens the newer alert and right the older one, Back closes. */
@OptIn(UnstableApi::class)
@Composable
private fun ClipPlayer(
    client: FrigateClient,
    alert: FrigateAlert,
    newer: FrigateAlert?,
    older: FrigateAlert?,
    minimizeStreams: Boolean,
    onOpen: (FrigateAlert) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    var status by remember { mutableStateOf<String?>("Loading clip…") }
    var ended by remember { mutableStateOf(false) }
    // Until the clip's first picture, black hides the last picture of the alert before
    var showingVideo by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    val frame = remember { AspectRatioFrameLayout(context).apply { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } }
    val video = remember {
        SurfaceView(context).also {
            frame.addView(it, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }
    val player = remember {
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(OkHttpDataSource.Factory(client.http)))
            .build()
            .apply {
                // Other apps' sound pauses meanwhile
                setAudioAttributes(
                    AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),
                    true,
                )
                setVideoSurfaceView(video)
            }
    }
    val playerWatch = remember(player) { PlayerWatch(player, "Clip player") }

    LaunchedEffect(player, alert.id) {
        AppLog.i("FrigateClip", "${alert.camera} ${alert.id}: opening, minimize streams ${if (minimizeStreams) "on" else "off"}")
        ended = false
        showingVideo = false
        // The cameras stop for the clip, but on older TVs their decoders are only free once their
        // players have finished releasing, which can take longer than Media3 waits
        if (minimizeStreams) playerWatch.awaitOthersGone { status = "Waiting for other decoders to release…" }
        status = "Loading clip…"
        player.setMediaItem(MediaItem.fromUri(client.clipUrl(alert)))
        player.playWhenReady = true
        player.prepare()
    }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                ended = state == Player.STATE_ENDED
                when (state) {
                    Player.STATE_READY -> status = null
                    Player.STATE_ENDED -> status = "Clip ended · OK plays it again"
                    else -> Unit
                }
            }

            override fun onRenderedFirstFrame() {
                showingVideo = true
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                if (size.width > 0 && size.height > 0) frame.setAspectRatio(size.width.toFloat() / size.height)
            }

            override fun onPlayerError(error: PlaybackException) {
                status = describeClipError(error)
                AppLog.w("FrigateClip", "${alert.camera} ${alert.id}: ${error.errorCodeName}", error)
            }
        }
        player.addListener(listener)
        onDispose { playerWatch.release() }
    }
    LifecycleStartEffect(player) { onStopOrDispose { player.pause() } }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        LaunchedEffect(Unit) { focus.requestFocus() }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .focusRequester(focus)
                .onPreviewKeyEvent { event ->
                    val down = event.type == KeyEventType.KeyDown
                    when (event.key) {
                        // On release, like Back
                        Key.Escape -> { if (!down) onDismiss(); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (!down) {
                                if (ended) player.seekTo(0)
                                player.playWhenReady = ended || !player.playWhenReady
                            }
                            true
                        }
                        Key.DirectionLeft -> { if (down) newer?.let(onOpen); true }
                        Key.DirectionRight -> { if (down) older?.let(onOpen); true }
                        else -> false
                    }
                }
                .focusable(),
            contentAlignment = Alignment.Center,
        ) {
            AndroidView(
                factory = {
                    frame.apply {
                        // Display only: the keys are handled above
                        isFocusable = false
                        descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
            if (!showingVideo) Box(Modifier.fillMaxSize().background(Color.Black))
            ClipLabel(
                "${alert.cameraName} · ${alertTime(alert)}",
                Modifier.align(Alignment.TopStart).padding(EdgePadding),
            )
            status?.let { ClipLabel(it) }
            if (newer != null) SwitchChevron("mdi:chevron-left", Modifier.align(Alignment.CenterStart))
            if (older != null) SwitchChevron("mdi:chevron-right", Modifier.align(Alignment.CenterEnd))
            if (ended) NotLiveMark(Modifier.align(Alignment.BottomEnd).padding(EdgePadding), size = 36.dp)
            if (showingVideo) ClipProgress(player, alert, Modifier.align(Alignment.BottomStart))
        }
    }
}

/** How far the clip has played, along the bottom of the screen */
@Composable
private fun ClipProgress(player: Player, alert: FrigateAlert, modifier: Modifier = Modifier) {
    val progress by produceState(0f, player, alert.id) {
        while (true) {
            value = clipProgress(player, alert)
            delay(CLIP_PROGRESS_INTERVAL_MS)
        }
    }
    Box(modifier.fillMaxWidth().height(CLIP_PROGRESS_HEIGHT).background(Color.White.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface))
    }
}

/**
 * 0 to 1. Frigate streams its clips as they're made, so the player usually can't know how long they
 * are: then it's how long the alert lasted, up to now if it's still going on.
 */
private fun clipProgress(player: Player, alert: FrigateAlert): Float {
    if (player.playbackState == Player.STATE_ENDED) return 1f
    val length = player.duration.takeIf { it != C.TIME_UNSET && it > 0 }
        ?: (((alert.endTime ?: (System.currentTimeMillis() / 1000.0)) - alert.startTime) * 1000).toLong()
    return if (length > 0) (player.currentPosition.toFloat() / length).coerceIn(0f, 1f) else 0f
}

/** A chip over the video, readable over any picture */
@Composable
private fun ClipLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@OptIn(UnstableApi::class)
private fun describeClipError(error: PlaybackException): String {
    val http = generateSequence(error.cause) { it.cause }.filterIsInstance<HttpDataSource.InvalidResponseCodeException>().firstOrNull()
    return when {
        http?.responseCode == 400 || http?.responseCode == 404 -> "Frigate has no recording of this alert"
        http != null -> "Frigate answered HTTP ${http.responseCode}"
        else -> "Can't play this clip: ${error.errorCodeName}"
    }
}
