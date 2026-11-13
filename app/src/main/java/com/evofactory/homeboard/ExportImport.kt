package com.evofactory.homeboard

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.storage.StorageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Checkbox
import androidx.tv.material3.CheckboxDefaults
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private const val FILE_FORMAT = "homeboard-settings"

/** A Home Assistant entity, with its name for showing in the list when importing */
data class NamedEntity(val id: String, val name: String)

/** What can be exported or imported. The login, if any, comes with its username. */
data class SettingsFile(
    val cameras: List<Camera> = emptyList(),
    val login: HaServer? = null,
    val username: String = "",
    val sensors: List<NamedEntity> = emptyList(),
    val actions: List<NamedEntity> = emptyList(),
    // The order of the sensors and actions together on the Assistant tab, and the actions also in
    // the top bar. Null in files from earlier versions: the sensors, then the actions, of which the
    // first ones were the top bar's.
    val order: List<String>? = null,
    val topBar: List<String>? = null,
    val frigate: FrigateServer? = null,
    // The apps visible in the row, in their order
    val appTiles: List<NamedEntity> = emptyList(),
) {
    /** Only what's checked, by [Row] key */
    fun only(keys: Set<String>) = copy(
        cameras = cameras.filter { "camera:${it.id}" in keys },
        login = login?.takeIf { LOGIN_KEY in keys },
        sensors = sensors.filter { "sensor:${it.id}" in keys },
        actions = actions.filter { "action:${it.id}" in keys },
        order = order?.filter { "sensor:$it" in keys || "action:$it" in keys },
        topBar = topBar?.filter { "action:$it" in keys },
        frigate = frigate?.takeIf { FRIGATE_KEY in keys },
        appTiles = appTiles.takeIf { APP_TILES_KEY in keys }.orEmpty(),
    )

    fun toJson(): String = JSONObject()
        .put("format", FILE_FORMAT)
        .put("version", 1)
        .put("cameras", JSONArray(camerasToJson(cameras)))
        .apply {
            login?.let {
                put(
                    "homeAssistantLogin",
                    JSONObject().put("url", it.url).put("token", it.token).put("refreshToken", it.refreshToken).put("username", username),
                )
            }
        }
        .put("sensors", entitiesToJson(sensors))
        .put("actions", entitiesToJson(actions))
        .apply {
            order?.let { put("order", JSONArray(it)) }
            topBar?.let { put("topBar", JSONArray(it)) }
            frigate?.let {
                put(
                    "frigate",
                    JSONObject().put("url", it.url).put("username", it.username).put("password", it.password).put("certPin", it.certPin),
                )
            }
        }
        .put("appTiles", entitiesToJson(appTiles))
        .toString(2)
}

private const val LOGIN_KEY = "login"
private const val FRIGATE_KEY = "frigate"
private const val APP_TILES_KEY = "appTiles"

private fun entitiesToJson(entities: List<NamedEntity>) =
    JSONArray(entities.map { JSONObject().put("id", it.id).put("name", it.name) })

private fun entitiesFromJson(array: JSONArray?) = List(array?.length() ?: 0) { i ->
    array!!.getJSONObject(i).let { NamedEntity(it.getString("id"), it.optString("name", it.getString("id"))) }
}

/** Null if this isn't a settings file */
fun settingsFromJson(json: String): SettingsFile? = runCatching {
    val root = JSONObject(json)
    if (root.optString("format") != FILE_FORMAT) return null
    val login = root.optJSONObject("homeAssistantLogin")
    val frigate = root.optJSONObject("frigate")
    SettingsFile(
        cameras = root.optJSONArray("cameras")?.let { camerasFromJson(it.toString()) }.orEmpty(),
        login = login?.let { HaServer(it.getString("url"), it.optString("token"), it.optString("refreshToken")) },
        username = login?.optString("username").orEmpty(),
        sensors = entitiesFromJson(root.optJSONArray("sensors")),
        actions = entitiesFromJson(root.optJSONArray("actions")),
        order = root.optJSONArray("order")?.let { array -> List(array.length()) { array.getString(it) } },
        topBar = root.optJSONArray("topBar")?.let { array -> List(array.length()) { array.getString(it) } },
        frigate = frigate?.let { FrigateServer(it.getString("url"), it.optString("username"), it.optString("password"), it.optString("certPin")) },
        appTiles = entitiesFromJson(root.optJSONArray("appTiles")),
    )
}.getOrNull()

