package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.layout.imePadding
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Inbox
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bench.app.BenchApp
import ai.bench.app.data.BenchError
import ai.bench.app.data.BenchStore
import ai.bench.app.data.BenchTask
import ai.bench.app.data.DiffFile
import ai.bench.app.data.Format
import ai.bench.app.data.GitState
import ai.bench.app.data.Session
import ai.bench.app.data.Report
import ai.bench.app.data.TaskGit
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.Mono
import ai.bench.app.ui.theme.Space
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

// The tabs and the screens they open, as the Android boards draw them. Shared pieces first.

/** A tab's large title, with an optional trailing bit. */
@Composable
fun TabTitle(title: String, trailing: @Composable () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().statusBarsPadding().padding(start = Space.lg, end = Space.lg, top = Space.sm, bottom = Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.sm),
    ) {
        Text(title, fontSize = 24.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
        trailing()
    }
}

/** A pushed screen's header: back, a title and a coloured line under it. */
@Composable
fun BackHeader(title: String, sub: @Composable () -> Unit, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = Space.sm), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub()
        }
    }
}

@Composable
fun SectionLabel(text: String, color: Color = Bench.colors.muted, top: Boolean = true) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.padding(start = 6.dp, top = if (top) 16.dp else 4.dp))
}

/** Rows under a section label: space groups them, no box or hairlines. */
@Composable
fun <T> Grouped(items: List<T>, row: @Composable (T) -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        items.forEach { androidx.compose.runtime.key(it) { row(it) } }
    }
}

/** A session's dot: dim when idle, a soft ring while Claude works. */
@Composable
fun BigDot(status: String) {
    val c = Bench.colors
    val color = when (status) { "running" -> c.accent; "waiting" -> c.warn; "error" -> c.bad; else -> null }
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        if (color != null) {
            if (status == "running") Box(Modifier.size(18.dp).background(color.copy(alpha = 0.2f), CircleShape))
            Box(Modifier.size(10.dp).background(color, CircleShape))
        }
    }
}

@Composable
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
fun ListRow(title: String, sub: String?, dot: String, trailing: String? = null, trailingColor: Color = Bench.colors.muted, id: String? = null, selected: Boolean = false, checked: Boolean? = null, onLongClick: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Bench.colors.accent.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick).padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // Selecting many: a check in front of every row. Otherwise a mark only for states that
        // need one; idle rows start at the edge, no empty gutter.
        if (checked != null) Box(
            Modifier.size(22.dp).background(if (checked) Bench.colors.accent else Color.Transparent, androidx.compose.foundation.shape.CircleShape)
                .then(if (checked) Modifier else Modifier.border(1.5.dp, Bench.colors.muted, androidx.compose.foundation.shape.CircleShape)),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(Icons.Default.Check, null, tint = Bench.colors.onAccent, modifier = Modifier.size(15.dp)) }
        else if (dot in setOf("running", "waiting", "error")) BigDot(dot)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.sharedTitle(id))
            sub?.let { Text(it, fontSize = 13.sp, color = Bench.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.let { Text(it, fontSize = 12.5.sp, color = trailingColor) }
    }
}

/**
 * A Home row: title, then what it is doing now (Tactic Remote's session-first home), no dot.
 * The state is in the words: the live step in the accent while working, the error in red.
 */
@Composable
private fun HomeRow(title: String, state: String?, stateColor: Color, where: String, trailing: String?, id: String, selected: Boolean, onClick: () -> Unit) {
    val c = Bench.colors
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (selected) c.accent.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).sharedTitle(id))
            trailing?.let { Text(it, fontSize = 12.5.sp, color = c.muted, modifier = Modifier.padding(start = 12.dp)) }
        }
        Text(
            androidx.compose.ui.text.buildAnnotatedString {
                if (state != null) {
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = stateColor)); append(state); pop()
                    if (where.isNotEmpty()) append(" · ")
                }
                append(where)
            },
            fontSize = 13.5.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun host(store: BenchStore) = store.api.config.baseUrl.substringAfter("://").substringBefore(":").substringBefore(".")

private fun where(s: Session, store: BenchStore, extra: String?): String {
    val st = store.state.value
    val p = st.projects.firstOrNull { it.id == s.project_id }?.name ?: if (s.project_id == null) "Ask Bench" else null
    return listOfNotNull(p, extra).joinToString(" · ")
}

// -- Home ------------------------------------------------------------------------------------

/** Sessions by day: Today, Yesterday, then the date. */
private fun dayOf(iso: String): String {
    val t = runCatching { java.time.OffsetDateTime.parse(iso).atZoneSameInstant(java.time.ZoneId.systemDefault()).toLocalDate() }.getOrNull() ?: return "Earlier"
    val today = java.time.LocalDate.now()
    return when (t) {
        today -> "Today"
        today.minusDays(1) -> "Yesterday"
        else -> t.format(java.time.format.DateTimeFormatter.ofPattern(if (t.year == today.year) "EEE d MMM" else "d MMM yyyy"))
    }
}

