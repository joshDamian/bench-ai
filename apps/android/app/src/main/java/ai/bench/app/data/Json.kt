package ai.bench.app.data

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

// Small readers for the untyped parts of the wire: event data and tool inputs.

fun JsonElement?.obj(): JsonObject? = this as? JsonObject
fun JsonElement?.arr(): JsonArray? = this as? JsonArray
fun JsonElement?.str(): String? = (this as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonElement?.bool(): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: false
fun JsonElement?.long(): Long? = (this as? JsonPrimitive)?.longOrNull

operator fun JsonElement?.get(key: String): JsonElement? = (this as? JsonObject)?.get(key)

/** Pretty-prints a JSON value for a tool's input. */
fun JsonElement.pretty(): String = PrettyJson.encodeToString(JsonElement.serializer(), this)

private val PrettyJson = kotlinx.serialization.json.Json { prettyPrint = true }

/** Decodes one socket frame. Null for a type this client does not know. */
fun parseServerMessage(text: String): ServerMessage? {
    val o = runCatching { BenchJson.parseToJsonElement(text).jsonObject }.getOrNull() ?: return null
    val j = BenchJson
    return runCatching {
        when (o["type"].str()) {
            "hello" -> ServerMessage.Hello(
                o["sessions"].arr().orEmpty().mapNotNull { runCatching { j.decodeFromJsonElement<Session>(it) }.getOrNull() },
                o["approvals"].arr().orEmpty().mapNotNull { runCatching { j.decodeFromJsonElement<Approval>(it) }.getOrNull() },
            )
            "session" -> ServerMessage.SessionChanged(j.decodeFromJsonElement(o["session"]!!))
            "session-removed" -> ServerMessage.SessionRemoved(o["id"].str()!!)
            "task" -> ServerMessage.TaskChanged(j.decodeFromJsonElement(o["task"]!!))
            "event" -> ServerMessage.Event(j.decodeFromJsonElement(o["event"]!!))
            "delta" -> ServerMessage.Delta(o["session_id"].str()!!, o["text"].str() ?: "", o["thinking"].bool())
            "approval" -> ServerMessage.ApprovalAdded(j.decodeFromJsonElement(o["approval"]!!))
            "approval-done" -> ServerMessage.ApprovalDone(o["id"].str()!!, o["session_id"].str() ?: "")
            "suggestion" -> ServerMessage.Suggestion(o["session_id"].str()!!, o["text"].str() ?: "")
            "commands" -> ServerMessage.Commands(o["session_id"].str()!!, j.decodeFromJsonElement(o["commands"] ?: JsonArray(emptyList())))
            "git" -> ServerMessage.Git(o["project_id"].str() ?: "")
            "pong" -> ServerMessage.Pong
            else -> null
        }
    }.getOrNull()
}