/** A drive: the TV's own storage or a USB key */
private class StorageRoot(val dir: File, val label: String, val removable: Boolean)

private fun storageRoots(context: Context): List<StorageRoot> {
    val storage = context.getSystemService(StorageManager::class.java)
    return context.getExternalFilesDirs(null).filterNotNull()
        .filter { Environment.getExternalStorageState(it) == Environment.MEDIA_MOUNTED }
        .map { dir ->
            val volume = storage.getStorageVolume(dir)
            StorageRoot(File(dir.path.substringBefore("/Android/data/")), volume?.getDescription(context) ?: "Storage", volume?.isRemovable == true)
        }
        .sortedByDescending { it.removable }
}

private fun jsonFile(name: String) = if (name.endsWith(".json", ignoreCase = true)) name else "$name.json"

private fun isValidFileName(name: String) = name.isNotBlank() && name != "." && name != ".." && '/' !in name

/** One line of the checklist */
private class Row(val key: String, val title: String, val subtitle: String)

private class Section(val title: String, val rows: List<Row>)

private fun sections(file: SettingsFile) = listOf(
    Section("Cameras", file.cameras.map { Row("camera:${it.id}", it.name, it.host) }),
    Section(
        "Home Assistant login",
        listOfNotNull(
            // Which account the login is for: one made with a long-lived access token has no username
            file.login?.let { Row(LOGIN_KEY, Uri.parse(it.url).host ?: it.url, if (file.username.isEmpty()) "Account: none, an access token" else "Account: ${file.username}") },
        ),
    ),
    Section("Home Assistant sensors", file.sensors.map { Row("sensor:${it.id}", it.name, it.id) }),
    Section("Home Assistant actions", file.actions.map { Row("action:${it.id}", it.name, it.id) }),
    Section(
        "Frigate login",
        listOfNotNull(file.frigate?.let { Row(FRIGATE_KEY, Uri.parse(it.url).host ?: it.url, if (it.username.isEmpty()) "Account: none" else "Account: ${it.username}") }),
    ),
    // One line: the order only means something as a whole
    Section(
        "App tiles",
        listOfNotNull(
            file.appTiles.takeIf { it.isNotEmpty() }?.let { tiles ->
                Row(APP_TILES_KEY, "${tiles.size} visible apps, in their order", tiles.joinToString(", ") { it.name })
            },
        ),
    ),
).filter { it.rows.isNotEmpty() }

/** Everything starts checked; the confirm button is greyed out once nothing is. */
@Composable
fun ChecklistDialog(
    title: String,
    confirmLabel: String,
    file: SettingsFile,
    onConfirm: (SettingsFile) -> Unit,
    onDismiss: () -> Unit,
    above: @Composable () -> Unit = {},
    below: @Composable () -> Unit = {},
) {
    val sections = remember(file) { sections(file) }
    val unchecked = remember(file) { mutableStateListOf<String>() }
    val allKeys = remember(sections) { sections.flatMap { section -> section.rows.map { it.key } } }
    val confirm = remember { FocusRequester() }
    val cancel = remember { FocusRequester() }
    val rows = remember { ListFocus() }
    val canConfirm = unchecked.size < allKeys.size

    TopDialog(
        title = title,
        onDismiss = onDismiss,
        buttons = listOfNotNull(
            // Partly checked counts as checked: one press clears the list
            DialogButton(if (canConfirm) "Uncheck all" else "Check all") {
                unchecked.clear()
                if (canConfirm) unchecked.addAll(allKeys)
            }.takeIf { allKeys.isNotEmpty() },
            DialogButton(confirmLabel, enabled = canConfirm, primary = true, focus = confirm) {
                onConfirm(file.only(allKeys.toSet() - unchecked.toSet()))
            },
        ),
        contentFocus = { rows.currentOrNull(allKeys) },
        closeFocus = cancel,
    ) {
        above()
        if (allKeys.isEmpty()) {
            Text("Nothing here", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
        }
        val listState = rememberLazyListState()
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).scrollIndicators(listState)) {
            sections.forEach { section ->
                item(key = section.title) {
                    Text(
                        section.title,
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 12.dp, top = 12.dp, end = 12.dp, bottom = 4.dp),
                    )
                }
                items(section.rows, key = { it.key }) { row ->
                    val checked = row.key !in unchecked
                    ListItem(
                        selected = false,
                        onClick = { if (checked) unchecked += row.key else unchecked -= row.key },
                        headlineContent = { Text(row.title) },
                        supportingContent = { Text(row.subtitle) },
                        leadingContent = {
                            Checkbox(
                                checked = checked,
                                onCheckedChange = null,
                                colors = CheckboxDefaults.colors(
                                    checkedColor = CheckedBox,
                                    uncheckedColor = UncheckedBox,
                                    checkmarkColor = Color.White,
                                ),
                            )
                        },
                        modifier = rows.row(row.key).ringToTop(),
                    )
                }
            }
        }
        below()
        LaunchedEffect(Unit) { runCatching { (if (allKeys.isEmpty()) cancel else confirm).requestFocus() } }
    }
}

