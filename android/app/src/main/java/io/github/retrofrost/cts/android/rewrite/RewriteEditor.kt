package io.github.retrofrost.cts.android.rewrite

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import io.github.retrofrost.cts.android.export.DeviceExportPolicy
import io.github.retrofrost.cts.android.export.ExportWorker
import io.github.retrofrost.cts.android.importer.MegaPackImporter
import io.github.retrofrost.cts.android.model.CtsCard
import io.github.retrofrost.cts.android.model.CtsProject
import io.github.retrofrost.cts.android.persistence.ProjectJson
import io.github.retrofrost.cts.android.renderer3.RendererV3Container
import io.github.retrofrost.cts.android.timeline.TimelineEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private enum class Page(val label: String) { Cards("Cards"), Media("Media"), Export("Export") }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RewriteEditor(session: EditorSession, store: ProjectStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snack = remember { SnackbarHostState() }
    val project = session.project
    var page by rememberSaveable { mutableStateOf(Page.Cards) }
    var position by rememberSaveable { mutableFloatStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var mediaTarget by rememberSaveable { mutableStateOf<String?>(null) }
    var showPaste by rememberSaveable { mutableStateOf(false) }
    var pasteText by rememberSaveable { mutableStateOf("") }
    var showRename by rememberSaveable { mutableStateOf(false) }
    var showNew by rememberSaveable { mutableStateOf(false) }
    var requestedWork by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingPermission by rememberSaveable { mutableStateOf(false) }
    val manager = remember(context) { WorkManager.getInstance(context) }
    val work by produceState(initialValue = emptyList<WorkInfo>(), manager) {
        manager.getWorkInfosByTagFlow(ExportWorker.TAG).collect { value = it }
    }
    val active = work.firstOrNull { !it.state.isFinished }
    val recent = active ?: work.firstOrNull { it.id.toString() == requestedWork } ?: work.lastOrNull()
    val ready = project.cards.isNotEmpty() || project.rendererV3 != null
    val duration = if (ready) TimelineEngine.duration(project).coerceAtLeast(0f) else 0f

    fun notify(message: String) { scope.launch { snack.showSnackbar(message) } }
    fun setProject(next: CtsProject) {
        playing = false
        session.apply(next)
        position = position.coerceIn(0f, if (next.cards.isEmpty() && next.rendererV3 == null) 0f else TimelineEngine.duration(next))
    }
    fun <T> task(block: () -> T, onSuccess: (T) -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try { onSuccess(withContext(Dispatchers.IO) { block() }) }
            catch (e: Exception) { notify(e.message ?: "The operation failed") }
            finally { busy = false }
        }
    }

    LaunchedEffect(session.revision) {
        val snapshot = session.project
        val revision = session.revision
        delay(350)
        val ok = withContext(Dispatchers.IO) { store.save(snapshot, revision) }
        if (!ok) notify(store.saveWarning ?: "Autosave failed")
    }
    LaunchedEffect(Unit) { store.restoreWarning?.let(::notify) }
    LaunchedEffect(duration) { position = position.coerceIn(0f, duration) }
    LaunchedEffect(playing, duration) {
        if (!playing || duration <= 0f) return@LaunchedEffect
        var previous = withFrameNanos { it }
        while (playing) {
            val now = withFrameNanos { it }
            position = (position + (now - previous) / 1_000_000_000f).coerceAtMost(duration)
            previous = now
            if (position >= duration) playing = false
        }
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = mediaTarget
        mediaTarget = null
        if (uri != null && id != null) task({ store.mediaSource(uri) }) { source ->
            session.updateCard(id) { it.copy(imageSubcard = it.imageSubcard.copy(source = source)) }
            notify("Artwork added")
        }
    }
    val audioPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task({ store.mediaSource(uri) to store.displayName(uri) }) { (source, name) ->
            setProject(session.project.copy(soundtrack = session.project.soundtrack.copy(uri = source, displayName = name)))
            notify("Soundtrack added")
        }
    }
    val introPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task({
            val seconds = MediaMetadataRetriever().let { retriever ->
                try {
                    retriever.setDataSource(context, uri)
                    require((retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0) > 0) { "Select a video file" }
                    (retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L) / 1000f
                } finally { retriever.release() }
            }
            require(seconds > 0f) { "Video has no readable duration" }
            Triple(store.mediaSource(uri), store.displayName(uri), seconds)
        }) { (source, name, seconds) ->
            setProject(session.project.copy(introVideo = session.project.introVideo.copy(uri = source, displayName = name, durationSeconds = seconds)))
            notify("Intro video added")
        }
    }
    val rendererPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task({
            val renderer = RendererV3Container.install(context, uri)
            renderer to runCatching { RendererV3Container.embeddedAudio(context, renderer) }.getOrNull()
        }) { (renderer, audio) ->
            val current = session.project
            val soundtrack = if (current.soundtrack.uri == null && audio != null)
                current.soundtrack.copy(uri = audio, displayName = "Renderer3 embedded audio") else current.soundtrack
            setProject(current.copy(rendererV3 = renderer, soundtrack = soundtrack))
            position = 0f
            notify("Loaded ${renderer.name}")
        }
    }
    val megaPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task({ MegaPackImporter.importPack(context, uri) }) { result ->
            setProject(result.project)
            position = 0f
            page = Page.Cards
            notify("Imported ${result.project.cards.size} cards" + if (result.warnings.isNotEmpty()) " · ${result.warnings.size} warnings" else "")
        }
    }
    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task({ CsvImport.cards(store.readDocument(uri)) }) { cards ->
            setProject(session.project.copy(cards = session.project.cards + cards))
            page = Page.Cards
            notify("Added ${cards.size} cards")
        }
    }
    val openPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) task({ ProjectJson.decode(store.readDocument(uri)) }) { opened ->
            setProject(opened)
            position = 0f
            page = Page.Cards
            val selection = opened.rendererV3
            if (selection != null && runCatching { RendererV3Container.checkedProjectFile(context, selection) }.isFailure)
                notify("Project opened. Reimport its Renderer3 file from the Media tab.")
            else notify("Project opened")
        }
    }
    val savePicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            val snapshot = session.project
            task({ store.writeDocument(uri, snapshot) }) { notify("Project saved") }
        }
    }
    fun filename(extension: String): String = session.project.name.replace(Regex("[^A-Za-z0-9._ -]"), "_").trim().ifBlank { "comparison" }.take(80) + extension
    val exportPicker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("video/mp4")) { uri ->
        if (uri != null) {
            val snapshot = session.project
            val name = filename(".mp4")
            task({
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                ExportWorker.enqueue(context, snapshot, uri, name)
            }) { id ->
                requestedWork = id.toString()
                page = Page.Export
                notify("Export started")
            }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (pendingPermission) {
            pendingPermission = false
            exportPicker.launch(filename(".mp4"))
        }
    }
    fun requestExport() {
        if (!ready || busy || active != null) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            pendingPermission = true
            permissions.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else exportPicker.launch(filename(".mp4"))
    }
    fun addCard() {
        val card = CtsCard(title = "New card")
        setProject(session.project.copy(cards = session.project.cards + card))
        page = Page.Cards
        editing = card.id
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Column {
                Text("Cubical Compare", maxLines = 1)
                Text(project.name, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } }, actions = {
                IconButton(enabled = session.canUndo && !busy, onClick = { playing = false; session.undo() }) { Icon(Icons.Default.Undo, "Undo") }
                IconButton(enabled = session.canRedo && !busy, onClick = { playing = false; session.redo() }) { Icon(Icons.Default.Redo, "Redo") }
            })
        },
        bottomBar = {
            NavigationBar {
                Page.entries.forEach { destination ->
                    NavigationBarItem(
                        selected = page == destination,
                        onClick = { page = destination },
                        icon = { Icon(when (destination) {
                            Page.Cards -> Icons.Default.TableRows
                            Page.Media -> Icons.Default.PermMedia
                            Page.Export -> Icons.Default.Movie
                        }, null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { if (!busy) menu = true }) { Icon(Icons.Default.Add, "Project actions") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    listOf<Pair<String, () -> Unit>>(
                        "Add card" to { addCard() },
                        "Paste CSV" to { showPaste = true },
                        "Import CSV" to { csvPicker.launch(arrayOf("text/*", "application/csv", "*/*")) },
                        "Open project" to { openPicker.launch(arrayOf("application/json", "*/*")) },
                        "Save project" to { savePicker.launch(filename(".cts.json")) },
                        "Rename project" to { showRename = true },
                        "New project" to { showNew = true },
                    ).forEach { (label, action) ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { menu = false; action() })
                    }
                }
            }
        },
        snackbarHost = { SnackbarHost(snack) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp)) {
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            FramePreview(project, position, Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(enabled = ready, onClick = {
                    if (position >= duration) position = 0f
                    playing = !playing
                }) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, if (playing) "Pause" else "Play") }
                Slider(
                    value = position.coerceIn(0f, duration.coerceAtLeast(0.01f)),
                    onValueChange = { playing = false; position = it },
                    valueRange = 0f..duration.coerceAtLeast(0.01f),
                    enabled = ready && duration > 0f,
                    modifier = Modifier.weight(1f),
                )
                Text("${TimelineEngine.formatTime(position)} / ${TimelineEngine.formatTime(duration)}", style = MaterialTheme.typography.labelSmall)
            }
            when (page) {
                Page.Cards -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 92.dp)) {
                    item { Text("${project.cards.size} cards", style = MaterialTheme.typography.titleMedium) }
                    if (project.cards.isEmpty()) item {
                        Text("Create a blank card, paste a table or import a CSV using the + menu.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    itemsIndexed(project.cards, key = { _, card -> card.id }) { index, card ->
                        CardRow(card, index, project.cards.lastIndex, !busy,
                            onEdit = { playing = false; position = TimelineEngine.editingTimeForCard(project, index).coerceIn(0f, duration); editing = card.id },
                            onMove = { delta -> playing = false; session.moveCard(card.id, delta) },
                            onImage = { mediaTarget = card.id; imagePicker.launch(arrayOf("image/*")) })
                    }
                }
                Page.Media -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Project media", style = MaterialTheme.typography.titleLarge)
                    MediaRow("Renderer3", project.rendererV3?.name ?: "Built-in renderer", !busy,
                        { rendererPicker.launch(arrayOf("*/*")) },
                        if (project.rendererV3 != null) {{ setProject(session.project.copy(rendererV3 = null)); position = 0f }} else null)
                    MediaRow("Soundtrack", project.soundtrack.displayName.ifBlank { "No soundtrack" }, !busy,
                        { audioPicker.launch(arrayOf("audio/*")) },
                        if (project.soundtrack.uri != null) {{ setProject(session.project.copy(soundtrack = session.project.soundtrack.copy(uri = null, displayName = ""))) }} else null)
                    if (project.soundtrack.uri != null) {
                        Text("Volume: ${(project.soundtrack.volume * 100).toInt()}%")
                        var volume by remember(project.soundtrack.volume) { mutableFloatStateOf(project.soundtrack.volume) }
                        Slider(value = volume, onValueChange = { volume = it }, valueRange = 0f..2f, onValueChangeFinished = { setProject(session.project.copy(soundtrack = session.project.soundtrack.copy(volume = volume))) })
                    }
                    if (project.rendererV3 == null) MediaRow("Intro video", project.introVideo.displayName.ifBlank { "No intro video" }, !busy,
                        { introPicker.launch(arrayOf("video/*")) },
                        if (project.introVideo.uri != null) {{ setProject(session.project.copy(introVideo = session.project.introVideo.copy(uri = null, displayName = "", durationSeconds = 0f))) }} else null)
                    MediaRow("MegaPack", "Import cards and bundled artwork", !busy, { megaPicker.launch(arrayOf("*/*")) }, null)
                    Spacer(Modifier.height(88.dp))
                }
                Page.Export -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Video export", style = MaterialTheme.typography.titleLarge)
                    Text("${project.export.width} × ${project.export.height} · ${project.export.fps} fps")
                    Text("Resolution", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(480, 720, 1080).forEach { h ->
                            FilterChip(project.export.height == h, onClick = {
                                val width = when (h) { 480 -> 854; 720 -> 1280; else -> 1920 }
                                val bitrate = when (h) { 480 -> 2_000_000; 720 -> 4_000_000; else -> 6_000_000 }
                                setProject(session.project.copy(export = session.project.export.copy(width = width, height = h, videoBitrate = bitrate)))
                            }, label = { Text("${h}p") }, enabled = active == null && !busy)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(24, 30, 60).forEach { fps ->
                            FilterChip(project.export.fps == fps, onClick = { setProject(session.project.copy(export = session.project.export.copy(fps = fps))) }, label = { Text("$fps fps") }, enabled = active == null && !busy)
                        }
                    }
                    TextButton(enabled = active == null && !busy, onClick = { setProject(session.project.copy(export = DeviceExportPolicy.defaultFor(context))) }) { Text("Use device recommendation") }
                    Button(enabled = ready && !busy && active == null, onClick = ::requestExport, modifier = Modifier.fillMaxWidth()) { Text("Export MP4") }
                    recent?.let { info ->
                        val progress = info.progress.getInt(ExportWorker.KEY_PROGRESS, 0).coerceIn(0, 100)
                        val detail = info.progress.getString(ExportWorker.KEY_DETAIL) ?: info.outputData.getString(ExportWorker.KEY_DETAIL)
                        Text(when (info.state) {
                            WorkInfo.State.SUCCEEDED -> "Export complete"
                            WorkInfo.State.FAILED -> "Export failed"
                            WorkInfo.State.CANCELLED -> "Export cancelled"
                            else -> info.progress.getString(ExportWorker.KEY_STAGE) ?: "Preparing export"
                        }, style = MaterialTheme.typography.titleMedium)
                        if (!info.state.isFinished) {
                            LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                            Text("$progress%")
                            OutlinedButton(onClick = { manager.cancelWorkById(info.id) }) { Text("Cancel export") }
                        }
                        if (!detail.isNullOrBlank()) Text(detail, style = MaterialTheme.typography.bodySmall)
                        if (info.state == WorkInfo.State.SUCCEEDED) {
                            val destination = info.outputData.getString(ExportWorker.KEY_DESTINATION_URI)
                            if (destination != null) OutlinedButton(onClick = {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(destination), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)) }
                                    .onFailure { notify("No video player is available") }
                            }) { Text("Open video") }
                        }
                    }
                    Spacer(Modifier.height(88.dp))
                }
            }
        }
    }

    val card = project.cards.firstOrNull { it.id == editing }
    if (card != null) CardDialog(card, onClose = { editing = null }, onSave = { updated ->
        session.updateCard(card.id) { updated }
        editing = null
    }, onDuplicate = { setProject(session.project.duplicateCard(card.id)); editing = null },
        onDelete = { setProject(session.project.removeCard(card.id)); editing = null })
    if (showNew) AlertDialog(onDismissRequest = { showNew = false }, title = { Text("New project?") },
        text = { Text("Start with an empty comparison. You can undo this change.") },
        confirmButton = { TextButton(onClick = { setProject(store.emptyProject()); position = 0f; showNew = false; page = Page.Cards }) { Text("New project") } },
        dismissButton = { TextButton(onClick = { showNew = false }) { Text("Cancel") } })
    if (showRename) {
        var name by rememberSaveable { mutableStateOf(project.name) }
        AlertDialog(onDismissRequest = { showRename = false }, title = { Text("Project name") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { setProject(session.project.copy(name = name.trim().ifBlank { "Untitled comparison" })); showRename = false }) { Text("Save") } },
            dismissButton = { TextButton(onClick = { showRename = false }) { Text("Cancel") } })
    }
    if (showPaste) AlertDialog(onDismissRequest = { showPaste = false }, title = { Text("Paste CSV") },
        text = { OutlinedTextField(pasteText, { pasteText = it }, label = { Text("CSV or TSV with headers") }, minLines = 5, maxLines = 10) },
        confirmButton = { TextButton(enabled = pasteText.isNotBlank() && !busy, onClick = {
            val input = pasteText
            task({ CsvImport.cards(input) }) { cards ->
                setProject(session.project.copy(cards = session.project.cards + cards))
                pasteText = ""; showPaste = false; page = Page.Cards
                notify("Added ${cards.size} cards")
            }
        }) { Text("Import") } },
        dismissButton = { TextButton(onClick = { showPaste = false }) { Text("Cancel") } })
}

