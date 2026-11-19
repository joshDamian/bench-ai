package ai.bench.app.ui.session

import android.content.Intent
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.luminance
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.text.style.TextAlign
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
import ai.bench.app.ui.theme.Space
import ai.bench.app.ui.theme.Type
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/** What the server says a path in the transcript is (POST /api/sessions/:id/files/stat). */
data class FileInfo(val asked: String, val path: String, val name: String, val mime: String, val size: Long, val open: Boolean, val kind: String, val artifact: Boolean) {
    /** Audio or video, which the viewer streams and plays. */
    val media get() = kind == "media"
    val audio get() = media && mime.startsWith("audio/")
    val viewable get() = media || (open && kind in setOf("image", "pdf", "text"))
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

    /** Where the player reads the file from: the server streams it, and can seek within it. */
    fun url(f: FileInfo): String = api.config.baseUrl + "/api/sessions/${BenchApi.enc(sessionId)}/files?path=${BenchApi.enc(f.path)}"

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
                Icon(
                    when {
                        f.media && f.audio -> Icons.Default.Audiotrack
                        f.media -> Icons.Default.Movie
                        else -> Icons.Default.Description
                    },
                    null, tint = c.accent, modifier = Modifier.size(22.dp),
                )
                Column(Modifier.weight(1f)) {
                    Text(f.name, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(f.meta, fontSize = 12.5.sp, color = c.muted)
                }
                Text(
                    when {
                        f.media -> "Play"
                        f.viewable -> "View"
                        else -> "Open"
                    },
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = c.accent,
                )
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
/** Files shown as highlighted code, and the highlighter's name for each where it differs. */
private val CODE = setOf("kt", "kts", "java", "swift", "js", "mjs", "cjs", "ts", "tsx", "jsx", "go", "py", "rb", "rs", "c", "h", "cpp", "cs", "php", "sh", "bash", "zsh", "json", "yaml", "yml", "toml", "sql", "css", "html", "xml", "gradle", "dart")
private val LANG = mapOf("kt" to "kotlin", "kts" to "kotlin", "js" to "javascript", "mjs" to "javascript", "cjs" to "javascript", "jsx" to "javascript", "ts" to "typescript", "tsx" to "typescript", "py" to "python", "rb" to "ruby", "rs" to "rust", "h" to "c", "cs" to "csharp", "sh" to "shell", "bash" to "shell", "zsh" to "shell", "yml" to "yaml")

@Composable
fun FileViewer(files: SessionFiles, f: FileInfo, top: (@Composable () -> Unit)? = null, bottom: (@Composable () -> Unit)? = null, onDismiss: () -> Unit) {
    val c = Bench.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var bytes by remember(f.path) { mutableStateOf<ByteArray?>(null) }
    var failed by remember(f.path) { mutableStateOf(false) }
    // Media streams through the player: it is never read into memory as one block.
    LaunchedEffect(f.path) { if (f.viewable && !f.media) { bytes = files.bytes(f); failed = bytes == null } }
    // Markdown opens rendered, at reading width; the toggle shows its source.
    val ext = f.name.substringAfterLast('.', "").lowercase()
    val markdown = ext in setOf("md", "markdown", "mdx")
    val page = ext in setOf("html", "htm")
    var source by remember(f.path) { mutableStateOf(false) }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // A dialog is its own window: without this its text falls back to black on the dark ground.
        androidx.compose.runtime.CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides c.text) {
        Column(Modifier.fillMaxSize().background(c.bg)) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Close, "Close") }
                Column(Modifier.weight(1f)) {
                    Text(f.name, fontSize = 16.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(f.meta, fontSize = 12.sp, color = c.muted)
                }
                if ((markdown || page) && bytes != null) ai.bench.app.ui.Segmented(listOf("rendered" to (if (page) "Page" else "Rendered"), "source" to "Source"), if (source) "source" else "rendered", { source = it == "source" }, Modifier.width(176.dp), height = 36.dp)
                TextButton(onClick = { scope.launch { handOff(context, files, f, share = true) } }) { Text("Share") }
            }
            top?.invoke()
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                val b = bytes
                when {
                    !f.viewable -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(32.dp)) {
                        Text("Bench can't show this kind of file here.", fontSize = 15.sp, color = c.muted)
                        ai.bench.app.ui.BigButton("Open with…", primary = true, height = 52.dp, fill = false) { scope.launch { handOff(context, files, f, share = false) } }
                    }
                    failed -> Text("This file couldn't be read. It may have been removed.", fontSize = 15.sp, color = c.muted, modifier = Modifier.padding(32.dp))
                    f.media -> MediaPreview(files, f)
                    b == null -> CircularProgressIndicator()
                    f.kind == "image" -> ZoomImage(b)
                    f.kind == "pdf" -> PdfPages(context, b, f.name)
                    markdown && !source -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
                        ai.bench.app.ui.MarkdownText(b.decodeToString().take(400_000), Modifier.widthIn(max = 680.dp).padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 32.dp))
                    }
                    page && !source -> HtmlPage(b.decodeToString(), f.path)
                    !markdown && ext in CODE -> Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).navigationBarsPadding(), contentAlignment = Alignment.TopCenter) {
                        ai.bench.app.ui.CodeCard(b.decodeToString().take(400_000), LANG[ext] ?: ext)
                    }
                    else -> Text(
                        b.decodeToString().take(400_000), fontFamily = Mono, fontSize = 12.5.sp, lineHeight = 18.sp, color = c.text, softWrap = false,
                        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()).padding(16.dp).navigationBarsPadding(),
                    )
                }
            }
            bottom?.invoke()
        }
        }
    }
}

