package ai.bench.app.ui

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
fun NewSessionSheet(store: BenchStore, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val s by store.state.collectAsState()
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    val recent = s.sessions.maxByOrNull { it.updated_at }?.project_id
    var projectId by remember { mutableStateOf(recent ?: s.projects.firstOrNull()?.id) }
    var where by remember { mutableStateOf("task") }
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

    ModalBottomSheet(
        onDismissRequest = onDismiss,
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
                providers.filter { it.enabled && it.ready }.forEach { p ->
                    Text(p.name, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp))
                    p.models.filter { it.enabled }.forEach { m -> Choice(m.label, m.id == modelId) { modelId = m.id; providerId = p.id; page = null } }
                }
                Text("Effort", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 16.dp, bottom = 8.dp))
                Segmented(listOf("" to "Auto") + listOf("low", "medium", "high", "max").map { it to ai.bench.app.data.Effort.of(it)!!.label }, effort ?: "", { effort = it.ifEmpty { null } })
            }
            "mode" -> Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                SheetBack("Mode") { page = null }
                Mode.entries.forEach { m -> Choice(m.label, m == mode) { mode = m; page = null } }
            }
            else -> Column(
                Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 22.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("New session", fontSize = 22.sp, fontWeight = FontWeight.Medium)
                androidx.compose.foundation.text.BasicTextField(
                    prompt, { prompt = it },
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 17.sp, lineHeight = 24.sp, color = c.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp).background(c.surface1, RoundedCornerShape(20.dp)).padding(16.dp).focusRequester(focus),
                    decorationBox = { inner -> androidx.compose.foundation.layout.Box { if (prompt.isEmpty()) Text("What should Claude do?", fontSize = 17.sp, color = c.muted); inner() } },
                )
                Column(Modifier.fillMaxWidth().background(c.surface1, RoundedCornerShape(20.dp)).clip(RoundedCornerShape(20.dp))) {
                    Field("Project", s.projects.firstOrNull { it.id == projectId }?.name ?: "Ask Bench") { page = "project" }
                    androidx.compose.material3.HorizontalDivider(color = c.line.copy(alpha = 0.5f), modifier = Modifier.padding(start = 16.dp))
                    Field("Model", listOfNotNull(modelName, effort?.let { ai.bench.app.data.Effort.of(it)?.label }).joinToString(" · ")) { page = "model" }
                    androidx.compose.material3.HorizontalDivider(color = c.line.copy(alpha = 0.5f), modifier = Modifier.padding(start = 16.dp))
                    Field("Mode", mode.label) { page = "mode" }
                }
                if (projectId != null) {
                    Segmented(listOf("task" to "New branch", "checkout" to "Checkout"), where, { where = it })
                    Text(if (where == "task") "Works on its own branch from $base, so $base stays clean." else "Works directly in the project's checked-out branch.",
                        fontSize = 12.5.sp, color = c.muted, modifier = Modifier.padding(horizontal = 6.dp))
                } else Text("A conversation about Bench itself: what's running, what it costs, starting work anywhere.", fontSize = 12.5.sp, color = c.muted, modifier = Modifier.padding(horizontal = 6.dp))
                error?.let { Text(it, color = c.bad, fontSize = 13.sp) }
                BigButton(if (busy) "Starting…" else "Start", primary = true, height = 56.dp, enabled = !busy && (projectId == null || where == "checkout" || prompt.isNotBlank())) {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val pid = projectId
                            val text = prompt.trim()
                            val session = if (pid != null && where == "task") store.api.createTask(pid, text.lineSequence().first().take(80), text, mode.wire)
                            else store.api.createSession(pid, null, mode.wire)
                            if (modelId != null || effort != null) runCatching {
                                store.api.patch("/api/sessions/${ai.bench.app.data.BenchApi.enc(session.id)}", buildJsonObject {
                                    modelId?.let { put("modelId", it) }; providerId?.let { put("providerId", it) }; effort?.let { put("effort", it) }
                                })
                            }
                            if (!(pid != null && where == "task") && text.isNotEmpty()) store.api.send(session.id, text)
                            onCreated(session.id)
                        } catch (e: BenchError) { error = e.message } finally { busy = false }
                    }
                }
            }
        }
    }
    LaunchedEffect(Unit) { kotlinx.coroutines.delay(250); runCatching { focus.requestFocus() } }
}

@Composable
private fun Field(label: String, value: String, onClick: () -> Unit) {
    val c = Bench.colors
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable(onClick = onClick).padding(horizontal = 16.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = c.muted, modifier = Modifier.width(76.dp))
        Text(value, fontSize = 15.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.muted)
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
        if (on) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun SheetBack(title: String, onBack: () -> Unit) {
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, modifier = Modifier.padding(bottom = 6.dp)) {
        androidx.compose.material3.IconButton(onClick = onBack) { androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.AutoMirrored.Filled.ArrowBack, "Back") }
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
                androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Search, null, tint = c.muted, modifier = Modifier.size(18.dp))
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
                        if (id == selected) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
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
            androidx.compose.material3.Icon(if (open) androidx.compose.material.icons.Icons.Default.KeyboardArrowUp else androidx.compose.material.icons.Icons.Default.KeyboardArrowDown, null, tint = c.muted)
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
                        if (id == selected) androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface2, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
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
