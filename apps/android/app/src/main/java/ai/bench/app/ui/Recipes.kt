package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bench.app.data.BenchStore
import ai.bench.app.data.Recipe
import ai.bench.app.ui.theme.Bench
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One word of a prompt being made into a recipe. Words in the same blank share its number; a
 * blank is one word or several in a row (an address, a full name), filled in as one.
 */
private class Word(val lead: String, val core: String, val trail: String, group: Int?) {
    var group by mutableStateOf(group)
}

private val BLANK = Regex("""^\{([^{}\n]{1,40})\}$""")

/** A prompt's words, keeping punctuation off the word itself ("region," is "region" then ","). */
private fun words(prompt: String): List<Word> = prompt.split(Regex("(?<=\\s)(?=\\S)")).map { tok ->
    val m = Regex("""^(\s*)([("'“]*)(.*?)([.,;:!?)"'”]*)(\s*)$""", RegexOption.DOT_MATCHES_ALL).find(tok)
    val lead = (m?.groupValues?.get(1) ?: "") + (m?.groupValues?.get(2) ?: "")
    val core = m?.groupValues?.get(3) ?: tok
    val trail = (m?.groupValues?.get(4) ?: "") + (m?.groupValues?.get(5) ?: "")
    Word(lead, core, trail, null)
}

/**
 * The prompt with its blanks: each run of words in one blank becomes {name}, keeping the
 * punctuation before its first word and after its last ("at {address}." stays a sentence).
 */
private fun joined(ws: List<Word>, names: Map<Int, String>): String {
    val out = StringBuilder()
    var i = 0
    while (i < ws.size) {
        val g = ws[i].group
        if (g == null) { out.append(ws[i].lead + ws[i].core + ws[i].trail); i++; continue }
        var j = i
        while (j + 1 < ws.size && ws[j + 1].group == g) j++
        out.append(ws[i].lead + "{" + (names[g] ?: "blank") + "}" + ws[j].trail)
        i = j + 1
    }
    return out.toString().trim()
}

/** A blank's first name: its words, lowercase, short ("allen avenue ikeja"); renamed by a tap. */
private fun nameFor(text: String) = text.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), "").trim().split(Regex("\\s+")).take(3).joinToString(" ").take(40).ifBlank { "blank" }

/**
 * Save as a recipe (the "Save a run as one" board): a name, and the prompt with blanks. Tap a
 * word to make it a blank; tap the word beside a blank to add it, so a blank can be a whole
 * address or name. Tap a blank to name it, or make it words again.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SaveRecipeSheet(store: BenchStore, title: String, prompt: String, projectId: String?, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(title) }
    // A prompt that already has {blanks} keeps them, each its own one-word blank.
    val names = remember(prompt) { mutableStateMapOf<Int, String>() }
    val ws = remember(prompt) {
        mutableStateListOf<Word>().apply {
            addAll(words(prompt).onEach { w -> BLANK.find(w.core)?.groupValues?.get(1)?.let { b -> val g = names.size + 1; names[g] = b; w.group = g } })
        }
    }
    var next by remember(prompt) { mutableStateOf(names.size + 1) }
    var naming by remember { mutableStateOf<Int?>(null) }
    /** Tapping a plain word: it joins the blank beside it, or starts one. */
    fun tap(i: Int) {
        val left = (i - 1 downTo 0).firstOrNull { ws[it].core.isNotBlank() }?.let { ws[it].group }
        val right = (i + 1 until ws.size).firstOrNull { ws[it].core.isNotBlank() }?.let { ws[it].group }
        val g = left ?: right ?: next++
        ws[i].group = g
        // Between two blanks: one blank now.
        if (left != null && right != null && left != right) { ws.forEach { if (it.group == right) it.group = left }; names.remove(right) }
        val words = ws.filter { it.group == g }
        names[g] = if (words.size == 1 || names[g] == null || names[g] == nameFor(words.dropLast(1).joinToString(" ") { it.core }) || names[g] == nameFor(words.drop(1).joinToString(" ") { it.core }))
            nameFor(words.joinToString(" ") { it.core }) else names[g]!!
    }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface2,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.surface3, RoundedCornerShape(2.dp))) },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Save as a recipe", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                Text("Blanks are filled in each time you use it.", fontSize = 14.sp, color = c.muted)
            }
            Field(name, { name = it }, "Name")
            FlowRow(
                Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()).background(c.surface1, RoundedCornerShape(16.dp)).padding(14.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                // Plain words one by one; each blank as one chip with its words, named above them.
                var i = 0
                while (i < ws.size) {
                    val w = ws[i]
                    val g = w.group
                    if (w.core.isBlank()) { i++; continue }
                    if (g == null) {
                        val at = i
                        Text(
                            w.lead.trim() + w.core + w.trail.trim(), fontSize = 16.sp, lineHeight = 24.sp,
                            modifier = Modifier.clickable { tap(at) }.padding(horizontal = 1.dp, vertical = 1.dp),
                        )
                        i++
                        continue
                    }
                    var j = i
                    while (j + 1 < ws.size && ws[j + 1].group == g) j++
                    val text = ws.subList(i, j + 1).joinToString(" ") { it.core + it.trail.trim() }.trimEnd(',', ';', ':')
                    Column(
                        Modifier.background(c.accent.copy(alpha = 0.16f), RoundedCornerShape(8.dp)).clickable { naming = g }.padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(names[g] ?: "blank", fontSize = 11.sp, lineHeight = 13.sp, color = c.accent, fontWeight = FontWeight.SemiBold)
                        Text(text, fontSize = 16.sp, lineHeight = 22.sp, color = c.text)
                    }
                    i = j + 1
                }
            }
            Text("Tap a word to make it a blank. Tap the words beside it to add them, for an address or a name.", fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
            error?.let { Text(it, fontSize = 14.sp, color = c.bad) }
            BigButton("Save recipe", primary = true, enabled = !busy && name.isNotBlank()) {
                busy = true
                scope.launch {
                    runCatching {
                        store.api.saveRecipe(null, buildJsonObject {
                            put("title", name.trim()); put("prompt", joined(ws, names))
                            if (projectId != null) put("projectId", projectId) else put("projectId", JsonNull)
                        })
                        store.setRecipes(store.api.recipes())
                    }.onSuccess { onSaved() }.onFailure { error = it.message; busy = false }
                }
            }
        }
    }
    naming?.let { g ->
        var text by remember(g) { mutableStateOf(names[g].orEmpty()) }
        AlertDialog(
            onDismissRequest = { naming = null }, containerColor = c.surface2,
            title = { Text("Name this blank") },
            text = { Field(text, { text = it }, "Like address or role") },
            confirmButton = { TextButton(onClick = { if (text.isNotBlank()) names[g] = text.trim().replace(Regex("[{}]"), "").take(40); naming = null }) { Text("Done") } },
            dismissButton = { TextButton(onClick = { ws.forEach { if (it.group == g) it.group = null }; names.remove(g); naming = null }) { Text("Make it words again", color = c.muted) } },
        )
    }
}

