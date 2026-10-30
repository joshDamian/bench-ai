package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.ExperimentalMaterial3Api
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

/** Rows on one rounded surface, divided by hairlines. */
@Composable
fun <T> Grouped(items: List<T>, row: @Composable (T) -> Unit) {
    Column(Modifier.fillMaxWidth().background(Bench.colors.surface1, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))) {
        items.forEachIndexed { i, it ->
            row(it)
            if (i < items.size - 1) HorizontalDivider(color = Bench.colors.line.copy(alpha = 0.5f), modifier = Modifier.padding(start = 50.dp))
        }
    }
}

/** A session's dot: dim when idle, a soft ring while Claude works. */
@Composable
fun BigDot(status: String) {
    val c = Bench.colors
    val color = when (status) { "running" -> c.accent; "waiting" -> c.warn; "error" -> c.bad; "done" -> c.good; else -> c.dotIdle.copy(alpha = 0.45f) }
    Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
        if (status == "running") Box(Modifier.size(18.dp).background(color.copy(alpha = 0.2f), CircleShape))
        Box(Modifier.size(10.dp).background(color, CircleShape))
    }
}

@Composable
fun ListRow(title: String, sub: String?, dot: String, trailing: String? = null, trailingColor: Color = Bench.colors.muted, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 64.dp).clickable(onClick = onClick).padding(horizontal = Space.lg),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        BigDot(dot)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, fontSize = 12.5.sp, color = Bench.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        trailing?.let { Text(it, fontSize = 12.sp, color = trailingColor) }
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
 * Home, as the Android v2 board draws it: what needs you as one card you can answer here,
 * what is working with its live step, then sessions by day. One filled action: New session.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(store: BenchStore, onOpen: (String) -> Unit, onNew: () -> Unit, onSearch: () -> Unit) {
    val s by store.state.collectAsState()
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refreshing by remember { mutableStateOf(false) }
    var answering by remember { mutableStateOf(setOf<String>()) }
    val shared by BenchApp.instance.pendingShare.collectAsState()

    val needs = s.approvals.map { it.session_id }.toSet()
    val working = s.sessions.filter { it.status == "running" && it.archived_at == null }.sortedByDescending { it.updated_at }
    val rest = s.sessions.filter { it.status != "running" && it.archived_at == null && it.id !in needs }
        .sortedByDescending { it.updated_at }.take(30)
    val byDay = rest.groupBy { dayOf(it.updated_at) }

    Scaffold(
        containerColor = c.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Column {
                Row(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(start = 22.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Bench", fontSize = 30.sp, fontWeight = FontWeight.Medium, letterSpacing = (-0.5).sp, modifier = Modifier.weight(1f))
                    IconButton(onClick = onSearch, modifier = Modifier.size(44.dp).background(c.surface1, CircleShape)) {
                        Icon(Icons.Default.Search, "Search sessions", tint = c.text)
                    }
                }
                ConnectionBanner(s.connected, s.dropped, s.error)
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNew,
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New session", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.onAccent) },
                containerColor = c.accent, contentColor = c.onAccent, shape = RoundedCornerShape(18.dp),
            )
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; store.refresh(); store.reconnectNow(); refreshing = false } },
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 112.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (shared.isNotEmpty()) item("share") {
                    Text("Choose a session to attach ${shared.size} shared file${if (shared.size == 1) "" else "s"} to", color = c.info, fontSize = 14.sp,
                        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).background(c.info.copy(alpha = 0.10f), RoundedCornerShape(18.dp)).padding(16.dp))
                }
                if (s.approvals.isNotEmpty()) {
                    item("needs") { Label("Needs you", c.warn, "${s.approvals.size}") }
                    items(s.approvals, key = { "a-${it.id}" }) { a ->
                        val session = s.sessions.firstOrNull { it.id == a.session_id }
                        ApprovalCard(
                            approval = a,
                            cwd = session?.cwd.orEmpty(),
                            sessionTitle = listOfNotNull(s.projects.firstOrNull { it.id == session?.project_id }?.name ?: "Ask Bench", session?.title).joinToString(" · "),
                            busy = a.id in answering,
                            onOpen = { onOpen(a.session_id) },
                            modifier = Modifier.padding(bottom = 10.dp).animateItem(),
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
                }
                if (working.isNotEmpty()) {
                    item("working") { Label("Working", c.muted, "${working.size}") }
                    item("working-list") { Grouped(working) { w -> ListRow(w.title, where(w, store, "Working · ${Format.ago(w.updated_at)}"), "running") { onOpen(w.id) } } }
                }
                byDay.forEach { (day, list) ->
                    item("day-$day") { Label(day) }
                    item("list-$day") { Grouped(list) { r -> ListRow(r.title, where(r, store, null), r.status, Format.ago(r.updated_at)) { onOpen(r.id) } } }
                }
                if (s.ready && s.sessions.isEmpty()) item("empty") {
                    Text(if (s.projects.isEmpty()) "Add a project to get started." else "No sessions yet.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(top = 32.dp, start = 6.dp))
                }
            }
        }
    }
}