@Composable
private fun CardRow(card: CtsCard, index: Int, last: Int, enabled: Boolean, onEdit: () -> Unit, onMove: (Int) -> Unit, onImage: () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).clickable(enabled = enabled, onClick = onEdit).padding(4.dp)) {
                Text("${index + 1} · ${card.title.ifBlank { "Untitled card" }}", maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                Text(listOf(card.badgePrimary, card.badgeSecondary).filter(String::isNotBlank).joinToString(" · "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            }
            IconButton(enabled = enabled, onClick = onImage) { Icon(Icons.Default.Image, "Attach artwork") }
            Column {
                IconButton(enabled = enabled && index > 0, onClick = { onMove(-1) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.ArrowUpward, "Move up") }
                IconButton(enabled = enabled && index < last, onClick = { onMove(1) }, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.ArrowDownward, "Move down") }
            }
        }
    }
}

@Composable
private fun MediaRow(title: String, detail: String, enabled: Boolean, choose: () -> Unit, remove: (() -> Unit)?) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row {
                TextButton(enabled = enabled, onClick = choose) { Text("Choose file") }
                if (remove != null) TextButton(enabled = enabled, onClick = remove) { Text("Remove") }
            }
        }
    }
}

@Composable
private fun CardDialog(card: CtsCard, onClose: () -> Unit, onSave: (CtsCard) -> Unit, onDuplicate: () -> Unit, onDelete: () -> Unit) {
    var title by rememberSaveable(card.id) { mutableStateOf(card.title) }
    var badge by rememberSaveable(card.id) { mutableStateOf(card.badgePrimary) }
    var label by rememberSaveable(card.id) { mutableStateOf(card.badgeSecondary) }
    var description by rememberSaveable(card.id) { mutableStateOf(card.description) }
    var source by rememberSaveable(card.id) { mutableStateOf(card.imageSubcard.source.orEmpty()) }
    var zoom by rememberSaveable(card.id) { mutableFloatStateOf(card.imageSubcard.cropZoom) }
    var focusX by rememberSaveable(card.id) { mutableFloatStateOf(card.imageSubcard.cropFocusX) }
    var focusY by rememberSaveable(card.id) { mutableFloatStateOf(card.imageSubcard.cropFocusY) }
    AlertDialog(onDismissRequest = onClose, title = { Text("Edit card") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(title, { title = it }, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(badge, { badge = it }, label = { Text("Badge value") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(label, { label = it }, label = { Text("Badge header") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("Description") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(source, { source = it }, label = { Text("Artwork path or URL") }, modifier = Modifier.fillMaxWidth())
            if (source.isNotBlank()) {
                Text("Image zoom")
                Slider(zoom.coerceIn(1f, 3f), { zoom = it }, valueRange = 1f..3f)
                Text("Horizontal focus")
                Slider(focusX.coerceIn(0f, 1f), { focusX = it })
                Text("Vertical focus")
                Slider(focusY.coerceIn(0f, 1f), { focusY = it })
            }
            Row { TextButton(onClick = onDuplicate) { Text("Duplicate") }; TextButton(onClick = onDelete) { Text("Delete") } }
        } },
        confirmButton = { TextButton(onClick = { onSave(card.copy(title = title, badgePrimary = badge, badgeSecondary = label, description = description,
            imageSubcard = card.imageSubcard.copy(source = source.trim().ifBlank { null }, cropFocusX = focusX, cropFocusY = focusY, cropZoom = zoom))) }) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}
