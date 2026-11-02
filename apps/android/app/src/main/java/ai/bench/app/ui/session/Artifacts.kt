package ai.bench.app.ui.session

import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.bench.app.data.BenchApi
import ai.bench.app.data.bool
import ai.bench.app.data.get
import ai.bench.app.data.long
import ai.bench.app.data.obj
import ai.bench.app.data.str
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.Mono
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/** What the server says a path in the transcript is (POST /api/sessions/:id/files/stat). */
data class FileInfo(val asked: String, val path: String, val name: String, val mime: String, val size: Long, val open: Boolean, val kind: String, val artifact: Boolean) {
    val viewable get() = open && kind in setOf("image", "pdf", "text")
    val meta get() = (if (name.contains('.')) name.substringAfterLast('.') else kind).uppercase() + " · " + when {
        size < 1024 -> "$size B"; size < 1024 * 1024 -> "${size / 1024} KB"; else -> "%.1f MB".format(size / 1024.0 / 1024.0)
    }
}

/** The session's files, keyed by the path as written in the transcript. Nothing is guessed from a name. */
class SessionFiles(val api: BenchApi, val sessionId: String) {
    val known = mutableStateMapOf<String, FileInfo?>()
    private val PATH = Regex("""(?:~?/|\.{1,2}/)?[\w@+.-]*[\w@+-](?:/[\w@+.-]+)*""")

    fun paths(text: String): List<String> = PATH.findAll(text).map { it.value.trimEnd('.') }.filter { t ->
        t.length in 3..1024 && !t.startsWith("~") && !t.matches(Regex("""^\d[\d.]*$""")) && (t.contains('/') || t.matches(Regex(""".*\.[A-Za-z][A-Za-z0-9]{0,7}$""")))
    }.distinct().toList()

    suspend fun stat(texts: List<String>) {
        val ask = texts.flatMap { paths(it) }.distinct().filter { it !in known }.take(500)
        if (ask.isEmpty()) return
        ask.forEach { known[it] = null }
        val r = runCatching {
            api.post("/api/sessions/${BenchApi.enc(sessionId)}/files/stat", buildJsonObject { putJsonArray("paths") { ask.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } } })
        }.getOrNull().obj() ?: return
        for ((p, f) in r) known[p] = FileInfo(p, f["path"].str().orEmpty(), f["name"].str().orEmpty(), f["mime"].str().orEmpty(), f["size"].long() ?: 0, f["open"].bool(), f["kind"].str().orEmpty(), f["artifact"].bool())
    }

    fun artifactsIn(text: String): List<FileInfo> = paths(text).mapNotNull { known[it] }.filter { it.artifact }.distinctBy { it.path }

    suspend fun bytes(f: FileInfo): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            api.http.newCall(api.request("/api/sessions/${BenchApi.enc(sessionId)}/files?path=${BenchApi.enc(f.path)}").get().build()).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
        }.getOrNull()
    }
}

val LocalFiles = compositionLocalOf<SessionFiles?> { null }
val LocalViewFile = compositionLocalOf<(FileInfo) -> Unit> { {} }

/** Cards for the files a reply names: an image as itself, anything else by name, type and size. */
@Composable
fun ArtifactCards(text: String) {
    val files = LocalFiles.current ?: return
    val view = LocalViewFile.current
    val list = files.artifactsIn(text)
    if (list.isEmpty()) return
    val c = Bench.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        list.forEach { f ->
            if (f.open && f.kind == "image") {
                var img by remember(f.path) { mutableStateOf<ImageBitmap?>(null) }
                LaunchedEffect(f.path) { img = files.bytes(f)?.let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } }
                Box(Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 320.dp).clip(RoundedCornerShape(18.dp)).background(c.surface1).clickable { view(f) }, contentAlignment = Alignment.Center) {
                    img?.let { Image(it, f.name, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth) } ?: CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else Row(
                Modifier.fillMaxWidth().background(c.surface1, RoundedCornerShape(18.dp)).clickable { view(f) }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Icon(Icons.Default.Description, null, tint = c.accent, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f)) {
                    Text(f.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(f.meta, fontSize = 12.5.sp, color = c.muted)
                }
                Text(if (f.viewable) "View" else "Open", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.accent)
            }
        }
    }
}

