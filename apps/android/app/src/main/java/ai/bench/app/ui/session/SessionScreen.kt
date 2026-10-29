package ai.bench.app.ui.session

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ai.bench.app.BenchApp
import ai.bench.app.data.Mode
import ai.bench.app.ui.ApprovalCard
import ai.bench.app.ui.MarkdownText
import ai.bench.app.ui.MutedText
import ai.bench.app.ui.StatusDot
import ai.bench.app.ui.statusLabel
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.Space
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionScreen(vm: SessionViewModel, onBack: () -> Unit) {
    val session by vm.session.collectAsState()
    val rows by vm.rows.collectAsState()
    val stream by vm.stream.collectAsState()
    val approvals by vm.approvals.collectAsState()
    val loading by vm.loading.collectAsState()
    val hasOlder by vm.hasOlder.collectAsState()
    val suggestion by vm.suggestion.collectAsState()
    val attachments by vm.attachments.collectAsState()
    val busy by vm.busyApprovals.collectAsState()
    val thinking by vm.thinking.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val list = rememberLazyListState()
    var draft by rememberSaveable(vm.sessionId) { mutableStateOf("") }
    var menu by remember { mutableStateOf(false) }
    var modeMenu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }

    val running = session?.status == "running"
    val cwd = session?.cwd.orEmpty()

    LaunchedEffect(Unit) { vm.errors.collect { snackbar.showSnackbar(it) } }

    // A file shared into the app while this session was chosen lands here.
    val shared by BenchApp.instance.pendingShare.collectAsState()
    LaunchedEffect(shared) {
        if (shared.isNotEmpty()) {
            vm.attach(context.contentResolver, shared)
            BenchApp.instance.pendingShare.value = emptyList()
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.attach(context.contentResolver, uris)
    }

    // The transcript sticks to the bottom while you are at the bottom, and leaves you be
    // when you have scrolled up to read.
    val total = rows.size + (if (stream.text.isNotEmpty() || stream.thinking.isNotEmpty()) 1 else 0) + approvals.size
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to atBottom }.distinctUntilChanged().collect { (scrolling, bottom) ->
            if (scrolling) follow = bottom
        }
    }
    LaunchedEffect(total, stream.text.length / 80, loading) {
        val last = list.layoutInfo.totalItemsCount - 1
        if (follow && last >= 0) list.scrollToItem(last, Int.MAX_VALUE / 2)
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bench.colors.bg),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    Column {
                        Text(session?.title ?: "Session", maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                            StatusDot(session?.status ?: "idle")
                            MutedText(statusLabel(session?.status ?: "idle") + (session?.let { " · " + Mode.of(it.permission_mode).label } ?: ""), maxLines = 1)
                        }
                    }
                },
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Permission mode…") }, onClick = { menu = false; modeMenu = true })
                            DropdownMenuItem({ Text("Rename") }, onClick = { menu = false; renaming = true })
                            DropdownMenuItem({ Text("Archive") }, onClick = { menu = false; vm.archive(onBack) })
                        }
                        DropdownMenu(modeMenu, { modeMenu = false }) {
                            Mode.entries.forEach { m ->
                                DropdownMenuItem(
                                    { Text(m.label + if (m.wire == session?.permission_mode) "  ✓" else "") },
                                    onClick = { modeMenu = false; vm.setMode(m.wire) },
                                )
                            }
                        }
                    }
                },
            )
        },
        containerColor = Bench.colors.bg,
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad).imePadding()) {
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.md),
                    verticalArrangement = Arrangement.spacedBy(Space.md),
                ) {
                    if (hasOlder) item("older") {
                        TextButton(onClick = vm::loadOlder, modifier = Modifier.fillMaxWidth()) { Text("Show earlier messages") }
                    }
                    if (loading && rows.isEmpty()) item("loading") {
                        Box(Modifier.fillMaxWidth().padding(Space.xl), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    }
                    if (!loading && rows.isEmpty()) item("empty") {
                        MutedText("Nothing here yet. Tell Claude what to work on.", Modifier.fillMaxWidth().padding(Space.xl))
                    }
                    items(rows, key = { it.key }, contentType = { it::class }) { row ->
                        TranscriptRow(row, cwd, thinking, vm::openThinking)
                    }
                    if (stream.text.isNotEmpty() || stream.thinking.isNotEmpty()) item("stream") {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            if (stream.thinking.isNotEmpty() && stream.text.isEmpty()) MutedText("Thinking…")
                            if (stream.text.isNotEmpty()) MarkdownText(stream.text)
                        }
                    }
                    items(approvals, key = { "approval-${it.id}" }) { a ->
                        ApprovalCard(a, cwd, onAnswer = { vm.answer(a.id, it) }, busy = a.id in busy)
                    }
                }
                androidx.compose.animation.AnimatedVisibility(!follow, Modifier.align(Alignment.BottomEnd).padding(Space.lg)) {
                    SmallFloatingActionButton(onClick = {
                        follow = true
                        scope.launch { if (total > 0) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE / 2) }
                    }) { Icon(Icons.Default.KeyboardArrowDown, "Jump to latest") }
                }
            }

            session?.pause?.let { p ->
                Text(
                    p.message.ifBlank { "Paused by the usage limit" },
                    color = Bench.colors.warn,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().background(Bench.colors.warn.copy(alpha = 0.10f)).padding(horizontal = Space.lg, vertical = Space.sm),
                )
            }

            if (suggestion.isNotBlank() && !running && approvals.isEmpty() && draft.isEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = Space.lg), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    item { AssistChip(onClick = { draft = suggestion }, label = { Text(suggestion, maxLines = 1, overflow = TextOverflow.Ellipsis) }) }
                }
            }

            if (attachments.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.xs), horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                    items(attachments, key = { it.key }) { a ->
                        Column {
                            InputChip(
                                selected = false,
                                onClick = { vm.removeAttachment(a.key) },
                                label = { Text(if (a.error != null) "${a.name}: ${a.error}" else a.name, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (a.error != null) Bench.colors.bad else Bench.colors.text) },
                                trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(16.dp), tint = Bench.colors.muted) },
                                modifier = Modifier.heightIn(max = 40.dp),
                            )
                            if (a.ref == null && a.error == null) LinearProgressIndicator(progress = { a.progress }, modifier = Modifier.fillMaxWidth())
                        }
                    }
                }
            }

            Composer(
                draft = draft,
                onDraft = { draft = it },
                running = running,
                canSend = draft.isNotBlank() || attachments.any { it.ref != null },
                onAttach = { picker.launch("*/*") },
                onSend = {
                    val text = draft
                    draft = ""
                    follow = true
                    scope.launch { if (!vm.send(text)) draft = text }
                },
                onStop = { vm.interrupt() },
            )
        }
    }

    if (renaming) {
        var title by remember { mutableStateOf(session?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Rename session") },
            text = { OutlinedTextField(title, { title = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { renaming = false; vm.rename(title) }, enabled = title.isNotBlank()) { Text("Rename") } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    running: Boolean,
    canSend: Boolean,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val c = Bench.colors
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surfaceRail)
            .navigationBarsPadding()
            .padding(horizontal = Space.sm, vertical = Space.sm),
        verticalAlignment = Alignment.Bottom,
    ) {
        IconButton(onClick = onAttach) { Icon(Icons.Default.AttachFile, "Attach", tint = c.muted) }
        TextField(
            value = draft,
            onValueChange = onDraft,
            modifier = Modifier.weight(1f).heightIn(min = 48.dp, max = 180.dp).verticalScroll(rememberScrollState()),
            placeholder = { Text(if (running) "Queue a message…" else "Message Claude") },
            shape = RoundedCornerShape(Bench.spec.radius.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = c.surface2, unfocusedContainerColor = c.surface2,
                focusedIndicatorColor = c.accent.copy(alpha = 0f), unfocusedIndicatorColor = c.accent.copy(alpha = 0f),
                cursorColor = c.accent,
            ),
        )
        if (running && !canSend) {
            FilledIconButton(onClick = onStop, colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.bad, contentColor = c.bg), modifier = Modifier.padding(start = Space.xs)) {
                Icon(Icons.Default.Stop, "Stop")
            }
        } else {
            FilledIconButton(onClick = onSend, enabled = canSend, modifier = Modifier.padding(start = Space.xs)) {
                Icon(Icons.AutoMirrored.Filled.Send, "Send")
            }
        }
    }
}
