package com.evofactory.homeboard

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.round
import kotlin.math.roundToInt

/**
 * A card of sensors and actions: a room's, those of one type that have no room, or a block of a
 * dashboard. [key] tells cards apart. [subtitles] head parts of the card, by the index in [ids]
 * of the part's first entity. [gauges] are the entities shown with a bar, and [graphs] the history
 * graphs among [ids].
 */
class SensorCard(
    val key: String,
    val title: String,
    val ids: List<String>,
    val subtitles: Map<Int, String> = emptyMap(),
    // A dashboard's entities shown as a gauge
    val gauges: Map<String, HaGauge> = emptyMap(),
    // A dashboard's history graphs, by their key in [ids] (see isGraphKey)
    val graphs: Map<String, HaGraph> = emptyMap(),
) {
    /** A weather alone: shown as a weather card, with its forecast */
    val isWeather get() = ids.size == 1 && isHaWeather(ids.first()) && subtitles.isEmpty()
}

/**
 * Everything chosen as cards, grouped like Home Assistant's default dashboard: one per room, and
 * for what has no room, one per type (sensors, binary sensors, scenes…). Each weather is a card
 * of its own. Cards come in the order of their first entity in [ids], and entities in their order
 * there, so one list of ids holds both orders. Rooms are known once connected: until then, an
 * entity is under its type.
 */
fun sensorCards(ids: List<String>, entities: Map<String, HaEntity>): List<SensorCard> {
    val anyRoom = ids.any { !isHaWeather(it) && entities[it]?.area != null }
    return ids
        .groupBy { id ->
            when {
                isHaWeather(id) -> "weather:$id"
                else -> entities[id]?.area?.let { "room:$it" } ?: "type:${haEntityType(id)}"
            }
        }
        .map { (key, cardIds) ->
            val first = cardIds.first()
            val title = when {
                isHaWeather(first) -> entities[first]?.name ?: "Weather"
                else -> entities[first]?.area ?: sensorTypeTitle(haEntityType(first), anyRoom)
            }
            SensorCard(key, title, cardIds)
        }
}

/** Title of the entities of a [domain] without a room: "Other …" when some have one */
fun sensorTypeTitle(domain: String, anyRoom: Boolean): String {
    val type = when (domain) {
        "sensor" -> "sensors"
        "binary_sensor" -> "binary sensors"
        "scene" -> "scenes"
        "script" -> "scripts"
        "button" -> "buttons"
        else -> domain
    }
    return if (anyRoom) "Other $type" else type.replaceFirstChar { it.uppercase() }
}

/** [ids] with the card [key] moved [by] cards (-1 up, 1 down) */
fun moveSensorCard(ids: List<String>, entities: Map<String, HaEntity>, key: String, by: Int): List<String> {
    val cards = sensorCards(ids, entities).toMutableList()
    val from = cards.indexOfFirst { it.key == key }
    if (from < 0 || from + by !in cards.indices) return ids
    cards.add(from + by, cards.removeAt(from))
    return cards.flatMap { it.ids }
}

/** [ids] with [id] moved [by] places within its card, by swapping with its neighbour */
fun moveSensor(ids: List<String>, entities: Map<String, HaEntity>, id: String, by: Int): List<String> {
    val group = sensorCards(ids, entities).firstOrNull { id in it.ids }?.ids
    val other = group?.getOrNull(group.indexOf(id) + by) ?: return ids
    // Swapped in place: the card's first sensor stays where it was, so the cards keep their order
    return ids.map {
        when (it) {
            id -> other
            other -> id
            else -> it
        }
    }
}

// The cards show this far into the margins above and below them, half of each, where they fade out
private val CardPeek = EdgePadding / 2

// The fade where cards are cut off: at the top only in the margin, so the cards show whole down to
// their usual edge; at the bottom the peek and as much again over the cards
private val CardFadeTop = CardPeek
private val CardFadeBottom = CardPeek * 2

/**
 * Where the cards show: [scroll]'s viewport, [height] pixels tall, with fades [fadeTop] and
 * [fadeBottom] at its edges, and [top] pixels above the first card
 */
private class CardWindow(val scroll: ScrollState, val height: Int, val fadeTop: Int, val fadeBottom: Int, val top: Int)

// Narrower and the names get cut: about 3 columns on a TV
private val MinCardWidth = 280.dp

