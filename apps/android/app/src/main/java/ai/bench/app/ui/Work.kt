package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bench.app.data.BenchApi
import ai.bench.app.data.BenchError
import ai.bench.app.data.BenchStore
import ai.bench.app.data.DiffFile
import ai.bench.app.data.Format
import ai.bench.app.data.GitState
import ai.bench.app.data.Mode
import ai.bench.app.data.TaskGit
import ai.bench.app.data.get
import ai.bench.app.data.str
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.Mono
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** A small tonal button for the row of actions under a title. */
@Composable
fun Chip(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    val c = Bench.colors
    Box(
        Modifier.height(40.dp).background(c.surface3.copy(alpha = if (enabled) 1f else 0.5f), RoundedCornerShape(20.dp)).clickable(enabled = enabled, onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (enabled) c.text else c.muted, maxLines = 1) }
}

/** A text prompt in a dialog: rename, commit message, what done means. */
@Composable
fun TextDialog(title: String, initial: String, action: String, placeholder: String = "", multiline: Boolean = false, number: Boolean = false, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    val c = Bench.colors
    var v by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.surface2,
        title = { Text(title) },
        text = {
            BasicTextField(
                v, { v = it }, singleLine = !multiline,
                textStyle = TextStyle(fontSize = 16.sp, color = c.text),
                cursorBrush = SolidColor(c.accent),
                keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Number) else KeyboardOptions.Default,
                modifier = Modifier.fillMaxWidth().heightIn(min = if (multiline) 96.dp else 52.dp).background(c.surface1, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 15.dp),
                decorationBox = { inner -> Box { if (v.isEmpty()) Text(placeholder, fontSize = 16.sp, color = c.muted); inner() } },
            )
        },
        confirmButton = { TextButton(onClick = { onDismiss(); onDone(v) }) { Text(action) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun TopRow(onBack: () -> Unit, menu: @Composable (close: () -> Unit) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Box(Modifier.weight(1f))
        Box {
            IconButton(onClick = { open = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert, "More", tint = Bench.colors.muted) }
            DropdownMenu(open, { open = false }, containerColor = Bench.colors.surface2) { menu { open = false } }
        }
    }
}

@Composable
private fun TitleBlock(title: String, sub: @Composable () -> Unit) {
    Column(Modifier.padding(start = 22.dp, end = 16.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, fontSize = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
        sub()
    }
}

// -- Task ------------------------------------------------------------------------------------

@Composable
fun TaskScreen(store: BenchStore, taskId: String, onBack: () -> Unit, onOpen: (String) -> Unit, onTerminal: () -> Unit, onSubagents: () -> Unit) {
    val s by store.state.collectAsState()
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val t = s.tasks.firstOrNull { it.id == taskId }
    var git by remember { mutableStateOf<TaskGit?>(null) }
    var files by remember { mutableStateOf<List<DiffFile>>(emptyList()) }
    var log by remember { mutableStateOf<List<JsonElement>>(emptyList()) }
    var tab by rememberSaveable { mutableStateOf("overview") }
    var openFile by rememberSaveable { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var shipping by remember { mutableStateOf(false) }
    suspend fun load() {
        git = store.api.taskGit(taskId)
        files = runCatching { store.api.taskDiff(taskId) }.getOrDefault(emptyList())
        log = runCatching { store.api.taskLog(taskId) }.getOrDefault(emptyList())
    }
    LaunchedEffect(taskId, t?.updated_at) { load() }
    fun act(label: String, op: suspend () -> Unit) {
        busy = label
        scope.launch {
            try { op() } catch (e: BenchError) { snackbar.showSnackbar(e.message ?: "That didn't work") }
            busy = null; load()
        }
    }
    fun edit(body: JsonObject) = act("Saving…") { store.api.patch("/api/tasks/${BenchApi.enc(taskId)}", body) }
    val sessions = s.sessions.filter { it.task_id == taskId && it.archived_at == null }.sortedBy { it.created_at }
    val base = t?.base_branch?.ifBlank { null } ?: "main"
    val active = t?.state == "active"
    val g = git

    Scaffold(
        containerColor = c.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopRow(onBack) { close ->
                DropdownMenuItem({ Text("Rename") }, onClick = { close(); dialog = "rename" })
                if (active) DropdownMenuItem({ Text("Commit…") }, onClick = { close(); dialog = "commit" })
                DropdownMenuItem({ Text("Subagents") }, onClick = { close(); onSubagents() })
                if (active) DropdownMenuItem({ Text("Close task…", color = c.bad) }, onClick = { close(); dialog = "close" })
                else DropdownMenuItem({ Text("Reopen") }, onClick = { close(); act("Reopening…") { store.api.taskAction(taskId, "reopen") } })
            }
        },
        bottomBar = {
            if (active) Column {
                HorizontalDivider(color = c.line)
                Row(Modifier.fillMaxWidth().background(c.bg).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("Ship…", height = 52.dp, size = 15.sp, fill = false) { shipping = true }
                    BigButton(busy ?: "Merge into $base", Modifier.weight(1f), primary = true, height = 52.dp, size = 15.sp, enabled = busy == null && g?.canMerge?.ok != false && g?.conflicted.isNullOrEmpty()) { shipping = true }
                }
            }
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(bottom = 32.dp)) {
            item("title") {
                TitleBlock(t?.title ?: "Task") {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(when (t?.state) { "merged" -> "Merged into $base ·"; "closed" -> "Closed ·"; else -> "Into $base ·" }, fontSize = 14.sp, color = c.muted)
                        g?.let {
                            Text("+${it.added}", fontSize = 14.sp, color = c.good); Text("−${it.removed}", fontSize = 14.sp, color = c.bad)
                            if (it.ahead > 0) Text("· ${it.ahead} ahead", fontSize = 14.sp, color = c.muted)
                            if (it.behind > 0) Text("· ${it.behind} behind", fontSize = 14.sp, color = c.warn)
                        }
                    }
                }
            }
            if (active) item("actions") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 22.dp, end = 16.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Chip("Push", busy == null) { act("Pushing…") { store.api.taskAction(taskId, "push") } }
                    if ((g?.behind ?: 0) > 0) Chip("Update from $base", busy == null) { act("Updating…") { store.api.taskAction(taskId, "update") } }
                    Chip("Terminal") { onTerminal() }
                }
            }
            item("tabs") {
                Column(Modifier.padding(top = 10.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                        TabButton("Overview", tab == "overview") { tab = "overview" }
                        TabButton(if (files.isEmpty()) "Changes" else "Changes ${files.size}", tab == "changes") { tab = "changes" }
                        TabButton("Log", tab == "log") { tab = "log" }
                    }
                    HorizontalDivider(color = c.line)
                }
            }
            busy?.let { b -> item("busy") { Text(b, fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(start = 22.dp, top = 12.dp)) } }
            when (tab) {
                "overview" -> {
                    val conflicted = g?.conflicted.orEmpty()
                    if (conflicted.isNotEmpty()) {
                        item("conflicts") {
                            Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp).fillMaxWidth().background(c.bad.copy(alpha = 0.10f), RoundedCornerShape(22.dp)).padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text("Merge conflicts", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.bad)
                                conflicted.forEach { Text(it, fontFamily = Mono, fontSize = 13.sp) }
                                Text("Ask Claude to resolve them in this task, or abort and go back to how it was before updating.", fontSize = 13.5.sp, color = c.muted)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    BigButton("Abort", height = 44.dp, size = 14.sp, danger = true, fill = false) { act("Aborting…") { store.api.taskAction(taskId, "abort-merge") } }
                                    BigButton("Ask Claude to resolve", Modifier.weight(1f), primary = true, height = 44.dp, size = 14.sp) {
                                        act("Asking Claude…") {
                                            val ses = sessions.lastOrNull() ?: store.api.createSession(t?.project_id, taskId, null)
                                            store.api.send(ses.id, "Merging $base into this branch left conflicts in: ${conflicted.joinToString(", ")}. Resolve them, keeping both sides' intent, then commit the merge.")
                                            onOpen(ses.id)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    val st = t?.check_status
                    if (t?.check_command != null || st != null) {
                        item("check-l") { Box(Modifier.padding(horizontal = 16.dp)) { SectionLabelV2("Check") } }
                        item("check") {
                            Box(Modifier.padding(horizontal = 16.dp)) {
                                Card {
                                    NavRow(
                                        when (st) { "passed" -> "Check passed"; "failed" -> "Check failed"; "running" -> "Checking…"; else -> "Not run yet" },
                                        if (active) "Run" else null, t?.check_command, valueColor = c.accent,
                                        labelColor = when (st) { "passed" -> c.good; "failed" -> c.bad; else -> c.text }, chevron = false,
                                    ) { if (active) act("Checking…") { store.api.taskAction(taskId, "check") } }
                                    t?.check_output?.takeIf { st == "failed" && it.isNotBlank() }?.let { out ->
                                        Custom { Text(out.lines().takeLast(14).joinToString("\n"), fontFamily = Mono, fontSize = 12.sp, lineHeight = 17.sp, color = c.muted, modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp)) }
                                    }
                                }
                            }
                        }
                    }
                    item("loop-l") { Box(Modifier.padding(horizontal = 16.dp)) { SectionLabelV2("Loop until done") } }
                    item("loop") {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            Card {
                                val l = t?.loop
                                SwitchRow(
                                    "Loop until done",
                                    when (l?.state) { "looping" -> "Attempt ${l.attempts} of ${l.max_attempts}"; "done" -> "Done: the check passed"; "gave-up" -> "Gave up: ${l.reason ?: "the check kept failing"}"; else -> "Reruns the check whenever Claude stops; a failure sends it back" },
                                    l?.state == "looping", enabled = active && !t?.check_command.isNullOrBlank(),
                                ) { on -> edit(buildJsonObject { if (on) putJsonObject("loop") { put("max_attempts", l?.max_attempts?.takeIf { it > 0 } ?: 5); put("max_minutes", l?.max_minutes?.takeIf { it > 0 } ?: 60) } else put("loop", JsonNull) }) }
                                NavRow("Done means", null, t?.done ?: "Say what finished looks like") { dialog = "done" }
                                NavRow("Check command", null, t?.check_command ?: "e.g. npm test") { dialog = "check" }
                                NavRow("Limits", "${l?.max_attempts?.takeIf { it > 0 } ?: 5} attempts · ${l?.max_minutes?.takeIf { it > 0 } ?: 60} min") { dialog = "attempts" }
                            }
                        }
                    }
                    item("ses-l") { Box(Modifier.padding(horizontal = 16.dp)) { SectionLabelV2("Sessions", "${sessions.size}") } }
                    item("ses") {
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            Card {
                                sessions.forEach { x -> NavRow(x.title, Format.ago(x.updated_at), statusLabel(x.status)) { onOpen(x.id) } }
                                if (active) NavRow("New session in this task", labelColor = c.accent, chevron = false) {
                                    act("Starting…") { val n = store.api.createSession(t?.project_id, taskId, null); onOpen(n.id) }
                                }
                            }
                        }
                    }
                }
                "changes" -> {
                    if (files.isEmpty()) item { Text("No changes yet.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(22.dp)) }
                    else item("files") { Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) { Grouped(files) { f -> FileDiff(f, openFile == f.path) { openFile = if (openFile == f.path) null else f.path } } } }
                }
                else -> {
                    if (log.isEmpty()) item { Text("Nothing logged yet. Decisions and reviews appear here.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(22.dp)) }
                    items(log.reversed()) { e ->
                        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().background(c.surface1, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(e["kind"].str()?.replaceFirstChar { it.uppercase() } ?: "Decision", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
                            Text(e["decision"].str().orEmpty(), fontSize = 15.sp, fontWeight = FontWeight.Medium)
                            e["evidence"].str()?.takeIf { it.isNotBlank() }?.let { MarkdownText(it) }
                        }
                    }
                }
            }
        }
    }

    when (dialog) {
        "rename" -> TextDialog("Rename task", t?.title.orEmpty(), "Rename", onDismiss = { dialog = null }) { v -> edit(buildJsonObject { put("title", v) }) }
        "commit" -> TextDialog("Commit in the task", t?.title.orEmpty(), "Commit", "Message", onDismiss = { dialog = null }) { v -> act("Committing…") { store.api.post("/api/tasks/${BenchApi.enc(taskId)}/commit", buildJsonObject { put("message", v) }) } }
        "done" -> TextDialog("Done means", t?.done.orEmpty(), "Save", "What finished looks like", multiline = true, onDismiss = { dialog = null }) { v -> edit(buildJsonObject { put("done", v) }) }
        "check" -> TextDialog("Check command", t?.check_command.orEmpty(), "Save", "npm test", onDismiss = { dialog = null }) { v -> edit(buildJsonObject { put("check", v) }) }
        "attempts" -> TextDialog("Most attempts", (t?.loop?.max_attempts?.takeIf { it > 0 } ?: 5).toString(), "Next", number = true, onDismiss = { dialog = null }) { a ->
            dialog = "minutes:$a"
        }
        "close" -> Confirm("Close this task?", "Its branch and worktree are kept until you remove them; you can reopen it.", "Close task", onDismiss = { dialog = null }) { act("Closing…") { store.api.taskAction(taskId, "close") } }
        else -> dialog?.takeIf { it.startsWith("minutes:") }?.let { d ->
            TextDialog("Most minutes", (t?.loop?.max_minutes?.takeIf { it > 0 } ?: 60).toString(), "Save", number = true, onDismiss = { dialog = null }) { m ->
                val a = d.substringAfter(':').toIntOrNull() ?: 5
                if (t?.loop?.state == "looping") edit(buildJsonObject { putJsonObject("loop") { put("max_attempts", a); put("max_minutes", m.toIntOrNull() ?: 60) } })
                else ai.bench.app.BenchApp.instance.getSharedPreferences("loop", 0).edit().putInt("$taskId.a", a).putInt("$taskId.m", m.toIntOrNull() ?: 60).apply()
            }
        }
    }
    if (shipping) ShipSheet(store, taskId, base, git, onDone = { shipping = false; scope.launch { load() } })
}

@Composable
fun SectionLabelV2(text: String, trailing: String? = null, color: Color = Bench.colors.muted) {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 22.dp, bottom = 10.dp)) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, fontSize = 13.sp, color = color) }
    }
}