/**
 * Home empties itself (the "Home that empties itself" boards): only what needs you, what is
 * ready for you, what is working, and what is coming up. Looking at a session takes it off;
 * everything else stays in its project, a tap away. The header names the most urgent group
 * in a few words and the rest in one quiet line, so it never grows with the day.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(store: BenchStore, onOpen: (String) -> Unit, onNew: () -> Unit, onSearch: () -> Unit, selected: String? = null, onSchedules: () -> Unit = {}, onSchedule: (String) -> Unit = {}, onAbout: () -> Unit = {}, onActivity: () -> Unit = {}) {
    val s by store.state.collectAsState()
    val away by store.activityUnseen.collectAsState()
    val reports by store.reports.collectAsState()
    val schedules by store.schedules.collectAsState()
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refreshing by remember { mutableStateOf(false) }
    var answering by remember { mutableStateOf(setOf<String>()) }
    val shared by BenchApp.instance.pendingShare.collectAsState()
    val sharedText by BenchApp.instance.pendingText.collectAsState()
    var opened by remember { mutableStateOf<Report?>(null) }
    var listing by remember { mutableStateOf<List<Report>?>(null) }
    var moreReady by rememberSaveable { mutableStateOf(false) }
    var takingOver by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { store.loadExtras() }

    val live = s.sessions.filter { it.archived_at == null }
    val waitingOn = s.approvals.map { it.session_id }.toSet()
    val working = live.filter { it.status == "running" }.sortedByDescending { it.updated_at }
    // Stopped with an error and not looked at: a decision, so it sits with what needs you.
    val failed = live.filter { it.status == "error" && it.unseen && it.id !in waitingOn && !it.capped }.sortedByDescending { it.updated_at }
    // Unattended runs that stopped at their cap: go on, or leave it there.
    val capped = live.filter { it.capped && it.id !in waitingOn }.sortedByDescending { it.updated_at }
    val spending by store.spending.collectAsState()
    // Reports and pages leave once read. Drafts are things to do, so they stay until each is sent,
    // even when you watched the session write them.
    val reads = reports.filter { !it.seen && (it.kind == "report" || it.kind == "page" || it.kind == "board") }
    val drafts = reports.filter { it.kind == "drafts" && (it.unsent ?: 0) > 0 }
    val carded = (reads + drafts).map { it.session_id }.toSet()
    // A session whose output is already a card is not listed again under it.
    val finished = live.filter { it.status == "idle" && it.unseen && it.id !in waitingOn && it.id !in carded && !it.capped }.sortedByDescending { it.updated_at }
    val readyCards = (if (reads.isNotEmpty()) 1 else 0) + (if (drafts.isNotEmpty()) 1 else 0)
    val readyCount = reads.size + drafts.size + finished.size
    // What a run asked the Mac for: it waits on the Mac, and the run carries on meanwhile.
    val devices by store.devices.collectAsState()
    // (What a run asks this phone is decided on this phone, in its own sheet.)
    val thisPhone = ai.bench.app.phone.PhoneShares.deviceId(androidx.compose.ui.platform.LocalContext.current)
    val macAsks = devices.filter { it.id != thisPhone }.flatMap { d -> d.asks.filter { it.session_id != null }.map { d to it } }
    val needCount = s.approvals.size + failed.size + capped.size + macAsks.size
    // Timed runs by when they start, then the pages being watched.
    val next = (schedules.filter { it.enabled && it.next_run_at != null }.sortedBy { it.next_run_at } + schedules.filter { it.enabled && it.watch != null }).distinctBy { it.id }
    val shown = (working + failed + finished + capped).map { it.id }.toSet() + waitingOn + carded
    val pinned = live.filter { it.pinned && it.id !in shown }
    val quiet = live.count { it.id !in shown && !it.pinned }

    val steps by store.steps.collectAsState()
    // New session shrinks to its + while you scroll down the list and comes back on the way up.
    val homeList = androidx.compose.foundation.lazy.rememberLazyListState()
    var fabOpen by remember { mutableStateOf(true) }
    LaunchedEffect(homeList) {
        var last = 0
        snapshotFlow { homeList.firstVisibleItemIndex * 100000 + homeList.firstVisibleItemScrollOffset }.collect { at ->
            if (at < 40) fabOpen = true else if (at > last + 8) fabOpen = false else if (at < last - 8) fabOpen = true
            last = at
        }
    }
    // Watch what is working, so each row can say what it is doing now.
    val workingIds = working.map { it.id }
    DisposableEffect(workingIds) {
        workingIds.forEach { store.watch(it) }
        onDispose { workingIds.forEach { store.unwatch(it) } }
    }

    // Four tiles: the day at a glance, and which list is open under them. Home opens on the most
    // urgent list that has something; a tile you pick stays picked until you leave the app.
    val workCount = working.size
    val auto = when {
        needCount > 0 -> "need"
        readyCount > 0 -> "ready"
        workCount > 0 -> "work"
        else -> "next"
    }
    var picked by rememberSaveable { mutableStateOf<String?>(null) }
    val open = picked ?: auto
    val order = listOf("need", "ready", "work", "next")
    val caughtUp = needCount + readyCount + workCount == 0
    val timed = next.firstOrNull { it.next_run_at != null }
    val nextTile: Pair<String?, String> = timed?.next_run_at?.let { tileWhen(it) } ?: (if (next.isNotEmpty()) "Watching" to "${next.size}" else null to "—")
    val (nextDay, nextTime) = nextTile

    Scaffold(
        containerColor = c.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 22.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Bench", fontSize = 30.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.5).sp, modifier = Modifier.weight(1f), maxLines = 1)
                    IconButton(onClick = onSearch, modifier = Modifier.size(44.dp).background(c.surface1, CircleShape)) {
                        Icon(Icons.Default.Search, "Search sessions", tint = c.text)
                    }
                }
                ConnectionBanner(s.connected, s.dropped, s.error)
            }
        },
        floatingActionButton = {
            LabelFab("New", fabOpen, onNew)
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; store.refresh(); store.reconnectNow(); refreshing = false } },
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            // A sideways swipe moves to the next or previous list. Rows that swipe themselves
            // (archive) take the drag first.
            val swipeAt = with(androidx.compose.ui.platform.LocalDensity.current) { 72.dp.toPx() }
            LazyColumn(
                state = homeList,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 112.dp),
                modifier = Modifier.fillMaxSize().pointerInput(open) {
                    var dx = 0f
                    detectHorizontalDragGestures(
                        onDragStart = { dx = 0f },
                        onDragEnd = {
                            val i = order.indexOf(open)
                            if (dx < -swipeAt && i < order.size - 1) picked = order[i + 1]
                            if (dx > swipeAt && i > 0) picked = order[i - 1]
                        },
                    ) { _, d -> dx += d }
                },
            ) {
                if (shared.isNotEmpty() || sharedText != null) item("share") {
                    Text(if (shared.isEmpty()) "Choose a session to send what you shared to" else "Choose a session to attach ${shared.size} shared file${if (shared.size == 1) "" else "s"} to", color = c.info, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).background(c.info.copy(alpha = 0.10f), RoundedCornerShape(18.dp)).padding(16.dp))
                }
                item("tiles") {
                    Column(Modifier.padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HomeTile(Icons.Outlined.Notifications, "$needCount", null, "Needs you", open == "need", warn = needCount > 0, empty = needCount == 0, Modifier.weight(1f)) { picked = "need" }
                            HomeTile(Icons.Outlined.Inbox, "$readyCount", null, "Ready", open == "ready", empty = readyCount == 0, modifier = Modifier.weight(1f)) { picked = "ready" }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            HomeTile(Icons.Outlined.Bolt, "$workCount", null, "Working", open == "work", empty = workCount == 0, modifier = Modifier.weight(1f)) { picked = "work" }
                            HomeTile(Icons.Outlined.Schedule, nextTime, nextDay, "Coming up", open == "next", empty = next.isEmpty(), modifier = Modifier.weight(1f)) { picked = "next" }
                        }
                    }
                }
                if (spending?.paused == true) item("paused") {
                    Row(
                        Modifier.padding(top = 12.dp).fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface1).padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Unattended runs are paused", fontSize = 14.5.sp, modifier = Modifier.weight(1f))
                        Text("Resume", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = c.accent, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable {
                            scope.launch { runCatching { store.api.setSpending(paused = false) }.onSuccess { store.setSpending(it) } }
                        }.padding(4.dp))
                    }
                }
                if (caughtUp && s.ready && s.sessions.isNotEmpty() && picked == null) item("caught") {
                    Column(Modifier.padding(start = 6.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("All caught up", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text("Nothing needs you, and nothing is running.", fontSize = 14.sp, color = c.muted)
                    }
                }

                when (open) {
                    // -- Needs you: approvals and questions, answered here; what waits on the Mac; runs at their cap; errors.
                    "need" -> {
                        item("need-l") { Label("Needs you") }
                        items(s.approvals, key = { "a-${it.id}" }) { a ->
                            val session = s.sessions.firstOrNull { it.id == a.session_id }
                            ApprovalCard(
                                approval = a,
                                cwd = session?.cwd.orEmpty(),
                                sessionTitle = listOfNotNull(session?.title, s.projects.firstOrNull { it.id == session?.project_id }?.name ?: "Ask Bench").joinToString(" · "),
                                busy = a.id in answering,
                                onOpen = { onOpen(a.session_id) },
                                onTakeOver = { takingOver = true },
                                modifier = Modifier.padding(top = 10.dp).animateItem(),
                                onAnswer = { body ->
                                    answering = answering + a.id
                                    scope.launch {
                                        try { store.api.answer(a.id, body); store.dropApproval(a.id) }
                                        catch (e: BenchError) { snackbar.showSnackbar(e.message ?: "Could not answer") }
                                        finally { answering = answering - a.id }
                                    }
                                },
                            )
                        }
                        items(macAsks, key = { "m-${it.second.id}" }) { (d, a) ->
                            val run = s.sessions.firstOrNull { it.id == a.session_id }
                            MacAskCard(d, a, run, store, Modifier.padding(top = 10.dp).animateItem(), onOpen = { a.session_id?.let(onOpen) })
                        }
                        items(capped, key = { "c-${it.id}" }) { r ->
                            CapCard(r, spending?.run_cap ?: 2.0, store, Modifier.padding(top = 10.dp).animateItem(), onOpen = { onOpen(r.id) }) { msg -> scope.launch { snackbar.showSnackbar(msg) } }
                        }
                        items(failed, key = { "f-${it.id}" }) { f ->
                            // The error itself, so you can tell what broke before opening it.
                            val why = f.error?.lineSequence()?.map { it.trim() }?.firstOrNull { it.isNotEmpty() }?.take(140)
                            HomeCard(listOf(f.title, where(f, store, null)).filter { it.isNotBlank() }.joinToString(" · "), why ?: "Stopped with an error", c.bad, "Open", Modifier.padding(top = 10.dp).animateItem()) { onOpen(f.id) }
                        }
                        if (needCount == 0) item("need-none") { Quiet("Nothing needs you.") }
                    }

                    // -- Ready: what each session made, shown; finished conversations under them.
                    "ready" -> {
                        item("ready-l") { Label("Ready for you") }
                        items(reads, key = { "rd-${it.path}" }) { r ->
                            ReportCard(r, s.sessions.firstOrNull { it.id == r.session_id }?.title, Modifier.padding(top = 10.dp).animateItem()) { opened = r }
                        }
                        items(drafts, key = { "dr-${it.path}" }) { r ->
                            DraftsCard(r, s.sessions.firstOrNull { it.id == r.session_id }?.title, Modifier.padding(top = 10.dp).animateItem()) { opened = r }
                        }
                        if (readyCards > 0 && finished.isNotEmpty()) item("ready-gap") { Box(Modifier.height(8.dp)) }
                        // Folded after five: the first screen stays the first screen on a busy day.
                        val visible = if (moreReady) finished else finished.take(5)
                        items(visible, key = { "r-${it.id}" }) { r ->
                            SwipeToArchive(onArchive = {
                                scope.launch {
                                    runCatching { store.api.archive(r.id, true) }
                                    val undo = snackbar.showSnackbar("Archived “${r.title}”", actionLabel = "Undo", duration = androidx.compose.material3.SnackbarDuration.Short)
                                    if (undo == androidx.compose.material3.SnackbarResult.ActionPerformed) runCatching { store.api.archive(r.id, false) }
                                    store.refresh()
                                }
                            }) {
                                HomeRow(r.title, if (r.pause != null) "Paused" else "Finished", if (r.pause != null) c.warn else c.muted, where(r, store, null), Format.ago(r.updated_at), r.id, r.id == selected) { onOpen(r.id) }
                            }
                        }
                        if (finished.size > visible.size) item("ready-more") {
                            MoreLine("${finished.size - visible.size} more finished") { moreReady = true }
                        }
                        if (readyCount == 0) item("ready-none") { Quiet("Nothing new to read.") }
                    }

                    // -- Working: each with what it is doing now.
                    "work" -> {
                        item("work-l") { Label("Working") }
                        items(working, key = { "w-${it.id}" }) { w ->
                            WorkCard(w.title, steps[w.id] ?: "Working", where(w, store, if (w.schedule_id != null) "scheduled" else null), Format.ago(w.updated_at), w.id, Modifier.padding(top = 10.dp).animateItem()) { onOpen(w.id) }
                        }
                        if (workCount == 0) item("work-none") { Quiet("Nothing is running.") }
                    }

                    // -- Coming up: timed runs by when they start, then the pages being watched.
                    else -> {
                        item("next-l") { Label("Coming up", trailing = "All schedules", onTrailing = onSchedules) }
                        items(next, key = { "n-${it.id}" }) { n ->
                            val waits = if (spending?.paused == true) " · waits while paused" else ""
                            val w = n.watch
                            if (w != null) UpcomingRow(n.title, ai.bench.app.data.pageName(w.url) + waits, checkedLabel(n)) { onSchedule(n.id) }
                            else UpcomingRow(n.title, scheduleDays(n.days) + waits, whenNext(n.next_run_at)) { onSchedule(n.id) }
                        }
                        if (next.isEmpty()) item("next-none") { Quiet("Nothing scheduled.") }
                    }
                }

                if (pinned.isNotEmpty()) {
                    item("pinned-l") { Label("Pinned") }
                    items(pinned, key = { "p-${it.id}" }) { p -> HomeRow(p.title, null, c.muted, where(p, store, null), Format.ago(p.updated_at), p.id, p.id == selected) { onOpen(p.id) } }
                }

                if (!s.ready && s.sessions.isEmpty()) item("skeleton") { ListSkeleton() }
                if (s.ready && s.sessions.isEmpty()) item("empty") {
                    Text(if (s.projects.isEmpty()) "Add a project to get started." else "No sessions yet.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(top = 32.dp, start = 6.dp))
                }
                // Everything else is in its project; one quiet line says so. And what ran while
                // you were away, one line that opens Activity.
                if (quiet > 0 || away > 0) item("quiet") {
                    Column(Modifier.padding(top = 18.dp)) {
                        HorizontalDivider(color = c.line)
                        if (quiet > 0) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onSearch).padding(horizontal = 6.dp, vertical = 14.dp)) {
                            Text("$quiet quiet conversation${if (quiet == 1) " is" else "s are"} in ${if (quiet == 1) "its project" else "their projects"}", fontSize = 14.sp, color = c.muted, modifier = Modifier.weight(1f))
                            Text("Show", fontSize = 14.sp, color = c.muted)
                        }
                        if (away > 0) Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onActivity).padding(horizontal = 6.dp, vertical = 14.dp)) {
                            Text("Activity: $away run${if (away == 1) "" else "s"} while you were away", fontSize = 14.sp, color = c.muted, modifier = Modifier.weight(1f))
                            Text("See", fontSize = 14.sp, color = c.muted)
                        }
                    }
                }
            }
        }
    }

    opened?.let { r -> ReportViewer(store, r, onOpen = onOpen, onAbout = onAbout) { opened = null } }
    if (takingOver) BrowserView(store.api) { takingOver = false }
    listing?.let { list -> ReportList(list, s.sessions, onDismiss = { listing = null }) { listing = null; opened = it } }
}

/**
 * What a session made, open to read: drafts in their own screen, anything else in the file
 * viewer, with the run's "Then" steps under it. Home and Research both open reports here.
 */
