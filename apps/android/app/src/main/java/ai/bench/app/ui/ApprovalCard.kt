package ai.bench.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bench.app.data.Approval
import ai.bench.app.data.Transcript
import ai.bench.app.data.arr
import ai.bench.app.data.bool
import ai.bench.app.data.get
import ai.bench.app.data.pretty
import ai.bench.app.data.str
import ai.bench.app.ui.session.MiniDiff
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.Mono
import ai.bench.app.ui.theme.Space
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** What Claude is asking to do, as a short verb phrase. */
fun askLine(a: Approval) = when (a.tool) {
    "Bash" -> "Run a command"
    "Edit", "MultiEdit" -> "Edit a file"
    "Write" -> "Create a file"
    "WebFetch" -> "Open a web page"
    "WebSearch" -> "Search the web"
    "ExitPlanMode" -> "Claude has a plan"
    "AskUserQuestion" -> "Claude is asking"
    else -> a.title ?: "Use ${a.tool}"
}

private fun answer(allow: Boolean, more: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
    buildJsonObject { put("allow", allow); more() }

/**
 * Inside a session, as the approval board draws it: a sheet rising from the bottom where the
 * message box was, with one big Allow and the other answers beneath.
 */
@Composable
fun ApprovalSheet(approval: Approval, cwd: String, onAnswer: (JsonObject) -> Unit, busy: Boolean = false, waiting: Int = 1) {
    val c = Bench.colors
    var denying by remember(approval.id) { mutableStateOf(false) }
    var note by remember(approval.id) { mutableStateOf("") }
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(24.dp, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
            .background(c.surface2, RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp))
            .navigationBarsPadding()
            .padding(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).background(c.surface3, RoundedCornerShape(2.dp)))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(askLine(approval), fontSize = 20.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            if (waiting > 1) Text("1 of $waiting", fontSize = 12.5.sp, color = c.muted)
        }
        when (approval.tool) {
            "ExitPlanMode" -> {
                Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) { MarkdownText(approval.input["plan"].str().orEmpty()) }
                BigButton("Approve · Accept edits", primary = true, enabled = !busy) { onAnswer(answer(true) { put("mode", "acceptEdits") }) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("Ask before edits", Modifier.weight(1f), height = 48.dp, enabled = !busy) { onAnswer(answer(true) { put("mode", "default") }) }
                    BigButton("Keep planning…", Modifier.weight(1f), height = 48.dp, enabled = !busy) { denying = true }
                }
            }
            "AskUserQuestion" -> Questions(approval, busy, onAnswer)
            else -> {
                Preview(approval, cwd, big = true)
                BigButton("Allow", primary = true, enabled = !busy) { onAnswer(answer(true)) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("Always allow", Modifier.weight(1f), height = 48.dp, enabled = !busy) { onAnswer(answer(true) { put("always", true) }) }
                    BigButton("Deny…", Modifier.weight(1f), height = 48.dp, danger = true, enabled = !busy) { denying = true }
                }
            }
        }
        if (denying) {
            OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), placeholder = { Text("Tell Claude why, or what to do instead") }, shape = RoundedCornerShape(16.dp))
            BigButton(if (approval.tool == "ExitPlanMode") "Send" else "Deny", height = 48.dp, danger = approval.tool != "ExitPlanMode", enabled = !busy) {
                onAnswer(answer(false) { put("message", note.ifBlank { if (approval.tool == "ExitPlanMode") "Keep planning." else "" }) })
            }
        }
    }
}

/** On Home, as its board draws it: the session it comes from, what it asks, and the answers in a row. */
@Composable
fun ApprovalCard(
    approval: Approval,
    cwd: String,
    onAnswer: (JsonObject) -> Unit,
    modifier: Modifier = Modifier,
    sessionTitle: String? = null,
    busy: Boolean = false,
    onOpen: () -> Unit = {},
) {
    val c = Bench.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(c.surface2, RoundedCornerShape(24.dp))
            .clickable(onClick = onOpen)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        sessionTitle?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(8.dp).background(c.warn, CircleShape))
                Text(it, fontSize = 12.5.sp, color = c.muted, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
            }
        }
        Text(askLine(approval), fontSize = 17.sp, fontWeight = FontWeight.Medium)
        when (approval.tool) {
            "ExitPlanMode", "AskUserQuestion" -> BigButton("Open", primary = true, height = 48.dp, size = 15.sp, onClick = onOpen)
            else -> {
                Preview(approval, cwd, big = false)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 2.dp)) {
                    BigButton("Allow", Modifier.weight(1f), primary = true, height = 48.dp, size = 15.sp, enabled = !busy) { onAnswer(answer(true)) }
                    BigButton("Deny", height = 48.dp, size = 15.sp, enabled = !busy, fill = false) { onAnswer(answer(false) { put("message", "") }) }
                }
            }
        }
    }
}