@Composable
private fun FileDiff(f: DiffFile, open: Boolean, onToggle: () -> Unit) {
    val c = Bench.colors
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(56.dp).clickable(onClick = onToggle).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Default.Description, null, tint = when (f.status) { "A" -> c.good; "D" -> c.bad; else -> c.warn }, modifier = Modifier.size(16.dp))
            Text(f.path, fontFamily = Mono, fontSize = 13.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.StartEllipsis)
            if (f.added > 0) Text("+${f.added}", fontSize = 12.5.sp, color = c.good)
            if (f.removed > 0) Text("−${f.removed}", fontSize = 12.5.sp, color = c.bad)
        }
        if (open) Column(Modifier.fillMaxWidth().background(c.wellBg).horizontalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
            f.lines.take(500).forEach { l ->
                val (fg, bg) = when {
                    l.startsWith("@@") -> c.muted to Color.Transparent
                    l.startsWith("+") -> c.good to c.good.copy(alpha = 0.08f)
                    l.startsWith("-") -> c.bad to c.bad.copy(alpha = 0.08f)
                    else -> c.wellText to Color.Transparent
                }
                Text(l.ifEmpty { " " }, fontFamily = Mono, fontSize = 11.5.sp, lineHeight = 18.sp, color = fg, softWrap = false, modifier = Modifier.background(bg).padding(horizontal = 16.dp))
            }
        }
    }
}

