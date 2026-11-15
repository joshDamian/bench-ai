package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bench.app.data.BenchStore
import ai.bench.app.data.Format
import ai.bench.app.data.Report
import ai.bench.app.data.Schedule
import ai.bench.app.ui.theme.Bench
import kotlinx.coroutines.launch

/**
 * Research, a tab of its own: everything Claude found out, wherever it ran. What is being
 * researched now, what is new to read, what schedules keep up to date, and the rest by date.
 * Before this, a report was on Home once and then only under Projects, Research, its session.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ResearchScreen(
    store: BenchStore,
    onOpen: (String) -> Unit,
    onResearch: (String) -> Unit,
    onSchedules: () -> Unit,
    onSchedule: (String) -> Unit,
    onAbout: () -> Unit,
    onKeep: (ai.bench.app.data.SchedulePrefill) -> Unit = {},
) {
    val s by store.state.collectAsState()
    val reports by store.reports.collectAsState()
    val schedules by store.schedules.collectAsState()
    val steps by store.steps.collectAsState()
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<Report?>(null) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { store.loadExtras() }

    val researchId = s.projects.firstOrNull { it.name == "Research" }?.id
    val live = s.sessions.filter { it.archived_at == null }
    val q = query.trim()
    fun hit(vararg text: String?) = q.isEmpty() || text.any { it?.contains(q, ignoreCase = true) == true }
    val sessionTitle = { id: String -> s.sessions.firstOrNull { it.id == id }?.title }

    // Running now: sessions in the Research project, and runs of research schedules.
    val researchSchedules = schedules.filter { it.project_id == researchId || it.keep_board || it.watch != null }
    val running = live.filter { it.status == "running" && (it.project_id == researchId || (it.schedule_id != null && researchSchedules.any { x -> x.id == it.schedule_id })) && hit(it.title) }
    DisposableEffect(running.map { it.id }) {
        running.forEach { store.watch(it.id) }
        onDispose { running.forEach { store.unwatch(it.id) } }
    }
    val readable = reports.filter { it.kind != "file" && !it.title.startsWith("Bench presents") && hit(it.title, it.gist, sessionTitle(it.session_id)) }
    val fresh = readable.filter { (it.kind == "drafts" && (it.unsent ?: 0) > 0) || (it.kind != "drafts" && !it.seen) }
    val kept = researchSchedules.filter { it.enabled && hit(it.title) }
    // Everything already read, and research conversations that made no file.
    val made = reports.map { it.session_id }.toSet()
    val talks = live.filter { it.project_id == researchId && it.status != "running" && it.id !in made && hit(it.title) }
    // Everything already read, under the session that made it (named once, not on every row),
    // and research conversations that made no file. Newest first.
    val earlier = (readable.groupBy { it.session_id }.filter { (_, list) -> list.any { it !in fresh } }.map { (sid, list) -> EarlierGroup(sid, sessionTitle(sid) ?: "Session", list.maxOf { it.modified }, list) } +
        talks.map { EarlierGroup(it.id, it.title, it.updated_at, emptyList()) }).sortedByDescending { it.at }
    val nothing = running.isEmpty() && fresh.isEmpty() && kept.isEmpty() && earlier.isEmpty()

    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.text) {
    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().statusBarsPadding().padding(start = 22.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (searching) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = TextStyle(fontSize = 17.sp, color = c.text), cursorBrush = SolidColor(c.accent),
                    modifier = Modifier.weight(1f).height(44.dp).background(c.surface1, RoundedCornerShape(22.dp)).padding(horizontal = 16.dp, vertical = 11.dp).focusRequester(focus),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search research", fontSize = 17.sp, color = c.muted); inner() } },
                )
                IconButton(onClick = { searching = false; query = "" }, modifier = Modifier.padding(start = 8.dp).size(44.dp)) { Icon(Icons.Default.Close, "Stop searching", tint = c.text) }
            } else {
                Text("Research", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, modifier = Modifier.weight(1f), maxLines = 1)
                IconButton(onClick = { searching = true }, modifier = Modifier.size(44.dp).background(c.surface1, CircleShape)) {
                    Icon(Icons.Default.Search, "Search research", tint = c.text)
                }
            }
        }
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; store.refresh(); store.loadExtras(); refreshing = false } },
            modifier = Modifier.fillMaxSize(),
        ) {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp), modifier = Modifier.fillMaxSize()) {
                // The one primary action: ask for something to be found out.
                item("ask") {
                    Row(
                        Modifier.padding(top = 10.dp).fillMaxWidth().height(52.dp).clip(RoundedCornerShape(26.dp)).background(c.surface1)
                            .clickable(onClickLabel = "Start research") { onResearch("") }.padding(start = 18.dp, end = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("What should Claude find out?", fontSize = 15.5.sp, color = c.muted, modifier = Modifier.weight(1f), maxLines = 1)
                        Box(Modifier.size(38.dp).background(c.accent, CircleShape), contentAlignment = Alignment.Center) {
                            Icon(Icons.Default.Add, null, tint = c.onAccent, modifier = Modifier.size(20.dp))
                        }
                    }
                }

                if (running.isNotEmpty()) {
                    item("run-l") { Label("Researching") }
                    items(running, key = { "w-${it.id}" }) { w ->
                        WorkCard(w.title, steps[w.id] ?: "Working", if (w.schedule_id != null) "Scheduled" else "", Format.ago(w.updated_at), w.id, Modifier.padding(top = 10.dp)) { onOpen(w.id) }
                    }
                }

                if (fresh.isNotEmpty()) {
                    item("new-l") { Label("New for you", trailing = "${fresh.size}") }
                    items(fresh, key = { "n-${it.path}" }) { r ->
                        if (r.kind == "drafts") DraftsCard(r, sessionTitle(r.session_id), Modifier.padding(top = 10.dp)) { opened = r }
                        else ReportCard(r, sessionTitle(r.session_id), Modifier.padding(top = 10.dp)) { opened = r }
                    }
                }

                if (kept.isNotEmpty()) {
                    item("kept-l") { Label("Kept up to date", trailing = "All schedules", onTrailing = onSchedules) }
                    item("kept") {
                        Column {
                            kept.forEachIndexed { i, x ->
                                if (i > 0) HorizontalDivider(color = c.line, modifier = Modifier.padding(horizontal = 6.dp))
                                KeptRow(x, reports.firstOrNull { it.schedule_id == x.id }) { onSchedule(x.id) }
                            }
                        }
                    }
                }

                // One row per research: the question asked, and what it made.
                if (earlier.isNotEmpty()) {
                    item("old-l") { Label("Earlier") }
                    items(earlier, key = { "q-${it.sessionId}" }) { g ->
                        val made = g.reports.sortedByDescending { it.modified }
                        val sub = when (made.size) {
                            0 -> "Answered in the conversation"
                            1 -> made[0].title
                            else -> "${made[0].title}, and ${made.size - 1} more"
                        }
                        QuestionRow(question(g.title), sub, dayLabel(g.at)) { if (made.isEmpty()) onOpen(g.sessionId) else detail = g.sessionId }
                    }
                }

                if (nothing && q.isNotEmpty()) item("none") { Quiet("Nothing matches “$q”.") }
                else if (nothing && s.ready) item("empty") { EmptyResearch(onResearch) }
            }
        }
    }

    }
    detail?.let { sid ->
        androidx.activity.compose.BackHandler { detail = null }
        val ses = s.sessions.firstOrNull { it.id == sid }
        ResearchDetail(
            question(ses?.title ?: "Research"), ses?.created_at.orEmpty(),
            reports.filter { it.session_id == sid && it.kind != "file" && !it.title.startsWith("Bench presents") }.sortedByDescending { it.modified },
            canKeep = ses != null && ses.schedule_id == null && ses.project_id != null,
            onBack = { detail = null }, onRead = { opened = it }, onConversation = { detail = null; onOpen(sid) },
            onKeep = { ses?.let { onKeep(ai.bench.app.data.SchedulePrefill(it.title, it.title, it.project_id)) } },
        )
    }
    opened?.let { r -> ReportViewer(store, r, onOpen = onOpen, onAbout = onAbout) { opened = null } }
}

/** A research's title is the question as it was asked; only its first letter is raised. */
private fun question(t: String) = t.trim().replaceFirstChar { it.uppercaseChar() }