/** A section label: more space above than below, so it belongs to what follows. */
@Composable
private fun Label(text: String, color: Color = Bench.colors.muted, trailing: String? = null) {
    Row(Modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 22.dp, bottom = 10.dp)) {
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = color, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color) }
    }
}

private fun Color.compositeOver(bg: Color) = Color(
    red = red * alpha + bg.red * (1 - alpha), green = green * alpha + bg.green * (1 - alpha), blue = blue * alpha + bg.blue * (1 - alpha), alpha = 1f,
)

/** Every session, searchable by title or project. Opened from Home's search button. */
@Composable
fun SessionsScreen(store: BenchStore, onOpen: (String) -> Unit, onBack: () -> Unit) {
    val s by store.state.collectAsState()
    val c = Bench.colors
    var query by rememberSaveable { mutableStateOf("") }
    var archived by rememberSaveable { mutableStateOf(false) }
    val focus = remember { androidx.compose.ui.focus.FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val needs = s.approvals.map { it.session_id }.toSet()
    val rows = s.sessions.filter { (archived || it.archived_at == null) && (query.isBlank() || it.title.contains(query, true) || where(it, store, null).contains(query, true)) }
        .sortedByDescending { it.updated_at }
    Scaffold(containerColor = c.bg, topBar = {
        Row(Modifier.fillMaxWidth().statusBarsPadding().padding(start = 6.dp, end = 16.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
            Row(Modifier.weight(1f).height(48.dp).background(c.surface1, RoundedCornerShape(24.dp)).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                androidx.compose.foundation.text.BasicTextField(
                    query, { query = it }, singleLine = true,
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, color = c.text),
                    cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                    modifier = Modifier.weight(1f).focusRequester(focus),
                    decorationBox = { inner -> Box { if (query.isEmpty()) Text("Search sessions", fontSize = 16.sp, color = c.muted); inner() } },
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
                        ListRow(r.title, where(r, store, null), if (r.id in needs) "waiting" else r.status, state, color) { onOpen(r.id) }
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

@Composable
fun ProjectsScreen(store: BenchStore, onOpen: (String) -> Unit, onAdd: () -> Unit) {
    val s by store.state.collectAsState()
    val c = Bench.colors
    Scaffold(
        containerColor = c.bg,
        topBar = { TabTitle("Projects") },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAdd, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add project", fontSize = 15.sp, fontWeight = FontWeight.Medium) },
                containerColor = c.accent.copy(alpha = 0.22f).compositeOver(c.surface1), contentColor = c.accent)
        },
    ) { pad ->
        LazyColumn(Modifier.fillMaxSize().padding(pad), contentPadding = PaddingValues(start = Space.lg, end = Space.lg, bottom = 96.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.ready && s.projects.isEmpty()) item { MutedText("No projects yet. Add one to clone a repository or use a folder on the server.") }
            items(s.projects, key = { it.id }) { p ->
                val tasks = s.tasks.count { it.project_id == p.id && it.state == "active" }
                val busy = s.sessions.any { it.project_id == p.id && it.status == "running" }
                val needs = s.approvals.any { a -> s.sessions.firstOrNull { it.id == a.session_id }?.project_id == p.id }
                Row(
                    Modifier.fillMaxWidth().background(c.surface1, RoundedCornerShape(20.dp)).clickable { onOpen(p.id) }.padding(horizontal = Space.lg, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Box(Modifier.size(10.dp).background(when { needs -> c.warn; busy -> c.accent; tasks > 0 -> c.accent.copy(alpha = 0.6f); else -> c.dotIdle.copy(alpha = 0.45f) }, CircleShape))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text(p.path.replace(Regex("^/(home|Users)/[^/]+"), "~"), fontSize = 12.5.sp, color = c.muted, fontFamily = Mono, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(when (tasks) { 0 -> "No tasks"; 1 -> "1 task"; else -> "$tasks tasks" }, fontSize = 12.5.sp, color = c.muted)
                }
            }
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
    Column(Modifier.height(48.dp).clickable(onClick = onClick).padding(horizontal = 12.dp), verticalArrangement = Arrangement.Center) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            dot?.let { Box(Modifier.size(6.dp).background(if (it == "running") c.accent else c.dotIdle.copy(alpha = 0.45f), CircleShape)) }
            Text(label, fontSize = 14.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal, color = if (on) c.text else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        }
        Box(Modifier.height(3.dp).fillMaxWidth().background(if (on) c.accent else Color.Transparent, RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)))
    }
}