/**
 * The Assistant tab: everything chosen ([entityIds]: weather, sensors, scenes, scripts and buttons), in
 * [sensorCards], with the actions as buttons among the sensors. The cards are spread over as many
 * columns as fit, each going to the shortest column. The remote can land on the cards, to scroll
 * when they don't all fit; where they're cut off, they show a little into the margins, fading
 * out (see [CardPeek]). Up from the top ones goes to
 * [tabFocus]. The remote moves from card to card: OK on one with buttons goes in, to move between
 * them, and Back comes out. [onSetUp] opens the Home Assistant settings: to set it up, log in
 * again or choose sensors.
 *
 * With a [dashboard] chosen (its key), the cards are that dashboard's instead, as the connection
 * read it, with a row of its views above them if it has several. A dashboard Home Assistant
 * builds itself has nothing to read: the chosen entities show instead.
 *
 * With [sectionColumns], each card has a column of its own instead (see [SectionColumns]).
 */
@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
fun HaAssistantTab(
    connection: HaConnection,
    homeAssistantSet: Boolean,
    entityIds: List<String>,
    dashboard: String?,
    tabFocus: FocusRequester,
    onSetUp: () -> Unit,
    modifier: Modifier = Modifier,
    // Each card (a dashboard's section, or a room) in a column of its own, scrolling sideways
    sectionColumns: Boolean = false,
    // Nothing below the tab: the cards go to the screen's edge, cut by it rather than fading out
    toScreenEdge: Boolean = false,
    // From the sides to the cards: the screen's margin on the tab, none in the Home tab's panel
    inset: Dp = EdgePadding,
    // Before the columns, scrolling with them, e.g. the Home tab's camera (with [sectionColumns])
    leading: LeadingItem? = null,
    // Selects the cards, as a group: up from the tiles on the Home tab
    columnsFocus: FocusRequester? = null,
) {
    val board = connection.dashboard(dashboard)
    val missing = connection.dashboardMissing(dashboard)
    val reading = dashboard != null && board == null && !missing
    val prompt = when {
        !homeAssistantSet -> null to "Set up Home Assistant"
        connection.status == HaStatus.TokenRejected -> "Home Assistant rejected the login" to "Log in again"
        board == null && !reading && entityIds.isEmpty() ->
            (if (missing) "This dashboard is built by Home Assistant itself, with nothing to read" else null) to "Choose sensors"
        else -> null
    }
    if (prompt != null) {
        SettingsPrompt(prompt.first, prompt.second, onSetUp, modifier)
        return
    }
    if (reading) {
        Text(
            haStatusMessage(connection) ?: "Reading the dashboard…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(horizontal = inset),
        )
        return
    }
    val entities = connection.entities
    var viewIndex by remember(board) { mutableIntStateOf(0) }
    // With something before the columns (the Home tab's camera), every view's cards follow each
    // other, without the views' names above them
    val views = board?.views.orEmpty().let { all ->
        if (leading != null && all.size > 1) listOf(DashboardView(all.first().title, all.flatMap { it.cards })) else all
    }
    val viewTabs = remember(views.size) { List(views.size) { FocusRequester() } }
    val cards = if (board != null) views.getOrNull(viewIndex)?.cards.orEmpty() else sensorCards(entityIds, entities)
    val names = board?.names.orEmpty()
    // Up from the top cards: to the views, if there are several, else to the tab
    val cardsUp = viewTabs.getOrNull(viewIndex)?.takeIf { views.size > 1 } ?: tabFocus
    // Roughly in rows of sensors: a weather takes about 5, with its next hours, and a heading 1
    val heights = cards.map { card ->
        if (card.isWeather) 5 else card.ids.size + 1 + card.subtitles.size + card.gauges.size + 4 * card.ids.count(::isHaWeather) +
            card.graphs.values.sumOf { 2 + it.entities.size }
    }
    val scroll = rememberScrollState()
    LaunchedEffect(viewIndex) { scroll.scrollTo(0) }
    // The card whose buttons the remote moves between, after OK on it; Back leaves it
    var entered by remember { mutableStateOf<String?>(null) }
    val message = haStatusMessage(connection)
        ?: "This dashboard is built by Home Assistant itself: your selection shows instead".takeIf { missing }
        ?: "Nothing on this dashboard can be shown here".takeIf { board != null && views.isEmpty() }
    val density = LocalDensity.current
    Column(
        modifier = modifier
            // Down from the top bar lands on the views, which are to one side, rather than on a card
            .focusProperties {
                onEnter = {
                    if (views.size > 1 && requestedFocusDirection == FocusDirection.Down) {
                        viewTabs.getOrNull(viewIndex)?.requestFocus()
                    }
                }
            }
            .focusGroup(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (views.size > 1) {
            SubTabRow(
                titles = views.map { it.title },
                selected = viewIndex,
                onSelect = { viewIndex = it },
                inset = inset,
                // 16 in all to the cards, with the column's 8
                below = 8.dp,
                itemModifier = { index -> Modifier.focusRequester(viewTabs[index]).focusProperties { up = tabFocus } },
            )
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // Into the margin below, unless over the status line or at the screen's edge already
            val peekBelow = if (message == null && !toScreenEdge) CardPeek else 0.dp
            val bottom = CardsBottom(
                peek = peekBelow,
                // Below the last card once scrolled to the end
                end = if (toScreenEdge) EdgePadding else peekBelow,
                fade = !toScreenEdge,
                // Kept clear below a selected card: the fade, or a margin from the screen's edge
                clearance = if (toScreenEdge) EdgePadding else CardFadeBottom,
            )
            val window = with(density) {
                CardWindow(scroll, constraints.maxHeight + (CardPeek + peekBelow).roundToPx(), CardFadeTop.roundToPx(), bottom.clearance.roundToPx(), CardPeek.roundToPx())
            }
            val width = maxWidth - inset * 2
            val columnCount = ((width + PanelItemSpacing) / (MinCardWidth + PanelItemSpacing)).toInt().coerceAtLeast(1)
            // Each card to the shortest column so far, the leftmost if equal
            val columns = List(columnCount) { mutableListOf<Int>() }
            val columnHeights = IntArray(columnCount)
            heights.forEachIndexed { index, height ->
                val column = columnHeights.indices.minBy { columnHeights[it] }
                columns[column] += index
                columnHeights[column] += height
            }
            // One card, selected as a whole, with its buttons once gone into
            @Composable
            fun Card(card: SensorCard, up: FocusRequester?, window: CardWindow?, minHeight: Dp? = null, maxHeight: Dp? = null) {
                // Selection's weather cards have the weather's name as title, which its reading says already
                val weather = card.ids.first().takeIf { card.isWeather && board == null }
                AssistantCard(
                    up = up,
                    window = window,
                    buttons = card.ids.count(::isInteractive),
                    entered = entered == card.key,
                    onEnter = { entered = card.key },
                    onLeave = { entered = null },
                    minHeight = minHeight,
                    maxHeight = maxHeight,
                ) { raised, firstButton, onButtonFocused ->
                    if (weather != null) {
                        WeatherReading(entities[weather], connection.hourly[weather], connection.daily[weather])
                    } else {
                        CardContent(card, connection, names, entered == card.key, raised, firstButton, onButtonFocused)
                    }
                }
            }
            if (sectionColumns) {
                SectionColumns(cards, viewIndex, bottom, inset, cardsUp, leading, columnsFocus) { card, up, columnWindow, minHeight, maxHeight ->
                    Card(card, up, columnWindow, minHeight, maxHeight)
                }
                return@BoxWithConstraints
            }
            // Scrolls only as much as a selected card needs, in whole pixels: TVs otherwise keep it at
            // a fixed height on screen, which moved everything by a pixel back and forth with
            // each update of a card (Kitchen TV, 2026-11-04)
            // Room on the sides for a card gone into, which is a little larger, when there's no inset
            val sideRoom = if (inset > 0.dp) 0.dp else PanelItemSpacing / 2
            CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
                Row(
                    modifier = Modifier
                        .extendVertically(CardPeek, peekBelow)
                        .extendHorizontally(sideRoom)
                        .fillMaxSize()
                        .fadingEdges(scroll, bottom = bottom.fade)
                        .verticalScroll(scroll)
                        // Where the cards start and end once scrolled to either end: the usual margins
                        .padding(start = inset + sideRoom, end = inset + sideRoom, top = CardPeek, bottom = bottom.end),
                    horizontalArrangement = Arrangement.spacedBy(PanelItemSpacing),
                ) {
                    columns.forEach { indices ->
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(PanelItemSpacing)) {
                            indices.forEachIndexed { position, index ->
                                val card = cards[index]
                                key(card.key) { Card(card, if (position == 0) cardsUp else null, window) }
                            }
                        }
                    }
                }
            }
        }
        message?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = inset),
            )
        }
    }
}

