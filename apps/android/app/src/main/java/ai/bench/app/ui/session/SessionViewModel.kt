package ai.bench.app.ui.session

import kotlinx.serialization.json.put

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ai.bench.app.data.BenchError
import ai.bench.app.data.BenchStore
import ai.bench.app.data.Row
import ai.bench.app.data.ServerMessage
import ai.bench.app.data.SessionEvent
import ai.bench.app.data.SlashCommand
import ai.bench.app.data.Transcript
import ai.bench.app.data.UploadRef
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

/** One file on its way to the session, or there already. */
data class Attachment(val key: Long, val name: String, val progress: Float, val ref: UploadRef? = null, val error: String? = null)

data class Stream(val text: String = "", val thinking: String = "")

/** The server's page size for a transcript read (src/store.mjs listEvents). */
private const val PAGE = 400

class SessionViewModel(val sessionId: String, private val store: BenchStore) : ViewModel() {
    private val api = store.api

    private val events = MutableStateFlow<List<SessionEvent>>(emptyList())
    private val _stream = MutableStateFlow(Stream())
    val stream: StateFlow<Stream> = _stream.asStateFlow()
    private val _loading = MutableStateFlow(true)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()
    private val _hasOlder = MutableStateFlow(false)
    val hasOlder: StateFlow<Boolean> = _hasOlder.asStateFlow()
    private val _suggestion = MutableStateFlow("")
    val suggestion: StateFlow<String> = _suggestion.asStateFlow()
    private val _commands = MutableStateFlow<List<SlashCommand>>(emptyList())
    val commands: StateFlow<List<SlashCommand>> = _commands.asStateFlow()
    private val _attachments = MutableStateFlow<List<Attachment>>(emptyList())
    val attachments: StateFlow<List<Attachment>> = _attachments.asStateFlow()
    private val _busyApprovals = MutableStateFlow<Set<String>>(emptySet())
    val busyApprovals: StateFlow<Set<String>> = _busyApprovals.asStateFlow()
    private val _thinking = MutableStateFlow<Map<String, String>>(emptyMap())
    val thinking: StateFlow<Map<String, String>> = _thinking.asStateFlow()
    private val _errors = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val errors = _errors.asSharedFlow()