// -- Project ---------------------------------------------------------------------------------

@Composable
fun ProjectScreen(store: BenchStore, projectId: String, onBack: () -> Unit, onOpen: (String) -> Unit, onTask: (String) -> Unit, onTerminal: () -> Unit, onSubagents: () -> Unit) {
    val s by store.state.collectAsState()
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val p = s.projects.firstOrNull { it.id == projectId }
    var git by remember { mutableStateOf<GitState?>(null) }
    var busy by remember { mutableStateOf<String?>(null) }
    var dialog by remember { mutableStateOf<String?>(null) }
    var newTask by remember { mutableStateOf(false) }
    var showDone by rememberSaveable { mutableStateOf(false) }
    suspend fun load() { git = store.api.projectGit(projectId) }
    LaunchedEffect(projectId) { load() }
    fun act(label: String, op: suspend () -> Unit) {
        busy = label
        scope.launch {
            try { op() } catch (e: BenchError) { snackbar.showSnackbar(e.message ?: "That didn't work") }
            busy = null; load()
        }
    }
    val g = git
    val tasks = s.tasks.filter { it.project_id == projectId && it.state == "active" }.sortedByDescending { it.updated_at }
    val done = s.tasks.filter { it.project_id == projectId && it.state != "active" }.sortedByDescending { it.updated_at }
    val loose = s.sessions.filter { it.project_id == projectId && it.task_id == null && it.archived_at == null }.sortedByDescending { it.updated_at }

    Scaffold(
        containerColor = c.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopRow(onBack) { close ->
                DropdownMenuItem({ Text("Rename") }, onClick = { close(); dialog = "rename" })
                DropdownMenuItem({ Text("Subagents") }, onClick = { close(); onSubagents() })
                DropdownMenuItem({ Text("Remove project…", color = c.bad) }, onClick = { close(); dialog = "remove" })
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { newTask = true }, icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New task", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent) },
                containerColor = c.accent, contentColor = c.onAccent, shape = RoundedCornerShape(18.dp),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 112.dp)) {
            item("title") {
                Column(Modifier.padding(start = 6.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(p?.name ?: "Project", fontSize = 28.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.3).sp)
                    Text(
                        listOfNotNull(g?.branch?.let { "On $it" }, g?.dirty?.size?.takeIf { it > 0 }?.let { "$it changed" }, g?.behind?.takeIf { it > 0 }?.let { "$it behind" }, g?.ahead?.takeIf { it > 0 }?.let { "$it ahead" })
                            .joinToString(" · ").ifEmpty { "Reading the repository…" },
                        fontSize = 14.sp, color = c.muted,
                    )
                }
            }
            item("actions") {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 6.dp, top = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (g?.hasRemote != false) Chip(if ((g?.behind ?: 0) > 0) "Pull ↓${g?.behind}" else "Pull", busy == null) { act("Pulling…") { store.api.projectAction(projectId, "pull") } }
                    if ((g?.ahead ?: 0) > 0) Chip("Push ↑${g?.ahead}", busy == null) { act("Pushing…") { store.api.projectAction(projectId, "push") } }
                    if (g?.dirty?.isNotEmpty() == true) Chip("Commit…", busy == null) { dialog = "commit" }
                    Chip("Switch branch", busy == null) { dialog = "branch" }
                    Chip("Terminal") { onTerminal() }
                }
                busy?.let { Text(it, fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 10.dp)) }
            }
            if (tasks.isNotEmpty()) {
                item("tl") { SectionLabelV2("Tasks", "${tasks.size}") }
                item("tasks") {
                    Grouped(tasks) { t ->
                        val ses = s.sessions.filter { it.task_id == t.id }.maxByOrNull { it.updated_at }
                        val (line, color) = taskLine(t, ses, s.approvals.any { it.session_id == ses?.id })
                        ListRow(t.title, t.branch, ses?.status ?: "idle", line, color) { onTask(t.id) }
                    }
                }
            }
            if (loose.isNotEmpty()) {
                item("sl") { SectionLabelV2("Sessions in the checkout", "${loose.size}") }
                item("loose") { Grouped(loose) { r -> ListRow(r.title, Format.ago(r.updated_at), r.status) { onOpen(r.id) } } }
            }
            if (done.isNotEmpty()) {
                item("dl") {
                    Text("${if (showDone) "⌄" else "›"}  Merged and closed · ${done.size}", fontSize = 13.5.sp, color = c.muted,
                        modifier = Modifier.padding(start = 6.dp, top = 22.dp, bottom = 10.dp).clickable { showDone = !showDone })
                }
                if (showDone) item("done") { Grouped(done) { t -> ListRow(t.title, t.branch, "idle", if (t.state == "merged") "Merged" else "Closed") { onTask(t.id) } } }
            }
            if (s.ready && tasks.isEmpty() && loose.isEmpty() && done.isEmpty()) item("empty") {
                Text("No work here yet. A task works on its own branch, so ${g?.branch ?: "main"} stays clean.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 28.dp))
            }
        }
    }

    when (dialog) {
        "rename" -> TextDialog("Rename project", p?.name.orEmpty(), "Rename", onDismiss = { dialog = null }) { v ->
            act("Renaming…") { store.api.patch("/api/projects/${BenchApi.enc(projectId)}", buildJsonObject { put("name", v) }); store.refresh() }
        }
        "commit" -> TextDialog("Commit ${g?.dirty?.size ?: 0} file${if (g?.dirty?.size == 1) "" else "s"} to ${g?.branch ?: "main"}", "", "Commit", "Message", onDismiss = { dialog = null }) { v ->
            act("Committing…") { store.api.post("/api/projects/${BenchApi.enc(projectId)}/commit", buildJsonObject { put("message", v) }) }
        }
        "remove" -> Confirm("Remove ${p?.name}?", "Bench forgets the project and its sessions. The folder on the server stays where it is.", "Remove", onDismiss = { dialog = null }) {
            scope.launch { runCatching { store.api.delete("/api/projects/${BenchApi.enc(projectId)}") }; store.refresh(); onBack() }
        }
        "branch" -> BranchSheet(g, onDismiss = { dialog = null }) { name, create ->
            dialog = null
            act("Switching…") {
                store.api.post("/api/projects/${BenchApi.enc(projectId)}/checkout", buildJsonObject {
                    put("branch", name); if (create) put("create", true); if (g?.dirty?.isNotEmpty() == true) put("stash", true)
                })
            }
        }
    }
    if (newTask) NewTaskSheet(store, projectId, g?.branch ?: "main", onDismiss = { newTask = false }) { sid -> newTask = false; onOpen(sid) }
}

