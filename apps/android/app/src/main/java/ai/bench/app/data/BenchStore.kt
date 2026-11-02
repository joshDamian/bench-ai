package ai.bench.app.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.util.concurrent.ConcurrentHashMap

/** The lists the app is drawn from, kept current by one socket (as web/api.mjs's store is). */
data class BenchState(
    val ready: Boolean = false,
    val connected: Boolean = false,
    /** The socket was open and has gone, as opposed to still being dialled. */
    val dropped: Boolean = false,
    val unauthorized: Boolean = false,
    val error: String? = null,
    val projects: List<Project> = emptyList(),
    val tasks: List<BenchTask> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val approvals: List<Approval> = emptyList(),
    val defaults: Defaults = Defaults(),
    val me: Me? = null,
    val version: String? = null,
)

class BenchStore(val api: BenchApi, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(BenchState())
    val state: StateFlow<BenchState> = _state.asStateFlow()

    /** Session-scoped traffic - events, deltas, suggestions - for whichever panes are open. */
    private val _session = MutableSharedFlow<ServerMessage>(extraBufferCapacity = 256)
    val sessionTraffic: SharedFlow<ServerMessage> = _session.asSharedFlow()

    /** Fires when the socket comes back, so open panes read what they missed. */
    private val _reconnected = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val reconnected: SharedFlow<Unit> = _reconnected.asSharedFlow()

    /** New approvals as they arrive, for notifications while the app is in the background. */
    private val _newApprovals = MutableSharedFlow<Approval>(extraBufferCapacity = 16)
    val newApprovals: SharedFlow<Approval> = _newApprovals.asSharedFlow()

    private val watched = ConcurrentHashMap<String, Int>()
    private var socket: WebSocket? = null
    private var retry = 0
    private var reconnectJob: Job? = null
    private var probeJob: Job? = null
    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { refresh() }
        connect()
    }

    fun stop() {
        started = false
        reconnectJob?.cancel()
        socket?.close(1000, null)
        socket = null
    }

    suspend fun refresh() {
        try {
            val s = api.state()
            _state.update {
                it.copy(
                    ready = true, unauthorized = false, error = null,
                    projects = s.projects, tasks = s.tasks, sessions = s.sessions,
                    approvals = s.approvals, defaults = s.defaults, me = s.me, version = s.version,
                )
            }
        } catch (e: BenchError) {
            _state.update { it.copy(ready = true, unauthorized = e.unauthorized, error = if (e.unauthorized) null else e.message) }
        }
    }

    // -- the socket -----------------------------------------------------------------------

    private fun connect() {
        if (!started) return
        val req = api.request("/api/ws").url(api.socketUrl()).build()
        val ws = api.http.newWebSocket(req, Listener())
        socket = ws
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            if (socket !== webSocket) return
            retry = 0
            _state.update { it.copy(connected = true, dropped = false) }
            sendWatch()
            scope.launch {
                refresh()
                _reconnected.emit(Unit)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (socket !== webSocket) return
            probeJob?.cancel()
            parseServerMessage(text)?.let(::onServerMessage)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dropped(webSocket, null)
        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dropped(webSocket, response)
    }

    private fun dropped(ws: WebSocket, response: Response?) {
        if (socket !== ws) return
        socket = null
        val unauthorized = response?.code == 401
        _state.update { it.copy(connected = false, dropped = true, unauthorized = it.unauthorized || unauthorized) }
        if (unauthorized || !started) return
        retry = (retry + 1).coerceAtMost(5)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            delay(500L shl retry)
            connect()
        }
    }

    /** Reconnects now: the app came to the foreground, or the network changed. */
    fun reconnectNow() {
        if (!started) return
        val ws = socket
        if (ws == null) {
            reconnectJob?.cancel()
            retry = 0
            connect()
            return
        }
        // A socket that looks open after a sleep may be dead; ask, and replace it if silent.
        if (!ws.send("""{"type":"ping"}""")) return replace()
        probeJob?.cancel()
        probeJob = scope.launch {
            delay(4000)
            replace()
        }
    }

    private fun replace() {
        socket?.cancel()
        socket = null
        retry = 0
        connect()
    }

    private fun sendWatch() {
        val msg = buildJsonObject {
            put("type", "watch")
            putJsonArray("ids") { watched.keys.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
        }
        socket?.send(msg.toString())
    }

    /** The server sends a session's transcript traffic only while someone watches it. */
    fun watch(sessionId: String) {
        watched.merge(sessionId, 1, Int::plus)
        sendWatch()
    }

    fun unwatch(sessionId: String) {
        watched.computeIfPresent(sessionId) { _, n -> if (n <= 1) null else n - 1 }
        sendWatch()
    }

    private fun onServerMessage(m: ServerMessage) {
        when (m) {
            is ServerMessage.Hello -> _state.update { it.copy(sessions = m.sessions, approvals = m.approvals) }
            is ServerMessage.SessionChanged -> _state.update { it.copy(sessions = it.sessions.upsert(m.session) { s -> s.id }) }
            is ServerMessage.SessionRemoved -> _state.update { it.copy(sessions = it.sessions.filterNot { s -> s.id == m.id }) }
            is ServerMessage.TaskChanged -> _state.update { it.copy(tasks = it.tasks.upsert(m.task) { t -> t.id }) }
            is ServerMessage.ApprovalAdded -> {
                val isNew = _state.value.approvals.none { it.id == m.approval.id }
                _state.update { it.copy(approvals = it.approvals.filterNot { a -> a.id == m.approval.id } + m.approval) }
                if (isNew) _newApprovals.tryEmit(m.approval)
            }
            is ServerMessage.ApprovalDone -> {
                _state.update { it.copy(approvals = it.approvals.filterNot { a -> a.id == m.id }) }
                _session.tryEmit(m)
            }
            is ServerMessage.Event, is ServerMessage.Delta, is ServerMessage.Suggestion, is ServerMessage.Commands -> _session.tryEmit(m)
            is ServerMessage.Git, ServerMessage.Pong -> Unit
        }
    }

    // -- writes that change shared lists --------------------------------------------------

    /** Applied locally at once; the socket's echo is idempotent. */
    fun applySession(s: Session) = _state.update { it.copy(sessions = it.sessions.upsert(s) { x -> x.id }) }
    fun dropApproval(id: String) = _state.update { it.copy(approvals = it.approvals.filterNot { a -> a.id == id }) }
}

private inline fun <T> List<T>.upsert(item: T, id: (T) -> String): List<T> {
    val i = indexOfFirst { id(it) == id(item) }
    return if (i < 0) listOf(item) + this else toMutableList().also { it[i] = item }
}
