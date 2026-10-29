package ai.bench.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ai.bench.app.data.Approval
import ai.bench.app.data.arr
import ai.bench.app.data.bool
import ai.bench.app.data.get
import ai.bench.app.data.pretty
import ai.bench.app.data.str
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.Space
import ai.bench.app.ui.session.MiniDiff
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A tool call waiting on the person: a permission, a question or a plan. Mirrors the web's
 * ApprovalCard, with the same answers sent back (src/contract.d.mts ApprovalAnswer).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ApprovalCard(
    approval: Approval,
    cwd: String,
    onAnswer: (JsonObject) -> Unit,
    modifier: Modifier = Modifier,
    sessionTitle: String? = null,
    busy: Boolean = false,
) {
    val c = Bench.colors
    val i = approval.input
    var note by remember(approval.id) { mutableStateOf("") }
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = c.surface2,
        border = BorderStroke(1.dp, c.warn.copy(alpha = 0.5f)),
    ) {
        Column(Modifier.padding(Space.md), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
            sessionTitle?.let { MutedText(it, maxLines = 1) }
            when (approval.tool) {
                "ExitPlanMode" -> {
                    Text("Claude has a plan", style = MaterialTheme.typography.titleSmall)
                    Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                        MarkdownText(i["plan"].str().orEmpty())
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Button(enabled = !busy, onClick = { onAnswer(answer(true) { put("mode", "acceptEdits") }) }) { Text("Approve, accept edits") }
                        OutlinedButton(enabled = !busy, onClick = { onAnswer(answer(true) { put("mode", "default") }) }) { Text("Approve, ask before edits") }
                    }
                    OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), placeholder = { Text("What should change?") }, singleLine = false)
                    TextButton(enabled = !busy, onClick = { onAnswer(answer(false) { put("message", note.ifBlank { "Keep planning." }) }) }) { Text("Keep planning") }
                }

                "AskUserQuestion" -> {
                    Text("Claude is asking", style = MaterialTheme.typography.titleSmall)
                    val answers = remember(approval.id) { mutableStateMapOf<String, String>() }
                    val qs = i["questions"].arr().orEmpty()
                    qs.forEach { q ->
                        val question = q["question"].str().orEmpty()
                        val multi = q["multiSelect"].bool()
                        Text(question, style = MaterialTheme.typography.bodyMedium)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
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
                                    colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.accent.copy(alpha = 0.18f)),
                                )
                            }
                        }
                        val typed = answers[question].orEmpty().takeIf { a -> q["options"].arr().orEmpty().none { it["label"].str() == a } }.orEmpty()
                        OutlinedTextField(typed, { answers[question] = it }, Modifier.fillMaxWidth(), placeholder = { Text("Or type an answer") })
                    }
                    val done = qs.all { !answers[it["question"].str().orEmpty()].isNullOrBlank() }
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Button(enabled = !busy && done, onClick = {
                            onAnswer(answer(true) { put("answers", JsonObject(answers.mapValues { JsonPrimitive(it.value) })) })
                        }) { Text("Answer") }
                        TextButton(enabled = !busy, onClick = { onAnswer(answer(false) { put("message", "The user declined to answer.") }) }) { Text("Skip") }
                    }
                }

                else -> {
                    Text(approval.title ?: "Claude wants to use ${approval.tool}", style = MaterialTheme.typography.titleSmall)
                    approval.reason?.let { MutedText(it) }
                    ApprovalPreview(approval.tool, i, cwd)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(Space.sm), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
                        Button(enabled = !busy, onClick = { onAnswer(answer(true)) }) { Text("Allow") }
                        OutlinedButton(enabled = !busy, onClick = { onAnswer(answer(true) { put("always", true) }) }) { Text("Always allow") }
                        OutlinedButton(
                            enabled = !busy,
                            onClick = { onAnswer(answer(false) { put("message", note) }) },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = c.bad),
                            border = BorderStroke(1.dp, c.bad.copy(alpha = 0.5f)),
                        ) { Text("Deny") }
                    }
                    OutlinedTextField(note, { note = it }, Modifier.fillMaxWidth(), placeholder = { Text("Optional: tell Claude why, or what to do instead") })
                }
            }
            Spacer(Modifier)
        }
    }
}

private fun answer(allow: Boolean, more: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) =
    buildJsonObject { put("allow", allow); more() }

@Composable
private fun ApprovalPreview(tool: String, i: JsonObject, cwd: String) {
    when (tool) {
        "Bash" -> CodeBlock("$ " + i["command"].str().orEmpty())
        "Edit", "MultiEdit" -> {
            MutedText(ai.bench.app.data.Transcript.rel(i["file_path"].str(), cwd))
            val edits = if (tool == "MultiEdit") i["edits"].arr().orEmpty() else listOf(i)
            MiniDiff(edits.map { (it["old_string"].str().orEmpty()) to (it["new_string"].str().orEmpty()) })
        }
        "Write" -> {
            MutedText(ai.bench.app.data.Transcript.rel(i["file_path"].str(), cwd))
            CodeBlock(i["content"].str().orEmpty().lines().take(40).joinToString("\n"))
        }
        else -> if (i.isNotEmpty()) CodeBlock(i.pretty())
    }
}
