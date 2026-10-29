package ai.bench.app.ui

import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.foundation.clickable
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

/** New session, as its board draws it: project chips, New task or Checkout, the first message, Start. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NewSessionSheet(store: BenchStore, onDismiss: () -> Unit, onCreated: (String) -> Unit) {
    val s by store.state.collectAsState()
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    var projectId by remember { mutableStateOf(s.projects.firstOrNull()?.id) }
    var where by remember { mutableStateOf("task") }
    var prompt by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val mode = Mode.of(s.defaults.mode ?: "acceptEdits")
    val base = s.tasks.firstOrNull { it.project_id == projectId }?.base_branch?.ifBlank { null } ?: "main"

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface2, shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)) {
        Column(
            Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("New session", fontSize = 22.sp)
            ProjectPicker(s, projectId) { projectId = it }
            if (projectId != null) Segmented(listOf("task" to "New task", "checkout" to "Checkout"), where, { where = it }, height = 44.dp)
            OutlinedTextField(
                prompt, { prompt = it }, Modifier.fillMaxWidth().heightIn(min = 140.dp),
                placeholder = { Text("What should Claude do?", fontSize = 16.sp) },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = c.text),
                shape = RoundedCornerShape(16.dp), colors = fieldColors(),
            )
            Text(
                when {
                    projectId == null -> "A conversation about all of Bench"
                    where == "task" -> "New branch from $base · ${mode.label}"
                    else -> "Works in the checkout · ${mode.label}"
                },
                fontSize = 13.sp, color = c.muted,
            )
            error?.let { Text(it, color = c.bad, fontSize = 13.sp) }
            BigButton(if (busy) "Starting…" else "Start", primary = true, enabled = !busy && (projectId == null || where == "checkout" || prompt.isNotBlank())) {
                busy = true
                error = null
                scope.launch {
                    try {
                        val pid = projectId
                        val text = prompt.trim()
                        val session = if (pid != null && where == "task") {
                            store.api.createTask(pid, text.lineSequence().first().take(80), text, mode.wire)
                        } else {
                            store.api.createSession(pid, null, mode.wire).also { if (text.isNotEmpty()) store.api.send(it.id, text) }
                        }
                        onCreated(session.id)
                    } catch (e: BenchError) {
                        error = e.message
                    } finally { busy = false }
                }
            }
        }
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
                    OutlinedTextField(query, { query = it; picked = null }, Modifier.fillMaxWidth(), label = { Text("Repository") }, placeholder = { Text("owner/repo") },
                        singleLine = true, shape = RoundedCornerShape(12.dp), colors = fieldColors())
                    if (matches.isNotEmpty()) Grouped(matches) { r ->
                        Column(
                            Modifier.fillMaxWidth().heightIn(min = 56.dp).clickable { picked = r; query = r.name; name = r.name.substringAfter("/") }.padding(horizontal = Space.lg, vertical = 8.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(r.name, fontSize = 15.sp, color = if (picked == r) c.accent else c.text)
                            Text(listOfNotNull(if (r.private) "Private" else "Public", r.updatedAt?.let { "updated ${ai.bench.app.data.Format.ago(it)} ago" }).joinToString(" · "), fontSize = 12.sp, color = c.muted)
                        }
                    }
                    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = fieldColors(),
                        supportingText = { if (name.isNotBlank()) Text("Cloned on the server with its GitHub login") })
                }
                "folder" -> OutlinedTextField(folder, { folder = it }, Modifier.fillMaxWidth(), label = { Text("Path on the server") }, placeholder = { Text("~/code/my-app") },
                    singleLine = true, shape = RoundedCornerShape(12.dp), colors = fieldColors(), supportingText = { Text("A git repository that's already there") })
                else -> OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true, shape = RoundedCornerShape(12.dp), colors = fieldColors(),
                    supportingText = { Text("Starts an empty git repository") })
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
            Text("Merge into $base", fontSize = 22.sp)
            Text("${git?.ahead ?: 0} commits · ${git?.files ?: 0} files · +${git?.added ?: 0} −${git?.removed ?: 0}", fontSize = 13.sp, color = c.muted)
            git?.canMerge?.let { m -> Text(if (m.ok) "No conflicts with $base" else m.reason ?: "Can't merge yet", fontSize = 13.sp, color = if (m.ok) c.good else c.bad) }
            Segmented(listOf("squash" to "Squash", "keep" to "Keep commits"), if (squash) "squash" else "keep", { squash = it == "squash" }, height = 44.dp)
            if (squash) OutlinedTextField(message, { message = it }, Modifier.fillMaxWidth(), label = { Text("Commit message") }, shape = RoundedCornerShape(16.dp), colors = fieldColors())
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
            BigButton("Open pull request instead", height = 48.dp, enabled = !busy) {
                busy = true
                scope.launch {
                    try { store.api.taskAction(taskId, "pr", buildJsonObject { put("title", t?.title.orEmpty()) }); onDone() }
                    catch (e: BenchError) { error = e.message } finally { busy = false }
                }
            }
        }
    }
}

