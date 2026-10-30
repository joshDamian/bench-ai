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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.clip
import ai.bench.app.ui.Card
import ai.bench.app.ui.NavRow
import ai.bench.app.ui.SwitchRow
import ai.bench.app.ui.Custom
import ai.bench.app.ui.PickDialog
import ai.bench.app.ui.Confirm
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material.icons.filled.Check
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import kotlinx.serialization.json.put
import kotlinx.serialization.json.contentOrNull
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
fun SessionScreen(vm: SessionViewModel, onBack: () -> Unit, onTask: (String) -> Unit = {}, onTerminal: () -> Unit = {}, onResume: () -> Unit = {}) {
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

    // Dictation goes through the phone's own speech recogniser and lands in the message box.
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        r.data?.getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.let { said -> draft = if (draft.isBlank()) said else "$draft $said" }
    }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNotEmpty()) vm.attach(context.contentResolver, uris)
    }

    // The transcript is drawn bottom-up (reverseLayout): it opens on the latest message, and
    // while you are at the bottom, new messages and streaming text keep it there without any
    // scrolling of ours. Scrolled up to read, it stays put. Where you were reading is kept per
    // session and restored next time; at the bottom, nothing is kept and it opens at the end.
    val bl = remember(rows) { blocks(rows) }
    val files = remember(vm.sessionId) { SessionFiles(vm.api, vm.sessionId) }
    LaunchedEffect(bl) { files.stat(bl.mapNotNull { (it as? Block.Turn)?.reply?.takeIf { r -> r.isNotEmpty() } }) }
    val atBottom by remember { derivedStateOf { list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 48 } }
    // Following the conversation: on while you're at the bottom, off once you scroll up to read,
    // on again when you come back down, jump to latest, or send. A list keeps the item you were
    // looking at in place when one is added below it, so following means scrolling to the new one.
    var restored by remember(vm.sessionId) { mutableStateOf(false) }
    var stick by remember(vm.sessionId) { mutableStateOf(true) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress to atBottom }.collect { (scrolling, bottom) -> if (scrolling || bottom) stick = bottom }
    }
    val newest = (if (stream.text.isNotEmpty() || stream.thinking.isNotEmpty()) "stream" else "") + (bl.lastOrNull()?.key ?: "") + approvals.size
    LaunchedEffect(newest) { if (stick && restored) list.animateScrollToItem(0) }
    val memory = remember { BenchApp.instance.getSharedPreferences("scroll", 0) }
    LaunchedEffect(bl.isNotEmpty(), loading) {
        if (restored || bl.isEmpty() || loading) return@LaunchedEffect
        restored = true
        val key = memory.getString(vm.sessionId, null)
        val i = key?.substringBefore('|')?.let { k -> listKeys(bl, stream.text.isNotEmpty()).indexOf(k) } ?: -1
        if (i > 0) list.scrollToItem(i, key!!.substringAfter('|').toIntOrNull() ?: 0) else list.scrollToItem(0)
    }
    DisposableEffect(vm.sessionId) {
        onDispose {
            val e = memory.edit()
            if (list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset < 48) e.remove(vm.sessionId)
            else list.layoutInfo.visibleItemsInfo.firstOrNull()?.let { e.putString(vm.sessionId, "${it.key}|${list.firstVisibleItemScrollOffset}") }
            e.apply()
        }
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
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Session settings") }
                },
            )
        },
        containerColor = Bench.colors.bg,
    ) { pad -> WithFiles(files) {
        Column(Modifier.fillMaxSize().padding(pad).consumeWindowInsets(pad).imePadding()) {
            Box(Modifier.weight(1f)) {
                LazyColumn(
                    state = list,
                    reverseLayout = true,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = Space.lg, end = Space.lg, top = Space.md, bottom = Space.lg),
                    verticalArrangement = Arrangement.spacedBy(18.dp, Alignment.Bottom),
                ) {
                    // Newest first: item 0 sits at the bottom of the screen.
                    if (stream.text.isNotEmpty() || stream.thinking.isNotEmpty()) item("stream") {
                        Column(Modifier.animateItem(), verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                            if (stream.thinking.isNotEmpty() && stream.text.isEmpty()) MutedText("Thinking…")
                            if (stream.text.isNotEmpty()) MarkdownText(stream.text)
                        }
                    }
                    items(bl.asReversed(), key = { it.key }, contentType = { it::class }) { b ->
                        Box(Modifier.animateItem(fadeInSpec = androidx.compose.animation.core.tween(180), placementSpec = null, fadeOutSpec = null)) {
                            when (b) {
                                is Block.User -> TranscriptRow(b.row, cwd, thinking, vm::openThinking)
                                is Block.Note -> TranscriptRow(b.row, cwd, thinking, vm::openThinking)
                                is Block.Turn -> TurnBlock(b, running && b.key == bl.lastOrNull()?.key, cwd, thinking, vm::openThinking)
                            }
                        }
                    }
                    if (!loading && rows.isEmpty()) item("empty") {
                        MutedText("Nothing here yet. Tell Claude what to work on.", Modifier.fillMaxWidth().padding(Space.xl))
                    }
                    if (loading && rows.isEmpty()) item("loading") {
                        Box(Modifier.fillMaxWidth().padding(Space.xl), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(24.dp)) }
                    }
                    if (hasOlder) item("older") {
                        // Reaching the top loads the earlier part on its own.
                        LaunchedEffect(Unit) { vm.loadOlder() }
                        Box(Modifier.fillMaxWidth().padding(Space.md), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    }
                }
                androidx.compose.animation.AnimatedVisibility(
                    !atBottom, Modifier.align(Alignment.BottomCenter).padding(Space.md),
                    enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.8f),
                    exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.scaleOut(targetScale = 0.8f),
                ) {
                    SmallFloatingActionButton(
                        onClick = { stick = true; scope.launch { list.animateScrollToItem(0) } },
                        shape = CircleShape, containerColor = Bench.colors.surface3, contentColor = Bench.colors.text,
                    ) { Icon(Icons.Default.KeyboardArrowDown, "Jump to latest") }
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

            val commands by vm.commands.collectAsState()
            val slash = if (draft.startsWith("/") && !draft.contains(' ')) commands.filter { it.name.startsWith(draft.drop(1), ignoreCase = true) }.take(6) else emptyList()
            if (slash.isNotEmpty()) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp).background(Bench.colors.surface2, RoundedCornerShape(22.dp)).clip(RoundedCornerShape(22.dp))) {
                    slash.forEach { cmd ->
                        Column(Modifier.fillMaxWidth().clickable { draft = "/${cmd.name} " }.padding(horizontal = 16.dp, vertical = 10.dp)) {
                            Text("/" + cmd.name + if (cmd.argumentHint.isNotBlank()) " " + cmd.argumentHint else "", fontFamily = ai.bench.app.ui.theme.Mono, fontSize = 14.sp)
                            if (cmd.description.isNotBlank()) Text(cmd.description, fontSize = 12.5.sp, color = Bench.colors.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            } else if (suggestion.isNotBlank() && !running && approvals.isEmpty() && draft.isEmpty()) {
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 2.dp)) {
                    Text(suggestion, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.background(Bench.colors.surface1, RoundedCornerShape(18.dp)).clickable { draft = suggestion }.padding(horizontal = 14.dp, vertical = 9.dp))
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
                onDictate = { runCatching { dictate.launch(android.content.Intent(android.speech.RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(android.speech.RecognizerIntent.EXTRA_LANGUAGE_MODEL, android.speech.RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)) } },
                onSend = {
                    val text = draft
                    draft = ""
                    stick = true
                    scope.launch { list.scrollToItem(0); if (!vm.send(text)) draft = text }
                },
                onStop = { vm.interrupt() },
            )
        }
    } }

    if (menu) session?.let { ses ->
        SessionSheet(ses, providers, vm, onDismiss = { menu = false }, onRename = { menu = false; renaming = true }, onTask = onTask, onResume = { menu = false; onResume() }, onDeleted = onBack)
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
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface2, shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp), dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.surface3, RoundedCornerShape(2.dp))) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Model", fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp, bottom = 4.dp))
            providers.filter { it.enabled }.forEach { p ->
                Text(if (p.ready) p.name else "${p.name} · not set up", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 12.dp, bottom = 4.dp))
                p.models.filter { it.enabled }.forEach { m ->
                    val on = m.id == s.model_id
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 52.dp)
                            .background(if (on) c.accent.copy(alpha = 0.12f) else androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(16.dp))
                            .then(if (p.ready) Modifier.clickable { onSet(kotlinx.serialization.json.buildJsonObject { put("providerId", p.id); put("modelId", m.id) }) } else Modifier)
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(m.label, fontSize = 15.sp, color = if (p.ready) c.text else c.muted, modifier = Modifier.weight(1f))
                        if (on) Icon(Icons.Default.Check, null, tint = c.accent, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Text("Effort", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 18.dp, bottom = 8.dp))
            ai.bench.app.ui.Segmented(
                listOf("" to "Auto") + listOf("low", "medium", "high", "max").map { it to ai.bench.app.data.Effort.of(it)!!.label },
                s.effort ?: "",
                { e -> onSet(kotlinx.serialization.json.buildJsonObject { if (e.isEmpty()) put("effort", kotlinx.serialization.json.JsonNull) else put("effort", e) }) },
            )
            Text("Applies from your next message.", fontSize = 12.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, top = 10.dp))
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
    onDictate: () -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
) {
    val c = Bench.colors
    Column(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp)
            .background(c.surface2, RoundedCornerShape(28.dp))
            .padding(6.dp),
    ) {
        androidx.compose.foundation.text.BasicTextField(
            value = draft,
            onValueChange = onDraft,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = c.text),
            cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
            modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp, max = 180.dp).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 6.dp),
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
                FilledIconButton(onClick = onStop, modifier = Modifier.size(44.dp), shape = RoundedCornerShape(14.dp), colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.bad.copy(alpha = 0.16f), contentColor = c.bad)) {
                    Box(Modifier.size(12.dp).background(c.bad, RoundedCornerShape(3.dp)))
                }
            } else if (!canSend) {
                IconButton(onClick = onDictate, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.Mic, "Dictate", tint = c.muted) }
            } else {
                FilledIconButton(
                    onClick = onSend, enabled = canSend, modifier = Modifier.size(44.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.accent, contentColor = c.onAccent, disabledContainerColor = c.surface3, disabledContentColor = c.muted),
                ) { Icon(Icons.Default.ArrowUpward, "Send", modifier = Modifier.size(20.dp)) }
            }
        }
    }
}

