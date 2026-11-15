package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bench.app.data.BenchStore
import ai.bench.app.ui.theme.Bench
import kotlinx.coroutines.launch
import kotlinx.serialization.json.put

/** "$2", "$7.50": a dollar amount as the caps show it. */
fun dollars(v: Double): String = if (v % 1.0 == 0.0) "$${v.toLong()}" else "$" + "%.2f".format(v)

/**
 * Spending: caps for what runs while the person is not watching, and one switch that pauses it
 * all (the Spending board). Runs the person starts are not capped.
 */
@Composable
fun SpendingScreen(store: BenchStore, onBack: () -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    val sp by store.spending.collectAsState()
    var picking by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { store.loadExtras() }
    fun save(daily: Double? = null, run: Double? = null, paused: Boolean? = null) = scope.launch {
        runCatching { store.api.setSpending(daily, run, paused) }.onSuccess { store.setSpending(it); error = null }.onFailure { error = it.message }
    }
    Page("Spending", "Caps for what runs while you are not watching. Runs you start yourself are not capped.", onBack) {
        val s = sp
        if (s == null) return@Page
        item("today") {
            Column(
                Modifier.padding(top = 22.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface1).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text("Unattended today", fontSize = 15.sp, color = c.muted, modifier = Modifier.weight(1f))
                    Text(dollars(s.today), fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                    Text(" of ${dollars(s.daily_cap)}", fontSize = 15.sp, color = c.muted)
                }
                val share = (s.today / s.daily_cap).coerceIn(0.0, 1.0).toFloat()
                Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(c.surface3)) {
                    Box(Modifier.fillMaxWidth(share).fillMaxHeight().clip(RoundedCornerShape(4.dp)).background(if (share >= 1f) c.warn else c.accent))
                }
                Text(if (s.runs_today == 0) "Nothing has run on its own today" else "${s.runs_today} run${if (s.runs_today == 1) "" else "s"} on their own today", fontSize = 13.sp, color = c.muted)
            }
        }
        item("caps") {
            Column(Modifier.padding(top = 10.dp)) {
                Card {
                    NavRow("Daily cap", dollars(s.daily_cap), "Unattended runs stop for the day at this") { picking = "daily" }
                    NavRow("Cap per run", dollars(s.run_cap), "A run that reaches it stops and asks you") { picking = "run" }
                }
            }
        }
        section("Pause")
        item("pause") { Card { SwitchRow("Pause unattended runs", "Schedules wait. Runs you start still work.", s.paused) { save(paused = it) } } }
        error?.let { e -> item("error") { Text(e, fontSize = 14.sp, color = c.bad, modifier = Modifier.padding(start = 6.dp, top = 10.dp)) } }
    }
    val s = sp ?: return
    when (picking) {
        "daily" -> PickDialog("Daily cap", listOf(2.0, 5.0, 10.0, 20.0, 50.0).map { it.toString() to dollars(it) }, s.daily_cap.toString(), onDismiss = { picking = null }) { picking = null; save(daily = it.toDouble()) }
        "run" -> PickDialog("Cap per run", listOf(0.5, 1.0, 2.0, 5.0, 10.0).map { it.toString() to dollars(it) }, s.run_cap.toString(), onDismiss = { picking = null }) { picking = null; save(run = it.toDouble()) }
    }
}


/**
 * Asks first: one list of what Bench does on its own, asks about, and never does (the Asks
 * first board). Inside the box is the default mode; leaving the box is a rule per action.
 */
