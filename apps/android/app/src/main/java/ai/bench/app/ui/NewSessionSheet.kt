package ai.bench.app.ui

import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.outlined.AccountTree
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bench.app.data.BenchError
import ai.bench.app.data.get
import ai.bench.app.data.str
import ai.bench.app.data.BenchStore
import ai.bench.app.data.GitRepo
import ai.bench.app.data.Mode
import ai.bench.app.data.TaskGit
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.Space
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Bench.colors.accent.copy(alpha = 0.22f),
    selectedLabelColor = Bench.colors.accent,
    labelColor = Bench.colors.text.copy(alpha = 0.8f),
)

@Composable
private fun fieldColors() = OutlinedTextFieldDefaults.colors(
    focusedContainerColor = Bench.colors.surface1, unfocusedContainerColor = Bench.colors.surface1,
    focusedBorderColor = Bench.colors.accent, unfocusedBorderColor = Bench.colors.lineStrong,
)

/**
 * New session, as the Android v2 board draws it: what Claude should do comes first, then
 * Project, Model and Mode as rows, then where it works. One filled action: Start.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionSheet(store: BenchStore, onDismiss: () -> Unit, onCreated: (String) -> Unit, research: Boolean = false, onSchedule: (ai.bench.app.data.SchedulePrefill) -> Unit = {}) {
    val s by store.state.collectAsState()
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    val recent = s.sessions.maxByOrNull { it.updated_at }?.project_id
    var projectId by remember { mutableStateOf(if (research) s.projects.firstOrNull { it.name == "Research" }?.id else recent ?: s.projects.firstOrNull()?.id) }
    // Research works in the Research project's own folder: no branch, a report at the end.
    var where by remember { mutableStateOf(if (research) "checkout" else "task") }
    LaunchedEffect(research) { if (research) runCatching { store.api.research() }.onSuccess { projectId = it.id; store.refresh() } }
    var prompt by remember { mutableStateOf("") }
    var mode by remember { mutableStateOf(Mode.of(s.defaults.mode)) }
    var modelId by remember { mutableStateOf(s.defaults.modelId) }
    var providerId by remember { mutableStateOf<String?>(null) }
    var effort by remember { mutableStateOf(s.defaults.effort) }
    var providers by remember { mutableStateOf<List<ai.bench.app.data.ProviderInfo>>(emptyList()) }
    var page by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { providers = runCatching { store.api.providers() }.getOrDefault(emptyList()) }
    val base = s.tasks.firstOrNull { it.project_id == projectId }?.base_branch?.ifBlank { null } ?: "main"
    val modelName = providers.flatMap { it.models }.firstOrNull { it.id == modelId }?.label ?: "Default model"
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Files go up once the session exists (uploads belong to a session), then ride on the first message.
    var picked by remember { mutableStateOf(listOf<Picked>()) }
    fun add(uris: List<android.net.Uri>) { picked = picked + uris.map { Picked.of(context.contentResolver, it) } }
    val files = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetMultipleContents()) { add(it) }
    val photos = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia(8)) { add(it) }
    var beforeTake by remember { mutableStateOf("") }
    val live = ai.bench.app.ui.session.rememberLiveDictation { heard, _ -> prompt = if (beforeTake.isBlank()) heard else beforeTake.trimEnd() + " " + heard }
    val askMic = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> if (ok) { beforeTake = prompt; live.start() } }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = Modifier.statusBarsPadding(),
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.surface2,
        shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = { androidx.compose.foundation.layout.Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.surface3, RoundedCornerShape(2.dp))) },
    ) {
        when (page) {
            "project" -> ProjectList(s, projectId, onBack = { page = null }) { projectId = it; page = null }
            "model" -> Column(Modifier.fillMaxWidth().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SheetBack("Model") { page = null }
                Choice("Default model", modelId == null) { modelId = null; providerId = null; page = null }
                // The provider in use open, the rest folded behind their name (Claude's "More models").
                var open by remember { mutableStateOf(setOfNotNull(providers.firstOrNull { p -> p.models.any { it.id == modelId } }?.id ?: providers.firstOrNull { it.enabled && it.ready }?.id)) }
                providers.filter { it.enabled && it.ready }.sortedByDescending { it.id in open }.forEach { p ->
                    val shown = p.id in open
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 44.dp).clip(RoundedCornerShape(12.dp)).clickable { open = if (shown) open - p.id else open + p.id }.padding(horizontal = 6.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.weight(1f))
                        if (!shown) Text("${p.models.count { it.enabled }}", fontSize = 13.sp, color = c.muted)
                        androidx.compose.material3.Icon(if (shown) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = c.muted, modifier = Modifier.size(18.dp))
                    }
                    if (shown) p.models.filter { it.enabled }.forEach { m -> Choice(m.label, m.id == modelId) { modelId = m.id; providerId = p.id; page = null } }
                }
                Text("Effort", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 8.dp))
                Segmented(listOf("" to "Auto") + listOf("low", "medium", "high", "max").map { it to ai.bench.app.data.Effort.of(it)!!.label }, effort ?: "", { effort = it.ifEmpty { null } })
                Text("Mode", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 4.dp))
                Mode.entries.forEach { m -> Choice(m.label, m == mode) { mode = m } }
                Text("For this session. Change the defaults in Settings.", fontSize = 12.5.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 10.dp))
            }
            "mode" -> Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SheetBack("Mode") { page = null }
                Mode.entries.forEach { m -> Choice(m.label, m == mode) { mode = m; page = null } }
            }
            else -> Column(
                Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(if (research) "Research" else "New session", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
                // Where it goes, as chips: the project (last used first) and how it works there.
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill(Icons.Outlined.Folder, s.projects.firstOrNull { it.id == projectId }?.name ?: "Ask Bench") { page = "project" }
                    if (projectId != null && !research) {
                        var whereMenu by remember { mutableStateOf(false) }
                        Box {
                            Pill(Icons.Outlined.AccountTree, if (where == "task") "New branch" else "Checkout") { whereMenu = true }
                            androidx.compose.material3.DropdownMenu(whereMenu, { whereMenu = false }) {
                                androidx.compose.material3.DropdownMenuItem({ Column { Text("New branch"); Text("Its own branch from $base, so $base stays clean", fontSize = 12.5.sp, color = c.muted) } }, onClick = { where = "task"; whereMenu = false })
                                androidx.compose.material3.DropdownMenuItem({ Column { Text("Checkout"); Text("Works in the project's checked-out branch", fontSize = 12.5.sp, color = c.muted) } }, onClick = { where = "checkout"; whereMenu = false })
                            }
                        }
                    }
                }
                androidx.compose.foundation.text.BasicTextField(
                    prompt, { prompt = it },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 25.sp, color = c.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 260.dp).padding(horizontal = 4.dp, vertical = 4.dp).focusRequester(focus),
                    decorationBox = { inner -> androidx.compose.foundation.layout.Box { if (prompt.isEmpty()) Text(if (live.listening) "Listening…" else if (research) "What should Claude find out?" else if (projectId == null) "Ask about Bench: what's running, what it costs…" else "What should Claude do?", fontSize = 17.sp, color = c.muted); inner() } },
                )
                if (research) Text("Claude writes a report you can read later. It shows on Home under Ready for you.", fontSize = 13.5.sp, lineHeight = 19.sp, color = c.muted, modifier = Modifier.padding(horizontal = 4.dp))
                if (picked.isNotEmpty()) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    picked.forEach { f -> AttachmentTile(f.name, f.uri, f.type.startsWith("image/"), f.size, null, null) { picked = picked - f } }
                }
                // "Whenever a new role is posted…": a run once would miss the point; offer a When… instead.
                if (projectId != null && WHENEVER.containsMatchIn(prompt)) Text(
                    "Run this whenever it happens instead",
                    fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium, color = c.accent,
                    modifier = Modifier.padding(horizontal = 4.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                        .clickable { onSchedule(ai.bench.app.data.SchedulePrefill(prompt.lineSequence().first().take(60), prompt.trim(), projectId)) }.padding(vertical = 4.dp),
                )
                error?.let { Text(it, color = c.bad, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 4.dp)) }
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    androidx.compose.material3.IconButton(onClick = { files.launch("*/*") }) { androidx.compose.material3.Icon(Icons.Default.Add, "Attach a file", tint = c.muted) }
                    androidx.compose.material3.IconButton(onClick = { photos.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly)) }) { androidx.compose.material3.Icon(Icons.Outlined.Image, "Attach photos", tint = c.muted) }
                    // Model and effort before the first send, as Claude's composer shows them.
                    Row(
                        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).clickable { page = "model" }.padding(horizontal = 10.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(listOfNotNull(modelName, effort?.let { ai.bench.app.data.Effort.of(it)?.label }).joinToString(" · "), fontSize = 14.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp))
                        androidx.compose.material3.Icon(Icons.Default.KeyboardArrowDown, null, tint = c.muted, modifier = Modifier.size(16.dp))
                    }
                    androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                    if (live.available) androidx.compose.material3.IconButton(onClick = {
                        when {
                            live.listening -> live.stop()
                            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED -> { beforeTake = prompt; live.start() }
                            else -> askMic.launch(android.Manifest.permission.RECORD_AUDIO)
                        }
                    }) { androidx.compose.material3.Icon(Icons.Default.Mic, if (live.listening) "Stop dictating" else "Dictate", tint = if (live.listening) c.accent else c.muted) }
                    val canStart = !busy && (!research || (projectId != null && prompt.isNotBlank())) && (projectId == null || where == "checkout" || prompt.isNotBlank() || picked.isNotEmpty())
                    androidx.compose.material3.FilledIconButton(
                        enabled = canStart,
                        modifier = Modifier.size(52.dp),
                        colors = androidx.compose.material3.IconButtonDefaults.filledIconButtonColors(containerColor = c.accent, contentColor = c.onAccent, disabledContainerColor = c.surface3, disabledContentColor = c.muted),
                        onClick = {
                            if (live.listening) live.stop()
                            busy = true
                            error = null
                            scope.launch {
                                try {
                                    val pid = projectId
                                    val text = prompt.trim()
                                    val asTask = pid != null && where == "task"
                                    // With files, the task starts quietly and the first message carries them.
                                    val session = if (asTask) store.api.createTask(pid!!, text.lineSequence().firstOrNull()?.take(80)?.ifBlank { null } ?: picked.first().name, if (picked.isEmpty()) text else null, mode.wire)
                                    else store.api.createSession(pid, null, mode.wire)
                                    if (modelId != null || effort != null) runCatching {
                                        store.api.patch("/api/sessions/${ai.bench.app.data.BenchApi.enc(session.id)}", buildJsonObject {
                                            modelId?.let { put("modelId", it) }; providerId?.let { put("providerId", it) }; effort?.let { put("effort", it) }
                                        })
                                    }
                                    val refs = picked.map { f -> store.api.upload(session.id, f.name, f.type, f.size, { context.contentResolver.openInputStream(f.uri) ?: error("Can't read ${f.name}") }) {} }
                                    if ((!asTask || picked.isNotEmpty()) && (text.isNotEmpty() || refs.isNotEmpty())) store.api.send(session.id, text, refs)
                                    onCreated(session.id)
                                } catch (e: Exception) { error = e.message ?: "Couldn't start the session" } finally { busy = false }
                            }
                        },
                    ) {
                        if (busy) androidx.compose.material3.CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.onAccent)
                        else androidx.compose.material3.Icon(Icons.Default.ArrowUpward, "Start")
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(250); runCatching { focus.requestFocus() } }
}

