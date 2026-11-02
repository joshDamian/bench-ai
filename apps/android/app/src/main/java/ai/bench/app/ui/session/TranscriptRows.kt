package ai.bench.app.ui.session

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.unit.sp
import ai.bench.app.data.Block
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import ai.bench.app.data.Row
import ai.bench.app.data.Tone
import ai.bench.app.data.ToolCall
import ai.bench.app.data.Transcript
import ai.bench.app.data.arr
import ai.bench.app.data.get
import ai.bench.app.data.pretty
import ai.bench.app.data.str
import ai.bench.app.ui.CodeBlock
import ai.bench.app.ui.MarkdownText
import ai.bench.app.ui.MutedText
import ai.bench.app.ui.Pill
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.Mono
import ai.bench.app.ui.theme.Space
import ai.bench.app.ui.theme.Type

@Composable
fun TranscriptRow(row: Row, cwd: String, thinking: Map<String, String>, onOpenThinking: (Row.Thinking) -> Unit) {
    when (row) {
        is Row.User -> UserBubble(row)
        is Row.Text -> Box(Modifier.fillMaxWidth().padding(start = if (row.subagent) Space.lg else 0.dp)) { MarkdownText(row.text) }
        is Row.Thinking -> ThinkingBox(row, thinking[row.key], onOpenThinking)
        is Row.Tools -> ToolRun(row.calls, cwd, Modifier.padding(start = if (row.subagent) Space.lg else 0.dp))
        is Row.TurnEnd -> TurnEnd(row)
        is Row.System -> Text(
            row.text,
            color = when (row.tone) { Tone.Bad -> Bench.colors.bad; Tone.Info -> Bench.colors.info; Tone.Muted -> Bench.colors.muted },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.fillMaxWidth(),
        )
        is Row.Check -> Collapsible(
            head = {
                Pill(row.head, if (row.ok) Bench.colors.good else Bench.colors.bad)
                Text(row.command, fontFamily = Mono, fontSize = Type.sm, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = Space.sm))
            },
        ) { CodeBlock(row.output) }
    }
}

@Composable
private fun UserBubble(row: Row.User) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Column(
            Modifier
                .widthIn(max = 300.dp)
                .background(Bench.colors.surface3, RoundedCornerShape(22.dp, 22.dp, 6.dp, 22.dp))
                .padding(horizontal = 16.dp, vertical = 11.dp),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            if (row.attachments.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(Space.xs)) {
                row.attachments.forEach { Pill(it, Bench.colors.info) }
            }
            if (row.text.isNotEmpty()) Text(row.text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp))
        }
    }
}

/**
 * One turn as the session board draws it: "› Worked for 2m 54s · 14 steps", the reply,
 * then its cost. While Claude works the line is the step in progress.
 */
@Composable
fun TurnBlock(turn: Block.Turn, live: Boolean, cwd: String, thinking: Map<String, String>, onOpenThinking: (Row.Thinking) -> Unit) {
    val c = Bench.colors
    var open by rememberSaveable(turn.key) { mutableStateOf(false) }
    val clip = LocalClipboardManager.current
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val n = turn.stepCount
        if (turn.done || n > 0) {
            Row(
                Modifier.heightIn(min = 30.dp)
                    .background(if (live && !turn.done) androidx.compose.ui.graphics.Color.Transparent else c.surface1, RoundedCornerShape(15.dp))
                    .clickable { open = !open }
                    .padding(horizontal = if (live && !turn.done) 0.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val current = turn.current
                if (live && !turn.done) {
                    Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(16.dp).background(c.accent.copy(alpha = 0.2f), CircleShape))
                        Box(Modifier.size(8.dp).background(c.accent, CircleShape))
                    }
                    Text(
                        current?.let { (if (it.name == "Bash") "$ " else "") + Transcript.headline(it, cwd) } ?: "Working",
                        fontFamily = if (current?.name == "Bash") Mono else null,
                        fontSize = 13.sp, color = c.text.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text("step $n", fontSize = 13.5.sp, color = c.muted)
                } else {
                    Icon(if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight, null, tint = c.muted, modifier = Modifier.size(14.dp))
                    Text(
                        listOfNotNull(turn.label, if (n > 0) "$n step${if (n == 1) "" else "s"}" else null).joinToString(" · "),
                        fontSize = 12.5.sp, color = if (turn.ok) c.muted else c.bad,
                    )
                }
            }
        }
        if (open) Column(
            Modifier.padding(start = Space.sm).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(Space.sm),
        ) {
            turn.steps.forEach { r ->
                when (r) {
                    is Row.Tools -> r.calls.forEach { ToolCallRow(it, cwd, Modifier) }
                    else -> TranscriptRow(r, cwd, thinking, onOpenThinking)
                }
            }
        }
        turn.prose.forEach { MarkdownText(it) }
        if (turn.reply.isNotEmpty()) { MarkdownText(turn.reply); ArtifactCards(turn.reply) }
        if (turn.done && (turn.cost != null || turn.reply.isNotEmpty())) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            turn.cost?.let { Text(it, fontSize = 12.5.sp, color = c.muted) }
            if (turn.reply.isNotEmpty()) {
                var copied by remember(turn.key) { mutableStateOf(false) }
                LaunchedEffect(copied) { if (copied) { kotlinx.coroutines.delay(1500); copied = false } }
                Text(if (copied) "Copied" else "Copy reply", fontSize = 12.5.sp, color = c.muted,
                    modifier = Modifier.clickable { clip.setText(AnnotatedString(turn.reply)); copied = true }.padding(vertical = 6.dp))
            }
        }
    }
}