@Composable
fun RulesScreen(store: BenchStore, onBack: () -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    var rules by remember { mutableStateOf<List<ai.bench.app.data.Rule>>(emptyList()) }
    var mode by remember { mutableStateOf<String?>(null) }
    var picking by remember { mutableStateOf<ai.bench.app.data.Rule?>(null) }
    var pickingMode by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        runCatching { store.api.rules() }.onSuccess { rules = it }
        runCatching { store.api.defaults() }.onSuccess { mode = (it as? kotlinx.serialization.json.JsonObject)?.get("mode")?.let { m -> (m as? kotlinx.serialization.json.JsonPrimitive)?.content } ?: "default" }
    }
    val words = mapOf("allow" to "Allowed", "ask" to "Asks first", "never" to "Never")
    Page("Asks first", "What Bench does on its own, what it asks you first, and what it never does.", onBack) {
        section("Inside the box")
        item("inside") {
            Card {
                NavRow("Read files and the web", "Allowed", chevron = false)
                NavRow("Change files and run commands", mode?.let { ai.bench.app.data.Mode.of(it).label } ?: "", "In projects, unless a session says otherwise") { pickingMode = true }
            }
        }
        section("Leaving the box")
        item("rules") {
            Card {
                rules.forEach { r ->
                    NavRow(r.label, words[r.value] ?: r.value, valueColor = if (r.value == "never") c.warn else if (r.value == "allow") c.good else null) { picking = r }
                }
            }
        }
        item("note") { Text("Never is enforced: Bench stops before the action and says so in the conversation. Never also shows in About me.", fontSize = 13.sp, lineHeight = 18.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 12.dp)) }
    }
    picking?.let { r ->
        PickDialog(r.label, words.entries.map { it.key to it.value }, r.value, onDismiss = { picking = null }) { v ->
            picking = null
            scope.launch { runCatching { store.api.setRule(r.key, v) }.onSuccess { rules = it } }
        }
    }
    if (pickingMode) PickDialog("Change files and run commands", ai.bench.app.data.Mode.entries.map { it.wire to it.label }, mode ?: "default", onDismiss = { pickingMode = false }) { m ->
        pickingMode = false
        scope.launch { runCatching { store.api.putDefaults(kotlinx.serialization.json.buildJsonObject { put("mode", m) }) }.onSuccess { mode = m } }
    }
}

/** Can reach: what Bench can use beyond its own folders, grouped by kind (the Can reach board). */
@Composable
fun CanReachScreen(store: BenchStore, onBack: () -> Unit, onBrowser: () -> Unit = {}) {
    val c = Bench.colors
    var reach by remember { mutableStateOf<ai.bench.app.data.Reach?>(null) }
    LaunchedEffect(Unit) { reach = runCatching { store.api.reach() }.getOrNull() }
    Page("Can reach", "What Bench can use beyond its own folders.", onBack) {
        val r = reach ?: return@Page
        section("Devices")
        item("devices") {
            if (r.devices.isEmpty()) Text("No devices yet. Your Mac's shares show here once Bench for Mac shares something.", fontSize = 14.5.sp, lineHeight = 20.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, end = 6.dp))
            else Card { r.devices.forEach { d -> NavRow(d.name, d.detail, chevron = false) } }
        }
        section("Accounts")
        item("accounts") {
            Card {
                r.accounts.forEach { a -> NavRow(a.name, a.detail, valueColor = if (a.detail == "Not connected") c.muted else null, chevron = false) }
                r.browser?.let { b ->
                    NavRow("Browser", when { !b.installed -> "Not installed"; b.sites.isEmpty() -> "No sign-ins"; else -> "${b.sites.size} sign-in${if (b.sites.size == 1) "" else "s"}" }, b.sites.take(3).joinToString(", ").ifBlank { null }) { onBrowser() }
                }
            }
        }
        section("On the server")
        item("server") { Card { r.server.forEach { x -> NavRow(x.name, x.detail, chevron = false) } } }
    }
}

/**
 * Housekeeping (the Housekeeping board): what Bench tidies on its own, once a day. It says what
 * it did in Activity, and what it archives can come back.
 */