/** A file chosen before the session exists. */
private data class Picked(val uri: android.net.Uri, val name: String, val type: String, val size: Long) {
    companion object {
        fun of(r: android.content.ContentResolver, uri: android.net.Uri): Picked {
            val (name, size) = r.query(uri, null, null, null, null)?.use { cur ->
                val n = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                val z = cur.getColumnIndex(android.provider.OpenableColumns.SIZE)
                if (cur.moveToFirst()) (if (n >= 0) cur.getString(n) else null) to (if (z >= 0) cur.getLong(z) else -1L) else null to -1L
            } ?: (null to -1L)
            return Picked(uri, name ?: uri.lastPathSegment ?: "file", r.getType(uri) ?: "application/octet-stream", size)
        }
    }
}

/** A chip that opens a choice: an icon, the current value, a caret. */
@Composable
private fun Pill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit) {
    val c = Bench.colors
    Row(
        Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(c.surface3).clickable(onClick = onClick).padding(horizontal = 12.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        androidx.compose.material3.Icon(icon, null, tint = c.muted, modifier = Modifier.size(16.dp))
        Text(text, fontSize = 14.sp, maxLines = 1)
        androidx.compose.material3.Icon(Icons.Default.KeyboardArrowDown, null, tint = c.muted, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun Field(label: String, value: String, onClick: () -> Unit) {
    val c = Bench.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = c.muted, modifier = Modifier.width(76.dp))
        Text(value, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.muted)
    }
}

@Composable
private fun Choice(label: String, on: Boolean, onClick: () -> Unit) {
    val c = Bench.colors
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).background(if (on) c.accent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 15.5.sp, modifier = Modifier.weight(1f))
        if (on) androidx.compose.material3.Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SheetBack(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Medium)
    }
}