@Composable
fun ReportViewer(store: BenchStore, r: Report, onOpen: (String) -> Unit, onAbout: () -> Unit, onClose: () -> Unit) {
    val s by store.state.collectAsState()
    val reports by store.reports.collectAsState()
    val schedules by store.schedules.collectAsState()
    val scope = rememberCoroutineScope()
    // Opening what a session made counts as looking at that session.
    LaunchedEffect(r.path) { store.markSeen(r.session_id) }
    if (r.kind == "drafts") ai.bench.app.ui.session.DraftsScreen(store, r, onAbout = { onClose(); onAbout() }) { onClose() }
    else {
        val files = remember(r.session_id) { ai.bench.app.ui.session.SessionFiles(store.api, r.session_id) }
        // A schedule's second report offers to keep one board instead.
        val sch = schedules.firstOrNull { it.id == r.schedule_id }
        var offered by remember(r.path) { mutableStateOf(sch != null && !sch.keep_board && r.kind != "board" && reports.count { it.schedule_id == sch.id && it.kind != "drafts" } >= 2 && !BoardOffers.dismissed(sch.id)) }
        ai.bench.app.ui.session.FileViewer(
            files, ai.bench.app.ui.session.FileInfo(r.path, r.path, r.name, if (r.name.endsWith(".html")) "text/html" else "text/markdown", r.size, true, "text", true),
            top = if (!offered || sch == null) null else ({
                BoardOffer(sch.title, onKeep = {
                    offered = false
                    scope.launch { runCatching { store.api.saveSchedule(sch.id, kotlinx.serialization.json.buildJsonObject { put("keepBoard", true) }) }; store.loadExtras() }
                }) { offered = false; BoardOffers.dismiss(sch.id) }
            }),
            bottom = s.sessions.firstOrNull { it.id == r.session_id }?.next_steps?.takeIf { it.isNotEmpty() }?.let { steps -> @Composable {
                ThenSteps(steps) { step ->
                    onClose()
                    scope.launch { runCatching { store.api.send(r.session_id, step) } }
                    onOpen(r.session_id)
                }
            } },
        ) { onClose() }
    }
}

