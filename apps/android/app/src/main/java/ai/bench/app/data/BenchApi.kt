package ai.bench.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import okio.source
import java.io.IOException
import java.io.InputStream
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resumeWithException

/** A refusal from the server, with its status and its own words. */
class BenchError(val status: Int, message: String) : Exception(message) {
    val unauthorized get() = status == 401
    /** No answer at all, as opposed to the server saying no. */
    val offline get() = status == 0
}

/**
 * The HTTP half of the server's API (see web/api.mjs). Every call carries the device's
 * token as a Bearer header, which the server accepts alongside its cookie.
 */
class BenchApi(val config: ServerConfig) {
    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .pingInterval(20, TimeUnit.SECONDS)
        .build()

    private val jsonType = "application/json".toMediaType()

    fun request(path: String): Request.Builder = Request.Builder().url(config.baseUrl + path).apply {
        config.token?.let { header("Authorization", "Bearer $it") }
    }

    private suspend fun call(req: Request): JsonElement? = withContext(Dispatchers.IO) {
        val res = try {
            http.newCall(req).await()
        } catch (e: IOException) {
            throw BenchError(0, "Can't reach the server: ${e.message ?: "network error"}")
        }
        res.use {
            val text = it.body?.string().orEmpty()
            val data = if (text.isBlank()) null else runCatching { BenchJson.parseToJsonElement(text) }.getOrNull()
            if (!it.isSuccessful) throw BenchError(it.code, data["error"].str() ?: "Request failed (${it.code})")
            data
        }
    }

    suspend fun get(path: String) = call(request(path).get().build())
    suspend fun post(path: String, body: JsonElement = JsonObject(emptyMap())) =
        call(request(path).post(body.toString().toRequestBody(jsonType)).build())
    suspend fun patch(path: String, body: JsonElement) =
        call(request(path).patch(body.toString().toRequestBody(jsonType)).build())
    suspend fun delete(path: String) = call(request(path).delete().build())

    private inline fun <reified T> JsonElement?.decode(): T = BenchJson.decodeFromJsonElement(this ?: JsonObject(emptyMap()))

    // -- calls ------------------------------------------------------------------------------

    suspend fun state(): StateSnapshot = get("/api/state").decode()
    suspend fun session(id: String, after: Long? = null): SessionDetail =
        get("/api/sessions/${enc(id)}" + (after?.let { "?after=$it" } ?: "")).decode()
    suspend fun olderEvents(id: String, before: Long): List<SessionEvent> =
        get("/api/sessions/${enc(id)}/events?before=$before").decode()
    suspend fun thinking(id: String, eventId: Long): Map<Int, String> {
        val blocks = get("/api/sessions/${enc(id)}/events/$eventId/thinking")["blocks"].arr() ?: return emptyMap()
        return blocks.associate { (it["index"].long() ?: -1).toInt() to (it["thinking"].str() ?: "") }
    }

    suspend fun send(id: String, text: String, uploads: List<UploadRef> = emptyList()) = post(
        "/api/sessions/${enc(id)}/messages",
        buildJsonObject {
            put("text", text)
            putJsonArray("images") {}
            putJsonArray("uploads") {
                uploads.forEach { u -> add(buildJsonObject { put("file", u.file); put("type", u.type) }) }
            }
        },
    )

    suspend fun interrupt(id: String) = post("/api/sessions/${enc(id)}/interrupt")
    suspend fun setMode(id: String, mode: String) = patch("/api/sessions/${enc(id)}", buildJsonObject { put("mode", mode) })
    suspend fun rename(id: String, title: String) = patch("/api/sessions/${enc(id)}", buildJsonObject { put("title", title) })
    suspend fun archive(id: String, archived: Boolean = true) = post("/api/sessions/${enc(id)}/archive", buildJsonObject { put("archived", archived) })