/**
 * Audio and video play in the viewer itself: the server streams the file, so a long video
 * starts at once and seeking costs only the bytes it skips. The transport is ours, in the
 * app's own colours, for both.
 */
// The authenticated data source and PlayerView's shutter are marked unstable in Media3;
// this is the one place that uses them.
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun MediaPreview(files: SessionFiles, f: FileInfo) {
    val c = Bench.colors
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val video = !f.audio
    val headers = remember(f.path) {
        files.api.config.token?.let { mapOf("Authorization" to "Bearer $it") } ?: emptyMap()
    }
    val player = remember(f.path) {
        val source = DefaultHttpDataSource.Factory()
            .setUserAgent("Bench Android")
            .setDefaultRequestProperties(headers)
        ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(source))
            .build()
    }
    var playing by remember(player) { mutableStateOf(false) }
    var buffering by remember(player) { mutableStateOf(true) }
    var duration by remember(player) { mutableStateOf(0L) }
    var position by remember(player) { mutableStateOf(0L) }
    var scrubbing by remember(player) { mutableStateOf(false) }
    var broken by remember(player) { mutableStateOf(false) }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) { playing = isPlaying }
            override fun onPlaybackStateChanged(state: Int) { buffering = state == Player.STATE_BUFFERING }
            override fun onPlayerError(error: PlaybackException) { broken = true; buffering = false }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    // Start it, then follow it. The listener above is already attached when this runs.
    LaunchedEffect(player) {
        player.setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_UNKNOWN).build(),
            true,
        )
        player.setMediaItem(MediaItem.fromUri(files.url(f)))
        player.prepare()
        player.playWhenReady = true
        // The bar follows the player, except while a finger is on it. Reading the state here too
        // covers what happened before the listener was attached (a very short file can be ready).
        while (true) {
            if (!scrubbing) {
                position = player.currentPosition.coerceAtLeast(0)
                duration = player.duration.coerceAtLeast(0)
            }
            playing = player.isPlaying
            buffering = player.playbackState == Player.STATE_BUFFERING
            if (player.playerError != null) broken = true
            delay(250)
        }
    }
    fun toggle() {
        if (player.playbackState == Player.STATE_ENDED) player.seekTo(0L)
        if (player.isPlaying) player.pause() else player.play()
    }
    val controls: @Composable () -> Unit = {
        MediaControls(
            playing = playing, position = position, duration = duration, buffering = buffering, enabled = !broken,
            onToggle = { toggle() },
            onSeek = { position = it; scrubbing = true },
            onSeekFinished = { scrubbing = false; player.seekTo(position) },
        )
    }
    when {
        broken -> Column(
            Modifier.fillMaxSize().padding(Space.xxl),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Text("This ${if (video) "video" else "audio"} can't be played here.", fontSize = Type.md, color = c.muted, textAlign = TextAlign.Center)
            Spacer(Modifier.height(Space.lg))
            ai.bench.app.ui.BigButton("Open with…", primary = true, height = 52.dp, fill = false) { scope.launch { handOff(context, files, f, share = false) } }
        }
        video -> Column(Modifier.fillMaxSize().padding(horizontal = Space.lg, vertical = Space.md), verticalArrangement = Arrangement.spacedBy(Space.md)) {
            Box(
                Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(Bench.spec.radius.dp)).background(c.surfaceSunken),
                contentAlignment = Alignment.Center,
            ) {
                androidx.compose.ui.viewinterop.AndroidView(
                    modifier = Modifier.fillMaxSize().clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { toggle() },
                    factory = { ctx ->
                        PlayerView(ctx).apply {
                            useController = false
                            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                            setShutterBackgroundColor(android.graphics.Color.TRANSPARENT)
                            setBackgroundColor(android.graphics.Color.TRANSPARENT)
                            this.player = player
                        }
                    },
                    update = { it.player = player },
                )
                if (buffering) CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.5.dp)
            }
            controls()
        }
        else -> Column(
            Modifier.fillMaxSize().padding(horizontal = Space.xl, vertical = Space.xl),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(112.dp).clip(CircleShape).background(c.surface1), contentAlignment = Alignment.Center) {
                if (buffering && !playing) CircularProgressIndicator(Modifier.size(32.dp), strokeWidth = 2.5.dp)
                else Icon(Icons.Default.Audiotrack, null, tint = c.accent, modifier = Modifier.size(44.dp))
            }
            Spacer(Modifier.height(Space.xl))
            Text(f.name, fontSize = Type.lg, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            Text(f.meta, fontSize = Type.sm, color = c.muted, modifier = Modifier.padding(top = Space.xs))
            Spacer(Modifier.height(Space.xxl))
            controls()
        }
    }
}

