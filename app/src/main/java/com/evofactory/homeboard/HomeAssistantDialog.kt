package com.evofactory.homeboard

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import kotlinx.coroutines.launch

/** The tabs once connected: the dashboard first, as it decides what the others are for, then choosing sensors and actions, and their order */
private enum class HaTab(val label: String) { Home("Home Tab"), Dashboard("HA Tab"), Sensors("Sensors"), Actions("Actions"), Order("Order") }

/**
 * Home Assistant settings, in two steps: the address and how to log in (username and password, or
 * a long-lived token), then, once connected, three tabs: the sensors and weather to show, the
 * scenes, scripts and buttons, and the order of everything on the Assistant tab (see [sensorCards]). All of
 * it shows there; the actions also chosen for the top bar, at most [MAX_HA_ACTIONS], are its
 * buttons too, in the same order. The first tab, "HA Tab", can have the Assistant tab show one of Home
 * Assistant's dashboards instead (see [parseDashboard]). Once set up, it opens on the second step, connecting with the
 * saved login; if that fails, it goes back to logging in. The password isn't kept: logging in gives
 * the TV a refresh token, which shows in the Home Assistant profile and is ended when it's
 * replaced, or on Log out.
 */
@OptIn(ExperimentalTvMaterial3Api::class) // FilterChip
@Composable
fun HomeAssistantDialog(
    initialServer: HaServer,
    initialUsername: String,
    initialEntities: List<String>,
    initialTopBar: List<String>,
    initialDisplay: HaDisplay,
    loginRejected: Boolean,
    onSave: (server: HaServer, username: String, entities: List<String>, topBar: List<String>, display: HaDisplay) -> Unit,
    onLogOut: () -> Unit,
    onDiscardLogin: (HaServer) -> Unit,
    onDismiss: () -> Unit,
) {
    var url by remember { mutableStateOf(initialServer.url) }
    var useToken by remember { mutableStateOf(initialServer.token.isNotEmpty()) }
    var username by remember { mutableStateOf(initialUsername) }
    var password by remember { mutableStateOf("") }
    var token by remember { mutableStateOf(initialServer.token) }
    // From the saved login, or from logging in here, and whose login it is
    var refreshToken by remember { mutableStateOf(if (loginRejected) "" else initialServer.refreshToken) }
    var loginUsername by remember { mutableStateOf(initialUsername) }
    // Logged in here but not saved yet: the login is ended if the dialog closes without saving
    var unsavedLogin by remember { mutableStateOf<HaServer?>(null) }
    // Set while Home Assistant waits for the two-factor code
    var codeFlow by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    // Everything chosen, in its order on the Assistant tab, and the actions also in the top bar
    var selected by remember { mutableStateOf(initialEntities) }
    var topBar by remember { mutableStateOf(initialTopBar.filter { it in initialEntities }) }
    // The dashboard the Assistant tab shows (see [HaDashboardInfo.key]), null for the choice above,
    // and Home Assistant's dashboards, each read if it can be
    var dashboard by remember { mutableStateOf(initialDisplay.dashboard) }
    // The alerts, apps and Continue Watching below the Assistant tab's cards
    var bottomRow by remember { mutableStateOf(initialDisplay.bottomRow) }
    // Each section in a column of its own on the Assistant tab
    var sectionColumns by remember { mutableStateOf(initialDisplay.sectionColumns) }
    // The same for the Home tab's panel, next to the camera
    var homeDashboard by remember { mutableStateOf(initialDisplay.homeDashboard) }
    var homeColumns by remember { mutableStateOf(initialDisplay.homeColumns) }
    var boards by remember { mutableStateOf<List<Pair<HaDashboardInfo, HaDashboard?>>>(emptyList()) }
    // The connected Home Assistant and what can be picked on it; set = second step, choosing
    var connected by remember { mutableStateOf<HaServer?>(null) }
    var available by remember { mutableStateOf<List<HaEntity>?>(null) }
    var connecting by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    // Logging in here, rather than using the saved login
    var showLogin by remember { mutableStateOf(!initialServer.isSet || loginRejected) }
    var tab by remember { mutableStateOf(HaTab.Home) }
    // The Order tab's row being moved, if any
    var movingKey by remember { mutableStateOf<String?>(null) }
    var sensorQuery by remember { mutableStateOf("") }
    var actionQuery by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val keyboard = LocalSoftwareKeyboardController.current
    val urlField = remember { FocusRequester() }
    val firstLoginField = remember { FocusRequester() }
    val passwordField = remember { FocusRequester() }
    val connectButton = remember { FocusRequester() }
    val closeButton = remember { FocusRequester() }
    val sensorSearch = remember { FocusRequester() }
    val actionSearch = remember { FocusRequester() }
    // To come back to the item last on, from the top row
    val loginFocus = remember { ListFocus() }
    val sensorFocus = remember { ListFocus() }
    val actionFocus = remember { ListFocus() }
    val dashboardFocus = remember { ListFocus() }
    val homeFocus = remember { ListFocus() }
    val orderFocus = remember { ListFocus() }
    // Without a password, the saved login is used (if it's for this username)
    val reuseLogin = password.isEmpty() && refreshToken.isNotEmpty() && username.trim() == loginUsername
    val canConnect = normalizeHaUrl(url) != null && when {
        useToken -> token.isNotBlank()
        codeFlow != null -> code.isNotBlank()
        else -> (username.isNotBlank() && password.isNotEmpty()) || reuseLogin
    }

    DisposableEffect(Unit) { onDispose { unsavedLogin?.let(onDiscardLogin) } }

    fun connect() {
        val baseUrl = normalizeHaUrl(url) ?: return
        if (!canConnect || connecting) return
        keyboard?.hide()
        url = baseUrl
        status = if (useToken || reuseLogin) "Connecting to $baseUrl…" else "Logging in…"
        connecting = true
        scope.launch {
            try {
                val server = if (useToken) {
                    HaServer(baseUrl, token = token.trim())
                } else {
                    val flow = codeFlow
                    val step = when {
                        flow != null -> haSendCode(baseUrl, flow, code.trim())
                        password.isNotEmpty() -> haLogIn(baseUrl, username.trim(), password)
                        else -> null
                    }
                    when (step) {
                        is HaLoginStep.NeedsCode -> {
                            codeFlow = step.flowId
                            code = ""
                            status = step.error ?: "Enter the code from your authenticator app"
                            return@launch
                        }
                        is HaLoginStep.LoggedIn -> {
                            unsavedLogin?.let(onDiscardLogin)
                            unsavedLogin = HaServer(baseUrl, refreshToken = step.refreshToken)
                            refreshToken = step.refreshToken
                            loginUsername = username.trim()
                            password = ""
                            codeFlow = null
                        }
                        null -> Unit
                    }
                    HaServer(baseUrl, refreshToken = refreshToken)
                }
                status = "Connecting to $baseUrl…"
                val (states, found) = haSession(server) {
                    getStates() to dashboards().map { it to parseDashboard(dashboardConfig(it.urlPath)) }
                }
                boards = found
                available = states
                    .filter { it.isPickable }
                    .sortedBy { it.name.lowercase() }
                connected = server
                status = null
            } catch (e: HaException) {
                // A failed code step can't be retried (e.g. too many wrong codes): log in again
                codeFlow = null
                status = e.message
                showLogin = true
                // The saved login no longer works: the password is needed
                if (e.tokenRejected && !useToken) refreshToken = ""
            } finally {
                connecting = false
            }
        }
    }

    // Ends the login, saved or made here, and goes back to logging in; the address and username stay
    fun logOut() {
        unsavedLogin?.let(onDiscardLogin)
        unsavedLogin = null
        if (initialServer.isSet) onLogOut()
        refreshToken = ""
        token = ""
        password = ""
        codeFlow = null
        connected = null
        available = null
        status = null
        showLogin = true
    }

    val entities = available
    val server = connected
    val logOutButton = DialogButton("Log out", onClick = ::logOut)

    if (entities == null || server == null) {
        if (!showLogin) {
            // Connecting with the saved login
            TopDialog(title = "Home Assistant", onDismiss = onDismiss, closeFocus = closeButton) {
                Text(
                    status ?: "Connecting…",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
                LaunchedEffect(Unit) {
                    closeButton.requestFocus()
                    connect()
                }
            }
            return
        }
        val loginKeys = listOf("url", "with-password", "with-token") + when {
            useToken -> listOf("token")
            codeFlow != null -> listOf("code")
            else -> listOf("username", "password")
        }
        TopDialog(
            title = "Home Assistant",
            onDismiss = onDismiss,
            buttons = listOfNotNull(
                // Greyed out until everything needed is filled in. Stays enabled while connecting
                // (presses are ignored), so the remote keeps its place on it
                DialogButton(
                    when {
                        useToken || reuseLogin -> "Connect"
                        codeFlow != null -> "Verify"
                        else -> "Log in"
                    },
                    enabled = canConnect,
                    primary = true,
                    focus = connectButton,
                    onClick = ::connect,
                ),
                logOutButton.takeIf { initialServer.isSet },
            ),
            contentFocus = { loginFocus.current(loginKeys) },
        ) {
            val fieldModifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
            TvTextField(
                value = url,
                onValueChange = { url = it },
                placeholder = "Address, e.g. http://192.168.1.20:8123",
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Next,
                onImeAction = {
                    keyboard?.hide()
                    firstLoginField.requestFocus()
                },
                modifier = fieldModifier.focusRequester(urlField).then(loginFocus.row("url")).ringToTop(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Log in with", style = MaterialTheme.typography.titleSmall)
                FilterChip(
                    selected = !useToken,
                    onClick = { useToken = false },
                    modifier = loginFocus.row("with-password").ringToTop(right = false),
                ) { Text("Username and password") }
                FilterChip(
                    selected = useToken,
                    onClick = { useToken = true },
                    modifier = loginFocus.row("with-token").ringToTop(left = false),
                ) { Text("Access token") }
            }
            when {
                useToken -> TvTextField(
                    value = token,
                    onValueChange = { token = it },
                    placeholder = "Long-lived access token",
                    keyboardType = KeyboardType.Ascii,
                    imeAction = ImeAction.Done,
                    onImeAction = {
                        keyboard?.hide()
                        if (canConnect) connectButton.requestFocus()
                    },
                    modifier = fieldModifier.focusRequester(firstLoginField).then(loginFocus.row("token")).ringToTop(),
                )
                codeFlow != null -> {
                    TvTextField(
                        value = code,
                        onValueChange = { code = it },
                        placeholder = "Two-factor code",
                        keyboardType = KeyboardType.NumberPassword,
                        imeAction = ImeAction.Done,
                        onImeAction = ::connect,
                        modifier = fieldModifier.focusRequester(firstLoginField).then(loginFocus.row("code")).ringToTop(),
                    )
                    LaunchedEffect(Unit) { firstLoginField.requestFocus() }
                }
                else -> Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    TvTextField(
                        value = username,
                        onValueChange = { username = it },
                        placeholder = "Username",
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next,
                        onImeAction = {
                            keyboard?.hide()
                            passwordField.requestFocus()
                        },
                        // Side by side: Right goes to the password, Left comes back
                        modifier = Modifier.weight(1f).focusRequester(firstLoginField).then(loginFocus.row("username")).ringToTop(right = false),
                    )
                    TvTextField(
                        value = password,
                        onValueChange = { password = it },
                        placeholder = if (reuseLogin) "Password (logged in already)" else "Password",
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done,
                        onImeAction = {
                            keyboard?.hide()
                            if (canConnect) connectButton.requestFocus()
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.weight(1f).focusRequester(passwordField).then(loginFocus.row("password")).ringToTop(left = false),
                    )
                }
            }
            Text(
                status ?: when {
                    useToken -> "Create the token in your Home Assistant profile → Security. It's long: type it " +
                        "with the Google TV app's keyboard on your phone, where you can paste it."
                    else -> "The password isn't stored: the TV keeps a login, listed in your Home Assistant " +
                        "profile → Security. A user just for the TV, without admin rights, is safest."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
            LaunchedEffect(Unit) {
                when {
                    canConnect -> connectButton
                    normalizeHaUrl(url) != null && !useToken && username.isNotBlank() -> passwordField
                    else -> urlField
                }.requestFocus()
            }
        }
        return
    }

    val byId = remember(entities) { entities.associateBy { it.id } }
    // What was chosen before comes first, so it can be seen in a long list. Not what's chosen
    // now: items would jump around while choosing
    val sensorSections = pickerSections(entities.filterNot { it.isAction }, sensorQuery, initialEntities)
    val actionSections = pickerSections(entities.filter { it.isAction }, actionQuery, initialEntities)
    val orderRows = orderRows(selected, topBar, byId)
    fun keysOf(sections: List<Pair<String, List<HaEntity>>>) = sections.flatMap { (_, items) -> items.map { it.id } }

    TopDialog(
        title = "Home Assistant",
        // Room for its five tabs, Log out, Save and the X
        width = 720.dp,
        onDismiss = onDismiss,
        tabs = HaTab.entries.map { DialogTab(it.label, selected = it == tab) { tab = it } },
        buttons = listOf(
            logOutButton,
            DialogButton("Save", primary = true) {
                // Drops entities that no longer exist
                val ids = entities.map { it.id }.toSet()
                val chosen = selected.filter { it in ids }
                onSave(server, if (server.refreshToken.isEmpty()) "" else loginUsername, chosen, topBar.filter { it in chosen },
                    HaDisplay(dashboard, bottomRow, sectionColumns, homeDashboard, homeColumns),
                )
                unsavedLogin = null
                onDismiss()
            },
        ),
        // The item last on, if still listed; otherwise the first in the order shown, or the search
        contentFocus = {
            when (tab) {
                HaTab.Sensors -> sensorFocus.currentOrNull(keysOf(sensorSections)) ?: sensorSearch
                HaTab.Actions -> actionFocus.currentOrNull(keysOf(actionSections)) ?: actionSearch
                HaTab.Order -> orderFocus.currentOrNull(orderRows.filter { it.focusable }.map { it.key })
                HaTab.Dashboard -> dashboardFocus.currentOrNull(listOf(MY_SELECTION) + boards.map { it.first.key } + BOTTOM_ROW + SECTION_COLUMNS)
                HaTab.Home -> homeFocus.currentOrNull(listOf(MY_SELECTION) + boards.map { it.first.key } + SECTION_COLUMNS)
            }
        },
        searchFocus = {
            when (tab) {
                HaTab.Sensors -> sensorSearch
                HaTab.Actions -> actionSearch
                HaTab.Order, HaTab.Dashboard, HaTab.Home -> null
            }
        },
        hint = when (tab) {
            HaTab.Sensors -> "${selected.count { !isHaAction(it) }} selected"
            HaTab.Actions -> "OK: top bar → chosen → off · ${selected.count(::isHaAction)} selected · " +
                "${topBar.size} of $MAX_HA_ACTIONS in the top bar"
            HaTab.Order -> when {
                orderRows.isEmpty() -> null
                movingKey != null -> "Up and down move it · OK keeps it there · Back cancels"
                else -> "OK on a card's title moves the card, OK on an item moves it within its card"
            }
            HaTab.Dashboard, HaTab.Home -> null
        },
    ) {
        when (tab) {
            HaTab.Sensors -> EntityPicker(
                sections = sensorSections,
                query = sensorQuery,
                onQueryChange = { sensorQuery = it },
                search = sensorSearch,
                rows = sensorFocus,
                mark = { if (it.id in selected) "✓" else "" },
                onPress = { entity ->
                    selected = if (entity.id in selected) selected - entity.id else selected + entity.id
                },
            )
            HaTab.Actions -> EntityPicker(
                sections = actionSections,
                query = actionQuery,
                onQueryChange = { actionQuery = it },
                search = actionSearch,
                rows = actionFocus,
                mark = {
                    when (it.id) {
                        in topBar -> "✓ Top bar"
                        in selected -> "✓"
                        else -> ""
                    }
                },
                // Off, then in the top bar if it has room, then out of it, then off again
                onPress = { entity ->
                    val id = entity.id
                    when (id) {
                        in topBar -> topBar = topBar - id
                        in selected -> selected = selected - id
                        else -> {
                            selected = selected + id
                            if (topBar.size < MAX_HA_ACTIONS) topBar = topBar + id
                        }
                    }
                },
            )
            HaTab.Dashboard -> DashboardChoice(
                boards = boards,
                chosen = dashboard,
                onChoose = { dashboard = it },
                selectionDetail = "The sensors and actions chosen in the other tabs, in the Order tab's order, by room",
                bottomRow = bottomRow,
                onBottomRowChange = { bottomRow = it },
                sectionColumns = sectionColumns,
                onSectionColumnsChange = { sectionColumns = it },
                rowModifier = { key -> dashboardFocus.row(key).ringToTop() },
            )
            HaTab.Home -> DashboardChoice(
                boards = boards,
                chosen = homeDashboard,
                onChoose = { homeDashboard = it },
                selectionDetail = "The weather and sensors chosen in the other tabs; OK on them opens the Assistant tab",
                bottomRow = null,
                onBottomRowChange = {},
                sectionColumns = homeColumns,
                onSectionColumnsChange = { homeColumns = it },
                rowModifier = { key -> homeFocus.row(key).ringToTop() },
            )
            HaTab.Order -> SensorOrder(
                rows = orderRows,
                ids = selected,
                movingKey = movingKey,
                onMovingChange = { movingKey = it },
                onMove = { key, by ->
                    selected = if (key.startsWith(CARD_ROW)) {
                        moveSensorCard(selected, byId, key.removePrefix(CARD_ROW), by)
                    } else {
                        moveSensor(selected, byId, key, by)
                    }
                },
                onCancel = { selected = it },
                rowModifier = { key -> orderFocus.row(key).ringToTop() },
            )
        }
        // On the first tab, on what's chosen
        LaunchedEffect(Unit) {
            withFrameNanos { }
            runCatching { homeFocus.requester(homeDashboard ?: MY_SELECTION).requestFocus() }
        }
    }
}

/**
 * [entities] matching every word of [query] (in the name, the full name, the room or the id), in
 * sections: the weather, the rooms (alphabetical), then by type those without a room. Those in
 * [first] come first in each.
 */
private fun pickerSections(entities: List<HaEntity>, query: String, first: List<String>): List<Pair<String, List<HaEntity>>> {
    val words = query.lowercase().split(' ').filter { it.isNotEmpty() }
    val listed = entities
        .filter { entity ->
            val text = "${entity.fullName} ${entity.name} ${entity.area.orEmpty()} ${entity.id}".lowercase()
            words.all { it in text }
        }
        .sortedBy { it.id !in first }
    val others = listed.filter { it.domain != "weather" }
    val byRoom = others.groupBy { it.area }
    val anyRoom = byRoom.keys.any { it != null }
    val noRoom = byRoom[null].orEmpty()
    return buildList {
        add("Weather" to listed.filter { it.domain == "weather" })
        byRoom.keys.filterNotNull().sortedBy { it.lowercase() }.forEach { add(it to byRoom.getValue(it)) }
        noRoom.map { haEntityType(it.id) }.distinct().sortedBy { TypeOrder.indexOf(it).takeIf { i -> i >= 0 } ?: TypeOrder.size }.forEach { type ->
            add(sensorTypeTitle(type, anyRoom) to noRoom.filter { haEntityType(it.id) == type })
        }
    }.filter { (_, items) -> items.isNotEmpty() }
}

// Types without a room, in this order; any other after them
private val TypeOrder = listOf("sensor", "binary_sensor", "scene", "script", "button")

/** The Sensors or Actions tab: a search, then [sections] of entities, each with its [mark] at the end. What OK does is the dialog's hint. */
@Composable
private fun TopDialogScope.EntityPicker(
    sections: List<Pair<String, List<HaEntity>>>,
    query: String,
    onQueryChange: (String) -> Unit,
    search: FocusRequester,
    rows: ListFocus,
    mark: (HaEntity) -> String,
    onPress: (HaEntity) -> Unit,
) {
    val keyboard = LocalSoftwareKeyboardController.current
    val keys = sections.flatMap { (_, items) -> items.map { it.id } }
    TvTextField(
        value = query,
        onValueChange = onQueryChange,
        placeholder = "Search",
        keyboardType = KeyboardType.Text,
        imeAction = ImeAction.Search,
        onImeAction = {
            keyboard?.hide()
            rows.currentOrNull(keys)?.requestFocus()
        },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .focusRequester(search)
            .ringToTop()
            .downToContent(),
    )
    if (keys.isEmpty()) {
        Text("Nothing matches", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).padding(top = 4.dp).scrollIndicators(listState)) {
        sections.forEach { (title, items) ->
            entitySection(title, items, mark, onPress) { entity -> rows.row(entity.id).ringToTop() }
        }
    }
}

private fun LazyListScope.entitySection(
    title: String,
    entities: List<HaEntity>,
    mark: (HaEntity) -> String,
    onPress: (HaEntity) -> Unit,
    itemModifier: (HaEntity) -> Modifier,
) {
    if (entities.isEmpty()) return
    item(key = "section $title") {
        Text(
            title,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
        )
    }
    items(entities, key = { it.id }) { entity ->
        // One line in small text, so more of a long list fits: the name, then the reading and the
        // full name (it tells apart entities named alike on different devices) and the id
        Surface(
            onClick = { onPress(entity) },
            shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
            colors = ClickableSurfaceDefaults.colors(
                containerColor = Color.Transparent,
                contentColor = MaterialTheme.colorScheme.onSurface,
                focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
                focusedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
            ),
            scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
            modifier = Modifier.fillMaxWidth().then(itemModifier(entity)),
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MdiIcon(entity.icon, size = 18.dp)
                Text(
                    entity.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 240.dp),
                )
                val detail = listOfNotNull(
                    entity.reading.takeUnless { entity.isAction },
                    entity.fullName.takeIf { it != entity.name },
                    entity.id,
                )
                Text(
                    detail.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(mark(entity), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// Dashboard tab: the row for the sensors and actions chosen in the other tabs
private const val MY_SELECTION = "my selection"

/**
 * The Home Tab and HA Tab tabs: the selection made in the other tabs (as [selectionDetail] says),
 * or one of Home Assistant's [boards], each with what it can show. OK [onChoose]s one, by its key
 * (null for the selection). Those with nothing that can be shown aren't offered, unless chosen
 * already. Below them, switches for the [bottomRow] (alerts, apps, Continue Watching) below the
 * Assistant tab's cards, unless null, and for [sectionColumns].
 */
@Composable
private fun DashboardChoice(
    boards: List<Pair<HaDashboardInfo, HaDashboard?>>,
    chosen: String?,
    onChoose: (String?) -> Unit,
    selectionDetail: String,
    bottomRow: Boolean?,
    onBottomRowChange: (Boolean) -> Unit,
    sectionColumns: Boolean,
    onSectionColumnsChange: (Boolean) -> Unit,
    rowModifier: (key: String) -> Modifier,
) {
    val listState = rememberLazyListState()
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(top = 4.dp).scrollIndicators(listState)) {
        item(key = MY_SELECTION) {
            ChoiceRow(
                "My selection",
                selectionDetail,
                checked = chosen == null,
                modifier = rowModifier(MY_SELECTION),
            ) { onChoose(null) }
        }
        val shown = boards.filter { (info, board) -> (board != null && board.views.isNotEmpty()) || info.key == chosen }
        items(shown, key = { it.first.key }) { (info, board) ->
            val detail = when {
                board == null -> "Built by Home Assistant itself, with nothing to read: your selection shows instead"
                board.views.isEmpty() -> "Nothing it shows can be shown here"
                else -> listOfNotNull(
                    if (board.views.size == 1) null else "${board.views.size} views",
                    "${board.cardCount} cards",
                    board.skipped.takeIf { it.isNotEmpty() }?.let { "not shown: ${it.joinToString(", ")}" },
                ).joinToString(" · ")
            }
            ChoiceRow(info.title, detail, checked = chosen == info.key, modifier = rowModifier(info.key)) { onChoose(info.key) }
        }
        if (bottomRow != null) {
            item(key = BOTTOM_ROW) {
                ChoiceRow(
                    "Show bottom row (Alerts / Apps / Continue watching)",
                    "Below the cards, as on the other tabs. Off: the cards take the whole height.",
                    checked = bottomRow,
                    switch = true,
                    modifier = rowModifier(BOTTOM_ROW).padding(top = 8.dp),
                ) { onBottomRowChange(!bottomRow) }
            }
        }
        item(key = SECTION_COLUMNS) {
            ChoiceRow(
                "Display sections as columns",
                "Each section in a column of its own, scrolling sideways. Off: the sections fill as many columns as fit.",
                checked = sectionColumns,
                switch = true,
                modifier = rowModifier(SECTION_COLUMNS).then(if (bottomRow == null) Modifier.padding(top = 8.dp) else Modifier),
            ) { onSectionColumnsChange(!sectionColumns) }
        }
    }
}

// The rows of the switches, after the dashboards
private const val BOTTOM_ROW = "bottom_row"
private const val SECTION_COLUMNS = "section_columns"

/** A row to choose, with a line saying more under its title, and a check mark when [checked], or a [switch] on when it is */
@Composable
internal fun ChoiceRow(title: String, detail: String, checked: Boolean, modifier: Modifier, switch: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onSurface,
            focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
            focusedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
        modifier = Modifier.fillMaxWidth().then(modifier),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalContentColor.current.copy(alpha = 0.7f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (switch) {
                SwitchMark(checked, LocalContentColor.current)
            } else {
                Text(if (checked) "✓" else "", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

// Order tab: the key of a card's title row, before the card's key
private const val CARD_ROW = "card:"

/** A row of the Order tab: a card's title, which moves the whole card, or an entity in it */
private class OrderRow(
    val key: String,
    val title: String,
    val entity: HaEntity?,
    val isCard: Boolean,
    val movable: Boolean,
    // Shown at the end, e.g. that an action is also in the top bar
    val note: String? = null,
) {
    // A title that can't move is only a label
    val focusable get() = !isCard || movable
}

/** The cards of the Assistant tab, each title followed by its entities; a weather card is only its title */
private fun orderRows(ids: List<String>, topBar: List<String>, entities: Map<String, HaEntity>): List<OrderRow> = buildList {
    val cards = sensorCards(ids, entities)
    cards.forEach { card ->
        val weather = card.ids.singleOrNull()?.takeIf(::isHaWeather)
        add(
            OrderRow(
                CARD_ROW + card.key,
                if (weather != null) "Weather: ${card.title}" else card.title,
                weather?.let(entities::get),
                isCard = true,
                movable = cards.size > 1,
            ),
        )
        if (weather != null) return@forEach
        card.ids.forEach { id ->
            val entity = entities[id]
            add(
                OrderRow(
                    id,
                    entity?.name ?: id,
                    entity,
                    isCard = false,
                    movable = card.ids.size > 1,
                    note = if (isHaAction(id)) "top bar".takeIf { id in topBar } else entity?.reading,
                ),
            )
        }
    }
}

/**
 * The Order tab: each card's title, then its entities. OK on a title moves the whole card, OK on
 * an entity moves it within its card: up and down move it, OK keeps the new order, Back puts back
 * [ids] as they were, through [onCancel]. [movingKey] is the row being moved: the dialog's hint
 * says so.
 */
@Composable
private fun SensorOrder(
    rows: List<OrderRow>,
    ids: List<String>,
    movingKey: String?,
    onMovingChange: (String?) -> Unit,
    onMove: (key: String, by: Int) -> Unit,
    onCancel: (List<String>) -> Unit,
    rowModifier: (key: String) -> Modifier,
) {
    var before by remember { mutableStateOf(ids) }
    val listState = rememberLazyListState()

    // Keeps the row being moved in view, with the row above it
    LaunchedEffect(ids, movingKey) {
        val index = rows.indexOfFirst { it.key == movingKey }
        if (index >= 0) listState.animateScrollToItem((index - 1).coerceAtLeast(0))
    }

    if (rows.isEmpty()) {
        Text("Nothing chosen yet", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp))
    }
    LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 340.dp).padding(top = 4.dp).scrollIndicators(listState)) {
        items(rows, key = { it.key }) { row ->
            if (!row.focusable) {
                Text(
                    row.title,
                    style = MaterialTheme.typography.labelLarge,
                    modifier = Modifier.padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
                )
                return@items
            }
            val moving = row.key == movingKey
            Surface(
                onClick = {
                    if (!row.movable) return@Surface
                    before = ids
                    onMovingChange(row.key)
                },
                shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(8.dp)),
                colors = ClickableSurfaceDefaults.colors(
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    focusedContainerColor = MaterialTheme.colorScheme.inverseSurface,
                    focusedContentColor = MaterialTheme.colorScheme.inverseOnSurface,
                ),
                scale = ClickableSurfaceDefaults.scale(focusedScale = 1f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = if (row.isCard) 6.dp else 0.dp)
                    .then(rowModifier(row.key))
                    .onPreviewKeyEvent { event ->
                        if (!moving) return@onPreviewKeyEvent false
                        val down = event.type == KeyEventType.KeyDown
                        when (event.key) {
                            Key.DirectionUp -> { if (down) onMove(row.key, -1); true }
                            Key.DirectionDown -> { if (down) onMove(row.key, 1); true }
                            Key.DirectionLeft, Key.DirectionRight -> true
                            // On release, so the press doesn't start moving it again
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (!down) onMovingChange(null); true }
                            Key.Back -> {
                                if (!down) {
                                    onCancel(before)
                                    onMovingChange(null)
                                }
                                true
                            }
                            else -> false
                        }
                    },
            ) {
                Row(
                    // A card's entities under its title, set in
                    modifier = Modifier.padding(start = if (row.isCard) 12.dp else 28.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when {
                        moving -> MdiIcon("mdi:arrow-up-down", size = 18.dp)
                        row.entity != null -> MdiIcon(row.entity.icon, size = 18.dp)
                    }
                    Text(
                        row.title,
                        style = if (row.isCard) MaterialTheme.typography.labelLarge else MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    row.note?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = LocalContentColor.current.copy(alpha = 0.7f),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}