/** A recipe from New: fill its blanks and start (the "Fill the blanks, start" board). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RunRecipeSheet(store: BenchStore, recipe: Recipe, onDismiss: () -> Unit, onStarted: (String) -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    val values = remember(recipe.id) { mutableStateListOf<String>().apply { addAll(recipe.blanks.map { "" }) } }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    fun filled(): String = recipe.blanks.foldIndexed(recipe.prompt) { i, p, b -> p.replace("{$b}", values[i].trim()) }
    ModalBottomSheet(
        onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = c.surface2,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { Box(Modifier.padding(top = 12.dp, bottom = 8.dp).size(36.dp, 4.dp).background(c.surface3, RoundedCornerShape(2.dp))) },
    ) {
        Column(Modifier.fillMaxWidth().imePadding().navigationBarsPadding().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(recipe.title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            recipe.blanks.forEachIndexed { i, b ->
                Text(b, fontSize = 13.sp, color = c.muted, modifier = Modifier.padding(start = 2.dp, top = 2.dp))
                Field(values[i], { values[i] = it }, "")
            }
            // The prompt as it will be sent: what you typed in bold where the blanks were.
            Text(
                buildAnnotatedString {
                    var rest = recipe.prompt
                    val re = Regex("""\{([^{}\n]{1,40})\}""")
                    while (true) {
                        val m = re.find(rest) ?: break
                        append(rest.substring(0, m.range.first))
                        val i = recipe.blanks.indexOf(m.groupValues[1].trim())
                        val v = if (i >= 0) values[i].trim() else ""
                        withStyle(SpanStyle(fontWeight = FontWeight.SemiBold, color = if (v.isEmpty()) c.muted else c.text)) { append(v.ifEmpty { m.groupValues[1] }) }
                        rest = rest.substring(m.range.last + 1)
                    }
                    append(rest)
                },
                fontSize = 14.sp, lineHeight = 20.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp),
            )
            error?.let { Text(it, fontSize = 14.sp, color = c.bad) }
            BigButton("Start", primary = true, enabled = !busy && values.all { it.isNotBlank() }) {
                busy = true
                scope.launch {
                    runCatching {
                        val pid = recipe.project_id?.takeIf { p -> store.state.value.projects.any { it.id == p } } ?: store.api.research().id
                        val s = store.api.createSession(pid)
                        store.api.send(s.id, filled())
                        s.id
                    }.onSuccess { store.refresh(); onStarted(it) }.onFailure { error = it.message; busy = false }
                }
            }
            TextButton(onClick = { deleting = true }, modifier = Modifier.padding(top = 2.dp)) { Text("Delete recipe", color = c.muted) }
        }
    }
    if (deleting) Confirm("Delete “${recipe.title}”?", "It leaves New. Sessions it started stay.", "Delete", onDismiss = { deleting = false }) {
        scope.launch { runCatching { store.api.deleteRecipe(recipe.id) }; store.setRecipes(runCatching { store.api.recipes() }.getOrDefault(emptyList())); onDismiss() }
    }
}

@Composable
private fun Field(value: String, onChange: (String) -> Unit, hint: String) {
    val c = Bench.colors
    BasicTextField(
        value, onChange, singleLine = true,
        textStyle = TextStyle(fontSize = 16.sp, color = c.text),
        cursorBrush = SolidColor(c.accent),
        modifier = Modifier.fillMaxWidth().background(c.surface1, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 15.dp),
        decorationBox = { inner -> Box { if (value.isEmpty() && hint.isNotEmpty()) Text(hint, fontSize = 16.sp, color = c.muted); inner() } },
    )
}