/** Choose a project, as its board draws it: search, the recent ones, then all of them. */
@Composable
private fun ProjectList(s: ai.bench.app.data.BenchState, selected: String?, onBack: () -> Unit, onPick: (String?) -> Unit) {
    val c = Bench.colors
    var query by remember { mutableStateOf("") }
    val lastUsed = s.sessions.groupBy { it.project_id }.mapValues { (_, v) -> v.maxOf { it.updated_at } }
    val recent = s.projects.filter { lastUsed[it.id] != null }.sortedByDescending { lastUsed[it.id] }.take(3)
    val all = s.projects.sortedBy { it.name.lowercase() }
    val match = { n: String -> query.isBlank() || n.contains(query, ignoreCase = true) }
    androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().heightIn(max = 620.dp).imePadding().navigationBarsPadding(), contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
        item { SheetBack("Project", onBack) }
        item {
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).background(c.surface1, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                androidx.compose.material3.Icon(Icons.Default.Search, null, tint = c.muted, modifier = Modifier.size(18.dp))
                androidx.compose.foundation.text.BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = c.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                    decorationBox = { inner -> androidx.compose.foundation.layout.Box { if (query.isEmpty()) Text("Search ${s.projects.size} projects", fontSize = 15.sp, color = c.muted); inner() } },
                )
            }
        }
        fun section(title: String, rows: List<Pair<String?, Pair<String, String>>>) {
            if (rows.isEmpty()) return
            item(title) { Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 20.dp, bottom = 10.dp)) }
            item("$title-list") {
                Grouped(rows) { (id, t) ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 58.dp).clickable { onPick(id) }.padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(t.first, fontSize = 15.5.sp, color = if (id == selected) c.accent else c.text)
                            Text(t.second, fontSize = 12.sp, color = c.muted, maxLines = 1)
                        }
                        if (id == selected) androidx.compose.material3.Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
        if (query.isBlank()) section("Recent", recent.map { it.id to (it.name to "Used ${ai.bench.app.data.Format.ago(lastUsed[it.id]!!)} ago") })
        section(if (query.isBlank()) "All projects" else "Matches",
            (listOf<Pair<String?, Pair<String, String>>>(null to ("Ask Bench" to "No project: about Bench itself")) + all.map { it.id to (it.name to it.path.replace(Regex("^/(home|Users)/[^/]+"), "~")) })
                .filter { match(it.second.first) })
    }
}

