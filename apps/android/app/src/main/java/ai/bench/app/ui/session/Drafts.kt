package ai.bench.app.ui.session

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.bench.app.data.BenchJson
import ai.bench.app.data.BenchStore
import ai.bench.app.data.Format
import ai.bench.app.data.Report
import ai.bench.app.ui.BigButton
import ai.bench.app.ui.theme.Bench
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

private val BLANK = Regex("\\[[^\\]\\n]{2,40}\\]")

@Serializable
data class Draft(val to: String = "", val subject: String = "", val body: String = "", val why: String? = null, val sent_at: String? = null)

@Serializable
private data class DraftsFile(val title: String = "", val drafts: List<Draft> = emptyList())

/**
 * Emails a session wrote for the person to send. Bench never sends them: each opens in the
 * person's own email app, filled in, and they mark it sent when it is gone.
 */
@Composable
fun DraftsScreen(store: BenchStore, report: Report, onAbout: (() -> Unit)? = null, onDismiss: () -> Unit) =
    DraftsScreen(store.api, report.session_id, report.path, report.name, report.title, report.size, { store.loadExtras() }, onAbout, onDismiss)

/** The same, from a file a session named (its card in the conversation). */
@Composable
fun DraftsScreen(api: ai.bench.app.data.BenchApi, sessionId: String, path: String, name: String, title: String, size: Long, onChanged: () -> Unit, onAbout: (() -> Unit)? = null, onDismiss: () -> Unit) {
    val c = Bench.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val files = remember(sessionId) { SessionFiles(api, sessionId) }
    var file by remember(path) { mutableStateOf<DraftsFile?>(null) }
    var failed by remember(path) { mutableStateOf(false) }
    var open by remember(path) { mutableStateOf(-1) }
    var noAbout by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { noAbout = runCatching { api.about().text == null }.getOrDefault(false) }
    LaunchedEffect(path) {
        val b = files.bytes(FileInfo(path, path, name, "application/json", size, true, "text", true))
        file = b?.let { runCatching { BenchJson.decodeFromString(DraftsFile.serializer(), it.decodeToString()) }.getOrNull() }
        failed = file == null
    }
    fun mark(i: Int, sent: Boolean) {
        val f = file ?: return
        val before = f.drafts[i]
        file = f.copy(drafts = f.drafts.toMutableList().also { it[i] = before.copy(sent_at = if (sent) java.time.Instant.now().toString() else null) })
        scope.launch {
            runCatching { api.markDraft(sessionId, name, i, sent) }
                .onFailure { file = file?.let { g -> g.copy(drafts = g.drafts.toMutableList().also { it[i] = before }) } }
            onChanged()
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.text) {
        Column(Modifier.fillMaxSize().background(c.bg)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close") }
                Column(Modifier.weight(1f)) {
                    Text(file?.title?.ifBlank { null } ?: title, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    file?.let { f ->
                        val left = f.drafts.count { it.sent_at == null }
                        Text(if (left == 0) "All sent" else "$left of ${f.drafts.size} to send", fontSize = 12.sp, color = c.muted)
                    }
                }
            }
            val f = file
            when {
                failed -> Text("These drafts couldn't be read. The file may have been changed or removed.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(32.dp))
                f == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                else -> LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 40.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // Drafts with blanks for the person's own details: About me fills those in.
                    if (noAbout && onAbout != null && f.drafts.any { it.sent_at == null && BLANK.containsMatchIn(it.body) }) item("about") {
                        Column(
                            Modifier.widthIn(max = 680.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface1).padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            Text("These drafts have blanks for your own details. With About me set up, Bench fills those in.", fontSize = 15.sp, lineHeight = 21.sp)
                            BigButton("Set up About me", height = 40.dp, fill = false, onClick = onAbout)
                        }
                    }
                    itemsIndexed(f.drafts) { i, d ->
                        DraftCard(d, expanded = open == i, onToggle = { open = if (open == i) -1 else i },
                            onOpenMail = { openInMail(context, d) },
                            onCopy = { copy(context, d) },
                            onSent = { mark(i, d.sent_at == null) })
                    }
                }
            }
        }
        }
    }
}

@Composable
private fun DraftCard(d: Draft, expanded: Boolean, onToggle: () -> Unit, onOpenMail: () -> Unit, onCopy: () -> Unit, onSent: () -> Unit) {
    val c = Bench.colors
    val sent = d.sent_at != null
    Column(
        Modifier.widthIn(max = 680.dp).fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface1).clickable(onClick = onToggle).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(d.to.ifBlank { "No address" }, fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (sent) Text("Sent " + Format.ago(d.sent_at!!), fontSize = 13.sp, color = c.good)
        }
        Text(d.subject.ifBlank { "No subject" }, fontSize = 16.sp, fontWeight = FontWeight.Medium, color = if (sent) c.muted else c.text)
        d.why?.takeIf { it.isNotBlank() }?.let { Text(it, fontSize = 13.5.sp, color = c.muted, lineHeight = 19.sp) }
        Text(d.body, fontSize = 14.5.sp, lineHeight = 21.sp, color = if (sent) c.muted else c.text, maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (!sent) BigButton("Open in Gmail", primary = true, height = 44.dp, fill = false, onClick = onOpenMail)
            TextButton(onClick = onCopy) { Text("Copy", color = c.text) }
            TextButton(onClick = onSent) { Text(if (sent) "Mark not sent" else "Mark sent", color = c.muted) }
        }
    }
}

/** Opens the draft in Gmail, filled in; any email app when Gmail is not installed. */
private fun openInMail(context: Context, d: Draft) {
    // Gmail reads the subject and body from the mailto address itself and ignores the extras,
    // so both go in the query; the extras stay for apps that read those instead.
    val mailto = "mailto:" + Uri.encode(d.to, "@,") + "?subject=" + Uri.encode(d.subject) + "&body=" + Uri.encode(d.body)
    val intent = Intent(Intent.ACTION_SENDTO, Uri.parse(mailto))
        .putExtra(Intent.EXTRA_EMAIL, arrayOf(d.to))
        .putExtra(Intent.EXTRA_SUBJECT, d.subject)
        .putExtra(Intent.EXTRA_TEXT, d.body)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(Intent(intent).setPackage("com.google.android.gm")) }
        .recoverCatching { context.startActivity(Intent.createChooser(intent, "Send with").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

private fun copy(context: Context, d: Draft) {
    val cm = context.getSystemService(ClipboardManager::class.java) ?: return
    cm.setPrimaryClip(ClipData.newPlainText(d.subject, "To: ${d.to}\nSubject: ${d.subject}\n\n${d.body}"))
}