/**
 * The cards one per column, as many columns as there are cards, scrolling sideways: as many as
 * fit show whole (three on the tab) with a tenth of the next at the right edge, and once
 * scrolled, a little of the columns before and after at each edge (a twentieth on the tab), until
 * the last column, which ends at the [inset]. Each column scrolls up and down on its own. [card]
 * draws a card, given where up goes from it and its column's window.
 */
@OptIn(ExperimentalFoundationApi::class) // LocalBringIntoViewSpec
@Composable
private fun BoxWithConstraintsScope.SectionColumns(
    cards: List<SensorCard>,
    viewIndex: Int,
    bottom: CardsBottom,
    inset: Dp,
    up: FocusRequester,
    leading: LeadingItem?,
    columnsFocus: FocusRequester?,
    card: @Composable (SensorCard, FocusRequester?, CardWindow?, Dp?, Dp?) -> Unit,
) {
    val density = LocalDensity.current
    val viewHeight = constraints.maxHeight
    // On the tab, a gap as wide as the margin: then a tenth of the fourth column shows at first,
    // and a twentieth of each neighbour once scrolled, with the same column width
    val gap = if (inset > 0.dp) inset else PanelItemSpacing
    // What comes before the columns, with its gap
    val lead = leading?.let { it.width + gap } ?: 0.dp
    // On the tab, as many as fit with a tenth of the next at the edge. Beside what comes first,
    // one column, with only the next one's edge showing: its rounded corners and padding, none of
    // what's inside it. Alone, it ends at the inset.
    val shown = if (leading != null) 1 else ((maxWidth - inset + gap) / (MinCardWidth + gap)).toInt().coerceAtLeast(1)
    val columnWidth = when {
        leading == null -> (maxWidth - inset - lead - gap * shown) / (shown + FirstPeek)
        cards.size > 1 -> maxWidth - inset - lead - gap - CardSidePadding
        else -> maxWidth - inset * 2 - lead
    }
    // What shows of each neighbour once scrolled: what's left, shared between the two edges
    val sidePeek = ((maxWidth - columnWidth * shown - gap * (shown + 1)) / 2).coerceAtLeast(0.dp)
    val sideways = rememberScrollState()
    // The column on the left of those shown whole, and the one selected
    var first by remember(viewIndex) { mutableIntStateOf(0) }
    var selected by remember(viewIndex) { mutableStateOf<Int?>(null) }
    LaunchedEffect(viewIndex) { sideways.scrollTo(0) }
    LaunchedEffect(selected) {
        val index = selected ?: return@LaunchedEffect
        if (leading != null) {
            // Only as far as the column and a twentieth of each neighbour need; back to the start
            // beside what comes first
            val target = with(density) {
                if (index <= 0) {
                    0
                } else {
                    val peek = CardSidePadding
                    val left = inset + lead + (columnWidth + gap) * index
                    val right = left + columnWidth
                    val end = right + if (index < cards.size - 1) gap + peek else inset
                    sideways.value.coerceIn((end - maxWidth).roundToPx(), (left - gap - peek).roundToPx())
                }
            }
            sideways.animateScrollTo(target.coerceIn(0, sideways.maxValue))
            return@LaunchedEffect
        }
        first = when {
            index < first -> index
            index > first + shown - 1 -> index - (shown - 1)
            else -> first
        }
        // Column [first] starts after a little of the one before and a gap; the first one at the inset
        val target = with(density) {
            if (first == 0) 0 else (inset + (columnWidth + gap) * first - gap - sidePeek).roundToPx()
        }
        sideways.animateScrollTo(target.coerceIn(0, sideways.maxValue))
    }
    // Sideways, only [selected] scrolls; up and down, each column keeps its selected card in view
    // Room on each side of a column for its card gone into, which is a little larger: half the
    // gap, and as much at the ends when there's no inset
    val sideRoom = gap / 2
    val endRoom = if (inset > 0.dp) 0.dp else sideRoom
    CompositionLocalProvider(LocalBringIntoViewSpec provides NoScroll) {
        Row(
            modifier = Modifier
                .extendHorizontally(endRoom)
                .fillMaxSize()
                .horizontalScroll(sideways)
                .padding(horizontal = inset + endRoom),
            horizontalArrangement = Arrangement.spacedBy(gap),
        ) {
            if (leading != null) {
                Box(Modifier.width(leading.width).onFocusChanged { if (it.hasFocus) selected = -1 }) { leading.content() }
            }
            // The columns, as a group the remote can be sent to
            Row(
                modifier = Modifier.then(if (columnsFocus != null) Modifier.focusRequester(columnsFocus) else Modifier).focusGroup(),
                horizontalArrangement = Arrangement.spacedBy(gap),
            ) {
            cards.forEachIndexed { index, item ->
                // Beside the camera: as tall as it, what doesn't fit scrolling inside the card
                if (leading != null) {
                    key(item.key) {
                        Box(
                            Modifier
                                .width(columnWidth)
                                .extendHorizontally(sideRoom)
                                .padding(horizontal = sideRoom)
                                .onFocusChanged { if (it.hasFocus) selected = index },
                        ) {
                            card(item, up, null, leading.height, leading.height)
                        }
                    }
                    return@forEachIndexed
                }
                key(item.key) {
                    val scroll = rememberScrollState()
                    val window = with(density) {
                        CardWindow(scroll, viewHeight + (CardPeek + bottom.peek).roundToPx(), CardFadeTop.roundToPx(), bottom.clearance.roundToPx(), CardPeek.roundToPx())
                    }
                    // At least the column's whole height, down to where the cards end once scrolled
                    val fullHeight = with(density) { viewHeight.toDp() } + bottom.peek - bottom.end
                    CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
                        Column(
                            Modifier
                                .width(columnWidth)
                                .extendHorizontally(sideRoom)
                                .extendVertically(CardPeek, bottom.peek)
                                .fillMaxHeight()
                                .onFocusChanged { if (it.hasFocus) selected = index }
                                .fadingEdges(scroll, bottom = bottom.fade)
                                .verticalScroll(scroll)
                                .padding(start = sideRoom, end = sideRoom, top = CardPeek, bottom = bottom.end),
                        ) {
                            card(item, up, window, fullHeight, null)
                        }
                    }
                }
            }
            }
        }
    }
}