/**
 * Where the session runs: one row showing the choice, opening to a searchable list with the
 * projects used most recently first. Scales to any number of projects.
 */
@Composable
private fun ProjectPicker(s: ai.bench.app.data.BenchState, selected: String?, onPick: (String?) -> Unit) {
    val c = Bench.colors
    var open by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val lastUsed = s.sessions.groupBy { it.project_id }.mapValues { (_, v) -> v.maxOf { it.updated_at } }
    val ordered = s.projects.sortedByDescending { lastUsed[it.id].orEmpty() }
    val name = s.projects.firstOrNull { it.id == selected }?.name ?: "Ask Bench"
    Column(Modifier.fillMaxWidth().background(c.surface1, RoundedCornerShape(16.dp))) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { open = !open }.padding(horizontal = 16.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Project", fontSize = 12.sp, color = c.muted)
                Text(name, fontSize = 16.sp)
            }
            androidx.compose.material3.Icon(if (open) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown, null, tint = c.muted)
        }
        if (open) {
            androidx.compose.material3.HorizontalDivider(color = c.line)
            if (s.projects.size > 6) androidx.compose.foundation.text.BasicTextField(
                query, { query = it }, singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = c.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                decorationBox = { inner -> androidx.compose.foundation.layout.Box { if (query.isEmpty()) Text("Search projects", fontSize = 15.sp, color = c.muted); inner() } },
            )
            val rows = listOf<Pair<String?, String>>(null to "Ask Bench") + ordered.map { it.id to it.name }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 280.dp)) {
                items(rows.filter { query.isBlank() || it.second.contains(query, ignoreCase = true) }.size) { i ->
                    val (id, label) = rows.filter { query.isBlank() || it.second.contains(query, ignoreCase = true) }[i]
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onPick(id); open = false; query = "" }.padding(horizontal = 16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    ) {
                        Text(label, fontSize = 15.sp, color = if (id == selected) c.accent else c.text, modifier = Modifier.weight(1f))
                        if (id == selected) androidx.compose.material3.Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
        }
    }
}

