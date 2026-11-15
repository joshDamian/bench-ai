package ai.bench.app.ui

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ai.bench.app.data.BenchApi
import ai.bench.app.data.BenchStore
import ai.bench.app.ui.theme.Bench
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * Taking over the server's browser (the "You are in control" board): its page as it is now,
 * taps and typing sent back. What is typed goes to the site; Bench keeps the session, not the
 * password. Done hands the browser back to the run.
 */
@Composable
fun BrowserView(api: BenchApi, onDone: () -> Unit) {
    val c = Bench.colors
    var frame by remember { mutableStateOf<ImageBitmap?>(null) }
    var url by remember { mutableStateOf("") }
    var closed by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var box by remember { mutableStateOf(IntSize.Zero) }
    var editing by remember { mutableStateOf(false) }
    var address by remember { mutableStateOf("") }
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val socket = remember { mutableStateOf<WebSocket?>(null) }
    fun send(type: String, block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit = {}) {
        socket.value?.send(buildJsonObject { put("type", type); block() }.toString())
    }
    DisposableEffect(Unit) {
        val wsUrl = api.config.baseUrl.replaceFirst(Regex("^http"), "ws") + "/api/browser/view"
        val ws = api.http.newWebSocket(api.request("/api/browser/view").url(wsUrl).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = runCatching { ai.bench.app.data.BenchJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when ((m["type"] as? JsonPrimitive)?.content) {
                    "frame" -> {
                        val b = Base64.decode((m["data"] as? JsonPrimitive)?.content ?: return, Base64.DEFAULT)
                        BitmapFactory.decodeByteArray(b, 0, b.size)?.let { frame = it.asImageBitmap() }
                    }
                    "url" -> url = (m["url"] as? JsonPrimitive)?.content.orEmpty()
                    // Why the browser couldn't start, in full, before the server closes.
                    "error" -> closed = (m["message"] as? JsonPrimitive)?.content
                }
            }
            // The server closing says why; answer it, or the socket only fails later without the reason.
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                if (closed == null) closed = reason.ifBlank { "The browser closed." }
                webSocket.close(1000, null)
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { if (closed == null) closed = reason.ifBlank { "The browser closed." } }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (closed == null) closed = if (response?.code == 404) "This server has no browser. Update it to the Go server." else "The browser couldn't be reached."
            }
        })
        socket.value = ws
        onDispose { ws.close(1000, null) }
    }
    // Pages lay out at this view's size while you are in control, as on a phone.
    LaunchedEffect(box, socket.value) {
        if (box != IntSize.Zero) send("size") { put("width", box.width / density); put("height", box.height / density) }
    }
    Dialog(onDismissRequest = onDone, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Column(Modifier.fillMaxSize().background(c.bg).imePadding()) {
            Row(
                Modifier.fillMaxWidth().background(c.surface1).statusBarsPadding().padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(10.dp).background(c.warn, CircleShape))
                Column(Modifier.weight(1f)) {
                    Text("You are in control", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    // The address: tap it to open another page.
                    if (editing) BasicTextField(
                        address, { address = it }, singleLine = true,
                        textStyle = TextStyle(fontSize = 14.sp, color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go, keyboardType = androidx.compose.ui.text.input.KeyboardType.Uri),
                        keyboardActions = KeyboardActions(onGo = { if (address.isNotBlank()) send("go") { put("url", address.trim()) }; editing = false }),
                        modifier = Modifier.fillMaxWidth().padding(top = 2.dp).clip(RoundedCornerShape(8.dp)).background(c.bg).padding(horizontal = 8.dp, vertical = 5.dp),
                        decorationBox = { inner -> Box { if (address.isEmpty()) Text("Web address or search", fontSize = 14.sp, color = c.muted); inner() } },
                    ) else {
                        val shown = url.removePrefix("https://").removePrefix("http://").takeIf { it.isNotBlank() && it != "about:blank" }
                        Text(shown ?: "Tap to open a web address", fontSize = 12.5.sp, color = if (shown == null) c.accent else c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.clickable { address = shown.orEmpty(); editing = true })
                    }
                }
                BigButton("Done", primary = true, height = 40.dp, fill = false, onClick = onDone)
            }
            Box(
                Modifier.weight(1f).fillMaxWidth().background(c.surface2).onSizeChanged { box = it }
                    .pointerInput(Unit) {
                        detectTapGestures { p ->
                            val f = frame ?: return@detectTapGestures
                            // The frame is drawn to the width; a tap below it misses the page.
                            val drawnH = box.width * f.height.toFloat() / f.width
                            if (p.y <= drawnH) send("tap") { put("x", p.x / box.width); put("y", p.y / drawnH) }
                        }
                    }
                    .pointerInput(Unit) {
                        var acc = 0f
                        detectVerticalDragGestures(onDragEnd = { acc = 0f }) { _, dy ->
                            acc += dy
                            if (kotlin.math.abs(acc) > 40f) { send("scroll") { put("dy", -acc / box.height.coerceAtLeast(1)) }; acc = 0f }
                        }
                    },
                contentAlignment = Alignment.TopCenter,
            ) {
                val f = frame
                when {
                    closed != null -> Text(closed!!, fontSize = 15.sp, lineHeight = 21.sp, color = c.muted, modifier = Modifier.padding(32.dp))
                    f == null -> Column(Modifier.padding(top = 80.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        CircularProgressIndicator()
                        Text("Starting the browser…", fontSize = 14.sp, color = c.muted)
                    }
                    else -> Image(f, null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
                }
            }
            Column(Modifier.fillMaxWidth().background(c.surface1).navigationBarsPadding().padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BasicTextField(
                        typed, { typed = it }, singleLine = true,
                        textStyle = TextStyle(fontSize = 16.sp, color = c.text),
                        cursorBrush = SolidColor(c.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = { if (typed.isNotEmpty()) { send("text") { put("text", typed) }; typed = "" } }),
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(c.bg).padding(horizontal = 14.dp, vertical = 12.dp),
                        decorationBox = { inner -> Box { if (typed.isEmpty()) Text("Type into the page", fontSize = 16.sp, color = c.muted); inner() } },
                    )
                    TextButton(onClick = { if (typed.isNotEmpty()) { send("text") { put("text", typed) }; typed = "" } }) { Text("Send", color = c.accent) }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Enter" to "Enter", "Backspace" to "Delete", "Tab" to "Tab").forEach { (k, label) ->
                        BigButton(label, height = 36.dp, size = 13.5.sp, fill = false) { send("key") { put("key", k) } }
                    }
                    BigButton("Back", height = 36.dp, size = 13.5.sp, fill = false) { send("back") }
                }
                Text("What you type goes to the site, not to Bench. Tap Done when you are through.", fontSize = 12.5.sp, color = c.muted)
            }
        }
    }
}