/** "Then": what the run offered to do next, under a result (the chains board). A tap does it. */
@Composable
fun ThenSteps(steps: List<String>, onPick: (String) -> Unit) {
    val c = Bench.colors
    Column(
        Modifier.fillMaxWidth().background(c.surface1, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("Then", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.padding(start = 4.dp))
        steps.forEach { step ->
            Text(step, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surface3).clickable { onPick(step) }.padding(horizontal = 16.dp, vertical = 13.dp))
        }
    }
}

/**
 * The floating button: a + and a short label, sized to its label. Material's extended FAB has
 * a fixed gap after the icon and a minimum width, which leaves a short word like "New" adrift.
 * Folds to its + while the list scrolls down.
 */
@Composable
fun LabelFab(label: String, expanded: Boolean, onClick: () -> Unit) {
    val c = Bench.colors
    androidx.compose.material3.FloatingActionButton(onClick = onClick, containerColor = c.accent, contentColor = c.onAccent, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.padding(start = 16.dp, end = if (expanded) 20.dp else 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Add, if (expanded) null else label, modifier = Modifier.size(22.dp))
            androidx.compose.animation.AnimatedVisibility(expanded, enter = androidx.compose.animation.expandHorizontally() + androidx.compose.animation.fadeIn(), exit = androidx.compose.animation.shrinkHorizontally() + androidx.compose.animation.fadeOut()) {
                Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** What a ready card calls one thing to read. */
internal fun readLabel(r: Report) = when (r.kind) {
    "board" -> if ((r.new ?: 0) > 0) "Board · ${r.new} new" else "Board"
    "page" -> "Page"
    else -> "Report"
}

/** Boards the person said "Not now" to, for this run of the app. */
object BoardOffers {
    private val no = mutableSetOf<String>()
    fun dismissed(id: String) = id in no
    fun dismiss(id: String) { no += id }
}

/** The offer on a schedule's second report: keep one board instead. */
@Composable
private fun BoardOffer(title: String, onKeep: () -> Unit, onNotNow: () -> Unit) {
    val c = Bench.colors
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface1).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text("This is $title's second report. Keep one board instead? Each run updates it and marks what is new.", fontSize = 15.sp, lineHeight = 21.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Keep one board", primary = true, height = 42.dp, fill = false, onClick = onKeep)
            BigButton("Not now", height = 42.dp, fill = false, onClick = onNotNow)
        }
    }
}

/** "Mon 8:00", "Tomorrow 9:00", "Today 18:30": when a schedule next runs, from the phone's clock. */
private fun whenNext(iso: String?): String {
    val t = runCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()) }.getOrNull() ?: return ""
    val today = java.time.LocalDate.now()
    val time = t.format(java.time.format.DateTimeFormatter.ofPattern("H:mm"))
    return when (t.toLocalDate()) {
        today -> "Today $time"
        today.plusDays(1) -> "Tomorrow $time"
        // Within the week a weekday is enough; a week away, "Sat" would read as today.
        in today.plusDays(2)..today.plusDays(5) -> t.format(java.time.format.DateTimeFormatter.ofPattern("EEE")) + " $time"
        else -> t.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")) + ", $time"
    }
}