/**
 * What comes before the columns and scrolls with them, e.g. the Home tab's camera, [width] wide
 * and [height] high: the cards next to it are no higher
 */
class LeadingItem(val width: Dp, val height: Dp, val content: @Composable () -> Unit)

// The share of the next column showing at first
private const val FirstPeek = 0.1f

// Between a card's edge and what's inside it
private val CardSidePadding = 16.dp

/** Never scrolls to bring something into view: for a scroll that's moved by hand */
@OptIn(ExperimentalFoundationApi::class)
private val NoScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float) = 0f
}

/**
 * Scrolls a selected card into view only when it isn't, and only as far as needed, in whole pixels
 * (Android's own behaviour on phones)
 */
@OptIn(ExperimentalFoundationApi::class)
private val MinimalScroll = object : BringIntoViewSpec {
    override fun calculateScrollDistance(offset: Float, size: Float, containerSize: Float): Float {
        val end = offset + size
        val distance = when {
            // In view already, or larger than the view and filling it
            offset >= 0 && end <= containerSize -> 0f
            offset < 0 && end > containerSize -> 0f
            abs(offset) < abs(end - containerSize) -> offset
            else -> end - containerSize
        }
        return round(distance)
    }
}

/** Measured [by] wider on each side than given, over what's next to it, without moving what's around */
private fun Modifier.extendHorizontally(by: Dp) = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.minWidth + 2 * extra, maxWidth = if (constraints.hasBoundedWidth) constraints.maxWidth + 2 * extra else constraints.maxWidth),
    )
    layout(placeable.width - 2 * extra, placeable.height) { placeable.place(-extra, 0) }
}