@Composable
private fun QuestionRow(title: String, sub: String, right: String, onClick: () -> Unit) {
    val c = Bench.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 16.sp, lineHeight = 22.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 13.5.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(right, fontSize = 13.5.sp, color = c.muted, maxLines = 1)
    }
}

/** One research: the question, everything it made, and the way back to its conversation. */
@Composable
private fun ResearchDetail(title: String, at: String, made: List<Report>, canKeep: Boolean, onBack: () -> Unit, onRead: (Report) -> Unit, onConversation: () -> Unit, onKeep: () -> Unit) {
    val c = Bench.colors
    androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.text) {
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding()) {
        IconButton(onClick = onBack, modifier = Modifier.padding(start = 4.dp, top = 4.dp).size(48.dp)) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = c.text)
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp)) {
            item("head") {
                Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Research · ${dayLabel(at)}", fontSize = 13.sp, color = c.muted)
                    Text(title, fontSize = 25.sp, lineHeight = 31.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp)
                }
            }
            item("made-l") { Label("What it made", trailing = "${made.size}") }
            items(made, key = { it.path }) { r ->
                if (r.kind == "drafts") DraftsCard(r, null, Modifier.padding(top = 10.dp)) { onRead(r) }
                else ReportCard(r, null, Modifier.padding(top = 10.dp)) { onRead(r) }
            }
        }
        HorizontalDivider(color = c.line)
        Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            BigButton("Open conversation", height = 50.dp, modifier = Modifier.weight(1f)) { onConversation() }
            if (canKeep) BigButton("Keep it updated", height = 50.dp, fill = false) { onKeep() }
        }
    }
    }
}