@Composable
private fun Preview(a: Approval, cwd: String, big: Boolean) {
    val c = Bench.colors
    val i = a.input
    when (a.tool) {
        "Bash" -> Box(
            Modifier.fillMaxWidth().background(c.wellBg, RoundedCornerShape(if (big) 16.dp else 14.dp))
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = if (big) 14.dp else 12.dp, vertical = if (big) 12.dp else 10.dp),
        ) {
            Text(i["command"].str().orEmpty(), fontFamily = Mono, fontSize = if (big) 13.5.sp else 12.5.sp, lineHeight = if (big) 20.sp else 18.sp, color = c.wellText)
        }
        "Edit", "MultiEdit" -> {
            MutedText(Transcript.rel(i["file_path"].str(), cwd))
            val edits = if (a.tool == "MultiEdit") i["edits"].arr().orEmpty() else listOf(i)
            Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                MiniDiff(edits.map { (it["old_string"].str().orEmpty()) to (it["new_string"].str().orEmpty()) })
            }
        }
        "Write" -> {
            MutedText(Transcript.rel(i["file_path"].str(), cwd))
            CodeBlock(i["content"].str().orEmpty().lines().take(if (big) 30 else 8).joinToString("\n"))
        }
        else -> {
            a.reason?.let { MutedText(it) }
            if (i.isNotEmpty()) CodeBlock(i.pretty().lines().take(if (big) 30 else 8).joinToString("\n"))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Questions(approval: Approval, busy: Boolean, onAnswer: (JsonObject) -> Unit) {
    val c = Bench.colors
    val answers = remember(approval.id) { mutableStateMapOf<String, String>() }
    val qs = approval.input["questions"].arr().orEmpty()
    Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(Space.md)) {
        qs.forEach { q ->
            val question = q["question"].str().orEmpty()
            val multi = q["multiSelect"].bool()
            Text(question, fontSize = 15.sp)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                q["options"].arr().orEmpty().forEach { o ->
                    val label = o["label"].str().orEmpty()
                    val current = answers[question].orEmpty()
                    val picked = if (multi) label in current.split(", ") else current == label
                    FilterChip(
                        selected = picked,
                        onClick = {
                            answers[question] = if (!multi) label else {
                                val set = current.split(", ").filter { it.isNotEmpty() }.toMutableSet()
                                if (!set.add(label)) set.remove(label)
                                set.joinToString(", ")
                            }
                        },
                        label = { Text(label) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accent.copy(alpha = 0.22f)),
                    )
                }
            }
            val typed = answers[question].orEmpty().takeIf { a -> q["options"].arr().orEmpty().none { it["label"].str() == a } }.orEmpty()
            OutlinedTextField(typed, { answers[question] = it }, Modifier.fillMaxWidth(), placeholder = { Text("Or type an answer") }, shape = RoundedCornerShape(16.dp))
        }
    }
    val done = qs.all { !answers[it["question"].str().orEmpty()].isNullOrBlank() }
    BigButton("Answer", primary = true, enabled = !busy && done) {
        onAnswer(answer(true) { put("answers", JsonObject(answers.mapValues { JsonPrimitive(it.value) })) })
    }
    TextButton(enabled = !busy, onClick = { onAnswer(answer(false) { put("message", "The user declined to answer.") }) }, modifier = Modifier.fillMaxWidth()) {
        Text("Skip", color = c.muted)
    }
}

/** The boards' pill buttons: filled accent, outlined, or outlined in the danger colour. */
@Composable
fun BigButton(
    text: String,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    danger: Boolean = false,
    height: Dp = 52.dp,
    size: TextUnit = if (height >= 52.dp) 16.sp else 14.sp,
    enabled: Boolean = true,
    fill: Boolean = true,
    onClick: () -> Unit,
) {
    val c = Bench.colors
    val m = modifier.then(if (fill) Modifier.fillMaxWidth() else Modifier).height(height)
    if (primary) {
        Button(onClick, m, enabled = enabled, shape = RoundedCornerShape(50), colors = ButtonDefaults.buttonColors(containerColor = c.accent, contentColor = c.onAccent)) {
            Text(text, fontSize = size, fontWeight = FontWeight.SemiBold)
        }
    } else {
        // Secondary actions are tonal, never outlined (Material 3 Expressive).
        Button(
            onClick, m, enabled = enabled, shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = c.surface3, contentColor = if (danger) c.bad else c.text),
        ) { Text(text, fontSize = size, fontWeight = FontWeight.Medium) }
    }
}