/** Add project, as its board draws it: clone a repository, a folder on the server, or a new one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddProjectSheet(store: BenchStore, onDismiss: () -> Unit, onAdded: (String) -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    var source by remember { mutableStateOf("clone") }
    var query by remember { mutableStateOf("") }
    var repos by remember { mutableStateOf<List<GitRepo>>(emptyList()) }
    var picked by remember { mutableStateOf<GitRepo?>(null) }
    var name by remember { mutableStateOf("") }
    var folder by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { repos = runCatching { store.api.repos() }.getOrDefault(emptyList()) }
    val matches = repos.filter { query.isBlank() || it.name.contains(query, ignoreCase = true) }.take(4)

    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.statusBarsPadding(), sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface2, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Text("Add project", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Segmented(listOf("clone" to "Clone", "folder" to "Folder", "new" to "New repo"), source, { source = it; error = null })
            when (source) {
                "clone" -> {
                    TonalField(query, { query = it; picked = null }, "Repository", "owner/repo")
                    if (matches.isNotEmpty()) Grouped(matches) { r ->
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { picked = r; query = r.name; name = r.name.substringAfter("/") }.padding(horizontal = Space.lg, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(r.name, fontSize = 15.sp, color = if (picked == r) c.accent else c.text)
                            Text(listOfNotNull(if (r.private) "Private" else "Public", r.updatedAt?.let { "updated ${ai.bench.app.data.Format.ago(it)} ago" }).joinToString(" · "), fontSize = 12.sp, color = c.muted)
                        }
                    }
                    TonalField(name, { name = it }, "Name", "my-app", "Cloned on the server with its GitHub login")
                }
                "folder" -> {
                    TonalField(folder, { folder = it }, "Path on the server", "~/code/my-app", "A git repository that's already there")
                    FolderBrowser(store, folder.ifBlank { null }) { folder = it }
                }
                else -> TonalField(name, { name = it }, "Name", "my-app", "Starts an empty git repository")
            }
            error?.let { Text(it, color = c.bad, fontSize = 13.sp) }
            val ready = when (source) { "clone" -> query.contains("/"); "folder" -> folder.isNotBlank(); else -> name.isNotBlank() }
            BigButton(if (busy) "Adding…" else when (source) { "clone" -> "Clone and add"; "folder" -> "Add"; else -> "Create" }, primary = true, enabled = ready && !busy) {
                busy = true
                error = null
                scope.launch {
                    try {
                        val r = when (source) {
                            "clone" -> store.api.post("/api/projects/clone", buildJsonObject { put("repo", picked?.name ?: query.trim()); if (name.isNotBlank()) put("name", name) })
                            "folder" -> store.api.post("/api/projects", buildJsonObject { put("path", folder.trim()) })
                            else -> store.api.post("/api/projects/create", buildJsonObject { put("name", name.trim()) })
                        }
                        store.refresh()
                        ((r as? kotlinx.serialization.json.JsonObject)?.get("id") as? kotlinx.serialization.json.JsonPrimitive)?.content?.let(onAdded) ?: onDismiss()
                    } catch (e: BenchError) { error = e.message } finally { busy = false }
                }
            }
        }
    }
}

/** Ship…: squash or keep the commits, a message, then Merge, or a pull request instead. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShipSheet(store: BenchStore, taskId: String, base: String, git: TaskGit?, onDone: () -> Unit) {
    val s by store.state.collectAsState()
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    val t = s.tasks.firstOrNull { it.id == taskId }
    var squash by remember { mutableStateOf(true) }
    var message by remember { mutableStateOf(t?.title.orEmpty()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDone, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface2, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("Merge into $base", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            Text("${git?.ahead ?: 0} commits · ${git?.files ?: 0} files · +${git?.added ?: 0} −${git?.removed ?: 0}", fontSize = 13.sp, color = c.muted)
            git?.canMerge?.let { m -> Text(if (m.ok) "No conflicts with $base" else m.reason ?: "Can't merge yet", fontSize = 13.sp, color = if (m.ok) c.good else c.bad) }
            Segmented(listOf("squash" to "Squash", "keep" to "Keep commits"), if (squash) "squash" else "keep", { squash = it == "squash" }, height = 44.dp)
            if (squash) TonalField(message, { message = it }, "Commit message", "What this change does")
            error?.let { Text(it, color = c.bad, fontSize = 13.sp) }
            BigButton(if (busy) "Merging…" else "Merge", primary = true, enabled = !busy && git?.canMerge?.ok != false) {
                busy = true
                scope.launch {
                    try {
                        store.api.taskAction(taskId, "merge", buildJsonObject { put("strategy", if (squash) "squash" else "merge"); if (squash) put("message", message); put("cleanup", true) })
                        onDone()
                    } catch (e: BenchError) { error = e.message } finally { busy = false }
                }
            }
            BigButton("Open a pull request instead", height = 52.dp, enabled = !busy) {
                busy = true
                scope.launch {
                    try { store.api.taskAction(taskId, "pr", buildJsonObject { put("title", t?.title.orEmpty()) }); onDone() }
                    catch (e: BenchError) { error = e.message } finally { busy = false }
                }
            }
        }
    }
}


/** A filled field with its label above and help below, each a fixed step from the field. */
@Composable
fun TonalField(value: String, onChange: (String) -> Unit, label: String, placeholder: String, help: String? = null) {
    val c = Bench.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.muted, modifier = Modifier.padding(start = 6.dp))
        androidx.compose.foundation.text.BasicTextField(
            value, onChange, singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = c.text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).background(c.surface1, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 15.dp),
            decorationBox = { inner -> androidx.compose.foundation.layout.Box { if (value.isEmpty()) Text(placeholder, fontSize = 16.sp, color = c.muted); inner() } },
        )
        help?.let { Text(it, fontSize = 12.5.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp)) }
    }
}