@Composable
private fun TurnEnd(row: Row.TurnEnd) {
    val clip = LocalClipboardManager.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(row.label, color = if (row.ok) Bench.colors.muted else Bench.colors.bad, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f))
        if (row.reply.isNotEmpty()) IconButton(onClick = { clip.setText(AnnotatedString(row.reply)) }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.ContentCopy, "Copy reply", tint = Bench.colors.muted, modifier = Modifier.size(16.dp))
        }
    }
}

@Composable
private fun ThinkingBox(row: Row.Thinking, loaded: String?, onOpen: (Row.Thinking) -> Unit) {
    val text = row.text ?: loaded
    Collapsible(
        head = { Text("Thinking" + if (row.length > 0) " · ${row.length} characters" else "", color = Bench.colors.muted, style = MaterialTheme.typography.labelMedium) },
        onOpen = { onOpen(row) },
    ) {
        if (text == null) MutedText("Loading…") else MarkdownText(text)
    }
}

/** A row that opens to show more. The open state survives scrolling away and back. */
@Composable
fun Collapsible(
    head: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    initiallyOpen: Boolean = false,
    flag: (@Composable () -> Unit)? = null,
    onOpen: () -> Unit = {},
    body: @Composable () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(initiallyOpen) }
    Column(
        modifier
            .fillMaxWidth()
            .background(Bench.colors.surface1, RoundedCornerShape(Bench.spec.radiusSm.dp))
            .animateContentSize(),
    ) {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open; if (open) onOpen() }.padding(horizontal = Space.sm, vertical = Space.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (open) Icons.Default.KeyboardArrowDown else Icons.AutoMirrored.Filled.KeyboardArrowRight,
                null,
                tint = Bench.colors.muted,
                modifier = Modifier.size(18.dp),
            )
            Row(Modifier.weight(1f).padding(start = Space.xs), verticalAlignment = Alignment.CenterVertically) { head() }
            flag?.invoke()
        }
        if (open) Box(Modifier.padding(start = Space.sm, end = Space.sm, bottom = Space.sm)) { body() }
    }
}

/** A run of tool calls folds to one line; one call is just that call. */
@Composable
private fun ToolRun(calls: List<ToolCall>, cwd: String, modifier: Modifier) {
    if (calls.size == 1) return ToolCallRow(calls[0], cwd, modifier)
    val failed = calls.any { it.failed }
    val pending = calls.any { it.pending }
    Collapsible(
        modifier = modifier,
        head = { Text(Transcript.summary(calls), style = MaterialTheme.typography.labelMedium, color = Bench.colors.muted) },
        flag = { RunFlag(pending, failed) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) { calls.forEach { ToolCallRow(it, cwd, Modifier) } }
    }
}

@Composable
private fun RunFlag(pending: Boolean, failed: Boolean) {
    when {
        pending -> ai.bench.app.ui.StatusDot("running", Modifier.padding(end = Space.xs))
        failed -> Pill("error", Bench.colors.bad)
    }
}

@Composable
private fun ToolCallRow(call: ToolCall, cwd: String, modifier: Modifier) {
    val i = call.input
    Collapsible(
        modifier = modifier,
        head = {
            Text(
                Transcript.headline(call, cwd),
                fontFamily = if (call.name == "Bash") Mono else null,
                fontSize = Type.sm,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                color = Bench.colors.text,
            )
        },
        flag = { RunFlag(call.pending, call.failed) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            when (call.name) {
                "Edit", "MultiEdit" -> {
                    val edits = if (call.name == "MultiEdit") i["edits"].arr().orEmpty() else listOf(i)
                    MiniDiff(edits.map { it["old_string"].str().orEmpty() to it["new_string"].str().orEmpty() })
                }
                "Write" -> CodeBlock(i["content"].str().orEmpty())
                "TodoWrite" -> Column {
                    i["todos"].arr().orEmpty().forEach { t ->
                        val st = t["status"].str()
                        Text(
                            (if (st == "completed") "✓ " else if (st == "in_progress") "▸ " else "○ ") + t["content"].str().orEmpty(),
                            color = if (st == "completed") Bench.colors.muted else Bench.colors.text,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                "Bash", "Read", "Glob", "Grep", "WebFetch", "WebSearch" -> Unit
                else -> if (i.isNotEmpty()) CodeBlock(i.pretty())
            }
            call.result?.let { r ->
                val text = r.text.lines().let { if (it.size > 200) (it.take(200) + "… ${it.size - 200} more lines").joinToString("\n") else r.text }
                if (text.isNotBlank()) CodeBlock(text, color = if (r.isError) Bench.colors.bad else Bench.colors.wellText)
            }
        }
    }
}

/** Old lines struck in red, new in green: an Edit's change at a glance. */
@Composable
fun MiniDiff(edits: List<Pair<String, String>>) {
    val c = Bench.colors
    val s = buildAnnotatedString {
        edits.forEachIndexed { n, (old, new) ->
            if (n > 0) append("\n")
            withStyle(SpanStyle(color = c.bad, background = c.bad.copy(alpha = 0.10f))) { old.lines().forEach { append("-$it\n") } }
            withStyle(SpanStyle(color = c.good, background = c.good.copy(alpha = 0.10f))) {
                new.lines().forEachIndexed { k, l -> append("+$l"); if (k < new.lines().size - 1) append("\n") }
            }
        }
    }
    Box(Modifier.fillMaxWidth().background(c.wellBg, RoundedCornerShape(Bench.spec.radiusSm.dp)).padding(Space.sm)) {
        Text(s, style = TextStyle(fontFamily = Mono, fontSize = Type.sm, color = c.wellText))
    }
}
