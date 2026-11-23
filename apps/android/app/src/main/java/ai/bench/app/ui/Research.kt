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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.ui.graphics.Color
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
 * Research, a tab of its own: one row per question, never one per file. Questions a schedule
 * answers again are under Following; questions asked once are under Answers, newest first.
 * New and running are states of a row, not sections, so one question is in one place.
 * A tap on a question opens its answer: the main file, with the others one chip away.
 * Design-brief.md, "View: Android Research tab", is what this is held to.
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
    var answer by rememberSaveable { mutableStateOf<String?>(null) }
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { store.loadExtras() }

    val researchId = s.projects.firstOrNull { it.name == "Research" }?.id
    val q = query.trim()
    fun hit(text: List<String?>) = q.isEmpty() || text.any { it?.contains(q, ignoreCase = true) == true }
    val readable = reports.filter { it.kind != "file" && !it.title.startsWith("Bench presents") }
    val bySession = readable.groupBy { it.session_id }

    // Following: research a schedule answers again.
    val followed = schedules.filter { it.enabled && (it.project_id == researchId || it.keep_board || it.watch != null) }
    val followedIds = followed.map { it.id }.toSet()
    val following = followed
        .map { x -> Followed(x, readable.filter { it.schedule_id == x.id && it.kind != "drafts" }.maxByOrNull { it.modified }) }
        .filter { hit(listOf(it.schedule.title, it.latest?.title)) }
        .sortedWith(compareByDescending<Followed> { it.unread }.thenByDescending { it.latest?.modified ?: it.schedule.last_run_at.orEmpty() })

    // Answers: every question asked once. A run of a followed schedule belongs to Following.
    val asked = s.sessions.filter {
        it.archived_at == null && (it.schedule_id == null || it.schedule_id !in followedIds) && (it.project_id == researchId || it.id in bySession)
    }
    val answers = asked.map { ses ->
        val files = filesOf(bySession[ses.id].orEmpty())
        val last = files.maxOfOrNull { it.modified }
        Question(ses.id, question(ses.title), files, ses.status == "running", if (last != null && last > ses.updated_at) last else ses.updated_at)
    }.filter { a -> hit(listOf(a.asked) + a.files.map { it.title } + a.files.map { it.gist }) }
        .sortedByDescending { if (it.running) "~" else it.at }
    val running = answers.filter { it.running }.map { it.sessionId }
    DisposableEffect(running) {
        running.forEach { store.watch(it) }
        onDispose { running.forEach { store.unwatch(it) } }
    }
    val nothing = following.isEmpty() && answers.isEmpty()

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

                if (following.isNotEmpty()) {
                    item("f-l") { Label("Following", trailing = "All schedules", onTrailing = onSchedules) }
                    items(if (q.isEmpty()) following.take(3) else following, key = { "f-${it.schedule.id}" }) { f ->
                        QuestionRow(f.schedule.title, followLine(f), null, unread = f.unread) {
                            val r = f.latest
                            if (r != null) opened = r else onSchedule(f.schedule.id)
                        }
                    }
                }

                // Answers under light day labels: Today, This week, Earlier.
                var day: String? = null
                answers.forEach { a ->
                    val label = if (a.running) "Today" else sectionLabel(a.at)
                    if (label != day) {
                        val first = day == null
                        day = label
                        item("a-l-$label") { Label(if (first && following.isNotEmpty()) "Answers, ${label.lowercase()}" else label) }
                    }
                    item("a-${a.sessionId}") {
                        val main = a.files.firstOrNull()
                        val unsent = a.files.sumOf { if (it.kind == "drafts") it.unsent ?: 0 else 0 }
                        when {
                            a.running -> QuestionRow(a.asked, steps[a.sessionId] ?: "Working", "now", unread = false, accentSub = true) { onOpen(a.sessionId) }
                            main == null -> QuestionRow(a.asked, "Answered in the conversation", dayLabel(a.at), unread = false) { onOpen(a.sessionId) }
                            else -> QuestionRow(
                                if (main.kind == "drafts") a.asked else main.title,
                                if (unsent > 0) (if (unsent == 1) "1 email to send" else "$unsent emails to send") else a.asked,
                                dayLabel(a.at), unread = a.unread, accentSub = unsent > 0,
                            ) { answer = a.sessionId }
                        }
                    }
                }

                if (nothing && q.isNotEmpty()) item("none") { Quiet("Nothing matches “$q”.") }
                else if (nothing && s.ready) item("empty") { EmptyResearch(onResearch) }
                else if (answers.isEmpty() && q.isEmpty()) item("a-0") { Column { Label("Answers"); Quiet("Nothing asked yet.") } }
            }
        }
    }
    }

    // The answer: the question's main file, with its other files as chips above it.
    answer?.let { sid ->
        androidx.activity.compose.BackHandler { answer = null }
        val files = filesOf(bySession[sid].orEmpty())
        val ses = s.sessions.firstOrNull { it.id == sid }
        var showing by remember(sid) { mutableStateOf(files.firstOrNull()?.path) }
        val r = files.firstOrNull { it.path == showing } ?: files.firstOrNull()
        if (r == null) { LaunchedEffect(sid) { answer = null }; return@let }
        val main = files.first()
        ReportViewer(
            store, r, onOpen = { answer = null; onOpen(it) }, onAbout = onAbout,
            above = if (files.size < 2) null else ({ AnswerFiles(files, r.path) { showing = it.path } }),
            below = {
                HorizontalDivider(color = c.line)
                Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("Open conversation", height = 50.dp, modifier = Modifier.weight(1f)) { answer = null; onOpen(sid) }
                    if (ses != null && ses.schedule_id == null && ses.project_id != null) BigButton("Follow", height = 50.dp, fill = false) {
                        answer = null; onKeep(ai.bench.app.data.SchedulePrefill(ses.title, ses.title, ses.project_id))
                    }
                }
            },
        ) {
            // Drafts open in their own screen: Back from there returns to the answer.
            if (r.kind == "drafts" && r.path != main.path) showing = main.path else answer = null
        }
    }
    opened?.let { r -> ReportViewer(store, r, onOpen = onOpen, onAbout = onAbout) { opened = null } }
}

