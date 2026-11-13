package com.evofactory.homeboard

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val RETRY_DELAY_MS = 5_000L

private val SensorFadeHeight = 40.dp

/** At most this many actions are also buttons in the top bar; all are on the Assistant tab */
const val MAX_HA_ACTIONS = 3

enum class HaStatus { Connecting, Connected, Offline, TokenRejected }

/** One connection to Home Assistant, shared by the panel's sensors and the top bar's actions */
class HaConnection {
    // Last known states stay on screen while reconnecting
    val entities = mutableStateMapOf<String, HaEntity>()

    // The dashboards shown (the Assistant tab's and the Home tab's), by key, once read; a key in
    // [missingDashboards] when Home Assistant builds that dashboard itself, with nothing to read
    private val dashboards = mutableStateMapOf<String, HaDashboard>()
    private val missingDashboards = mutableStateMapOf<String, Unit>()

    /** The dashboard [key], once read; null for none, while it isn't read, or when it's [dashboardMissing] */
    fun dashboard(key: String?): HaDashboard? = key?.let { dashboards[it] }

    /** Whether Home Assistant builds the dashboard [key] itself: there's nothing to read */
    fun dashboardMissing(key: String?): Boolean = key != null && key in missingDashboards

    internal fun setDashboard(key: String, board: HaDashboard?) {
        if (board == null) {
            dashboards.remove(key)
            missingDashboards[key] = Unit
        } else {
            dashboards[key] = board
            missingDashboards.remove(key)
        }
    }

    // Forgets dashboards no longer shown
    internal fun keepDashboards(keys: List<String>) {
        dashboards.keys.retainAll(keys.toSet())
        missingDashboards.keys.retainAll(keys.toSet())
    }

    // Weather forecasts by entity id
    val hourly = mutableStateMapOf<String, List<HaForecast>>()
    val daily = mutableStateMapOf<String, List<HaForecast>>()
    var session by mutableStateOf<HaSession?>(null)
        internal set

    // The scene, script or button running: one at a time, from the top bar or the Assistant tab
    var runningAction by mutableStateOf<String?>(null)
        private set