/** The browser on the server: the sites it is signed in to (the Browser board). */
@Composable
fun BrowserScreen(store: BenchStore, onBack: () -> Unit) {
    val c = Bench.colors
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<ai.bench.app.data.BrowserStatus?>(null) }
    var viewing by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { status = runCatching { store.api.browser() }.getOrNull() }
    Page("Browser", "A browser on your server that sessions use. Sign-ins stay there; passwords are never kept.", onBack) {
        val s = status ?: return@Page
        if (!s.installed) {
            item("none") { Text("Chromium isn't installed on the server. Install it there with: npx playwright install chromium", fontSize = 14.5.sp, lineHeight = 20.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, end = 6.dp, top = 18.dp)) }
            return@Page
        }
        section("Signed in")
        item("sites") {
            if (s.sites.isEmpty()) Text("No sites yet. When a run needs you to sign in, you take over and sign in once.", fontSize = 14.5.sp, lineHeight = 20.sp, color = c.muted, modifier = Modifier.padding(start = 6.dp, end = 6.dp))
            else Card {
                s.sites.forEach { site ->
                    NavRow(site, "Sign out", valueColor = c.text, chevron = false) {
                        scope.launch { runCatching { store.api.signOut(site) }.onSuccess { status = it } }
                    }
                }
            }
        }
        item("open") { Column(Modifier.padding(top = 18.dp)) { BigButton("Open the browser", onClick = { viewing = true }) } }
    }
    if (viewing) BrowserView(store.api) { viewing = false; scope.launch { status = runCatching { store.api.browser() }.getOrNull() } }
}