/** A question's files, the main one first: a report, page or board before drafts, newest first. */
private fun filesOf(list: List<Report>) = list.sortedWith(compareBy<Report> { it.kind == "drafts" }.thenByDescending { it.modified })

private class Question(val sessionId: String, val asked: String, val files: List<Report>, val running: Boolean, val at: String) {
    val unread get() = files.any { (it.kind == "drafts" && (it.unsent ?: 0) > 0) || (it.kind != "drafts" && !it.seen) }
}

private class Followed(val schedule: Schedule, val latest: Report?) {
    val unread get() = latest != null && !latest.seen
}

/** A research's title is the question as it was asked; only its first letter is raised. */
private fun question(t: String) = t.trim().replaceFirstChar { it.uppercaseChar() }

/** One question: what it found, then the question or its state. The dot marks what you have not seen. */
@Composable
private fun QuestionRow(title: String, sub: String, right: String?, unread: Boolean, accentSub: Boolean = false, onClick: () -> Unit) {
    val c = Bench.colors
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(start = 2.dp, end = 6.dp, top = 12.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.padding(top = 8.dp).size(8.dp).background(if (unread) c.accent else Color.Transparent, CircleShape))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 16.sp, lineHeight = 22.sp, fontWeight = if (unread) FontWeight.SemiBold else FontWeight.Normal, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(sub, fontSize = 13.5.sp, color = if (accentSub) c.accent else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (right != null) Text(right, fontSize = 13.5.sp, color = c.muted, maxLines = 1)
    }
}

/** The files one question made, as chips; the one shown is filled. */
@Composable
private fun AnswerFiles(files: List<Report>, showing: String, onPick: (Report) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        files.forEach { f ->
            val name = if (f.kind == "drafts") (f.unsent ?: 0).let { if (it > 0) "$it emails to send" else "Emails" } else f.title
            FilterChip(if (name.length > 40) name.take(39) + "…" else name, f.path == showing) { onPick(f) }
        }
    }
}

/** A followed question: how fresh it is. */
private fun followLine(f: Followed): String {
    val x = f.schedule
    val w = x.watch
    val report = f.latest
    return when {
        w != null -> "Watching ${ai.bench.app.data.pageName(w.url)}, ${checkedLabel(x).replaceFirstChar { it.lowercase() }}"
        report?.kind == "board" && (report.new ?: 0) > 0 -> "${report.new} new, updated ${agoText(report.modified)}"
        report != null -> "Updated ${agoText(report.modified)}"
        x.last_run_at != null -> "Updated ${agoText(x.last_run_at)}"
        else -> "First run ${whenLabel(x.next_run_at)}"
    }
}

private fun agoText(iso: String) = Format.ago(iso).let { if (it == "now") "just now" else "$it ago" }

private fun whenLabel(iso: String?): String =
    runCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()).format(java.time.format.DateTimeFormatter.ofPattern("EEE H:mm")) }.getOrDefault("soon")

private fun localDate(iso: String): java.time.ZonedDateTime? =
    runCatching { java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault()) }
        .recoverCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()) }.getOrNull()

/** Today, This week, else Earlier. */
private fun sectionLabel(iso: String): String {
    val d = localDate(iso)?.toLocalDate() ?: return "Earlier"
    val today = java.time.LocalDate.now()
    return when {
        d == today -> "Today"
        d.isAfter(today.minusDays(7)) -> "This week"
        else -> "Earlier"
    }
}

/** Today's time, else the weekday this week, else the date. */
private fun dayLabel(iso: String): String {
    val t = localDate(iso) ?: return ""
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
private fun EmptyResearch(onResearch: (String) -> Unit) {
    val c = Bench.colors
    Column(Modifier.padding(top = 32.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.padding(horizontal = 6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("No research yet", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            Text("Claude searches, compares and writes a report. Reports stay here, and the ones you follow stay up to date.", fontSize = 14.5.sp, lineHeight = 21.sp, color = c.muted)
        }
        Label("Try")
        listOf("Hackathons I can enter this month", "Cheapest way to send USDT to Kenya", "What competitors shipped this week").forEach { t ->
            Text(t, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface1).clickable { onResearch(t) }.padding(horizontal = 16.dp, vertical = 13.dp))
        }
    }
}