    /** Switches [id] on or off, if Home Assistant is connected; a failure shows as a toast. Its new state comes back with its updates. */
    fun toggle(id: String, scope: CoroutineScope, context: Context) {
        val session = session ?: return notConnected(context)
        scope.launch {
            try {
                session.toggle(id)
            } catch (e: HaException) {
                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Sets a select to [option], if Home Assistant is connected; a failure shows as a toast. Its new state comes back with its updates. */
    fun select(id: String, option: String, scope: CoroutineScope, context: Context) {
        val session = session ?: return notConnected(context)
        scope.launch {
            try {
                session.selectOption(id, option)
            } catch (e: HaException) {
                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Runs a scene or script or presses a button, unless one is running or Home Assistant isn't connected; a failure shows as a toast */
    fun runAction(id: String, scope: CoroutineScope, context: Context) {
        val session = session ?: return notConnected(context)
        if (runningAction != null) return
        runningAction = id
        scope.launch {
            try {
                session.run(id)
            } catch (e: HaException) {
                Toast.makeText(context, e.message, Toast.LENGTH_LONG).show()
            } finally {
                runningAction = null
            }
        }
    }
    var status by mutableStateOf(HaStatus.Connecting)
        internal set

    /** Says why OK did nothing: what's on screen is only the last known state */
    private fun notConnected(context: Context) {
        val why = problem?.let { ": $it" } ?: if (status == HaStatus.Connecting) ": still connecting" else ""
        Toast.makeText(context, "Not connected to Home Assistant$why", Toast.LENGTH_LONG).show()
    }

    // For Network status
    var connectedAt by mutableStateOf<Long?>(null)
        internal set
    var problem by mutableStateOf<String?>(null)
        internal set
    var failedConnections by mutableIntStateOf(0)
        internal set
}

/**
 * Follows [entityIds] on [server]. Connected only while the launcher is on screen; reconnects on
 * its own, except when the token is rejected: then it waits for a new login or an app restart, so
 * a wrong login doesn't hammer Home Assistant. It also reads the [dashboards] (their keys, see
 * [HaDashboardInfo.key]) and follows their entities, and reconnects at once when one is saved in
 * Home Assistant, to show the new one.
 */
@Composable
fun rememberHaConnection(server: HaServer, entityIds: List<String>, dashboards: List<String>): HaConnection {
    val connection = remember(server) { HaConnection() }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    LaunchedEffect(server, entityIds, dashboards) {
        connection.keepDashboards(dashboards)
        if (!server.isSet || (entityIds.isEmpty() && dashboards.isEmpty())) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            if (connection.status == HaStatus.TokenRejected) return@repeatOnLifecycle
            while (true) {
                // Set when the dashboard was saved: reconnect at once rather than after a wait
                var reload = false
                try {
                    haSession(server) {
                        val saved = CompletableDeferred<Unit>()
                        // Followed first, so a save while reading isn't missed
                        if (dashboards.isNotEmpty()) followDashboardEdits(dashboards.map(::dashboardUrlPath).toSet()) { saved.complete(Unit) }
                        val boards = dashboards.map { key -> parseDashboard(dashboardConfig(dashboardUrlPath(key))) }
                        dashboards.forEachIndexed { index, key -> connection.setDashboard(key, boards[index]) }
                        val followed = (entityIds + boards.flatMap { it?.entityIds.orEmpty() }).distinct()
                        followEntities(followed, connection.entities)
                        for (id in followed.filter(::isHaWeather)) {
                            followForecast(id, "hourly") { connection.hourly[id] = it }
                            followForecast(id, "daily") { connection.daily[id] = it }
                        }
                        connection.session = this
                        connection.status = HaStatus.Connected
                        connection.connectedAt = System.currentTimeMillis()
                        connection.problem = null
                        connection.failedConnections = 0
                        try {
                            saved.await()
                            reload = true
                        } finally {
                            connection.session = null
                            connection.connectedAt = null
                        }
                    }
                } catch (e: HaException) {
                    connection.problem = e.message
                    connection.failedConnections++
                    if (e.tokenRejected) {
                        connection.status = HaStatus.TokenRejected
                        return@repeatOnLifecycle
                    }
                    connection.status = HaStatus.Offline
                }
                if (!reload) delay(RETRY_DELAY_MS)
            }
        }
    }
    return connection
}

/**
 * Home Assistant tiles: the weather, then sensor readings in the Assistant tab's cards, one after
 * the other. OK on a tile [onOpen]s that tab, which has room for them all. [sensorsFocus] selects
 * the sensors; up from the top tile goes to [upFocus]. A rejected login shows a button to
 * [onLogIn] instead.
 */
@Composable
fun HomeAssistantPanel(
    connection: HaConnection,
    entityIds: List<String>,
    sensorsFocus: FocusRequester,
    upFocus: FocusRequester,
    onLogIn: () -> Unit,
    onOpen: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (connection.status == HaStatus.TokenRejected) {
        SettingsPrompt(
            "Home Assistant rejected the login",
            "Log in again",
            onLogIn,
            modifier,
            buttonModifier = Modifier.focusRequester(sensorsFocus).focusProperties { up = upFocus },
        )
        return
    }
    val entities = connection.entities
    // The weather has its own tiles, above
    val cards = sensorCards(entityIds.filterNot(::isHaWeather), entities)
    val weatherIds = entityIds.filter(::isHaWeather)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        weatherIds.forEachIndexed { index, id ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .selectableTile(if (index == 0) upFocus else null, onOpen)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                WeatherReading(entities[id], connection.hourly[id], connection.daily[id])
            }
        }
        // Takes all the height left, however many sensors there are; those that don't fit fade out
        val cardColor = MaterialTheme.colorScheme.surfaceVariant
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .focusRequester(sensorsFocus)
                .selectableTile(if (weatherIds.isEmpty()) upFocus else null, onOpen)
                .clip(RoundedCornerShape(12.dp))
                .background(cardColor),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    // At full height, so the last rows are cut by the card rather than squeezed
                    .wrapContentHeight(Alignment.Top, unbounded = true)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                // Compact: the weather and sensors share the height of the camera
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                cards.forEachIndexed { index, card ->
                    // A single card needs no title
                    if (cards.size > 1) {
                        Text(
                            card.title,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = if (index > 0) 4.dp else 0.dp),
                        )
                    }
                    card.ids.forEach { id -> SensorReading(id, entities[id]) }
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(SensorFadeHeight)
                    .background(Brush.verticalGradient(listOf(cardColor.copy(alpha = 0f), cardColor))),
            )
        }
        haStatusMessage(connection)?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** While not connected: connecting the first time, or offline. Null once connected. */
fun haStatusMessage(connection: HaConnection): String? = when (connection.status) {
    HaStatus.Connecting -> if (connection.entities.isEmpty()) "Connecting to Home Assistant…" else null
    HaStatus.Offline -> "Home Assistant offline · retrying"
    HaStatus.TokenRejected, HaStatus.Connected -> null
}

/**
 * The remote can land on it and it shows; [up] overrides where up goes from here. OK calls
 * [onClick], on release like elsewhere, if given.
 */
@Composable
fun Modifier.selectableTile(up: FocusRequester?, onClick: (() -> Unit)? = null): Modifier {
    var focused by remember { mutableStateOf(false) }
    val border = MaterialTheme.colorScheme.border
    return this
        .onFocusChanged { focused = it.isFocused }
        .focusProperties { if (up != null) this.up = up }
        .then(
            if (onClick == null) {
                Modifier
            } else {
                Modifier.onPreviewKeyEvent { event ->
                    when (event.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (event.type == KeyEventType.KeyUp) onClick()
                            true
                        }
                        else -> false
                    }
                }
            },
        )
        .focusable()
        .then(if (focused) Modifier.border(3.dp, border, RoundedCornerShape(12.dp)) else Modifier)
}

/**
 * Action buttons (scenes, scripts and buttons) for the top bar: round, as large as the cog, showing only
 * the icon. The name shows underneath while the remote is on one, in the margin below the bar.
 * Greyed out until connected, and while one runs; a failure shows as a toast.
 */
@Composable
fun HaActionButtons(connection: HaConnection, actionIds: List<String>) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val ready = connection.session != null && connection.runningAction == null
    actionIds.forEach { id ->
        key(id) {
            val entity = connection.entities[id]
            val name = entity?.name ?: id
            val domainIcon = defaultHaIcon(id.substringBefore('.'), null, "")
            var focused by remember { mutableStateOf(false) }
            Box(Modifier.size(SettingsButtonSize)) {
                Surface(
                    onClick = { connection.runAction(id, scope, context) },
                    modifier = Modifier
                        .fillMaxSize()
                        .onFocusChanged { focused = it.isFocused }
                        .semantics { contentDescription = name },
                    shape = ClickableSurfaceDefaults.shape(CircleShape),
                    colors = ClickableSurfaceDefaults.colors(
                        containerColor = colors.surfaceVariant,
                        contentColor = if (ready) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.5f),
                        focusedContainerColor = colors.onSurface,
                        focusedContentColor = colors.inverseOnSurface,
                    ),
                ) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        MdiIcon(entity?.icon ?: domainIcon, fallback = domainIcon, color = LocalContentColor.current)
                    }
                }
                if (focused) TopBarButtonLabel(name)
            }
        }
    }
}