/** Play or pause, seek, and the two times: the same for audio and for video. */
@Composable
private fun MediaControls(
    playing: Boolean,
    position: Long,
    duration: Long,
    buffering: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    onSeekFinished: () -> Unit,
) {
    val c = Bench.colors
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Space.sm)) {
        Slider(
            value = if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f,
            onValueChange = { onSeek((duration * it).toLong()) },
            onValueChangeFinished = onSeekFinished,
            enabled = enabled && duration > 0,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.md)) {
            IconButton(
                onClick = onToggle,
                enabled = enabled,
                modifier = Modifier.size(52.dp).clip(CircleShape).background(if (enabled) c.accent else c.surface2),
            ) {
                if (buffering && !playing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = c.muted)
                else Icon(
                    if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (playing) "Pause" else "Play",
                    tint = if (enabled) c.onAccent else c.muted,
                    modifier = Modifier.size(26.dp),
                )
            }
            Text(clock(position), fontFamily = Mono, fontSize = Type.sm, color = c.muted)
            Spacer(Modifier.weight(1f))
            Text(clock(duration, unknown = duration <= 0), fontFamily = Mono, fontSize = Type.sm, color = c.muted)
        }
    }
}

private fun clock(ms: Long, unknown: Boolean = false): String {
    if (unknown || ms < 0) return "--:--"
    val s = ms / 1000
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s % 3600) / 60, s % 60)
    else "%d:%02d".format(s / 60, s % 60)
}