/** "Every Monday", "Weekdays", "Mon, Wed": a schedule's days in words. days: 0 Sunday .. 6 Saturday. */
fun scheduleDays(days: String): String {
    val d = days.split(',').mapNotNull { it.trim().toIntOrNull() }.toSet()
    val names = listOf("Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday")
    return when {
        d.size == 7 -> "Every day"
        d == setOf(1, 2, 3, 4, 5) -> "Weekdays"
        d == setOf(0, 6) -> "Weekends"
        d.size == 1 -> "Every ${names[d.first()]}"
        else -> d.sorted().joinToString(", ") { names[it].take(3) }
    }
}

/** An unattended run that stopped at its cap: go on with another run's worth, or stop there. */
@Composable
private fun CapCard(r: ai.bench.app.data.Session, runCap: Double, store: BenchStore, modifier: Modifier, onOpen: () -> Unit, onError: (String) -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    fun act(action: String) {
        busy = true
        scope.launch { runCatching { store.api.budget(r.id, action) }.onFailure { onError(it.message ?: "Couldn't do that") }; busy = false }
    }
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface1).clickable(onClick = onOpen).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(r.title, fontSize = 12.5.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Stopped at its ${dollars(r.budget ?: runCap)} cap", fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold)
        Text("What it made so far is kept.", fontSize = 14.5.sp, color = c.muted)
        Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BigButton("Add ${dollars(runCap)} and go on", primary = true, height = 44.dp, fill = false, enabled = !busy) { act("more") }
            BigButton("Stop here", height = 44.dp, fill = false, enabled = !busy) { act("stop") }
        }
    }
}

/** A run asked the Mac for something: it is decided on the Mac (the "Approve on your Mac" board). */
@Composable
private fun MacAskCard(d: ai.bench.app.data.LinkedDevice, a: ai.bench.app.data.DeviceAsk, run: ai.bench.app.data.Session?, store: BenchStore, modifier: Modifier, onOpen: () -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface1).clickable(onClick = onOpen).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(listOfNotNull(run?.title, store.state.value.projects.firstOrNull { it.id == run?.project_id }?.name).joinToString(" · "), fontSize = 12.5.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("Approve on your Mac", fontSize = 16.5.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "It asked for ${askTitle(a).replaceFirstChar { it.lowercase() }}. " +
                if (d.online) "It is waiting on your ${d.name}." else "Your ${d.name} is asleep, so the request waits there; the run carries on with other steps.",
            fontSize = 14.5.sp, lineHeight = 20.sp, color = c.muted,
        )
        Row(Modifier.padding(top = 6.dp)) {
            BigButton("Cancel request", height = 44.dp, fill = false) { scope.launch { runCatching { store.api.cancelDeviceAsk(a.id) }; store.loadDevices() } }
        }
    }
}

