package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import ai.bench.app.data.Schedule
import ai.bench.app.ui.theme.Bench
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Sessions that start on their own: "every Monday at 8:00, look for new hackathons". What
 * they make lands on Home under Ready for you.
 */
@Composable
fun SchedulesScreen(store: BenchStore, onBack: () -> Unit, onOpen: (String) -> Unit) {
    val list by store.schedules.collectAsState()
    val s by store.state.collectAsState()
    var editing by remember { mutableStateOf<Schedule?>(null) }
    var adding by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { store.loadExtras() }
    Page("Schedules", "Sessions that start on their own", onBack, actions = {
        IconButton(onClick = { adding = true }) { Icon(Icons.Default.Add, "New schedule") }
    }) {
        if (list.isEmpty()) item("empty") {
            Column(Modifier.padding(start = 6.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Nothing scheduled", fontSize = 15.sp, color = Bench.colors.muted)
                BigButton("New schedule", primary = true, height = 48.dp, fill = false) { adding = true }
            }
        } else {
            section("Schedules")
            item("list") {
                Card {
                    list.sortedWith(compareBy({ !it.enabled }, { it.next_run_at ?: "~" })).forEach { sc ->
                        val project = s.projects.firstOrNull { it.id == sc.project_id }?.name
                        NavRow(sc.title, if (sc.enabled) "${scheduleDays(sc.days)} · ${sc.time}" else "Off", project) { editing = sc }
                    }
                }
            }
        }
    }
    if (adding || editing != null) ScheduleEditor(store, editing, onDismiss = { adding = false; editing = null }, onOpen = onOpen)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScheduleEditor(store: BenchStore, existing: Schedule?, onDismiss: () -> Unit, onOpen: (String) -> Unit) {
    val c = Bench.colors
    val st by store.state.collectAsState()
    val scope = rememberCoroutineScope()
    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var prompt by remember { mutableStateOf(existing?.prompt.orEmpty()) }
    var days by remember { mutableStateOf(existing?.days?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.toSet() ?: setOf(1)) }
    var time by remember { mutableStateOf(existing?.time ?: "08:00") }
    var enabled by remember { mutableStateOf(existing?.enabled ?: true) }
    var projectId by remember { mutableStateOf(existing?.project_id) }
    var picking by remember { mutableStateOf(false) }
    var pickingTime by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    // A new schedule goes to the Research project unless you pick another; it is made on first use.
    LaunchedEffect(Unit) { if (projectId == null) projectId = runCatching { store.api.research().id }.getOrNull() }
    val projectName = st.projects.firstOrNull { it.id == projectId }?.name ?: if (projectId == null) "…" else "Research"

    fun save(then: (Schedule) -> Unit = { onDismiss() }) {
        val pid = projectId ?: return
        busy = true; error = null
        scope.launch {
            try {
                val saved = store.api.saveSchedule(existing?.id, buildJsonObject {
                    put("title", title.trim()); put("prompt", prompt.trim()); put("project_id", pid)
                    put("days", days.sorted().joinToString(",")); put("time", time); put("enabled", enabled)
                    if (existing == null) put("tz", java.time.ZoneId.systemDefault().id)
                })
                store.loadExtras()
                then(saved)
            } catch (e: BenchError) { error = e.message } finally { busy = false }
        }
    }

    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface2, modifier = Modifier.padding(top = 40.dp)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(if (existing == null) "New schedule" else "Edit schedule", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Input(title, { title = it }, "Name, like Hackathons this week", single = true)
            Input(prompt, { prompt = it }, "What to do each time: find, compare, draft…", single = false)
            Text("Days", fontSize = 13.sp, color = c.muted)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Monday first, as most calendars outside the US show it.
                listOf(1, 2, 3, 4, 5, 6, 0).forEach { d ->
                    val on = d in days
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(if (on) c.accent else c.surface3).clickable { days = if (on) days - d else days + d },
                        contentAlignment = Alignment.Center,
                    ) { Text("SMTWTFS"[d].toString(), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = if (on) c.onAccent else c.text) }
                }
            }
            Card {
                NavRow("Time", time) { pickingTime = true }
                NavRow("Project", projectName) { picking = true }
                SwitchRow("On", "Uses your default model and mode", enabled) { enabled = it }
            }
            error?.let { Text(it, fontSize = 14.sp, color = c.bad) }
            BigButton(if (existing == null) "Save" else "Save changes", primary = true, enabled = !busy && projectId != null && title.isNotBlank() && prompt.isNotBlank() && days.isNotEmpty()) { save() }
            if (existing != null) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Run now", Modifier.weight(1f), height = 48.dp, enabled = !busy) {
                    scope.launch {
                        runCatching { store.api.runSchedule(existing.id) }.onSuccess { onDismiss(); onOpen(it.id) }.onFailure { error = it.message }
                    }
                }
                BigButton("Delete…", Modifier.weight(1f), height = 48.dp, danger = true, enabled = !busy) { confirmDelete = true }
            }
        }
    }
    if (picking) PickDialog("Project", st.projects.map { it.id to it.name }, projectId.orEmpty(), onDismiss = { picking = false }) { projectId = it; picking = false }
    if (pickingTime) {
        val (h, m) = time.split(':').map { it.toIntOrNull() ?: 0 }
        val tp = rememberTimePickerState(h, m, is24Hour = true)
        AlertDialog(
            onDismissRequest = { pickingTime = false }, containerColor = c.surface2,
            confirmButton = { TextButton(onClick = { time = "%02d:%02d".format(tp.hour, tp.minute); pickingTime = false }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { pickingTime = false }) { Text("Cancel", color = c.muted) } },
            text = { TimeInput(tp, colors = TimePickerDefaults.colors(timeSelectorSelectedContainerColor = c.accent, timeSelectorSelectedContentColor = c.onAccent)) },
        )
    }
    if (confirmDelete && existing != null) AlertDialog(
        onDismissRequest = { confirmDelete = false }, containerColor = c.surface2,
        title = { Text("Delete “${existing.title}”?") },
        text = { Text("It stops running. Sessions it already started stay.", color = c.muted) },
        confirmButton = { TextButton(onClick = { scope.launch { runCatching { store.api.deleteSchedule(existing.id) }; store.loadExtras(); onDismiss() } }) { Text("Delete", color = c.bad) } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel", color = c.muted) } },
    )
}

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