/** Switch branch: search or type a new name; uncommitted files are stashed first, and it says so. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BranchSheet(g: GitState?, onDismiss: () -> Unit, onPick: (String, Boolean) -> Unit) {
    val c = Bench.colors
    var q by remember { mutableStateOf("") }
    val list = g?.branches.orEmpty().filter { q.isBlank() || it.name.contains(q, true) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface2, shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.surface3, RoundedCornerShape(2.dp))) }) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Switch branch", fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp))
            BasicTextField(
                q, { q = it }, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = c.text), cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).background(c.surface1, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp, vertical = 14.dp),
                decorationBox = { inner -> Box { if (q.isEmpty()) Text("Search or new branch name", fontSize = 15.sp, color = c.muted); inner() } },
            )
            Column(Modifier.heightIn(max = 340.dp).verticalScroll(rememberScrollState())) {
                Card {
                    if (q.isNotBlank() && g?.branches.orEmpty().none { it.name == q.trim() }) NavRow("Create “${q.trim()}”", null, "From ${g?.branch ?: "the current branch"}", labelColor = c.accent, chevron = false) { onPick(q.trim(), true) }
                    list.forEach { b ->
                        NavRow(b.name, if (b.current) "Current" else null, if (b.remoteOnly) "Remote only" else null, valueColor = c.accent, labelColor = if (b.current) c.accent else null, chevron = false) { if (!b.current) onPick(b.name, false) }
                    }
                }
            }
            if (g?.dirty?.isNotEmpty() == true) Text(
                "${g.dirty.size} file${if (g.dirty.size == 1) " has" else "s have"} uncommitted changes. Bench stashes them before switching.",
                fontSize = 13.5.sp, lineHeight = 20.sp, modifier = Modifier.fillMaxWidth().background(c.warn.copy(alpha = 0.10f), RoundedCornerShape(18.dp)).padding(16.dp),
            )
        }
    }
}

/** New task: what to do first, then branch-from, done means and check; Start makes the branch and the first session. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NewTaskSheet(store: BenchStore, projectId: String, branch: String, onDismiss: () -> Unit, onStarted: (String) -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    val s by store.state.collectAsState()
    var prompt by remember { mutableStateOf("") }
    var title by remember { mutableStateOf("") }
    var from by remember { mutableStateOf(branch) }
    var done by remember { mutableStateOf("") }
    var check by remember { mutableStateOf(s.projects.firstOrNull { it.id == projectId }?.check_command.orEmpty()) }
    var mode by remember { mutableStateOf(Mode.of(s.defaults.mode)) }
    var picking by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface2, shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.surface3, RoundedCornerShape(2.dp))) }) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, bottom = 22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("New task", fontSize = 22.sp, fontWeight = FontWeight.Medium)
            BasicTextField(
                prompt, { prompt = it }, textStyle = TextStyle(fontSize = 17.sp, lineHeight = 24.sp, color = c.text), cursorBrush = SolidColor(c.accent),
                modifier = Modifier.fillMaxWidth().heightIn(min = 130.dp).background(c.surface1, RoundedCornerShape(20.dp)).padding(16.dp),
                decorationBox = { inner -> Box { if (prompt.isEmpty()) Text("What should Claude do?", fontSize = 17.sp, color = c.muted); inner() } },
            )
            TonalField(title, { title = it }, "Title", "From the first line if left empty")
            TonalField(from, { from = it }, "Branch from", "main")
            TonalField(done, { done = it }, "Done means", "Optional: what finished looks like")
            TonalField(check, { check = it }, "Check", "Optional: e.g. npm test", "Bench runs it in the task's worktree to check the work")
            Card { NavRow("Mode", mode.label) { picking = true } }
            error?.let { Text(it, color = c.bad, fontSize = 13.sp) }
            BigButton(if (busy) "Starting…" else "Start task", primary = true, height = 56.dp, enabled = !busy && prompt.isNotBlank()) {
                busy = true; error = null
                scope.launch {
                    try {
                        val r = store.api.post("/api/tasks", buildJsonObject {
                            put("projectId", projectId); put("prompt", prompt.trim())
                            put("title", title.trim().ifEmpty { prompt.trim().lineSequence().first().take(80) })
                            if (from.isNotBlank()) put("baseBranch", from.trim()); put("mode", mode.wire)
                        })
                        val taskId = r["task_id"].str() ?: r["task"]["id"].str()
                        if (taskId != null && (done.isNotBlank() || check.isNotBlank())) runCatching {
                            store.api.patch("/api/tasks/${BenchApi.enc(taskId)}", buildJsonObject { if (done.isNotBlank()) put("done", done.trim()); if (check.isNotBlank()) put("check", check.trim()) })
                        }
                        store.refresh()
                        val sid = r["id"].str()?.takeIf { r["task_id"].str() != null } ?: r["session"]["id"].str()
                            ?: store.state.value.sessions.firstOrNull { it.task_id == taskId }?.id
                        if (sid != null) onStarted(sid) else onDismiss()
                    } catch (e: BenchError) { error = e.message } finally { busy = false }
                }
            }
        }
    }
    if (picking) PickDialog("Mode", Mode.entries.map { it.wire to it.label }, mode.wire, onDismiss = { picking = false }) { mode = Mode.of(it) }
}