/** A card for something that needs a decision but is not an approval: an error, say. */
@Composable
private fun HomeCard(context: String, title: String, titleColor: Color, action: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Bench.colors
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface1).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(context, fontSize = 12.5.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = titleColor)
        }
        Text(action, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(50)).background(c.surface3).padding(horizontal = 16.dp, vertical = 9.dp))
    }
}

@Composable
private fun MoreLine(text: String, onClick: () -> Unit) {
    val c = Bench.colors
    Row(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(text, fontSize = 14.sp, color = c.muted)
        Icon(Icons.Default.KeyboardArrowDown, null, tint = c.muted, modifier = Modifier.size(16.dp))
    }
}

@Composable
private fun UpcomingRow(title: String, days: String, at: String, onClick: () -> Unit) {
    val c = Bench.colors
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(horizontal = 6.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(days, fontSize = 13.5.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(at, fontSize = 14.sp, color = c.muted)
    }
}

/** Several reports at once: pick one. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReportList(list: List<Report>, sessions: List<ai.bench.app.data.Session>, onDismiss: () -> Unit, onPick: (Report) -> Unit) {
    val c = Bench.colors
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.statusBarsPadding(), containerColor = c.surface2) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 20.dp)) {
            Text("Ready for you", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
            list.forEach { r ->
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).clickable { onPick(r) }.padding(horizontal = 6.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(r.title, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(listOfNotNull(when (r.kind) { "page" -> "Page"; "drafts" -> "Drafts"; else -> "Report" }, sessions.firstOrNull { it.id == r.session_id }?.title, Format.ago(r.modified)).joinToString(" · "), fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun FilterChip(text: String, on: Boolean, onClick: () -> Unit) {
    val c = Bench.colors
    Text(
        text, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        color = if (on) c.bg else c.muted,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) c.text else c.surface1).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

/** A section label: more space above than below, so it belongs to what follows. */
@Composable
internal fun Label(text: String, color: Color = Bench.colors.muted, trailing: String? = null, onTrailing: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color, modifier = if (onTrailing != null) Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onTrailing).padding(4.dp) else Modifier) }
    }
}

/** A list with nothing in it: one muted line. */
@Composable
internal fun Quiet(text: String) {
    Text(text, fontSize = 15.sp, color = Bench.colors.muted, modifier = Modifier.padding(start = 6.dp, top = 6.dp))
}

/** The next run for the Coming up tile: the day above, the time large. */
private fun tileWhen(iso: String): Pair<String?, String> {
    val t = runCatching { java.time.Instant.parse(iso).atZone(java.time.ZoneId.systemDefault()) }.getOrNull() ?: return null to "—"
    val today = java.time.LocalDate.now()
    val time = t.format(java.time.format.DateTimeFormatter.ofPattern("H:mm"))
    val day = when (t.toLocalDate()) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        in today.plusDays(2)..today.plusDays(5) -> t.format(java.time.format.DateTimeFormatter.ofPattern("EEEE"))
        else -> t.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM"))
    }
    return day to time
}

/**
 * One of Home's four tiles: how many, and the tab for that list. Only Needs you takes a colour,
 * and only when something needs you; the open tile is a quiet fill.
 */
@Composable
private fun HomeTile(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String, small: String?, label: String, on: Boolean, warn: Boolean = false, empty: Boolean = false, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val c = Bench.colors
    val bg by androidx.compose.animation.animateColorAsState(if (on) c.surface3 else c.surface1, label = "tile")
    Column(
        modifier.height(96.dp).clip(RoundedCornerShape(18.dp)).background(bg)
            .clickable(onClickLabel = "Show $label", onClick = onClick)
            .semantics { selected = on; contentDescription = "$label: ${listOfNotNull(small, value).joinToString(" ")}" }
            .padding(14.dp),
        verticalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Box(Modifier.size(34.dp).background(if (warn) c.warn else if (on) c.surface1 else c.surface2, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, tint = if (warn) c.bg else if (empty) c.muted.copy(alpha = 0.7f) else c.text, modifier = Modifier.size(18.dp))
            }
            Box(Modifier.weight(1f))
            Column(horizontalAlignment = Alignment.End) {
                small?.let { Text(it, fontSize = 12.sp, lineHeight = 14.sp, color = c.muted, maxLines = 1) }
                Text(value, fontSize = if (small != null) 22.sp else 26.sp, lineHeight = if (small != null) 26.sp else 30.sp, fontWeight = FontWeight.SemiBold, color = if (empty) c.muted.copy(alpha = 0.7f) else c.text, maxLines = 1)
            }
        }
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = if (on) c.text else if (empty) c.muted.copy(alpha = 0.7f) else c.muted, maxLines = 1)
    }
}

