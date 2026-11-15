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
    suspend fun put(path: String, body: JsonElement) =
        call(request(path).put(body.toString().toRequestBody(jsonType)).build())
    suspend fun patch(path: String, body: JsonElement) =
        call(request(path).patch(body.toString().toRequestBody(jsonType)).build())
    suspend fun seen(id: String) = runCatching { post("/api/sessions/${enc(id)}/seen") }
    suspend fun pin(id: String, on: Boolean) = patch("/api/sessions/${enc(id)}", buildJsonObject { put("pinned", on) })
    suspend fun reports(): List<Report> = get("/api/reports").lossy()
    suspend fun markDraft(sessionId: String, file: String, index: Int, sent: Boolean) = post("/api/reports/drafts", kotlinx.serialization.json.buildJsonObject {
        put("sessionId", kotlinx.serialization.json.JsonPrimitive(sessionId)); put("file", kotlinx.serialization.json.JsonPrimitive(file))
        put("index", kotlinx.serialization.json.JsonPrimitive(index)); put("sent", kotlinx.serialization.json.JsonPrimitive(sent))
    })
    suspend fun schedules(): List<Schedule> = get("/api/schedules").lossy()
    suspend fun activity(): ActivityFeed = get("/api/activity").decode()
    suspend fun seenActivity() = post("/api/activity/seen")
    suspend fun brief(): Boolean = (get("/api/brief").decode<JsonObject>()["morning_brief"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
    suspend fun setBrief(on: Boolean) = put("/api/brief", buildJsonObject { put("morning_brief", on) })
    suspend fun undoActivity(id: Long) = post("/api/activity/$id/undo")
    suspend fun housekeeping(): Housekeeping = get("/api/housekeeping").decode()
    suspend fun setHousekeeping(key: String, on: Boolean): Housekeeping = put("/api/housekeeping", buildJsonObject { put(key, on) }).decode()
    suspend fun archived(): ArchiveList = get("/api/archive").decode()
    suspend fun restore(kind: String, id: String) = post("/api/archive/restore", buildJsonObject { put("kind", kind); put("id", id) })
    suspend fun emptyArchive() = delete("/api/archive")
    suspend fun deleteSchedule(id: String, runs: Boolean) = delete("/api/schedules/${enc(id)}${if (runs) "?runs=1" else ""}")
    suspend fun recipes(): List<Recipe> = get("/api/recipes").lossy()
    suspend fun saveRecipe(id: String?, body: JsonObject): Recipe = (if (id == null) post("/api/recipes", body) else patch("/api/recipes/${enc(id)}", body)).decode()
    suspend fun deleteRecipe(id: String) = delete("/api/recipes/${enc(id)}")
    suspend fun saveSchedule(id: String?, body: JsonObject): Schedule = (if (id == null) post("/api/schedules", body) else patch("/api/schedules/${enc(id)}", body)).decode()
    suspend fun schedule(id: String): ScheduleDetail = get("/api/schedules/${enc(id)}").decode()
    suspend fun deleteSchedule(id: String) = delete("/api/schedules/${enc(id)}")
    suspend fun runSchedule(id: String): Session = post("/api/schedules/${enc(id)}/run").decode()
    suspend fun research(): Project = post("/api/research").decode()
    suspend fun about(): About = get("/api/about").decode()
    suspend fun saveAbout(text: String? = null, include: Boolean? = null): About = put("/api/about", buildJsonObject {
        text?.let { put("text", it) }; include?.let { put("include", it) }
    }).decode()
    suspend fun aboutDraft(action: String): About = post("/api/about/draft", buildJsonObject { put("action", action) }).decode()
    suspend fun aboutUndo(): About = post("/api/about/undo").decode()
    /** A session that turns a CV into a draft: attach the CV to it and send the prompt. */
    suspend fun aboutFromCv(): Pair<Session, String> = post("/api/about/from-cv").let { r -> r["session"].decode<Session>() to (r["prompt"].str() ?: "") }
    suspend fun rules(): List<Rule> = get("/api/rules").lossy()
    suspend fun reach(): Reach = get("/api/reach").decode()
    suspend fun browser(): BrowserStatus = get("/api/browser").decode()
    suspend fun signOut(site: String): BrowserStatus = post("/api/browser/sign-out", buildJsonObject { put("site", site) }).decode()
    suspend fun spending(): Spending = get("/api/spending").decode()
    suspend fun setSpending(dailyCap: Double? = null, runCap: Double? = null, paused: Boolean? = null): Spending = put("/api/spending", buildJsonObject {
        dailyCap?.let { put("daily_cap", it) }; runCap?.let { put("run_cap", it) }; paused?.let { put("paused", it) }
    }).decode()
    /** A run stopped at its cap: "more" goes on with another run's worth, "stop" leaves it there. */
    suspend fun budget(id: String, action: String): Session = post("/api/sessions/${enc(id)}/budget", buildJsonObject { put("action", action) }).decode()
    suspend fun setRule(key: String, value: String): List<Rule> = put("/api/rules", buildJsonObject { put("key", key); put("value", value) }).lossy()
    suspend fun delete(path: String) = call(request(path).delete().build())

    /** A shape this client cannot read is a BenchError the screen shows, never a crash. */
    private inline fun <reified T> JsonElement?.decode(): T = try {
        BenchJson.decodeFromJsonElement(this ?: JsonObject(emptyMap()))
    } catch (e: Exception) {
        throw BenchError(500, "Unexpected answer from the server: ${e.message}")
    }

    // -- calls ------------------------------------------------------------------------------

    /**
     * The state, read list by list and item by item: one record this client cannot read
     * (a newer server's shape) is skipped, not the reason nothing shows.
     */
    suspend fun state(): StateSnapshot {
        val raw = get("/api/state").obj() ?: throw BenchError(500, "Unexpected answer from the server")
        return StateSnapshot(
            version = raw["version"].str(),
            projects = raw["projects"].lossy(),
            tasks = raw["tasks"].lossy(),
            sessions = raw["sessions"].lossy(),
            approvals = raw["approvals"].lossy(),
            defaults = runCatching { BenchJson.decodeFromJsonElement<Defaults>(raw["defaults"]!!) }.getOrDefault(Defaults()),
            me = runCatching { BenchJson.decodeFromJsonElement<Me>(raw["me"]!!) }.getOrNull(),
        )
    }

    private inline fun <reified T> JsonElement?.lossy(): List<T> =
        arr().orEmpty().mapNotNull { runCatching { BenchJson.decodeFromJsonElement<T>(it) }.getOrNull() }
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

    /** With no project, an Ask Bench conversation. */
    suspend fun createSession(projectId: String?, taskId: String? = null, mode: String? = null): Session = post(
        "/api/sessions",
        buildJsonObject {
            projectId?.let { put("projectId", it) }
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

    // -- tasks, projects, providers, pairing ---------------------------------------------------

    suspend fun taskGit(id: String): TaskGit? = runCatching { get("/api/tasks/${enc(id)}")["git"].let { BenchJson.decodeFromJsonElement<TaskGit>(it!!) } }.getOrNull()
    suspend fun taskDiff(id: String): List<DiffFile> = DiffFile.parse(get("/api/tasks/${enc(id)}/diff")["diff"].str().orEmpty())
    suspend fun taskLog(id: String): List<JsonElement> = get("/api/tasks/${enc(id)}/log").arr().orEmpty()
    suspend fun taskAction(id: String, action: String, body: JsonObject = JsonObject(emptyMap())) = post("/api/tasks/${enc(id)}/$action", body)
    suspend fun projectGit(id: String): GitState? = runCatching { get("/api/projects/${enc(id)}/git").decode<GitState>() }.getOrNull()
    suspend fun projectAction(id: String, action: String) = post("/api/projects/${enc(id)}/$action")
    suspend fun providers(): List<ProviderInfo> = get("/api/providers").lossy()
    suspend fun defaults(): JsonElement? = get("/api/defaults")
    suspend fun putDefaults(body: JsonObject) = call(request("/api/defaults").put(body.toString().toRequestBody(jsonType)).build())
    suspend fun setModel(id: String, on: Boolean) = patch("/api/models/${enc(id)}", buildJsonObject { put("enabled", on) })
    suspend fun repos(): List<GitRepo> = get("/api/github/repos")["repos"].lossy()
    suspend fun pairing(): JsonElement? = post("/api/pairing")

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
