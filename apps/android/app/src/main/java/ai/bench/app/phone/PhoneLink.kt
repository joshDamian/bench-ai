package ai.bench.app.phone

import android.content.Context
import android.os.Build
import ai.bench.app.data.BenchApi
import ai.bench.app.data.str
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/** Something a session asked this phone to share; decided here. */
data class PhoneAsk(val id: String, val kind: String, val app: String?, val access: String?, val why: String, val sessionId: String?)

/**
 * This phone's link to the server (/api/linked-devices/link), the same one Bench for Mac holds:
 * it says what the phone shares, does the calls sessions make (PhoneActions), and receives what
 * sessions ask it to share, which the person decides here. Linked from the first share until
 * the person unlinks the phone.
 */
object PhoneLink {
    private val json = Json { ignoreUnknownKeys = true }
    private lateinit var app: Context
    private var api: BenchApi? = null
    private var scope: CoroutineScope? = null
    private var socket: WebSocket? = null
    private var pinger: Job? = null
    private var retry = 0

    private val _online = MutableStateFlow(false)
    val online: StateFlow<Boolean> = _online.asStateFlow()
    private val _asks = MutableStateFlow<List<PhoneAsk>>(emptyList())
    val asks: StateFlow<List<PhoneAsk>> = _asks.asStateFlow()

    fun linked(c: Context) = c.getSharedPreferences("phone", 0).getBoolean("linked", false)

    fun start(context: Context, api: BenchApi, scope: CoroutineScope) {
        app = context.applicationContext
        PhoneShares.load(app)
        stop()
        this.api = api
        this.scope = scope
        if (linked(app)) connect()
    }

    fun stop() {
        pinger?.cancel()
        socket?.close(1000, null)
        socket = null
        _online.value = false
    }

    /** The first share links the phone; after that, changes are sent as they happen. */
    fun sharesChanged() {
        if (!::app.isInitialized) return
        if (!linked(app) && PhoneShares.shares.value.isNotEmpty()) {
            app.getSharedPreferences("phone", 0).edit().putBoolean("linked", true).apply()
            connect()
            return
        }
        send(hello("shares"))
    }

    /** Unlinks: the server forgets this phone and what it shared. */
    suspend fun unlink() {
        val a = api ?: return
        runCatching { a.forgetDevice(PhoneShares.deviceId(app)) }
        app.getSharedPreferences("phone", 0).edit().putBoolean("linked", false).apply()
        stop()
    }

    private fun connect() {
        val a = api ?: return
        if (socket != null) return
        val url = a.socketUrl().replace("/api/ws", "/api/linked-devices/link")
        socket = a.http.newWebSocket(a.request("/api/linked-devices/link").url(url).build(), Listener())
    }

    private fun name(): String =
        android.provider.Settings.Global.getString(app.contentResolver, "device_name")?.takeIf { it.isNotBlank() } ?: Build.MODEL

    private fun hello(type: String) = buildJsonObject {
        put("type", type)
        put("id", PhoneShares.deviceId(app))
        put("name", name())
        put("kind", "phone")
        put("version", ai.bench.app.BuildConfigInfo.versionName)
        putJsonArray("shares") { PhoneShares.shares.value.forEach { add(json.parseToJsonElement(json.encodeToString(it))) } }
    }

    private fun send(o: JsonObject) { socket?.send(o.toString()) }

    private class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (socket !== webSocket) return
            retry = 0
            _online.value = true
            send(hello("hello"))
            pinger?.cancel()
            pinger = scope?.launch {
                while (isActive) { delay(25_000); send(buildJsonObject { put("type", "ping") }) }
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            val msg = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
            when (msg["type"].str()) {
                "call" -> scope?.launch(Dispatchers.IO) { answerCall(msg) }
                "ask" -> (msg["ask"] as? JsonObject)?.let { a ->
                    val ask = PhoneAsk(a["id"].str() ?: return, a["kind"].str() ?: return, a["path"].str()?.takeIf { it.isNotBlank() }, a["access"].str()?.takeIf { it.isNotBlank() }, a["why"].str().orEmpty(), a["session_id"].str())
                    _asks.update { list -> list.filter { it.id != ask.id } + ask }
                    ai.bench.app.Notifier.phoneAsk(app, ask)
                }
                "cancel_ask" -> msg["id"].str()?.let { id -> _asks.update { list -> list.filter { it.id != id } }; ai.bench.app.Notifier.clear(app, id) }
            }
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket)
    }

    private fun dropped(ws: WebSocket) {
        if (socket !== ws) return
        socket = null
        pinger?.cancel()
        _online.value = false
        if (!linked(app)) return
        retry = (retry + 1).coerceAtMost(6)
        scope?.launch { delay(1000L shl retry); if (socket == null && linked(app)) connect() }
    }

    /** Reconnects now: the app came to the foreground, or the network changed. */
    fun reconnectNow() { if (::app.isInitialized && linked(app) && socket == null) { retry = 0; connect() } }

    private fun answerCall(msg: JsonObject) {
        val id = msg["id"].str() ?: return
        val out = runCatching { PhoneActions.run(app, msg["tool"].str().orEmpty(), msg["share"].str().orEmpty(), (msg["args"] as? JsonObject) ?: JsonObject(emptyMap())) }
        send(buildJsonObject {
            put("type", "result")
            put("id", id)
            out.onSuccess { put("ok", true); put("text", it) }
            out.onFailure { put("ok", false); put("error", it.message ?: "The phone could not do that") }
        })
    }

    /**
     * The person decided an ask. Approving adds the share (with the access asked for, asking
     * first), then says so; the server tells the session.
     */
    fun answer(c: Context, ask: PhoneAsk, approved: Boolean, access: String? = ask.access) {
        if (approved) {
            val k = PhoneShares.catalog.firstOrNull { it.kind == ask.kind && (ask.kind != "messages" || it.name.equals(ask.app, true) || it.id.equals(ask.app, true)) }
            if (k != null) PhoneShares.set(c, PhoneShare(k.id, k.kind, k.name, k.path, access ?: k.accesses.firstOrNull()?.first, "ask"))
        }
        _asks.update { list -> list.filter { it.id != ask.id } }
        ai.bench.app.Notifier.clear(c, ask.id)
        send(buildJsonObject { put("type", "answer"); put("id", ask.id); put("approved", approved) })
    }
}