/** Measured [top] and [bottom] taller than given, over what's above and below it, without moving what's around */
private fun Modifier.extendVertically(top: Dp, bottom: Dp) = layout { measurable, constraints ->
    val above = top.roundToPx()
    val extra = above + bottom.roundToPx()
    val placeable = measurable.measure(constraints.copy(minHeight = constraints.minHeight + extra, maxHeight = constraints.maxHeight + extra))
    layout(placeable.width, constraints.maxHeight) { placeable.place(0, -above) }
}

/**
 * How the cards end at the bottom: how far they show into the margin below ([peek]), the space
 * under the last one once scrolled to the end, whether they [fade] out, and the room kept clear
 * below a selected card
 */
private class CardsBottom(val peek: Dp, val end: Dp, val fade: Boolean, val clearance: Dp)

/** Fades out at the top, and at the [bottom] if asked, where [scroll] has more: to transparent, so what's behind shows */
private fun Modifier.fadingEdges(scroll: ScrollState, bottom: Boolean = true) = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        if (scroll.value > 0) {
            val fade = CardFadeTop.toPx()
            drawRect(
                Brush.verticalGradient(listOf(Color.Transparent, Color.Black), startY = 0f, endY = fade),
                size = Size(size.width, fade),
                blendMode = BlendMode.DstIn,
            )
        }
        if (bottom && scroll.value < scroll.maxValue) {
            val fade = CardFadeBottom.toPx()
            drawRect(
                Brush.verticalGradient(listOf(Color.Black, Color.Transparent), startY = size.height - fade, endY = size.height),
                topLeft = Offset(0f, size.height - fade),
                size = Size(size.width, fade),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * For a card taller than the screen, which the remote would otherwise leave before its end is
 * seen: up and down scroll through it, half a screen at a time, before going to the next card,
 * until it's clear of the [window]'s fades. The card's column must be at the top of the cards.
 * Not while [active] is false, e.g. inside the card, where the buttons scroll into view.
 */
@Composable
private fun Modifier.scrollsThrough(window: CardWindow, active: Boolean): Modifier {
    val scope = rememberCoroutineScope()
    var top by remember { mutableIntStateOf(0) }
    var bottom by remember { mutableIntStateOf(0) }
    val scroll = window.scroll
    return this
        .onPlaced {
            top = window.top + it.positionInParent().y.roundToInt()
            bottom = top + it.size.height
        }
        .onPreviewKeyEvent { event ->
            // Hidden that way, or under the fade; no further than it can scroll
            val step = when (event.key) {
                Key.DirectionDown -> minOf(bottom - (scroll.value + window.height - window.fadeBottom), window.height / 2, scroll.maxValue - scroll.value)
                Key.DirectionUp -> -minOf(scroll.value + window.fadeTop - top, window.height / 2, scroll.value)
                else -> 0
            }
            // Nothing hidden that way: the remote moves on
            if (step == 0 || !active || (event.key == Key.DirectionDown) != (step > 0)) return@onPreviewKeyEvent false
            if (event.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(step.toFloat()) }
            true
        }
}

/**
 * A card on the Assistant tab, which the remote selects as a whole, outlined; [up] is where up
 * goes from it. If it has [buttons], OK [onEnter]s it: it's then raised and a little larger, the
 * remote moves between its buttons, from the first one, and Back [onLeave]s it, back on the card.
 * An arrow past its edge (from the first or last button, or sideways) leaves it for the next card
 * that way. [content] gets whether the card is raised, its first button, and what to
 * call when a button is selected, with its index. A card taller than the screen scrolls through,
 * see [scrollsThrough]. With a [maxHeight] instead of a [window], the card is no taller, and what
 * doesn't fit scrolls inside it, fading out at its edges (see [scrollsInside]). It's at least
 * [minHeight] high, e.g. to fill a column.
 */
@OptIn(ExperimentalFoundationApi::class) // BringIntoViewRequester, LocalBringIntoViewSpec
@Composable
private fun AssistantCard(
    up: FocusRequester?,
    window: CardWindow?,
    buttons: Int,
    entered: Boolean,
    onEnter: () -> Unit,
    onLeave: () -> Unit,
    minHeight: Dp? = null,
    maxHeight: Dp? = null,
    content: @Composable (raised: Boolean, firstButton: FocusRequester, onButtonFocused: (Int) -> Unit) -> Unit,
) {
    val card = remember { FocusRequester() }
    val firstButton = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    var focusedButton by remember { mutableIntStateOf(0) }
    // Selected, it comes into view clear of the fades
    val bringer = remember { BringIntoViewRequester() }
    val scope = rememberCoroutineScope()
    var size by remember { mutableStateOf(IntSize.Zero) }
    val scale by animateFloatAsState(if (entered) EnteredCardScale else 1f, label = "card scale")
    // Once the buttons can take the selection, or the card again
    var moveFocus by remember { mutableStateOf(false) }
    LaunchedEffect(entered, moveFocus) {
        if (!moveFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { (if (entered) firstButton else card).requestFocus() }
        moveFocus = false
    }
    val colors = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    val inner = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .onSizeChanged { size = it }
            .bringIntoViewRequester(bringer)
            .then(if (window != null) Modifier.scrollsThrough(window, active = !entered) else Modifier.scrollsInside(inner, active = !entered))
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .onPreviewKeyEvent { event ->
                val release = event.type == KeyEventType.KeyUp
                val direction = ArrowDirections[event.key]
                when {
                    // Past the edge: on to the next card that way, if there's one
                    entered && direction != null -> {
                        val edge = when (event.key) {
                            Key.DirectionUp -> focusedButton == 0
                            Key.DirectionDown -> focusedButton == buttons - 1
                            else -> true
                        }
                        if (!edge) return@onPreviewKeyEvent false
                        if (!release) {
                            val moved = if (event.key == Key.DirectionUp && up != null) {
                                runCatching { up.requestFocus() }.isSuccess
                            } else {
                                focusManager.moveFocus(direction)
                            }
                            if (moved) onLeave()
                        }
                        true
                    }
                    !entered && focused && buttons > 0 && event.key in OkKeys -> {
                        if (release) {
                            onEnter()
                            moveFocus = true
                        }
                        true
                    }
                    entered && event.key == Key.Back -> {
                        if (release) {
                            onLeave()
                            moveFocus = true
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusRequester(card)
            .onFocusChanged {
                focused = it.isFocused
                if (it.isFocused && window != null) {
                    val rect = Rect(0f, -window.fadeTop.toFloat(), size.width.toFloat(), size.height + window.fadeBottom.toFloat())
                    scope.launch { bringer.bringIntoView(rect) }
                }
            }
            .focusProperties {
                if (up != null) this.up = up
                // Inside, only the buttons take the selection
                canFocus = !entered
            }
            .focusable()
            // Selected: a bright outline; gone into: a dim one, while its button is lit
            .then(
                when {
                    focused -> Modifier.border(3.dp, colors.onSurface, shape)
                    entered -> Modifier.border(3.dp, colors.border, shape)
                    else -> Modifier
                },
            )
            .heightIn(min = minHeight ?: Dp.Unspecified, max = maxHeight ?: Dp.Unspecified)
            .background(if (entered) RaisedSurface else colors.surfaceVariant, shape)
            .padding(horizontal = CardSidePadding, vertical = if (maxHeight != null) 0.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (maxHeight == null) {
            content(entered, firstButton) { focusedButton = it }
        } else {
            // Scrolls inside the card, fading out at its edges; its buttons come into view as they're selected
            CompositionLocalProvider(LocalBringIntoViewSpec provides MinimalScroll) {
                Column(
                    Modifier.fadingEdges(inner).verticalScroll(inner).padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    content(entered, firstButton) { focusedButton = it }
                }
            }
        }
    }
}

/**
 * For a card whose content scrolls inside it ([scroll]): up and down scroll through what's hidden
 * that way, half the card at a time, before going to the next card. Not while [active] is false,
 * e.g. inside the card, where the buttons scroll into view.
 */
@Composable
private fun Modifier.scrollsInside(scroll: ScrollState, active: Boolean): Modifier {
    val scope = rememberCoroutineScope()
    return onPreviewKeyEvent { event ->
        val half = scroll.viewportSize / 2
        val step = when (event.key) {
            Key.DirectionDown -> minOf(scroll.maxValue - scroll.value, half)
            Key.DirectionUp -> -minOf(scroll.value, half)
            else -> 0
        }
        if (step == 0 || !active) return@onPreviewKeyEvent false
        if (event.type == KeyEventType.KeyDown) scope.launch { scroll.animateScrollBy(step.toFloat()) }
        true
    }
}

private val OkKeys = setOf(Key.DirectionCenter, Key.Enter, Key.NumPadEnter)

private val ArrowDirections = mapOf(
    Key.DirectionUp to FocusDirection.Up,
    Key.DirectionDown to FocusDirection.Down,
    Key.DirectionLeft to FocusDirection.Left,
    Key.DirectionRight to FocusDirection.Right,
)

// The card gone into: a little larger, within the gap between columns
private const val EnteredCardScale = 1.03f

/**
 * A room's or a type's card, or a dashboard's block: its title, then its sensors, with its actions
 * as buttons among them, a step lighter on a [raised] card, which is lighter itself. A dashboard's
 * block can have headings within it, and weathers, shown with their forecast.
 */
@Composable
private fun CardContent(
    card: SensorCard,
    connection: HaConnection,
    // The names a dashboard's cards give entities, in place of their own
    names: Map<String, String>,
    entered: Boolean,
    raised: Boolean,
    firstButton: FocusRequester,
    onButtonFocused: (Int) -> Unit,
) {
    // What the remote lands on once in the card, in order: buttons and switches
    val interactive = card.ids.filter(::isInteractive)
    // A dashboard's cards can have no title
    if (card.title.isNotEmpty()) CardHeading(card.title)
    card.ids.forEachIndexed { position, id ->
        card.subtitles[position]?.let { CardHeading(it, Modifier.padding(top = 8.dp)) }
        val index = interactive.indexOf(id)
        card.graphs[id]?.let { graph ->
            HistoryGraph(connection, graph, names)
            return@forEachIndexed
        }
        if (isHaWeather(id)) {
            WeatherReading(connection.entities[id], connection.hourly[id], connection.daily[id])
            return@forEachIndexed
        }
        if (index < 0) {
            val gauge = card.gauges[id]
            if (gauge != null) {
                GaugeReading(id, connection.entities[id], names[id], gauge)
            } else {
                SensorReading(id, connection.entities[id], names[id])
            }
            return@forEachIndexed
        }
        val modifier = Modifier
            .then(if (index == 0) Modifier.focusRequester(firstButton) else Modifier)
            .onFocusChanged { if (it.isFocused) onButtonFocused(index) }
            // Only once gone into the card
            .focusProperties { canFocus = entered }
        if (isHaToggle(id)) {
            ToggleRow(connection, id, names[id], modifier)
        } else if (isHaSelect(id)) {
            SelectRow(connection, id, names[id], modifier)
        } else {
            ActionButton(connection, id, name = names[id], raised = raised, modifier = modifier)
        }
    }
}

/** A card's title, or the title of a part of it */
@Composable
private fun CardHeading(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier.padding(bottom = 2.dp),
    )
}

/** Buttons (scenes, scripts, buttons), switches (lights, switches, automations…) and selects: what OK acts on in a card */
private fun isInteractive(id: String) = isHaAction(id) || isHaToggle(id) || isHaSelect(id)

/**
 * A light, switch, fan or input boolean: a sensor row with a switch at its end, showing whether
 * it's on. Selected once in its card, OK switches it; greyed out until connected.
 */
@Composable
private fun ToggleRow(connection: HaConnection, id: String, name: String?, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val entity = connection.entities[id]
    val on = entity?.state == "on"
    val ready = connection.session != null
    Surface(
        onClick = { connection.toggle(id, scope, context) },
        // As wide as the card's padding allows, so its icon and text line up with the sensors' above and below
        modifier = modifier.widenBy(ToggleRowInset),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (ready) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.5f),
            focusedContainerColor = colors.onSurface,
            focusedContentColor = colors.inverseOnSurface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = ToggleRowInset, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MdiIcon(entity?.icon ?: "", color = LocalContentColor.current)
            Text(
                name ?: entity?.name ?: id,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            SwitchMark(on, LocalContentColor.current)
        }
    }
}

/**
 * A select or input select: a sensor row with its option, and a mark that it opens a menu. OK
 * opens the menu of its options, on the current one, and the one chosen is set; greyed out until
 * connected.
 */
@Composable
private fun SelectRow(connection: HaConnection, id: String, name: String?, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val entity = connection.entities[id]
    val options = entity?.attributes?.optJSONArray("options")?.let { list -> (0 until list.length()).map { list.optString(it) } }.orEmpty()
    val ready = connection.session != null
    var menuOpen by remember { mutableStateOf(false) }
    // Back on the row once the menu closes
    val row = remember { FocusRequester() }
    var refocus by remember { mutableStateOf(false) }
    LaunchedEffect(refocus) {
        if (!refocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { row.requestFocus() }
        refocus = false
    }
    Surface(
        onClick = { if (options.isNotEmpty()) menuOpen = true },
        modifier = modifier.focusRequester(row).widenBy(ToggleRowInset),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = if (ready) colors.onSurfaceVariant else colors.onSurfaceVariant.copy(alpha = 0.5f),
            focusedContainerColor = colors.onSurface,
            focusedContentColor = colors.inverseOnSurface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(start = ToggleRowInset, end = ToggleRowInset - 4.dp, top = 4.dp, bottom = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MdiIcon(entity?.icon ?: "", color = LocalContentColor.current)
            Text(
                name ?: entity?.name ?: id,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false),
            )
            Text(
                entity?.reading ?: "—",
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            MdiIcon("mdi:menu-down", color = LocalContentColor.current)
        }
        if (menuOpen) {
            ContextMenu(
                title = name ?: entity?.name ?: id,
                actions = options.map { option ->
                    MenuAction(if (option == entity?.state) "✓  $option" else option) {
                        connection.select(id, option, scope, context)
                    }
                },
                onDismiss = {
                    menuOpen = false
                    refocus = true
                },
                placement = MenuPlacement.Below,
                initialIndex = options.indexOf(entity?.state).coerceAtLeast(0),
            )
        }
    }
}

// How far a switch's row reaches into the card's padding on each side
private val ToggleRowInset = 8.dp

/** Measured [by] wider on each side and placed as much to the left: the same width as the others, plus [by] on each side */
private fun Modifier.widenBy(by: Dp) = layout { measurable, constraints ->
    val extra = by.roundToPx()
    val placeable = measurable.measure(
        constraints.copy(minWidth = constraints.maxWidth + extra * 2, maxWidth = constraints.maxWidth + extra * 2),
    )
    layout(constraints.maxWidth, placeable.height) { placeable.place(-extra, 0) }
}

/**
 * A small switch: filled in [color] with a [knob] on the right when [on], an outline with the knob
 * on the left when off. The knob is the row's background, so it stands out on the fill: light on a
 * selected row, whose text is dark.
 */
@Composable
internal fun SwitchMark(on: Boolean, color: Color) {
    Box(
        modifier = Modifier
            .size(width = 36.dp, height = 20.dp)
            .then(if (on) Modifier.background(SwitchOn, CircleShape) else Modifier.border(2.dp, color.copy(alpha = 0.6f), CircleShape))
            .padding(3.dp),
        contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .size(14.dp)
                .background(if (on) SwitchKnob else color.copy(alpha = 0.6f), CircleShape),
        )
    }
}

/** A scene, script or button, as a button the width of its card; greyed out until connected, and while one runs */
@Composable
private fun ActionButton(connection: HaConnection, id: String, name: String?, raised: Boolean, modifier: Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = MaterialTheme.colorScheme
    val entity = connection.entities[id]
    val domainIcon = defaultHaIcon(id.substringBefore('.'), null, "")
    val ready = connection.session != null && connection.runningAction == null
    Surface(
        onClick = { connection.runAction(id, scope, context) },
        modifier = modifier.fillMaxWidth(),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = if (raised) HigherSurface else RaisedSurface,
            contentColor = if (ready) colors.onSurface else colors.onSurface.copy(alpha = 0.5f),
            focusedContainerColor = colors.onSurface,
            focusedContentColor = colors.inverseOnSurface,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MdiIcon(entity?.icon ?: domainIcon, fallback = domainIcon, color = LocalContentColor.current)
            Text(
                name ?: entity?.name ?: id,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (connection.runningAction == id) Text("Running…", style = MaterialTheme.typography.bodySmall)
        }
    }
}