/**
 * A page a session made, live: its script runs, its state stays (localStorage), and it reaches
 * no files. From the network it gets only what a page is built from: scripts, styles and fonts
 * from the common CDNs (a chart library, say), and pictures (a listing's photos). Anything else,
 * a fetch to some API included, is refused. Its colours come from the app's theme through the
 * CSS variables the reports note names. Links open outside, in the browser or the email app.
 */
/** Where a page may load scripts, styles and fonts from. */
private val PAGE_CDNS = setOf("cdnjs.cloudflare.com", "cdn.jsdelivr.net", "unpkg.com", "cdn.tailwindcss.com", "code.jquery.com", "esm.sh", "fonts.googleapis.com", "fonts.gstatic.com")
private val PICTURES = setOf("png", "jpg", "jpeg", "webp", "gif", "svg", "avif")

@android.annotation.SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlPage(html: String, key: String) {
    val c = Bench.colors
    val context = LocalContext.current
    fun hex(x: androidx.compose.ui.graphics.Color) = "#%06X".format(x.toArgb() and 0xFFFFFF)
    val dark = c.bg.luminance() < 0.5f
    val theme = "<style>:root{color-scheme:${if (dark) "dark" else "light"};--bg:${hex(c.bg)}!important;--surface:${hex(c.surface1)}!important;--text:${hex(c.text)}!important;--muted:${hex(c.muted)}!important;--accent:${hex(c.accent)}!important;--line:${hex(c.line)}!important}html{background:var(--bg)}</style>"
    val doc = remember(html, theme) {
        val viewport = if (html.contains("name=\"viewport\"", true)) "" else "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
        val at = html.indexOf("</head>", ignoreCase = true)
        if (at >= 0) html.substring(0, at) + viewport + theme + html.substring(at) else viewport + theme + html
    }
    // One origin per page, so pages keep their own storage and cannot read each other's.
    val origin = "https://p" + Integer.toHexString(key.hashCode()) + ".pages.bench.invalid/"
    androidx.compose.ui.viewinterop.AndroidView(
        modifier = Modifier.fillMaxSize().navigationBarsPadding(),
        factory = { ctx ->
            android.webkit.WebView(ctx).apply {
                setBackgroundColor(c.bg.toArgb())
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.allowFileAccess = false
                settings.allowContentAccess = false
                webViewClient = object : android.webkit.WebViewClient() {
                    override fun shouldInterceptRequest(view: android.webkit.WebView, req: android.webkit.WebResourceRequest): android.webkit.WebResourceResponse? {
                        val u = req.url
                        val host = u.host.orEmpty()
                        if (host.endsWith(".pages.bench.invalid")) return null
                        val picture = req.method == "GET" && (req.requestHeaders["Accept"].orEmpty().startsWith("image/") || u.path.orEmpty().substringAfterLast('.').lowercase() in PICTURES)
                        if (u.scheme == "https" && req.method == "GET" && (host in PAGE_CDNS || picture)) return null
                        return android.webkit.WebResourceResponse("text/plain", "utf-8", 403, "Blocked", emptyMap(), java.io.ByteArrayInputStream(ByteArray(0)))
                    }
                    override fun shouldOverrideUrlLoading(view: android.webkit.WebView, req: android.webkit.WebResourceRequest): Boolean {
                        val u = req.url
                        if (u.host?.endsWith(".pages.bench.invalid") == true) return false
                        if (u.scheme in setOf("http", "https", "mailto", "tel")) runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        return true
                    }
                }
                loadDataWithBaseURL(origin, doc, "text/html", "utf-8", null)
            }
        },
    )
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
    viewing?.let { f ->
        // Email drafts open as drafts (Open in Gmail, Copy, Mark sent), not as JSON.
        if (f.name.endsWith(".drafts.json")) DraftsScreen(files.api, files.sessionId, f.path, f.name, f.name.removeSuffix(".drafts.json"), f.size, {}) { viewing = null }
        else FileViewer(files, f) { viewing = null }
    }
}