private val CheckedBox = Color(0xFF5A595F)
private val UncheckedBox = Color(0xFF8E8C92)

/** To another TV on the network, or to a file. */
@Composable
fun ExportDialog(file: SettingsFile, onDismiss: () -> Unit) {
    var toFile by remember { mutableStateOf(false) }

    if (toFile) {
        ExportToFile(file, onDone = onDismiss, onBack = { toFile = false })
    } else {
        ExportToTv(file, onSaveToFile = { toFile = true }, onDismiss = onDismiss)
    }
}

/** Save as: choose what to export, then the folder and file name. */
@Composable
private fun ExportToFile(file: SettingsFile, onDone: () -> Unit, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var chosen by remember { mutableStateOf<SettingsFile?>(null) }

    ChecklistDialog(
        title = "Export settings",
        confirmLabel = "Next",
        file = file,
        onConfirm = { chosen = it },
        onDismiss = onBack,
        below = {
            Text(
                "The file isn't encrypted: it holds camera addresses with their passwords, the Home Assistant login " +
                    "and the Frigate password. " +
                    "Keep it safe and delete it when you're done.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp),
            )
        },
    )
    // On top of the list, which keeps its checkmarks for coming back to
    chosen?.let { settings ->
        FileBrowser(
            title = "Save settings as",
            defaultName = "homeboard-settings-${SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.ROOT).format(Date())}.json",
            onPick = { target ->
                scope.launch {
                    val error = withContext(Dispatchers.IO) {
                        runCatching { target.writeText(settings.toJson()) }.onFailure { AppLog.w("Settings", "Can't export the settings", it) }.exceptionOrNull()
                    }
                    if (error == null) {
                        Toast.makeText(context, "Saved ${target.name}", Toast.LENGTH_LONG).show()
                        onDone()
                    } else {
                        Toast.makeText(context, "Can't save here. Try another folder, such as Download or Documents.", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { chosen = null },
        )
    }
}

/** From another TV on the network, or from a settings file anywhere, choosing from what's in it. */
@Composable
fun ImportDialog(onImport: (SettingsFile) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var opened by remember { mutableStateOf<SettingsFile?>(null) }
    var fromFile by remember { mutableStateOf(false) }

    val settings = opened
    if (settings != null) {
        ChecklistDialog(
            title = "Import settings",
            confirmLabel = "Import",
            file = settings,
            onConfirm = {
                onImport(it)
                onDismiss()
            },
            onDismiss = onDismiss,
        )
    } else if (!fromFile) {
        ImportFromTv(onImport = onImport, onOpenFile = { fromFile = true }, onDismiss = onDismiss)
    } else {
        FileBrowser(
            title = "Import settings",
            defaultName = null,
            onPick = { file ->
                scope.launch {
                    val read = withContext(Dispatchers.IO) { runCatching { settingsFromJson(file.readText()) }.onFailure { AppLog.w("Settings", "Can't read the settings file", it) }.getOrNull() }
                    if (read != null) {
                        opened = read
                    } else {
                        Toast.makeText(context, "${file.name} isn't a settings file", Toast.LENGTH_LONG).show()
                    }
                }
            },
            onDismiss = { fromFile = false },
        )
    }
}

/** What the browser shows, in order */
private class BrowserRow(val key: String, val title: String, val icon: String, val subtitle: String? = null, val file: File? = null, val onClick: () -> Unit)

/**
 * Goes through the drives and folders. This is our own because TVs often have no system file
 * picker, only a stub. With a [defaultName] it saves: type a name or pick a file to replace.
 * Without one it opens the file picked. Back goes up a folder. Holding OK on a file offers
 * to delete it.
 */
@Composable
private fun FileBrowser(title: String, defaultName: String?, onPick: (File) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    // Android 10 and older need the permission for anywhere outside the app's folder
    val needsPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.R
    var allowed by remember {
        mutableStateOf(!needsPermission || context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED)
    }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        allowed = granted
        if (!granted) {
            Toast.makeText(context, "HomeBoard needs access to storage to do this", Toast.LENGTH_LONG).show()
            onDismiss()
        }
    }
    LaunchedEffect(Unit) { if (!allowed) askPermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) }
    if (!allowed) return

    val roots = remember { storageRoots(context) }
    // A USB key is the likely choice, so show the drives then. Else start where files can always be saved.
    var dir by remember {
        mutableStateOf(
            if (roots.any { it.removable }) null
            else roots.firstOrNull()?.let { root -> File(root.dir, Environment.DIRECTORY_DOWNLOADS).takeIf { it.isDirectory } ?: root.dir },
        )
    }
    var name by remember { mutableStateOf(defaultName.orEmpty()) }
    var newFolder by remember { mutableStateOf<String?>(null) }
    var folders by remember { mutableStateOf<List<File>?>(null) }
    var files by remember { mutableStateOf<List<File>>(emptyList()) }
    var deleting by remember { mutableStateOf<File?>(null) }
    // The row to focus once a folder is shown: the one we came up from, or else the first
    var focusKey by remember { mutableStateOf<String?>(null) }
    val current = dir
    val rootOfCurrent = roots.firstOrNull { it.dir == current }

    fun goUp() {
        val from = dir ?: return onDismiss()
        focusKey = from.path
        dir = if (rootOfCurrent != null) null else from.parentFile
    }

    fun delete(file: File) {
        scope.launch {
            val deleted = withContext(Dispatchers.IO) { runCatching { file.delete() }.onFailure { AppLog.w("Settings", "Can't delete the settings file", it) }.getOrDefault(false) }
            if (deleted) {
                val index = files.indexOf(file)
                focusKey = (files.getOrNull(index + 1) ?: files.getOrNull(index - 1))?.path
                files = files - file
                Toast.makeText(context, "Deleted ${file.name}", Toast.LENGTH_LONG).show()
            } else {
                AppLog.w("Settings", "Can't delete the settings file")
                Toast.makeText(context, "Can't delete ${file.name}", Toast.LENGTH_LONG).show()
            }
        }
    }

    LaunchedEffect(current) {
        if (current == null) {
            folders = emptyList()
            files = emptyList()
        } else {
            val listed = withContext(Dispatchers.IO) { current.listFiles()?.filterNot { it.name.startsWith(".") } }
            folders = listed?.filter { it.isDirectory }?.sortedBy { it.name.lowercase() }
            files = listed.orEmpty().filter { it.isFile && it.name.endsWith(".json", ignoreCase = true) }.sortedByDescending { it.lastModified() }
        }
    }

    val target = current?.takeIf { isValidFileName(name) }?.let { File(it, jsonFile(name.trim())) }
    val rows = buildList {
        if (current == null) {
            roots.forEach { root ->
                add(BrowserRow(root.dir.path, root.label, if (root.removable) "mdi:usb-flash-drive" else "mdi:television") {
                    focusKey = null
                    dir = root.dir
                })
            }
        } else {
            add(BrowserRow("up", "Up", "mdi:arrow-up") { goUp() })
            folders.orEmpty().forEach { folder ->
                add(BrowserRow(folder.path, folder.name, "mdi:folder") {
                    focusKey = null
                    dir = folder
                })
            }
            files.forEach { file ->
                val modified = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(file.lastModified()))
                add(BrowserRow(file.path, file.name, "mdi:file-document-outline", modified, file) {
                    if (defaultName == null) onPick(file) else name = file.name
                })
            }
        }
    }

    val listState = rememberLazyListState()
    val rowFocus = remember { ListFocus() }
    val rowKeys = rows.map { it.key }
    val saveButton = remember { FocusRequester() }
    val focusIndex = rows.indexOfFirst { it.key == focusKey }.takeIf { it >= 0 } ?: if (current != null && rows.size > 1) 1 else 0
    val focusedKey = rows.getOrNull(focusIndex)?.key

    TopDialog(
        title = title,
        // Back goes up a folder, the X closes
        onDismiss = ::goUp,
        onClose = onDismiss,
        buttons = if (defaultName == null) {
            emptyList()
        } else {
            listOf(
                DialogButton("New folder", enabled = current != null) { newFolder = "" },
                DialogButton(if (target?.exists() == true) "Replace" else "Save", enabled = target != null, primary = true, focus = saveButton) {
                    target?.let(onPick)
                },
            )
        },
        contentFocus = { rowFocus.currentOrNull(rowKeys) },
        hint = "Hold OK on a file to delete it".takeIf { files.isNotEmpty() },
    ) {
        // On its own line: a deep folder's path would squeeze the buttons
        Text(
            current?.let { (rootOfCurrent ?: roots.firstOrNull { root -> it.path.startsWith(root.dir.path + "/") })?.let { root -> root.label + it.path.removePrefix(root.dir.path) } ?: it.path } ?: "Choose a drive",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.StartEllipsis,
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, end = 12.dp),
        )
        val adding = newFolder
        if (adding != null && current != null) {
            val field = remember { FocusRequester() }
            TvTextField(
                value = adding,
                onValueChange = { newFolder = it },
                placeholder = "New folder name",
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
                onImeAction = {
                    keyboard?.hide()
                    val created = File(current, adding.trim())
                    if (isValidFileName(adding) && (created.isDirectory || created.mkdir())) {
                        focusKey = null
                        dir = created
                        newFolder = null
                    } else {
                        Toast.makeText(context, "Can't create that folder here", Toast.LENGTH_LONG).show()
                    }
                },
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp).focusRequester(field)
                    .ringToTop(),
            )
            LaunchedEffect(Unit) { runCatching { field.requestFocus() } }
        } else if (defaultName != null) {
            TvTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "File name",
                keyboardType = KeyboardType.Text,
                imeAction = ImeAction.Done,
                onImeAction = { keyboard?.hide() },
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 12.dp, end = 12.dp)
                    .ringToTop(),
            )
        }
        if (current != null && folders == null) {
            Text("Can't read this folder", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
        }
        if (current == null && roots.isEmpty()) {
            Text("No storage available on this TV", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(12.dp))
        }
        LazyColumn(state = listState, modifier = Modifier.fillMaxWidth().heightIn(max = 300.dp).scrollIndicators(listState)) {
            items(rows, key = { it.key }) { row ->
                ListItem(
                    selected = false,
                    onClick = row.onClick,
                    onLongClick = row.file?.let { file -> { deleting = file } },
                    headlineContent = { Text(row.title) },
                    supportingContent = row.subtitle?.let { { Text(it) } },
                    leadingContent = { MdiIcon(row.icon, size = 24.dp) },
                    modifier = rowFocus.row(row.key).ringToTop(),
                )
                if (row.file != null && deleting == row.file) {
                    ContextMenu(
                        title = "Delete this file?",
                        actions = listOf(
                            MenuAction("Delete") { delete(row.file) },
                            MenuAction("Cancel") {},
                        ),
                        onDismiss = { deleting = null },
                        placement = MenuPlacement.Below,
                    )
                }
            }
        }
        // Once a folder's contents are in, not while the previous one's are still showing
        LaunchedEffect(current, folders, files, newFolder == null) {
            if (newFolder != null) return@LaunchedEffect
            if (rows.isEmpty()) {
                runCatching { close.requestFocus() }
            } else {
                listState.scrollToItem(focusIndex)
                withFrameNanos { }
                focusedKey?.let { runCatching { rowFocus.requester(it).requestFocus() } }
            }
        }
    }
}