private class EarlierGroup(val sessionId: String, val title: String, val at: String, val reports: List<Report>)

/** A research schedule: how fresh it is, and how often it runs. */
@Composable
private fun KeptRow(x: Schedule, report: Report?, onClick: () -> Unit) {
    val c = Bench.colors
    val w = x.watch
    val sub = when {
        w != null -> "Watching ${ai.bench.app.data.pageName(w.url)} · ${checkedLabel(x).replaceFirstChar { it.lowercase() }}"
        report?.kind == "board" && (report.new ?: 0) > 0 -> "Updated ${agoText(report.modified)} · ${report.new} new"
        x.last_run_at != null -> "Updated ${agoText(x.last_run_at)}"
        else -> "First run ${whenLabel(x.next_run_at)}"
    }
    LibraryRow(x.title, sub, if (w != null) "Watch" else scheduleDays(x.days), onClick)
}

private fun agoText(iso: String) = Format.ago(iso).let { if (it == "now") "just now" else "$it ago" }

private fun whenLabel(iso: String?): String =
    runCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE H:mm")) }.getOrDefault("soon")

/** Today's time, else the weekday this week, else the date. */
private fun dayLabel(iso: String): String {
    val t = runCatching { java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault()) }
        .recoverCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()) }.getOrNull() ?: return ""
    val today = java.time.LocalDate.now()
    val d = t.toLocalDate()
    return when {
        d == today -> t.format(java.time.format.DateTimeFormatter.ofPattern("H:mm"))
        d.isAfter(today.minusDays(6)) -> t.format(java.time.format.DateTimeFormatter.ofPattern("EEE"))
        d.year == today.year -> t.format(java.time.format.DateTimeFormatter.ofPattern("d MMM"))
        else -> t.format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy"))
    }
}

@Composable
private fun LibraryRow(title: String, sub: String, right: String, onClick: () -> Unit) {
    val c = Bench.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 13.5.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(right, fontSize = 13.5.sp, color = c.muted, maxLines = 1)
    }
}

@Composable
private fun EmptyResearch(onResearch: (String) -> Unit) {
    val c = Bench.colors
    Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("No research yet", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Claude searches, compares and writes a report. Reports stay here, and the ones on a schedule stay up to date.", fontSize = 14.5.sp, lineHeight = 21.sp, color = c.muted)
        }
        Label("Try")
        listOf("Hackathons I can enter this month", "Cheapest way to send USDT to Kenya", "What competitors shipped this week").forEach { t ->
            Text(t, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface1).clickable { onResearch(t) }.padding(horizontal = 16.dp, vertical = 13.dp))
        }
    }
}