/** A report, page or board as Home shows it: what it says before you open it. */
@Composable
internal fun ReportCard(r: Report, from: String?, modifier: Modifier, onOpen: () -> Unit) {
    val c = Bench.colors
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface1).clickable(onClick = onOpen).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(listOfNotNull(readLabel(r), from, Format.ago(r.modified)).joinToString(" · "), fontSize = 12.5.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(r.title, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 3, overflow = TextOverflow.Ellipsis)
        r.gist?.let { Text(it, fontSize = 14.5.sp, lineHeight = 21.sp, color = c.muted, maxLines = 3, overflow = TextOverflow.Ellipsis) }
        val lines = r.preview.orEmpty()
        if (lines.isNotEmpty()) Column(Modifier.padding(top = 4.dp)) {
            lines.forEach { l ->
                HorizontalDivider(color = c.line)
                Column(Modifier.padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    Text(l.title, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    l.sub?.let { Text(it, fontSize = 12.5.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            Text("Read", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(50)).background(c.surface3).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 9.dp))
        }
    }
}

/** Email drafts as Home shows them: the first ones, who to and the subject. */
@Composable
internal fun DraftsCard(r: Report, from: String?, modifier: Modifier, onOpen: () -> Unit) {
    val c = Bench.colors
    val n = r.unsent ?: r.drafts ?: 0
    val lines = r.preview.orEmpty()
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(c.surface1).clickable(onClick = onOpen).padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(listOfNotNull("$n draft${if (n == 1) "" else "s"} to send", from, Format.ago(r.modified)).joinToString(" · "), fontSize = 12.5.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(r.title, fontSize = 19.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        lines.forEach { l ->
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface2).padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                l.sub?.let { Text(it, fontSize = 12.5.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                Text(l.title, fontSize = 14.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (n > lines.size && lines.isNotEmpty()) "+${n - lines.size} more" else "", fontSize = 13.sp, color = c.muted, modifier = Modifier.weight(1f))
            Text("Review", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clip(RoundedCornerShape(50)).background(c.surface3).clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 9.dp))
        }
    }
}

/** A working session: what it is doing now, in the accent. */
@Composable
internal fun WorkCard(title: String, step: String, where: String, ago: String, id: String, modifier: Modifier, onClick: () -> Unit) {
    val c = Bench.colors
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface1).clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).sharedTitle(id))
            Text(ago, fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(start = 12.dp))
        }
        Text(step, fontSize = 14.5.sp, color = c.accent, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (where.isNotEmpty()) Text(where, fontSize = 12.5.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

private fun Color.compositeOver(bg: Color) = Color(
    red = red * alpha + bg.red * (1 - alpha), green = green * alpha + bg.green * (1 - alpha), blue = blue * alpha + bg.blue * (1 - alpha), alpha = 1f,
)

/** Every session, searchable by title or project. Opened from Home's search button. */
@Composable
fun SessionsScreen(store: BenchStore, onOpen: (String) -> Unit, onBack: () -> Unit, askOnly: Boolean = false) {
    val s by store.state.collectAsState()
    val c = Bench.colors
    var query by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    // Long-press starts selecting; then a tap adds or removes a row.
    var picked by remember { mutableStateOf(setOf<String>()) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { if (!askOnly) runCatching { focus.requestFocus() } }
    val needs = s.approvals.map { it.session_id }.toSet()
    val rows = s.sessions.filter { (!askOnly || it.project_id == null) && (archived || it.archived_at == null) && (query.isBlank() || it.title.contains(query, true) || where(it, store, null).contains(query, true)) }
        .sortedByDescending { it.updated_at }
    androidx.activity.compose.BackHandler(enabled = picked.isNotEmpty()) { picked = emptySet() }
    fun archiveAll(on: Boolean) {
        val ids = picked.toList()
        picked = emptySet()
        scope.launch {
            ids.forEach { runCatching { store.api.archive(it, on) } }
            store.refresh()
            val r = snackbar.showSnackbar("${if (on) "Archived" else "Restored"} ${ids.size}", "Undo", duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) { ids.forEach { runCatching { store.api.archive(it, !on) } }; store.refresh() }
        }
    }
    Scaffold(containerColor = c.bg, snackbarHost = { SnackbarHost(snackbar) }, topBar = {
        if (picked.isNotEmpty()) {
            val allArchived = picked.all { id -> s.sessions.firstOrNull { it.id == id }?.archived_at != null }
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 6.dp, end = 8.dp, top = 8.dp, bottom = 8.dp).height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { picked = emptySet() }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Stop selecting") }
                Text("${picked.size} selected", fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                androidx.compose.material3.TextButton(onClick = { archiveAll(!allArchived) }) { Text(if (allArchived) "Restore" else "Archive", fontSize = 16.sp, color = c.accent) }
            }
        } else
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Row(Modifier.weight(1f).height(48.dp).background(c.surface1, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.text.BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = c.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text(if (askOnly) "Search Ask Bench" else "Search sessions", fontSize = 16.sp, color = c.muted); inner() } },
                )
            }
        }
    }) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad).imePadding(), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp)) {
            item { Segmented(listOf("open" to "Open", "all" to "Include archived"), if (archived) "all" else "open", { archived = it == "all" }, Modifier.padding(vertical = 8.dp)) }
            if (rows.isEmpty()) item { Text(if (query.isBlank()) "No sessions yet." else "Nothing matches \u201C$query\u201D.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(top = 24.dp, start = 6.dp)) }
            else item {
                Box(Modifier.padding(top = 10.dp)) {
                    Grouped(rows) { r ->
                        val (state, color) = when {
                            r.id in needs -> "Needs you" to c.warn
                            r.status == "running" -> "Working" to c.accent
                            r.archived_at != null -> "Archived" to c.muted
                            else -> Format.ago(r.updated_at) to c.muted
                        }
                        val toggle = { picked = if (r.id in picked) picked - r.id else picked + r.id }
                        ListRow(r.title, where(r, store, null), if (r.id in needs) "waiting" else r.status, state, color,
                            checked = if (picked.isEmpty()) null else r.id in picked, onLongClick = toggle) { if (picked.isEmpty()) onOpen(r.id) else toggle() }
                    }
                }
            }
        }
    }
}

/** The boards' segmented control: a pill of equal parts, the chosen one filled. */
@Composable
fun Segmented(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit, modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 44.dp) {
    val c = Bench.colors
    // A tonal track with the chosen part raised: quiet, so it never competes with the primary button.
    Row(modifier.fillMaxWidth().height(height).background(c.surface1, RoundedCornerShape(50)).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (id, label) ->
            val on = id == selected
            val bg by androidx.compose.animation.animateColorAsState(if (on) c.surface3 else Color.Transparent, label = "seg")
            Box(
                Modifier.weight(1f).fillMaxSize().background(bg, RoundedCornerShape(50)).clickable { onPick(id) },
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, color = if (on) c.text else c.muted, maxLines = 1)
            }
        }
    }
}

