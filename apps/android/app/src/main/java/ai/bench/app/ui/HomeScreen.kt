package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import ai.bench.app.BenchApp
import ai.bench.app.data.BenchError
import ai.bench.app.data.BenchStore
import ai.bench.app.data.Format
import ai.bench.app.data.Session
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.Space
import kotlinx.coroutines.launch

/**
 * What needs you, then your sessions by project, newest first. The one primary action is
 * starting a session; answering an approval is a tap on its card, here, without opening it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(store: BenchStore, onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val s by store.state.collectAsState()
    val shared by BenchApp.instance.pendingShare.collectAsState()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var refreshing by remember { mutableStateOf(false) }
    var projectFilter by rememberSaveable { mutableStateOf<String?>(null) }
    var newSession by remember { mutableStateOf(false) }
    var answering by remember { mutableStateOf(setOf<String>()) }

    val live = s.sessions.filter { it.archived_at == null && (projectFilter == null || it.project_id == projectFilter) }
        .sortedByDescending { it.updated_at }
    val projectName = s.projects.associate { it.id to it.name }
    val sessionById = s.sessions.associateBy { it.id }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        containerColor = Bench.colors.bg,
        topBar = {
            Column {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Bench.colors.bg),
                    title = { Text("Bench") },
                    actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
                )
                ConnectionBanner(s.connected, s.dropped, s.error)
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { newSession = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New session") })
        },
    ) { pad ->
        PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = { scope.launch { refreshing = true; store.refresh(); store.reconnectNow(); refreshing = false } },
            modifier = Modifier.fillMaxSize().padding(pad),
        ) {
            LazyColumn(
                contentPadding = PaddingValues(start = Space.lg, end = Space.lg, top = Space.sm, bottom = 96.dp()),
                verticalArrangement = Arrangement.spacedBy(Space.sm),
                modifier = Modifier.fillMaxSize(),
            ) {
                if (shared.isNotEmpty()) item("share") {
                    Text(
                        "Choose a session to attach ${shared.size} shared file${if (shared.size == 1) "" else "s"} to",
                        color = Bench.colors.info,
                        style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.fillMaxWidth().background(Bench.colors.info.copy(alpha = 0.10f), MaterialTheme.shapes.small).padding(Space.md),
                    )
                }
                if (s.approvals.isNotEmpty()) {
                    item("needs-head") { SectionHead("Needs you", s.approvals.size) }
                    items(s.approvals, key = { "a-${it.id}" }) { a ->
                        val session = sessionById[a.session_id]
                        ApprovalCard(
                            approval = a,
                            cwd = session?.cwd.orEmpty(),
                            sessionTitle = listOfNotNull(projectName[session?.project_id], session?.title).joinToString(" · "),
                            busy = a.id in answering,
                            onAnswer = { body ->
                                answering = answering + a.id
                                scope.launch {
                                    try {
                                        store.api.answer(a.id, body)
                                        store.dropApproval(a.id)
                                    } catch (e: BenchError) {
                                        snackbar.showSnackbar(e.message ?: "Could not answer")
                                    } finally {
                                        answering = answering - a.id
                                    }
                                }
                            },
                            modifier = Modifier.clickable { onOpen(a.session_id) },
                        )
                    }
                }
                if (s.projects.size > 1) item("filters") {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), modifier = Modifier.padding(top = Space.sm)) {
                        item { FilterChip(projectFilter == null, { projectFilter = null }, { Text("All") }) }
                        items(s.projects, key = { it.id }) { p ->
                            FilterChip(projectFilter == p.id, { projectFilter = if (projectFilter == p.id) null else p.id }, { Text(p.name) })
                        }
                    }
                }
                item("sessions-head") { SectionHead("Sessions", live.size) }
                if (s.ready && live.isEmpty()) item("empty") {
                    MutedText(if (s.projects.isEmpty()) "No projects yet. Add one from Bench on your computer." else "No sessions yet. Start one to give Claude some work.")
                }
                items(live, key = { it.id }) { session ->
                    SessionRow(session, projectName[session.project_id], s.tasks.firstOrNull { it.id == session.task_id }?.title) { onOpen(session.id) }
                }
            }
        }
    }

    if (newSession) NewSessionSheet(store, onDismiss = { newSession = false }, onCreated = { newSession = false; onOpen(it) })
}

private fun Int.dp() = androidx.compose.ui.unit.Dp(this.toFloat())

@Composable
private fun SectionHead(title: String, count: Int) {
    Row(Modifier.fillMaxWidth().padding(top = Space.md, bottom = Space.xs), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        MutedText("$count")
    }
}

@Composable
private fun SessionRow(s: Session, project: String?, task: String?, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(Bench.colors.surface1, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(Space.md),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.md),
    ) {
        StatusDot(s.status)
        Column(Modifier.weight(1f)) {
            Text(s.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MutedText(listOfNotNull(project, task, statusLabel(s.status)).joinToString(" · "), maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            MutedText(Format.ago(s.updated_at))
            if (s.cost > 0) MutedText(Format.cost(s.cost))
        }
    }
}