/** The list's keys in drawing order (newest first), for finding a remembered item again. */
private fun listKeys(bl: List<Block>, streaming: Boolean): List<String> =
    (if (streaming) listOf("stream") else emptyList()) + bl.asReversed().map { it.key }

/** Session settings, as its board draws it: rename, mode, the usage limit, spend by model, archive and delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionSheet(
    s: ai.bench.app.data.Session, providers: List<ai.bench.app.data.ProviderInfo>, vm: SessionViewModel,
    onDismiss: () -> Unit, onRename: () -> Unit, onTask: (String) -> Unit, onResume: () -> Unit, onDeleted: () -> Unit,
) {
    val c = Bench.colors
    var mode by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss, containerColor = c.surface2, shape = RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.surface3, RoundedCornerShape(2.dp))) }) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text("Session", fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 6.dp))
            Card {
                NavRow("Rename", null, s.title) { onRename() }
                NavRow("Permission mode", Mode.of(s.permission_mode).label) { mode = true }
                s.task_id?.let { t -> NavRow("Task", null, "Changes, log and loop") { onDismiss(); onTask(t) } }
                var routing by remember { mutableStateOf<kotlinx.serialization.json.JsonElement?>(null) }
                LaunchedEffect(s.id) { routing = vm.routing() }
                routing?.let { r ->
                    val all = (r as? kotlinx.serialization.json.JsonObject)?.get("all")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
                    val off = (r as? kotlinx.serialization.json.JsonObject)?.get("enabled")?.toString() == "false"
                    val line = when {
                        all != null -> "All: ${ai.bench.app.ui.routingLabel(all, providers)}"
                        off -> "Off: as Claude Code decides"
                        else -> ai.bench.app.ui.TIERS.joinToString(" · ") { (k, label, _) -> "$label ${ai.bench.app.ui.routingLabel((r as kotlinx.serialization.json.JsonObject)[k]?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }, providers)}" }
                    }
                    NavRow("Subagents", null, line, chevron = false)
                }
                if (s.claude_session_id != null) NavRow("Open in Claude Code", null, "Its own terminal UI, resumed on the server") { onResume() }
                SwitchRow("Resume after the usage limit", s.pause?.let { "Paused: ${it.message}" } ?: "Picks up where it stopped when the limit lifts", s.auto_resume ?: true) { vm.setAutoResume(it) }
                if (s.pause != null) NavRow("Resume now", labelColor = c.accent, chevron = false) { vm.resumeNow(); onDismiss() }
            }
            val usage = s.model_usage?.entries?.mapNotNull { (k, v) -> (v as? kotlinx.serialization.json.JsonObject)?.get("cost")?.let { k to (it.toString().toDoubleOrNull() ?: 0.0) } }.orEmpty().sortedByDescending { it.second }
            Card {
                NavRow("Spend", ai.bench.app.data.Format.cost(s.cost), "${s.input_tokens + s.output_tokens} tokens", chevron = false)
                usage.forEach { (model, cost) ->
                    NavRow(providers.flatMap { it.models }.firstOrNull { it.name == model || it.id == model }?.label ?: model, ai.bench.app.data.Format.cost(cost), chevron = false)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (s.archived_at == null) ai.bench.app.ui.BigButton("Archive", Modifier.weight(1f), height = 52.dp) { vm.archive(onDeleted) }
                else ai.bench.app.ui.BigButton("Unarchive", Modifier.weight(1f), height = 52.dp) { vm.unarchive(); onDismiss() }
                ai.bench.app.ui.BigButton("Delete", Modifier.weight(1f), height = 52.dp, danger = true) { deleting = true }
            }
        }
    }
    if (mode) PickDialog("Permission mode", Mode.entries.map { it.wire to it.label }, s.permission_mode, onDismiss = { mode = false }) { vm.setMode(it) }
    if (deleting) Confirm("Delete this session?", "Its conversation is removed from Bench. This can't be undone.", "Delete", onDismiss = { deleting = false }) { vm.delete { onDismiss(); onDeleted() } }
}
