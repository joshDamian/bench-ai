package ai.bench.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ai.bench.app.data.About
import ai.bench.app.data.BenchStore
import ai.bench.app.data.Rule
import ai.bench.app.ui.theme.Bench
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private const val TEMPLATE = "# About me\n\n## Who you are\n\n\n## Your work\n\n\n## Links\n\n\n## How you write\n\n"

/** About me's sections: "## Heading" and the lines under it. The "# About me" title is left out. */
fun aboutSections(text: String): List<Pair<String, String>> {
    val out = mutableListOf<Pair<String, String>>()
    var head: String? = null
    val body = StringBuilder()
    fun flush() { head?.let { out += it to body.toString().trim() }; body.clear() }
    for (line in text.lines()) {
        when {
            line.startsWith("## ") -> { flush(); head = line.removePrefix("## ").trim() }
            line.startsWith("# ") && head == null -> {}
            else -> if (head != null) body.appendLine(line) else if (line.isNotBlank()) { head = ""; body.appendLine(line) }
        }
    }
    flush()
    return out.filter { it.first.isNotEmpty() || it.second.isNotEmpty() }
}

/** The line Settings shows under About me: the start of "Who you are". */
fun aboutSummary(a: About?): String {
    val who = a?.text?.let { aboutSections(it).firstOrNull()?.second }?.lineSequence()?.firstOrNull { it.isNotBlank() }
    return who?.let { if (it.length > 44) it.take(42).trimEnd() + "…" else it } ?: "Not set up"
}

/**
 * About me: who the person is, read by every session (the About me boards). Empty, it offers a
 * CV to start from; a draft from a CV waits to be checked; saved, it reads as a page with the
 * Never list under it.
 */
