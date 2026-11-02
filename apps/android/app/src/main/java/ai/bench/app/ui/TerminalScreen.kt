package ai.bench.app.ui

import android.annotation.SuppressLint
import android.util.Base64
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import ai.bench.app.data.BenchApi
import ai.bench.app.ui.theme.Bench
import ai.bench.app.ui.theme.Mono
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString

/**
 * A shell on the server, drawn by xterm.js in a WebView. The socket to /api/terminal lives
 * here in Kotlin, so it carries the device token like every other request; the page only
 * draws and reports keystrokes (src/terminal.mjs: raw output down, input/resize frames up).
 */
private class RemoteShell(private val api: BenchApi, private val query: String, val onState: (String?) -> Unit) {
    var web: WebView? = null
    private var socket: WebSocket? = null
    private var cols = 80
    private var rows = 24
    private val pending = StringBuilder()

    fun connect() {
        socket?.cancel()
        onState("Connecting…")
        val url = api.config.baseUrl.replaceFirst(Regex("^http"), "ws") + "/api/terminal?$query"
        socket = api.http.newWebSocket(api.request("/api/terminal?$query").url(url).build(), object : WebSocketListener() {
            override fun onOpen(ws: WebSocket, response: Response) { onState(null); resize(cols, rows) }
            override fun onMessage(ws: WebSocket, text: String) = feed(text.toByteArray())
            override fun onMessage(ws: WebSocket, bytes: ByteString) = feed(bytes.toByteArray())
            override fun onClosed(ws: WebSocket, code: Int, reason: String) { if (ws === socket) onState("The shell closed.") }
            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (ws === socket) onState(if (response?.code == 409) "The shell's folder is gone on the server." else "Disconnected.")
            }
        })
    }

    private fun feed(b: ByteArray) {
        val b64 = Base64.encodeToString(b, Base64.NO_WRAP)
        web?.post { web?.evaluateJavascript("feed('$b64')", null) }
    }

    fun input(s: String) {
        socket?.send(org.json.JSONObject().put("type", "input").put("data", s).toString())
    }

    fun resize(c: Int, r: Int) {
        cols = c; rows = r
        socket?.send(org.json.JSONObject().put("type", "resize").put("cols", c).put("rows", r).toString())
    }

    fun close() { socket?.close(1000, null); socket = null }
}

private class Bridge(private val shell: RemoteShell) {
    @JavascriptInterface fun input(d: String) = shell.input(d)
    @JavascriptInterface fun resize(c: Int, r: Int) = shell.resize(c, r)
    @JavascriptInterface fun ready() = shell.connect()
}

/** Keys a phone keyboard lacks, in a row above it. */
private val KEYS = listOf("esc" to "\u001b", "tab" to "\t", "ctrl-c" to "\u0003", "↑" to "\u001b[A", "↓" to "\u001b[B", "←" to "\u001b[D", "→" to "\u001b[C", "|" to "|", "~" to "~", "/" to "/", "-" to "-")

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun TerminalPane(api: BenchApi, query: String, place: String, modifier: Modifier = Modifier) {
    val c = Bench.colors
    var problem by remember { mutableStateOf<String?>("Connecting…") }
    val shell = remember(query) { RemoteShell(api, query) { problem = it } }
    DisposableEffect(shell) { onDispose { shell.close() } }

    Column(modifier.fillMaxSize().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(7.dp).background(if (problem == null) c.good else c.warn, CircleShape))
            Text(problem ?: place, fontSize = 12.sp, color = c.muted, fontFamily = if (problem == null) Mono else null, maxLines = 1, modifier = Modifier.weight(1f))
            if (problem != null && problem != "Connecting…") Text("Reconnect", fontSize = 13.sp, color = c.accent, modifier = Modifier.clickable { shell.connect() }.padding(4.dp))
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 12.dp)
                .background(c.wellBg, RoundedCornerShape(16.dp))
                .border(1.dp, c.line, RoundedCornerShape(16.dp)),
        ) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        settings.javaScriptEnabled = true
                        settings.allowFileAccess = false
                        addJavascriptInterface(Bridge(shell), "Bridge")
                        shell.web = this
                        val hex = { col: androidx.compose.ui.graphics.Color -> "%23" + "%06x".format(col.toArgb() and 0xffffff) }
                        loadUrl("file:///android_asset/terminal/index.html?fg=${hex(c.wellText)}&accent=${hex(c.accent)}")
                    }
                },
                modifier = Modifier.fillMaxSize().padding(2.dp),
            )
        }
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            KEYS.forEach { (label, seq) ->
                Box(
                    Modifier.height(36.dp).background(c.surface2, RoundedCornerShape(10.dp)).clickable { shell.input(seq) }.padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, fontSize = 13.sp, fontFamily = Mono, color = c.text) }
            }
        }
    }
}

/** A full screen for a session's shell, opened from its header. */
@Composable
fun TerminalScreen(api: BenchApi, query: String, title: String, place: String, onBack: () -> Unit) {
    Scaffold(containerColor = Bench.colors.bg, topBar = { BackHeader(title, { Text("Terminal", fontSize = 12.5.sp, color = Bench.colors.muted) }, onBack) }) { pad ->
        TerminalPane(api, query, place, Modifier.padding(pad))
    }
}
