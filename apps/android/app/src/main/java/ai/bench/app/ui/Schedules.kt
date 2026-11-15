package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.automirrored.outlined.ManageSearch
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimeInput
import androidx.compose.material3.TimePickerDefaults
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bench.app.data.BenchError
import ai.bench.app.data.BenchStore
import ai.bench.app.data.Effort
import ai.bench.app.data.ProviderInfo
import ai.bench.app.data.pageName
import ai.bench.app.ui.theme.Bench
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sessions that start on their own: "every Monday at 8:00, look for new hackathons". What
 * they make lands on Home under Ready for you. New ones start from Home's New menu.
 */
@Composable
fun SchedulesScreen(store: BenchStore, onBack: () -> Unit, onEdit: (String?) -> Unit) {
    val list by store.schedules.collectAsState()
    val s by store.state.collectAsState()
    LaunchedEffect(Unit) { store.loadExtras() }
    Page("Schedules", "Sessions that start on their own, at a time or when a page changes", onBack, actions = {
        IconButton(onClick = { onEdit(null) }) { Icon(Icons.Default.Add, "New schedule") }
    }) {
        if (list.isEmpty()) item("empty") {
            Column(Modifier.padding(start = 6.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Nothing scheduled", fontSize = 15.sp, color = Bench.colors.muted)
                BigButton("New schedule", primary = true, height = 48.dp, fill = false) { onEdit(null) }
            }
        } else {
            section("Schedules")
            item("list") {
                Card {
                    list.sortedWith(compareBy({ !it.enabled }, { it.next_run_at ?: "~" })).forEach { sc ->
                        val project = s.projects.firstOrNull { it.id == sc.project_id }?.name
                        val sub = sc.watch?.let { "Watching " + pageName(it.url) } ?: project
                        NavRow(sc.title, if (!sc.enabled) "Off" else if (sc.watch != null) "When it changes" else "${scheduleDays(sc.days)} · ${sc.time}", sub) { onEdit(sc.id) }
                    }
                }
            }
        }
    }
}

/** New schedule, or one being changed: as the "Schedule, from the menu" board draws it. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(store: BenchStore, id: String?, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val c = Bench.colors
    val st by store.state.collectAsState()
    val all by store.schedules.collectAsState()
    val existing = remember(id) { id?.let { i -> all.firstOrNull { it.id == i } } }
    // From a session's Keep watching: its prompt, kept on one board.
    val prefill = remember(id) { if (id == null) store.schedulePrefill.also { store.schedulePrefill = null } else null }
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(existing?.title ?: prefill?.title.orEmpty()) }
    var prompt by remember { mutableStateOf(existing?.prompt ?: prefill?.prompt.orEmpty()) }
    var days by remember { mutableStateOf(existing?.days?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: setOf(1)) }
    var time by remember { mutableStateOf(existing?.time ?: "08:00") }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var keepBoard by remember { mutableStateOf(existing?.keep_board ?: (prefill != null)) }
    var watching by remember { mutableStateOf(existing?.watch != null) }
    var url by remember { mutableStateOf(existing?.watch?.url.orEmpty()) }
    var everyHours by remember { mutableStateOf(existing?.watch?.every_hours ?: 6) }
    var projectId by remember { mutableStateOf(existing?.project_id ?: prefill?.projectId) }
    var modelId by remember { mutableStateOf(existing?.model_id) }
    var providerId by remember { mutableStateOf(existing?.provider_id) }
    var effort by remember { mutableStateOf(existing?.effort) }
    var providers by remember { mutableStateOf<List<ProviderInfo>>(emptyList()) }
    var picking by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // A new schedule goes to the Research project unless you pick another; it is made on first use.
    LaunchedEffect(Unit) {
        providers = runCatching { store.api.providers() }.getOrDefault(emptyList())
        if (projectId == null) projectId = runCatching { store.api.research().id }.getOrNull().also { store.refresh() }
    }
    val projectName = st.projects.firstOrNull { it.id == projectId }?.name ?: "Research"
    val modelName = listOfNotNull(
        providers.flatMap { it.models }.firstOrNull { it.id == modelId }?.label ?: "Default model",
        effort?.let { Effort.of(it)?.label },
    ).joinToString(" · ")
    val ready = !busy && projectId != null && title.isNotBlank() && prompt.isNotBlank() && (if (watching) url.isNotBlank() else days.isNotEmpty())

    fun save() {
        val pid = projectId ?: return
        busy = true; error = null
        scope.launch {
            try {
                store.api.saveSchedule(existing?.id, buildJsonObject {
                    put("title", title.trim()); put("prompt", prompt.trim()); put("projectId", pid)
                    if (watching) {
                        put("watch", buildJsonObject { put("url", url.trim().let { if (it.contains("://")) it else "https://$it" }); put("everyHours", everyHours) })
                    } else {
                        put("watch", JsonNull); put("days", days.sorted().joinToString(",")); put("time", time)
                    }
                    put("enabled", enabled); put("keepBoard", keepBoard)
                    if (modelId != null) put("modelId", modelId) else put("modelId", JsonNull)
                    if (providerId != null) put("providerId", providerId) else put("providerId", JsonNull)
                    if (effort != null) put("effort", effort) else put("effort", JsonNull)
                    if (existing == null) put("tz", java.time.ZoneId.systemDefault().id)
                })
                store.loadExtras()
                onBack()
            } catch (e: BenchError) { error = e.message } finally { busy = false }
        }
    }

    Page(if (existing == null) "New schedule" else "Edit schedule", null, onBack, bottom = {
        Box(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp)) {
            BigButton(if (existing == null) "Save" else "Save changes", primary = true, enabled = ready) { save() }
        }
    }) {
        item("fields") {
            Column(Modifier.padding(top = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Segmented(listOf("time" to "At a time", "page" to "When a page changes"), if (watching) "page" else "time", { watching = it == "page" })
                Input(title, { title = it }, if (watching) "Name, like Lagos roles" else "Name, like Hackathon scan", single = true)
                if (watching) {
                    Input(url, { url = it }, "Page to watch, like linkedin.com/jobs", single = true)
                    Input(prompt, { prompt = it }, "Tell me when… a role in Lagos is posted", single = false)
                } else {
                    Input(prompt, { prompt = it }, "What to do each time: find, compare, draft…", single = false)
                    Text("Days", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(start = 4.dp, top = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        // Monday first, as most calendars outside the US show it.
                        listOf(1, 2, 3, 4, 5, 6, 0).forEach { d ->
                            val on = d in days
                            Box(
                                Modifier.size(40.dp).clip(CircleShape).background(if (on) c.accent else c.surface3).clickable { days = if (on) days - d else days + d },
                                contentAlignment = Alignment.Center,
                            ) { Text("SMTWTFS"[d].toString(), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) c.onAccent else c.text) }
                        }
                    }
                }
            }
        }
        item("rows") {
            Column(Modifier.padding(top = 12.dp)) {
                Card {
                    if (watching) NavRow("Check every", hoursLabel(everyHours), "Bench only tells you when the change matters") { picking = "every" }
                    else NavRow("Time", time) { picking = "time" }
                    NavRow("Project", projectName) { picking = "project" }
                    NavRow("Model", modelName) { picking = "model" }
                    SwitchRow("Keep one board", "Each run updates it and marks what is new", keepBoard) { keepBoard = it }
                    if (existing != null) SwitchRow("On", if (watching) null else existing.next_run_at?.let { "Next: " + it.take(10) }, enabled) { enabled = it }
                }
                error?.let { Text(it, fontSize = 14.sp, color = c.bad, modifier = Modifier.padding(start = 6.dp, top = 8.dp)) }
            }
        }
        if (existing != null) item("actions") {
            Row(Modifier.padding(top = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Run now", Modifier.weight(1f), height = 48.dp, enabled = !busy) {
                    scope.launch { runCatching { store.api.runSchedule(existing.id) }.onSuccess { onOpen(it.id) }.onFailure { error = it.message } }
                }
                BigButton("Delete…", Modifier.weight(1f), height = 48.dp, danger = true, enabled = !busy) { confirmDelete = true }
            }
        }
    }

    when (picking) {
        "every" -> PickDialog("Check every", listOf(1, 3, 6, 12, 24).map { it.toString() to hoursLabel(it) }, everyHours.toString(), onDismiss = { picking = null }) { everyHours = it.toInt(); picking = null }
        "project" -> PickDialog("Project", st.projects.sortedBy { it.name.lowercase() }.map { it.id to it.name }, projectId.orEmpty(), onDismiss = { picking = null }) { projectId = it; picking = null }
        "model" -> {
            val opts = listOf("" to "Default model") + providers.filter { it.enabled && it.ready }.flatMap { p -> p.models.filter { it.enabled }.map { "${p.id}|${it.id}" to "${it.label} · ${p.name}" } }
            PickDialog("Model", opts, if (modelId == null) "" else "${providerId.orEmpty()}|$modelId", onDismiss = { picking = null }) { v ->
                if (v.isEmpty()) { modelId = null; providerId = null } else { providerId = v.substringBefore('|'); modelId = v.substringAfter('|') }
                picking = "effort"
            }
        }
        "effort" -> PickDialog("Effort", listOf("" to "Auto") + listOf("low", "medium", "high", "max").map { it to Effort.of(it)!!.label }, effort.orEmpty(), onDismiss = { picking = null }) {
            effort = it.ifEmpty { null }; picking = null
        }
        "time" -> {
            val (h, m) = time.split(':').map { it.toIntOrNull() ?: 0 }
            val tp = rememberTimePickerState(h, m, is24Hour = true)
            AlertDialog(
                onDismissRequest = { picking = null }, containerColor = c.surface2,
                confirmButton = { TextButton(onClick = { time = "%02d:%02d".format(tp.hour, tp.minute); picking = null }) { Text("Done") } },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel", color = c.muted) } },
                text = { TimeInput(tp, colors = TimePickerDefaults.colors(timeSelectorSelectedContainerColor = c.accent, timeSelectorSelectedContentColor = c.onAccent)) },
            )
        }
    }
    if (confirmDelete && existing != null) AlertDialog(
        onDismissRequest = { confirmDelete = false }, containerColor = c.surface2,
        title = { Text("Delete “${existing.title}”?") },
        text = { Text("It stops running. Sessions it already started stay.", color = c.muted) },
        confirmButton = { TextButton(onClick = { scope.launch { runCatching { store.api.deleteSchedule(existing.id) }; store.loadExtras(); onBack() } }) { Text("Delete", color = c.bad) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = c.muted) } },
    )
}

/** "hour", "6 hours", "day". */
fun hoursLabel(h: Int): String = when (h) { 1 -> "hour"; 24 -> "day"; else -> "$h hours" }

/** When a watch last looked at its page: "Checked 2h ago", or "First look soon". */
fun checkedLabel(sch: ai.bench.app.data.Schedule): String =
    sch.checked_at?.let { "Checked ${ai.bench.app.data.Format.ago(it).let { a -> if (a == "now") "just now" else "$a ago" }}" } ?: "First look soon"

@Composable
private fun Input(value: String, onChange: (String) -> Unit, hint: String, single: Boolean) {
    val c = Bench.colors
    BasicTextField(
        value, onChange, singleLine = single,
        textStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = c.text),
        cursorBrush = SolidColor(c.accent),
        modifier = Modifier.fillMaxWidth().heightIn(min = if (single) 52.dp else 110.dp, max = 220.dp).background(c.surface1, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 15.dp),
        decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, fontSize = 16.sp, color = c.muted); inner() } },
    )
}