/** Browse the server's folders: tap to go in, "Use" to choose one. Repositories are marked. */
@Composable
private fun FolderBrowser(store: BenchStore, start: String?, onPick: (String) -> Unit) {
    val c = Bench.colors
    var at by remember { mutableStateOf<kotlinx.serialization.json.JsonElement?>(null) }
    var path by remember { mutableStateOf(start) }
    LaunchedEffect(path) {
        at = runCatching { store.api.get("/api/fs" + (path?.let { "?path=" + java.net.URLEncoder.encode(it, "UTF-8") } ?: "")) }.getOrNull()
    }
    val dir = at["path"].str()
    Column(Modifier.fillMaxWidth().background(c.surface1, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            Text(dir?.replace(Regex("^/(home|Users)/[^/]+"), "~") ?: "Reading…", fontSize = 13.sp, color = c.muted, fontFamily = ai.bench.app.ui.theme.Mono, maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.StartEllipsis, modifier = Modifier.weight(1f))
            if (dir != null) Text("Use", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.accent, modifier = Modifier.clickable { onPick(dir) }.padding(start = 12.dp, top = 4.dp, bottom = 4.dp))
        }
        androidx.compose.material3.HorizontalDivider(color = c.line.copy(alpha = 0.5f))
        androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 260.dp)) {
            at["parent"].str()?.let { parent -> item { BrowseRow("..", "Up a folder", c.muted) { path = parent } } }
            val entries = (at["entries"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            items(entries.size) { i ->
                val e = entries[i]
                val name = e["name"].str().orEmpty()
                BrowseRow(name, if (e["repo"].let { it is kotlinx.serialization.json.JsonPrimitive && it.content == "true" }) "Git repository" else null, c.text) { path = "$dir/$name" }
            }
            if (entries.isEmpty() && at != null) item { Text("No folders here.", fontSize = 14.sp, color = c.muted, modifier = Modifier.padding(16.dp)) }
        }
    }
}

@Composable
private fun BrowseRow(name: String, sub: String?, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.Center) {
        Text(name, fontSize = 15.sp, color = color)
        sub?.let { Text(it, fontSize = 12.sp, color = Bench.colors.accent) }
    }
}

/** A prompt about something that keeps happening: "whenever", "every time", "when … is posted". */
private val WHENEVER = Regex("""\b(whenever|every time|each time|when (a |an |the )?(new )?\w+( \w+)? (is|are|gets?) (posted|listed|published|added|released|changed?|updated|announced))\b""", RegexOption.IGNORE_CASE)