/** Saves the file to the app's cache and hands it to another app: Share, or Open with. */
suspend fun handOff(context: android.content.Context, files: SessionFiles, f: FileInfo, share: Boolean) {
    val b = files.bytes(f) ?: return
    val dir = File(context.cacheDir, "artifacts").apply { mkdirs() }
    val out = File(dir, f.name.ifBlank { "file" }).apply { writeBytes(b) }
    val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", out)
    val intent = if (share) Intent(Intent.ACTION_SEND).setType(f.mime.ifBlank { "*/*" }).putExtra(Intent.EXTRA_STREAM, uri)
        else Intent(Intent.ACTION_VIEW).setDataAndType(uri, f.mime.ifBlank { "*/*" })
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    runCatching { context.startActivity(Intent.createChooser(intent, if (share) "Share ${f.name}" else "Open ${f.name}").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

/** The viewer: images pinch to zoom, PDFs page by page, text as it is; Share and Open with for anything. */
@Composable
fun FileViewer(files: SessionFiles, f: FileInfo, onDismiss: () -> Unit) {
    val c = Bench.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bytes by remember(f.path) { mutableStateOf<ByteArray?>(null) }
    var failed by remember(f.path) { mutableStateOf(false) }
    LaunchedEffect(f.path) { if (f.viewable) { bytes = files.bytes(f); failed = bytes == null } }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.bg)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close") }
                Column(Modifier.weight(1f)) {
                    Text(f.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(f.meta, fontSize = 12.sp, color = c.muted)
                }
                TextButton(onClick = { scope.launch { handOff(context, files, f, share = true) } }) { Text("Share") }
            }
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val b = bytes
                when {
                    !f.viewable -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(32.dp)) {
                        Text("Bench can't show this kind of file here.", fontSize = 15.sp, color = c.muted)
                        ai.bench.app.ui.BigButton("Open with…", primary = true, height = 52.dp, fill = false) { scope.launch { handOff(context, files, f, share = false) } }
                    }
                    failed -> Text("This file couldn't be read. It may have been removed.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(32.dp))
                    b == null -> CircularProgressIndicator()
                    f.kind == "image" -> ZoomImage(b)
                    f.kind == "pdf" -> PdfPages(context, b, f.name)
                    else -> Text(
                        b.decodeToString().take(400_000), fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp, color = c.text, softWrap = false,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
                    )
                }
            }
        }
    }
}

@Composable
private fun ZoomImage(b: ByteArray) {
    val img = remember(b) { BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } ?: return
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(androidx.compose.ui.geometry.Offset.Zero) }
    val state = rememberTransformableState { z, pan, _ -> scale = (scale * z).coerceIn(1f, 6f); offset = if (scale == 1f) androidx.compose.ui.geometry.Offset.Zero else offset + pan }
    Image(img, null, Modifier.fillMaxSize().transformable(state).graphicsLayer(scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y), contentScale = ContentScale.Fit)
}

@Composable
private fun PdfPages(context: android.content.Context, b: ByteArray, name: String) {
    val pages = remember(b) {
        runCatching {
            val f = File(context.cacheDir, "artifacts").apply { mkdirs() }.let { File(it, "view-$name") }.apply { writeBytes(b) }
            PdfRenderer(ParcelFileDescriptor.open(f, ParcelFileDescriptor.MODE_READ_ONLY)).use { r ->
                (0 until minOf(r.pageCount, 60)).map { i ->
                    r.openPage(i).use { p ->
                        val bmp = android.graphics.Bitmap.createBitmap(p.width * 2, p.height * 2, android.graphics.Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(android.graphics.Color.WHITE)
                        p.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        bmp.asImageBitmap()
                    }
                }
            }
        }.getOrDefault(emptyList())
    }
    LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)) {
        items(pages) { Image(it, null, Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.FillWidth) }
    }
}

/** Provides the session's files to the transcript and shows the viewer when a card is tapped. */
@Composable
fun WithFiles(files: SessionFiles, content: @Composable () -> Unit) {
    var viewing by remember { mutableStateOf<FileInfo?>(null) }
    CompositionLocalProvider(LocalFiles provides files, LocalViewFile provides { viewing = it }) { content() }
    viewing?.let { FileViewer(files, it) { viewing = null } }
}