/** Home's New: one button, a sheet of what you can start (the "New opens a menu" board). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewMenu(onDismiss: () -> Unit, recipes: List<ai.bench.app.data.Recipe> = emptyList(), onRecipe: (ai.bench.app.data.Recipe) -> Unit = {}, onPick: (String) -> Unit) {
    val c = Bench.colors
    androidx.compose.material3.ModalBottomSheet(
        onDismissRequest = onDismiss, containerColor = c.surface2,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.surface3, RoundedCornerShape(2.dp))) },
    ) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("New", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, top = 4.dp, bottom = 8.dp))
            listOf(
                Triple("session", Icons.Outlined.ChatBubbleOutline, "New session" to "Ask, or work in a project"),
                Triple("research", Icons.AutoMirrored.Outlined.ManageSearch, "Research" to "Find and compare; a report to read later"),
                Triple("schedule", Icons.Outlined.Schedule, "Schedule" to "Runs at a time, or when a page changes"),
                Triple("project", Icons.Outlined.Folder, "Project" to "Clone a repo, open a folder or start one"),
            ).forEach { (key, icon, text) ->
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable { onPick(key) }.padding(horizontal = 6.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(48.dp).background(c.surface3, RoundedCornerShape(14.dp)), contentAlignment = Alignment.Center) { Icon(icon, null, tint = c.text, modifier = Modifier.size(22.dp)) }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(text.first, fontSize = 16.5.sp, fontWeight = FontWeight.Medium)
                        Text(text.second, fontSize = 13.5.sp, color = c.muted)
                    }
                }
            }
            // Saved prompts with blanks: under the four, so the four never move.
            if (recipes.isNotEmpty()) {
                Text("Your recipes", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp))
                Column(Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                    recipes.forEach { r ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onRecipe(r) }.heightIn(min = 52.dp).padding(horizontal = 6.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(r.title, fontSize = 15.5.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                                if (r.blanks.isNotEmpty()) Text(r.blanks.joinToString(" · "), fontSize = 12.5.sp, color = c.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}


/** A schedule's own page: what it keeps, its runs and its prompt (the schedule page board). */
@Composable
fun SchedulePage(store: BenchStore, id: String, onBack: () -> Unit, onEdit: () -> Unit, onOpen: (String) -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    val st by store.state.collectAsState()
    var detail by remember { mutableStateOf<ai.bench.app.data.ScheduleDetail?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var viewing by remember { mutableStateOf(false) }
    LaunchedEffect(id) { runCatching { store.api.schedule(id) }.onSuccess { detail = it }.onFailure { error = it.message } }
    val d = detail
    val sch = d?.schedule
    val project = st.projects.firstOrNull { it.id == sch?.project_id }?.name
    Page(sch?.title ?: "Schedule", sch?.let { listOfNotNull(it.watch?.let { w -> "Watching " + pageName(w.url) } ?: "${scheduleDays(it.days)} at ${it.time}", project).joinToString(" · ") }, onBack, bottom = {
        if (sch != null) Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Edit", Modifier.weight(1f), onClick = onEdit)
            BigButton("Run now", Modifier.weight(1f), primary = true) {
                scope.launch { runCatching { store.api.runSchedule(sch.id) }.onSuccess { onOpen(it.id) }.onFailure { error = it.message } }
            }
        }
    }) {
        if (d == null || sch == null) {
            item("loading") { Text(error ?: "", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 24.dp)) }
            return@Page
        }
        item("state") {
            Text(
                if (!sch.enabled) "Off" else if (sch.watch != null) "On · ${checkedLabel(sch).replaceFirstChar { it.lowercase() }}" else sch.next_run_at?.let { "On · next ${java.time.Instant.parse(it).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM, H:mm"))}" } ?: "On",
                fontSize = 14.sp, color = if (sch.enabled) c.good else c.muted, modifier = Modifier.padding(start = 6.dp, top = 12.dp),
            )
        }
        d.board?.let { b ->
            section("What it keeps")
            item("board") {
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface1).clickable { viewing = true }.padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(if (b.new > 0) "Board · ${b.new} new" else "Board", fontSize = 12.5.sp, color = c.muted)
                    Text(b.title.ifBlank { b.name }, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                    if (b.history.isNotEmpty()) Text("${b.history.size} earlier version${if (b.history.size == 1) "" else "s"} kept", fontSize = 12.5.sp, color = c.muted)
                }
            }
        }
        section("Runs", d.runs.size.takeIf { it > 0 }?.toString())
        item("runs") {
            if (d.runs.isEmpty()) Text(if (sch.watch != null) "Nothing has changed yet." else "It has not run yet.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp))
            else Card {
                d.runs.forEach { r ->
                    val day = runCatching { java.time.Instant.parse(r.created_at).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")) }.getOrDefault("")
                    val state = when { r.capped -> "Stopped at its cap"; r.archived && sch.watch != null -> "Nothing new"; r.status == "running" -> "Working"; r.status == "error" -> "Stopped with an error"; else -> "Finished" }
                    NavRow(day, dollars(r.cost), state, valueColor = null, labelColor = null) { onOpen(r.id) }
                }
            }
        }
        section("Prompt")
        item("prompt") { Text(sch.prompt, fontSize = 15.sp, lineHeight = 22.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, end = 6.dp)) }
    }
    val b = d?.board
    if (viewing && b != null && b.session_id != null) {
        val files = remember(b.session_id) { ai.bench.app.ui.session.SessionFiles(store.api, b.session_id) }
        ai.bench.app.ui.session.FileViewer(files, ai.bench.app.ui.session.FileInfo(b.path, b.path, b.name, if (b.name.endsWith(".html")) "text/html" else "text/markdown", 0, true, "text", true)) { viewing = false }
    }
}