@Composable
fun HousekeepingScreen(store: BenchStore, onBack: () -> Unit, onArchive: () -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    var h by remember { mutableStateOf<ai.bench.app.data.Housekeeping?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { runCatching { store.api.housekeeping() }.onSuccess { h = it }.onFailure { failed = true } }
    fun set(key: String, on: Boolean) = scope.launch { runCatching { store.api.setHousekeeping(key, on) }.onSuccess { h = it } }
    Page("Housekeeping", "Bench does these on its own and says what it did in Activity. What it archives can come back.", onBack) {
        if (failed) {
            item("old") { Text("This server can't do housekeeping yet. Update it to the Go server.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 24.dp)) }
            return@Page
        }
        val x = h ?: return@Page
        item("switches") {
            Column(Modifier.padding(top = 18.dp)) {
                Card {
                    SwitchRow("Archive conversations you have not opened in 30 days", "Pinned ones stay", x.archive_idle) { set("archive_idle", it) }
                    SwitchRow("Remove branches once merged", "Their work stays in main", x.prune_branches) { set("prune_branches", it) }
                    SwitchRow("Free disk from old work folders", x.freed?.let { f -> "Last time: ${bytes(f.bytes)} on ${runCatching { java.time.Instant.parse(f.at).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("d MMM")) }.getOrDefault("")}" } ?: "Folders of tasks finished a week ago", x.free_disk) { set("free_disk", it) }
                    SwitchRow("Keep one board per schedule", "Instead of a new report every run", x.keep_board) { set("keep_board", it) }
                }
            }
        }
        item("archive") {
            Column(Modifier.padding(top = 10.dp)) {
                Card { NavRow("Archive", if (x.archived == 0) "Empty" else "${x.archived} thing${if (x.archived == 1) "" else "s"}", "Bring back anything, or empty it for good") { onArchive() } }
            }
        }
    }
}

private fun bytes(b: Double): String = when {
    b >= 1 shl 30 -> "%.1f GB".format(b / (1 shl 30))
    b >= 1 shl 20 -> "%.0f MB".format(b / (1 shl 20))
    else -> "%.0f KB".format(b / 1024)
}

/** The archive: conversations and schedules put away, each with Restore, and Empty for good. */
@Composable
fun ArchiveScreen(store: BenchStore, onBack: () -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    var list by remember { mutableStateOf<ai.bench.app.data.ArchiveList?>(null) }
    var emptying by remember { mutableStateOf(false) }
    suspend fun load() { list = runCatching { store.api.archived() }.getOrNull() }
    LaunchedEffect(Unit) { load() }
    fun restore(kind: String, id: String) = scope.launch {
        runCatching { store.api.restore(kind, id) }
        load(); store.refresh(); store.loadExtras()
    }
    Page("Archive", "What was put away. Restore brings it back where it was.", onBack) {
        val l = list ?: return@Page
        if (l.sessions.isEmpty() && l.schedules.isEmpty()) {
            item("empty") { Text("The archive is empty.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 24.dp)) }
            return@Page
        }
        if (l.schedules.isNotEmpty()) {
            section("Schedules")
            item("schedules") { Card { l.schedules.forEach { x -> NavRow(x.title, "Restore", "Comes back off", valueColor = c.accent, chevron = false) { restore("schedule", x.id) } } } }
        }
        if (l.sessions.isNotEmpty()) {
            section("Conversations", l.sessions.size.toString())
            item("sessions") {
                Card {
                    l.sessions.forEach { x ->
                        val project = store.state.value.projects.firstOrNull { it.id == x.project_id }?.name
                        NavRow(x.title, "Restore", listOfNotNull(project, x.archived_at?.let { "archived " + ai.bench.app.data.Format.ago(it) + " ago" }).joinToString(" · "), valueColor = c.accent, chevron = false) { restore("session", x.id) }
                    }
                }
            }
        }
        item("emptyit") { Column(Modifier.padding(top = 20.dp)) { BigButton("Empty archive…", danger = true, height = 48.dp) { emptying = true } } }
    }
    if (emptying) Confirm("Empty the archive?", "Everything in it is removed for good. This can't be undone.", "Empty", onDismiss = { emptying = false }) {
        emptying = false
        scope.launch { runCatching { store.api.emptyArchive() }; load() }
    }
}