// -- Projects --------------------------------------------------------------------------------

/** Projects: Ask Bench first, then every project in one list with what's happening in it. */
@Composable
fun ProjectsScreen(store: BenchStore, onOpen: (String) -> Unit, onAdd: () -> Unit, onAsk: () -> Unit = {}) {
    val s by store.state.collectAsState()
    val c = Bench.colors
    val asks = s.sessions.count { it.project_id == null && it.archived_at == null }
    Scaffold(
        containerColor = c.bg,
        topBar = { TabTitle("Projects") },
        floatingActionButton = {
            LabelFab("Add project", true, onAdd)
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 112.dp)) {
            item("ask") {
                Box(Modifier.padding(top = 4.dp)) {
                    Grouped(listOf(0)) { ListRow("Ask Bench", "Questions about Bench itself: what's running, what it costs", "idle", if (asks > 0) "$asks" else null) { onAsk() } }
                }
            }
            if (s.projects.isNotEmpty()) {
                item("l") { Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 22.dp, bottom = 10.dp)) { Text("Projects", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.weight(1f)); Text("${s.projects.size}", fontSize = 13.sp, color = c.muted) } }
                item("list") {
                    val lastUsed = s.sessions.groupBy { it.project_id }.mapValues { (_, v) -> v.maxOf { it.updated_at } }
                    Grouped(s.projects.sortedByDescending { lastUsed[it.id].orEmpty() }) { p ->
                        val tasks = s.tasks.count { it.project_id == p.id && it.state == "active" }
                        val busy = s.sessions.any { it.project_id == p.id && it.status == "running" }
                        val needs = s.approvals.any { a -> s.sessions.firstOrNull { it.id == a.session_id }?.project_id == p.id }
                        ListRow(
                            p.name,
                            listOfNotNull(when (tasks) { 0 -> null; 1 -> "1 task"; else -> "$tasks tasks" }, lastUsed[p.id]?.let { "used ${Format.ago(it)} ago" }).joinToString(" · ").ifEmpty { "No work yet" },
                            when { needs -> "waiting"; busy -> "running"; else -> "idle" },
                            when { needs -> "Needs you"; busy -> "Working"; else -> null },
                            if (needs) c.warn else c.accent,
                        ) { onOpen(p.id) }
                    }
                }
            }
            if (s.ready && s.projects.isEmpty()) item { Text("No projects yet. Add one to clone a repository or use a folder on the server.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 24.dp)) }
        }
    }
}

@Composable
fun taskLine(t: BenchTask, s: Session?, needs: Boolean): Pair<String, Color> {
    val c = Bench.colors
    return when {
        needs -> "Needs you" to c.warn
        s?.status == "running" -> "Working" to c.accent
        t.loop?.state == "looping" -> "Looping" to c.accent
        t.check_status == "passed" -> "Ready to merge" to c.good
        t.check_status == "failed" -> "Check failed" to c.bad
        else -> Format.ago(t.updated_at) to c.muted
    }
}

@Composable
fun TabButton(label: String, on: Boolean, dot: String? = null, onClick: () -> Unit) {
    val c = Bench.colors
    // As wide as its label: the underline fills the tab, not the row (it had pushed the other tabs off screen).
    Column(Modifier.height(48.dp).width(androidx.compose.foundation.layout.IntrinsicSize.Max).clickable(onClick = onClick).padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            dot?.let { Box(Modifier.size(6.dp).background(if (it == "running") c.accent else c.dotIdle.copy(alpha = 0.45f), CircleShape)) }
            Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) c.text else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        }
        Box(Modifier.height(3.dp).fillMaxWidth().background(if (on) c.accent else Color.Transparent, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
    }
}


/**
 * Swipe a row toward the start to archive it, deliberately: the row has to be dragged
 * past half its width and released there. A quick flick or a diagonal brush while
 * scrolling springs back. Crossing the line ticks once and the backing turns solid,
 * so you know letting go will archive.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SwipeToArchive(onArchive: () -> Unit, content: @Composable () -> Unit) {
    val haptics = androidx.compose.ui.platform.LocalHapticFeedback.current
    var width by remember { mutableStateOf(1) }
    val holder = remember { arrayOfNulls<androidx.compose.material3.SwipeToDismissBoxState>(1) }
    val state = androidx.compose.material3.rememberSwipeToDismissBoxState(
        positionalThreshold = { it * 0.5f },
        confirmValueChange = { v ->
            // Only a drag held past halfway counts; a fast fling from a short drag does not.
            val dragged = holder[0]?.let { st -> runCatching { -st.requireOffset() }.getOrDefault(0f) } ?: 0f
            if (v == androidx.compose.material3.SwipeToDismissBoxValue.EndToStart && dragged >= width * 0.5f) {
                onArchive(); true
            } else false
        },
    )
    holder[0] = state
    val past by remember { androidx.compose.runtime.derivedStateOf { runCatching { -state.requireOffset() }.getOrDefault(0f) >= width * 0.5f } }
    LaunchedEffect(past) { if (past) haptics.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress) }
    androidx.compose.material3.SwipeToDismissBox(
        state = state,
        modifier = Modifier.onSizeChanged { width = it.width.coerceAtLeast(1) },
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            val c = Bench.colors
            val tint by androidx.compose.animation.animateColorAsState(if (past) c.warn else c.warn.copy(alpha = 0.18f), label = "archive")
            Row(
                Modifier.fillMaxSize().background(tint).padding(horizontal = Space.lg),
                horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Default.Archive, null, tint = if (past) c.onAccent else c.warn)
                Text(if (past) "Release to archive" else "Archive", color = if (past) c.onAccent else c.warn, fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = Space.sm))
            }
        },
    ) { Box(Modifier.background(Bench.colors.bg)) { content() } }
}