    val session = store.state.map { s -> s.sessions.firstOrNull { it.id == sessionId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, store.state.value.sessions.firstOrNull { it.id == sessionId })
    val approvals = store.state.map { s -> s.approvals.filter { it.session_id == sessionId } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Rows are rebuilt off the main thread: a long session is thousands of events. */
    val rows: StateFlow<List<Row>> = combine(events, session) { e, s -> Transcript.rows(e, s?.cwd.orEmpty()) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private var pendingDelta = Stream()
    private var flushJob: Job? = null
    private var nextKey = 0L

    init {
        store.watch(sessionId)
        viewModelScope.launch { load(null) }
        viewModelScope.launch { store.reconnected.collect { load(events.value.lastOrNull()?.id) } }
        viewModelScope.launch {
            store.sessionTraffic.collect { m ->
                when (m) {
                    is ServerMessage.Event -> if (m.event.session_id == sessionId) onEvent(m.event)
                    is ServerMessage.Delta -> if (m.sessionId == sessionId) onDelta(m)
                    is ServerMessage.Suggestion -> if (m.sessionId == sessionId) _suggestion.value = m.text
                    is ServerMessage.Commands -> if (m.sessionId == sessionId) _commands.value = m.commands
                    is ServerMessage.ApprovalDone -> _busyApprovals.update { it - m.id }
                    else -> Unit
                }
            }
        }
    }

    private suspend fun load(after: Long?) {
        try {
            val d = api.session(sessionId, after)
            store.applySession(d.session)
            events.update { Transcript.merge(it, d.events, suffix = after != null) }
            if (after == null) _hasOlder.value = d.events.size >= PAGE
            d.commands?.let { _commands.value = it }
            if (d.events.any { Transcript.finishesStream(it.kind) }) clearStream()
        } catch (e: BenchError) {
            _errors.tryEmit(e.message ?: "Could not load the session")
        } finally {
            _loading.value = false
        }
    }

    private var loadingOlder = false
    fun loadOlder() {
        val first = events.value.firstOrNull()?.id ?: return
        if (loadingOlder) return
        loadingOlder = true
        viewModelScope.launch {
            runCatching { api.olderEvents(sessionId, first) }
                .onSuccess { older ->
                    _hasOlder.value = older.size >= PAGE
                    events.update { (older + it).distinctBy { e -> e.id }.sortedBy { e -> e.id } }
                }
                .onFailure { _errors.tryEmit(it.message ?: "Could not load earlier messages") }
            loadingOlder = false
        }
    }

    private fun onEvent(e: SessionEvent) {
        if (e.kind == "user" || e.kind == "assistant") _suggestion.value = ""
        events.update { list -> if (list.any { it.id == e.id }) list else list + e }
        if (Transcript.finishesStream(e.kind)) clearStream()
    }

    /** Deltas arrive per token; they are drawn at most every 50ms, not per token. */
    private fun onDelta(d: ServerMessage.Delta) {
        pendingDelta = if (d.thinking) pendingDelta.copy(thinking = pendingDelta.thinking + d.text) else pendingDelta.copy(text = pendingDelta.text + d.text)
        if (flushJob?.isActive == true) return
        flushJob = viewModelScope.launch {
            delay(50)
            val p = pendingDelta
            pendingDelta = Stream()
            _stream.update { Stream(it.text + p.text, it.thinking + p.thinking) }
        }
    }

    private fun clearStream() {
        flushJob?.cancel()
        pendingDelta = Stream()
        _stream.value = Stream()
    }

    fun openThinking(row: Row.Thinking) {
        if (row.text != null || _thinking.value.containsKey(row.key)) return
        viewModelScope.launch {
            runCatching { api.thinking(sessionId, row.eventId) }
                .onSuccess { blocks -> _thinking.update { it + (row.key to (blocks[row.index] ?: "")) } }
                .onFailure { _thinking.update { it + (row.key to "That reasoning could not be loaded.") } }
        }
    }

    // -- actions --------------------------------------------------------------------------

    /** Sends the composer's text with whatever has finished uploading. False if refused. */
    suspend fun send(text: String): Boolean {
        val ready = _attachments.value
        if (ready.any { it.ref == null && it.error == null }) {
            _errors.tryEmit("Wait for the upload to finish")
            return false
        }
        val refs = ready.mapNotNull { it.ref }
        if (text.isBlank() && refs.isEmpty()) return false
        return try {
            api.send(sessionId, text, refs)
            _attachments.value = emptyList()
            true
        } catch (e: BenchError) {
            _errors.tryEmit(e.message ?: "Could not send")
            false
        }
    }

    fun interrupt() = viewModelScope.launch { runCatching { api.interrupt(sessionId) }.onFailure { _errors.tryEmit(it.message ?: "") } }

    fun setMode(mode: String) = viewModelScope.launch { runCatching { api.setMode(sessionId, mode) }.onFailure { _errors.tryEmit(it.message ?: "") } }

    /** Provider, model or effort for the next turn on (PATCH /api/sessions/:id). */
    fun configure(body: kotlinx.serialization.json.JsonObject) = viewModelScope.launch {
        runCatching { api.patch("/api/sessions/${ai.bench.app.data.BenchApi.enc(sessionId)}", body) }.onFailure { _errors.tryEmit(it.message ?: "") }
    }
    suspend fun providers() = runCatching { api.providers() }.getOrDefault(emptyList())

    suspend fun routing() = runCatching { api.get("/api/sessions/${ai.bench.app.data.BenchApi.enc(sessionId)}/routing") }.getOrNull()
    fun unarchive() = viewModelScope.launch { runCatching { api.archive(sessionId, false) }.onFailure { _errors.tryEmit(it.message ?: "") } }
    fun delete(done: () -> Unit) = viewModelScope.launch {
        runCatching { api.delete("/api/sessions/${ai.bench.app.data.BenchApi.enc(sessionId)}") }.onSuccess { done() }.onFailure { _errors.tryEmit(it.message ?: "") }
    }
    fun setAutoResume(on: Boolean) = viewModelScope.launch {
        runCatching { api.post("/api/sessions/${ai.bench.app.data.BenchApi.enc(sessionId)}/limit", kotlinx.serialization.json.buildJsonObject { put("autoResume", on) }) }
    }
    fun resumeNow() = viewModelScope.launch {
        runCatching { api.post("/api/sessions/${ai.bench.app.data.BenchApi.enc(sessionId)}/limit", kotlinx.serialization.json.buildJsonObject { put("resumeNow", true) }) }
    }

    fun rename(title: String) = viewModelScope.launch { runCatching { api.rename(sessionId, title) }.onFailure { _errors.tryEmit(it.message ?: "") } }

    fun archive(done: () -> Unit) = viewModelScope.launch {
        runCatching { api.archive(sessionId) }.onSuccess { done() }.onFailure { _errors.tryEmit(it.message ?: "") }
    }

    fun answer(approvalId: String, body: JsonObject) {
        _busyApprovals.update { it + approvalId }
        viewModelScope.launch {
            try {
                api.answer(approvalId, body)
                store.dropApproval(approvalId)
            } catch (e: BenchError) {
                _busyApprovals.update { it - approvalId }
                _errors.tryEmit(e.message ?: "Could not answer")
            }
        }
    }

    fun attach(resolver: ContentResolver, uris: List<Uri>) {
        for (uri in uris) {
            val (name, size) = resolver.query(uri, null, null, null, null)?.use { c ->
                val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val s = c.getColumnIndex(OpenableColumns.SIZE)
                if (c.moveToFirst()) (if (n >= 0) c.getString(n) else null) to (if (s >= 0) c.getLong(s) else -1L) else null to -1L
            } ?: (null to -1L)
            val type = resolver.getType(uri) ?: "application/octet-stream"
            val key = nextKey++
            val label = name ?: uri.lastPathSegment ?: "file"
            _attachments.update { it + Attachment(key, label, 0f) }
            viewModelScope.launch {
                try {
                    val ref = api.upload(sessionId, label, type, size, { resolver.openInputStream(uri) ?: error("Can't read $label") }) { p ->
                        _attachments.update { list -> list.map { if (it.key == key) it.copy(progress = p) else it } }
                    }
                    _attachments.update { list -> list.map { if (it.key == key) it.copy(progress = 1f, ref = ref) else it } }
                } catch (e: Exception) {
                    _attachments.update { list -> list.map { if (it.key == key) it.copy(error = e.message ?: "Upload failed") else it } }
                }
            }
        }
    }

    fun removeAttachment(key: Long) = _attachments.update { list -> list.filterNot { it.key == key } }

    override fun onCleared() {
        store.unwatch(sessionId)
    }
}