    suspend fun createSession(projectId: String, taskId: String? = null, mode: String? = null): Session = post(
        "/api/sessions",
        buildJsonObject {
            put("projectId", projectId)
            taskId?.let { put("taskId", it) }
            mode?.let { put("mode", it) }
        },
    ).decode()

    /**
     * Makes a task - a branch in its own worktree - and the server starts its first session
     * and sends it the prompt (src/service.mjs createTask). Returns that session.
     */
    suspend fun createTask(projectId: String, title: String, prompt: String?, mode: String?): Session {
        val r = post(
            "/api/tasks",
            buildJsonObject {
                put("projectId", projectId)
                put("title", title)
                prompt?.takeIf { it.isNotBlank() }?.let { put("prompt", it.trim()) }
                mode?.let { put("mode", it) }
            },
        )
        return BenchJson.decodeFromJsonElement(r["session"] ?: throw BenchError(500, "The server made the task but sent no session"))
    }

    suspend fun answer(approvalId: String, body: JsonObject) = post("/api/approvals/${enc(approvalId)}", body)

    /**
     * Streams a file to a session's attachments as raw bytes, never holding it in memory,
     * as the web client does for large files.
     */
    suspend fun upload(
        sessionId: String,
        name: String,
        type: String,
        size: Long,
        open: () -> InputStream,
        onProgress: (Float) -> Unit,
    ): UploadRef = withContext(Dispatchers.IO) {
        val body = object : RequestBody() {
            override fun contentType() = "application/octet-stream".toMediaType()
            override fun contentLength() = if (size > 0) size else -1
            override fun writeTo(sink: BufferedSink) {
                open().source().use { src ->
                    var sent = 0L
                    val buf = okio.Buffer()
                    while (true) {
                        val n = src.read(buf, 64 * 1024)
                        if (n == -1L) break
                        sink.write(buf, n)
                        sent += n
                        if (size > 0) onProgress(sent.toFloat() / size)
                    }
                }
            }
        }
        val q = "name=${enc(name)}&type=${enc(type)}"
        val data = call(request("/api/sessions/${enc(sessionId)}/uploads?$q").post(body).build())
        UploadRef(data["file"].str() ?: error("No file in the answer"), data["name"].str() ?: name, type)
    }

    fun socketUrl(): String = config.baseUrl.replaceFirst(Regex("^http"), "ws") + "/api/ws"

    companion object {
        fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

        /** Trades a pairing code for a device token. */
        suspend fun pair(link: PairLink, deviceName: String): ServerConfig = withContext(Dispatchers.IO) {
            val http = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).build()
            val body = buildJsonObject { put("code", link.code); put("name", deviceName) }
            val req = Request.Builder().url("${link.baseUrl}/api/pair")
                .post(body.toString().toRequestBody("application/json".toMediaType())).build()
            val res = try {
                http.newCall(req).await()
            } catch (e: IOException) {
                throw BenchError(0, "Can't reach ${link.baseUrl}: ${e.message ?: "network error"}")
            }
            res.use {
                val text = it.body?.string().orEmpty()
                if (!it.isSuccessful) {
                    val msg = runCatching { BenchJson.parseToJsonElement(text)["error"].str() }.getOrNull()
                    throw BenchError(it.code, msg ?: "Pairing failed (${it.code})")
                }
                val token = Pairing.tokenFromCookies(it.headers("Set-Cookie"))
                    ?: throw BenchError(500, "The server paired but sent no token")
                ServerConfig(link.baseUrl, token, deviceName)
            }
        }

        /** Whether a server answers at this address, and whether it already knows us. */
        suspend fun ping(config: ServerConfig): Boolean = runCatching {
            BenchApi(config).get("/api/ping")["paired"].bool()
        }.getOrDefault(false)
    }
}

data class UploadRef(val file: String, val name: String, val type: String)

suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) = cont.resumeWith(Result.success(response))
        override fun onFailure(call: Call, e: IOException) {
            if (!cont.isCancelled) cont.resumeWithException(e)
        }
    })
    cont.invokeOnCancellation { runCatching { cancel() } }
}