@Composable
fun AboutScreen(store: BenchStore, onBack: () -> Unit, onOpen: (String) -> Unit, onRules: () -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var about by remember { mutableStateOf<About?>(null) }
    var rules by remember { mutableStateOf<List<Rule>>(emptyList()) }
    var editing by remember { mutableStateOf<String?>(null) }
    var reading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    suspend fun load() {
        runCatching { store.api.about() }.onSuccess { about = it; if (it.draft != null) reading = false }.onFailure { error = it.message }
        runCatching { store.api.rules() }.onSuccess { rules = it }
    }
    LaunchedEffect(Unit) { load() }
    // While a CV is being read, look for the draft every few seconds.
    LaunchedEffect(reading) { while (reading) { delay(3000); load() } }
    fun act(block: suspend () -> About) {
        busy = true; error = null
        scope.launch { runCatching { block() }.onSuccess { about = it }.onFailure { error = it.message }; busy = false }
    }
    val pick = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true; error = null
        scope.launch {
            try {
                val (name, size) = context.contentResolver.query(uri, null, null, null, null)?.use { cur ->
                    val n = cur.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    val z = cur.getColumnIndex(android.provider.OpenableColumns.SIZE)
                    if (cur.moveToFirst()) (if (n >= 0) cur.getString(n) else null) to (if (z >= 0) cur.getLong(z) else -1L) else null to -1L
                } ?: (null to -1L)
                val (session, prompt) = store.api.aboutFromCv()
                val ref = store.api.upload(session.id, name ?: "cv", context.contentResolver.getType(uri) ?: "application/octet-stream", size, { context.contentResolver.openInputStream(uri) ?: error("Can't read the file") }) {}
                store.api.send(session.id, prompt, listOf(ref))
            } catch (e: Exception) { reading = false; error = e.message ?: "Couldn't read the CV" }
        }
    }

    val a = about
    val never = rules.filter { it.value == "never" }
    editing?.let { draft -> AboutEditor(draft, busy, onCancel = { editing = null }) { text -> act { store.api.saveAbout(text = text).also { editing = null } } }; return }

    val title: String
    val sub: String?
    when {
        a == null -> { title = "About me"; sub = null }
        reading -> { title = "About me"; sub = null }
        a.draft != null -> { title = "Check this"; sub = "Drafted from your CV. Nothing is used until you save." }
        a.text == null -> { title = "About me"; sub = "Every session reads this, so drafts, applications and reports come out with your details in them. It stays on your server." }
        else -> { title = "About me"; sub = null }
    }
    val bottom: @Composable () -> Unit = {
        if (a != null && !reading) Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            error?.let { Text(it, fontSize = 14.sp, color = c.bad, modifier = Modifier.padding(bottom = 6.dp)) }
            when {
                a.draft != null -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("Edit", Modifier.weight(1f), enabled = !busy) { editing = a.draft }
                    BigButton("Save", Modifier.weight(1f), primary = true, enabled = !busy) { act { store.api.aboutDraft("save") } }
                }
                a.text == null -> {
                    BigButton("Start from my CV", primary = true, enabled = !busy) { pick.launch("*/*") }
                    TextButton(onClick = { editing = TEMPLATE }, modifier = Modifier.fillMaxWidth()) { Text("Write it myself", color = c.text) }
                }
            }
        }
    }
    Page(title, sub, onBack, actions = {
        if (a?.text != null && a.draft == null && !reading) TextButton(onClick = { editing = a.text }) { Text("Edit", color = c.accent, fontSize = 15.sp) }
    }, bottom = bottom) {
        when {
            a == null -> item("loading") { Box(Modifier.fillMaxWidth().padding(top = 60.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() } }
            reading -> item("reading") {
                Column(Modifier.fillMaxWidth().padding(top = 48.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
                    Text("Reading your CV", fontSize = 17.sp, fontWeight = FontWeight.Medium)
                    Text("It takes a minute. You can leave; the draft waits here.", fontSize = 14.sp, color = c.muted)
                }
            }
            a.draft != null -> {
                item("draft") { Sections(a.draft) }
                item("never") { NeverList(never, linked = false, onRules) }
                item("discard") { TextButton(onClick = { act { store.api.aboutDraft("discard") } }) { Text("Throw this draft away", color = c.muted) } }
            }
            a.text == null -> item("prompts") {
                Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(
                        "Who you are" to "Name, role, where you are",
                        "Your work" to "Stack, projects, things you are proud of",
                        "Links" to "GitHub, LinkedIn, portfolio",
                        "How you write" to "Tone, length, words you avoid",
                        "Never" to "What Bench must never do. It stops first.",
                    ).forEach { (h, d) ->
                        Column(Modifier.fillMaxWidth().border(1.dp, c.lineStrong, RoundedCornerShape(16.dp)).padding(horizontal = 16.dp, vertical = 13.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(h, fontSize = 15.5.sp, fontWeight = FontWeight.Medium)
                            Text(d, fontSize = 14.sp, color = c.muted)
                        }
                    }
                }
            }
            else -> {
                item("text") { Sections(a.text) }
                item("never") { NeverList(never, linked = true, onRules) }
                item("include") {
                    Column(Modifier.padding(top = 18.dp)) {
                        Card { SwitchRow("Use in every session", "Off: only when you mention it", a.include) { on -> act { store.api.saveAbout(include = on) } } }
                    }
                }
                a.changed_by?.let { by ->
                    item("changed") {
                        Row(Modifier.padding(start = 6.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Changed by ", fontSize = 13.sp, color = c.muted)
                            Text(by.title ?: "a session", fontSize = 13.sp, color = c.text, modifier = Modifier.clickable { onOpen(by.session_id) })
                            Text(" · ", fontSize = 13.sp, color = c.muted)
                            Text("Undo", fontSize = 13.sp, color = c.text, fontWeight = FontWeight.Medium, modifier = Modifier.clickable(enabled = !busy) { act { store.api.aboutUndo() } })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Sections(text: String) {
    val c = Bench.colors
    Column(Modifier.padding(start = 6.dp, end = 6.dp)) {
        aboutSections(text).forEach { (head, body) ->
            Column(Modifier.padding(top = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (head.isNotEmpty()) Text(head, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted)
                if (body.isBlank() || body.equals("Not in the CV.", true)) Text(if (body.isBlank()) "Not set" else body, fontSize = 15.5.sp, color = c.muted)
                else MarkdownText(body)
            }
        }
    }
}

/** The Never rules, shown with About me; they are kept in Asks first, where they are changed. */
@Composable
private fun NeverList(never: List<Rule>, linked: Boolean, onRules: () -> Unit) {
    val c = Bench.colors
    Column(Modifier.padding(start = 6.dp, end = 6.dp, top = 18.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
            Text("Never", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = c.muted, modifier = Modifier.weight(1f))
            Text(if (linked) "Bench stops before these" else "On to start · change any time", fontSize = 13.sp, color = c.muted)
        }
        if (never.isEmpty()) Text("Nothing. Bench asks before anything that leaves your server.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(vertical = 8.dp))
        never.forEach { r ->
            Row(Modifier.fillMaxWidth().heightIn(min = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Lock, null, tint = c.muted, modifier = Modifier.size(16.dp))
                Text(r.label, fontSize = 15.5.sp)
            }
        }
        if (linked) Text("Change in Asks first", fontSize = 14.sp, color = c.accent, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRules).padding(vertical = 8.dp))
    }
}

/** One plain page with headings, saved as it is. */
@Composable
private fun AboutEditor(initial: String, busy: Boolean, onCancel: () -> Unit, onSave: (String) -> Unit) {
    val c = Bench.colors
    var text by remember { mutableStateOf(initial) }
    Column(Modifier.fillMaxSize().background(c.bg).statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onCancel) { Text("Cancel", color = c.muted, fontSize = 15.sp) }
            Box(Modifier.weight(1f))
            BigButton("Save", primary = true, height = 40.dp, fill = false, enabled = !busy && text.isNotBlank()) { onSave(text) }
            Box(Modifier.size(12.dp))
        }
        BasicTextField(
            text, { text = it },
            textStyle = TextStyle(fontSize = 15.5.sp, lineHeight = 24.sp, color = c.text),
            cursorBrush = SolidColor(c.accent),
            modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 22.dp, vertical = 10.dp),
        )
        Text("Headings start with ##. The Never list is changed in Asks first.", fontSize = 13.sp, color = c.muted, modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 22.dp, vertical = 12.dp))
    }
}
