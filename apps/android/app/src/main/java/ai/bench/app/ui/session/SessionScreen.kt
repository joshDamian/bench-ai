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
import androidx.compose.foundation.layout.consumeWindowInsets
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
import ai.bench.app.ui.ApprovalSheet
import ai.bench.app.data.Block
import ai.bench.app.data.blocks
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import kotlinx.serialization.json.put
import androidx.compose.material.icons.filled.ArrowUpward
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
fun SessionScreen(vm: SessionViewModel, onBack: () -> Unit, onTask: (String) -> Unit = {}, onTerminal: () -> Unit = {}) {
    var providers by remember { mutableStateOf<List<ai.bench.app.data.ProviderInfo>>(emptyList()) }
    var picking by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { providers = vm.providers() }
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
        // The screen handles the bottom itself: the message box sits on the keyboard when it is
        // up and above the navigation bar when it is down, never both.
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Bench.colors.bg),
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                title = {
                    val st = session?.status ?: "idle"
                    val waiting = approvals.isNotEmpty() || st == "waiting"
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(session?.title ?: "Session", maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Text(
                            if (waiting) "Needs you" else statusLabel(st) + (session?.let { " · " + Mode.of(it.permission_mode).label } ?: ""),
                            fontSize = 12.5.sp, maxLines = 1,
                            color = when { waiting -> Bench.colors.warn; st == "running" -> Bench.colors.accent; st == "error" -> Bench.colors.bad; else -> Bench.colors.muted },
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onTerminal) { Icon(Icons.Default.Terminal, "Terminal") }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(menu, { menu = false }) {
                            session?.task_id?.let { t -> DropdownMenuItem({ Text("Changes") }, onClick = { menu = false; onTask(t) }) }
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
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = list,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = Space.lg, vertical = Space.md),
                    verticalArrangement = Arrangement.spacedBy(18.dp),
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
                    val bl = blocks(rows)
                    items(bl, key = { it.key }, contentType = { it::class }) { b ->
                        when (b) {
                            is Block.User -> TranscriptRow(b.row, cwd, thinking, vm::openThinking)
                            is Block.Note -> TranscriptRow(b.row, cwd, thinking, vm::openThinking)
                            is Block.Turn -> TurnBlock(b, running && b.key == bl.lastOrNull()?.key, cwd, thinking, vm::openThinking)
                        }
                    }
                    if (stream.text.isNotEmpty() || stream.thinking.isNotEmpty()) item("stream") {
                        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            if (stream.thinking.isNotEmpty() && stream.text.isEmpty()) MutedText("Thinking…")
                            if (stream.text.isNotEmpty()) MarkdownText(stream.text)
                        }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(!follow, Modifier.align(Alignment.BottomEnd).padding(Space.lg)) {
                    SmallFloatingActionButton(onClick = {
                        follow = true
                        scope.launch { if (total > 0) list.animateScrollToItem(list.layoutInfo.totalItemsCount - 1, Int.MAX_VALUE / 2) }
                    }, shape = CircleShape, containerColor = Bench.colors.surface3, contentColor = Bench.colors.text) { Icon(Icons.Default.KeyboardArrowDown, "Jump to latest") }
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

            val pending = approvals.firstOrNull()
            if (pending != null) {
                ApprovalSheet(pending, cwd, onAnswer = { vm.answer(pending.id, it) }, busy = pending.id in busy, waiting = approvals.size)
            } else Composer(
                model = session?.let { modelLine(it, providers) } ?: "",
                onModel = { picking = true },
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

    if (picking) session?.let { ses ->
        ModelSheet(ses, providers, onDismiss = { picking = false }) { vm.configure(it) }
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

/** "Opus 4.7 · High": the session's model and effort, in words. */
private fun modelLine(s: ai.bench.app.data.Session, providers: List<ai.bench.app.data.ProviderInfo>): String {
    val m = providers.flatMap { it.models }.firstOrNull { it.id == s.model_id }?.label ?: "Default model"
    val e = s.effort?.let { ai.bench.app.data.Effort.of(it)?.label }
    return listOfNotNull(m, e).joinToString(" · ")
}

/** Choose the provider's model and the effort for this session; applies from the next turn. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModelSheet(s: ai.bench.app.data.Session, providers: List<ai.bench.app.data.ProviderInfo>, onDismiss: () -> Unit, onSet: (kotlinx.serialization.json.JsonObject) -> Unit) {
    val c = Bench.colors
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface2) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Model", fontSize = 22.sp)
            Text("Applies from the next message.", fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(bottom = 8.dp))
            providers.filter { it.enabled }.forEach { p ->
                Text(if (p.ready) p.name else "${p.name} · not set up", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = if (p.ready) c.accent else c.muted, modifier = Modifier.padding(top = 10.dp, bottom = 2.dp))
                p.models.filter { it.enabled }.forEach { m ->
                    val on = m.id == s.model_id
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp)
                            .background(if (on) c.accent.copy(alpha = 0.14f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(14.dp))
                            .then(if (p.ready) Modifier.clickable { onSet(kotlinx.serialization.json.buildJsonObject { put("providerId", p.id); put("modelId", m.id) }) } else Modifier)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(m.label, fontSize = 15.sp, color = if (p.ready) c.text else c.muted, modifier = Modifier.weight(1f))
                        if (on) Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Text("Effort", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.accent, modifier = Modifier.padding(top = 18.dp, bottom = 6.dp))
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                (listOf<Pair<String?, String>>(null to "Default") + ai.bench.app.data.Effort.entries.map { it.wire to it.label }).forEach { (wire, label) ->
                    androidx.compose.material3.FilterChip(
                        selected = s.effort == wire,
                        onClick = { onSet(kotlinx.serialization.json.buildJsonObject { if (wire == null) put("effort", kotlinx.serialization.json.JsonNull) else put("effort", wire) }) },
                        label = { Text(label, fontSize = 14.sp) },
                        shape = RoundedCornerShape(10.dp),
                        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(selectedContainerColor = c.accent.copy(alpha = 0.22f), selectedLabelColor = c.accent),
                    )
                }
            }
        }
    }
}

@Composable
private fun Composer(
    model: String,
    onModel: () -> Unit,
    draft: String,
    onDraft: (String) -> Unit,
    running: Boolean,
    canSend: Boolean,
    onAttach: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val c = Bench.colors
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)
            .background(c.surface2, RoundedCornerShape(26.dp))
            .border(1.dp, c.line, RoundedCornerShape(26.dp))
            .padding(start = 6.dp, end = 6.dp, top = 4.dp, bottom = 6.dp),
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = draft,
            onValueChange = onDraft,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = c.text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 180.dp).padding(horizontal = 12.dp, vertical = 11.dp),
            decorationBox = { inner ->
                Box {
                    if (draft.isEmpty()) Text(if (running) "Queue a message" else "Message Claude", fontSize = 16.sp, color = c.muted)
                    inner()
                }
            },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            IconButton(onClick = onAttach, modifier = Modifier.size(40.dp)) { Icon(Icons.Default.Add, "Attach", tint = c.muted) }
            Row(
                Modifier.height(32.dp).background(c.text.copy(alpha = 0.06f), RoundedCornerShape(16.dp)).clickable(onClick = onModel).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(model, fontSize = 13.sp, color = c.text.copy(alpha = 0.85f), maxLines = 1)
                Icon(Icons.Default.KeyboardArrowDown, null, tint = c.muted, modifier = Modifier.size(16.dp))
            }
            Box(Modifier.weight(1f))
            if (running && !canSend) {
                FilledIconButton(onClick = onStop, modifier = Modifier.size(40.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.bad.copy(alpha = 0.16f), contentColor = c.bad)) {
                    Box(Modifier.size(12.dp).background(c.bad, RoundedCornerShape(3.dp)))
                }
            } else {
                FilledIconButton(
                    onClick = onSend, enabled = canSend, modifier = Modifier.size(40.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.accent, contentColor = c.onAccent, disabledContainerColor = c.text.copy(alpha = 0.08f), disabledContentColor = c.muted),
                ) { Icon(Icons.Default.ArrowUpward, "Send", modifier = Modifier.size(20.dp)) }
            }
        }
    }
}