/** A sensor's icon (in [iconColor], e.g. its line's in a graph), name (or the [name] given to it) and reading, on one line */
@Composable
fun SensorReading(id: String, entity: HaEntity?, name: String? = null, iconColor: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MdiIcon(entity?.icon ?: "", color = iconColor)
        Text(
            name ?: entity?.name ?: id,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        val reading = entity?.reading ?: "—"
        // No unit in a reading that's only a dash
        val unit = entity?.unit?.takeIf { reading.endsWith(" $it") }.orEmpty()
        Text(
            buildAnnotatedString {
                append(reading.removeSuffix(unit))
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Normal)) {
                    append(unit)
                }
            },
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
        )
    }
}

/**
 * Before Home Assistant is set up: the panel's final layout, empty, in the cards' grey (an empty
 * weather card, then a card offering to set it up), so the Home tab already looks as it will
 */
@Composable
fun HomeAssistantSetUpOutline(buttonFocus: FocusRequester, upFocus: FocusRequester, onSetUp: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(96.dp)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)),
        )
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            MdiIcon("mdi:home-assistant", size = 40.dp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Your sensors, switches and weather",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp, bottom = 12.dp),
            )
            Button(onClick = onSetUp, modifier = Modifier.focusRequester(buttonFocus).focusProperties { up = upFocus }) {
                Text("Set up Home Assistant")
            }
        }
    }
}
