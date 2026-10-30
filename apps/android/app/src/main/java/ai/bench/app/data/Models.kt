package ai.bench.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// The wire contract, mirrored from src/contract.d.mts. Only the fields this client reads
// are declared; the decoder ignores the rest, so a server that grows a field never breaks
// an installed app.

val BenchJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false
    coerceInputValues = true
    isLenient = true
}

@Serializable
data class Project(
    val id: String,
    val name: String = "Project",
    val path: String = "",
    val check_command: String? = null,
)

@Serializable
data class TaskLoop(
    val max_attempts: Int = 0,
    val max_minutes: Int = 0,
    val attempts: Int = 0,
    val state: String = "looping",
    val reason: String? = null,
)

@Serializable
data class BenchTask(
    val id: String,
    val project_id: String? = null,
    val title: String = "Task",
    val description: String? = null,
    val base_branch: String = "",
    val branch: String = "",
    val state: String = "active",
    val done: String? = null,
    val check_command: String? = null,
    val check_status: String? = null,
    val check_output: String? = null,
    val loop: TaskLoop? = null,
    val check_at: String? = null,
    val updated_at: String = "",
)

@Serializable
data class Session(
    val id: String,
    /** Null for a session outside any project: the server's "ask" sessions. */
    val project_id: String? = null,
    val task_id: String? = null,
    val title: String = "Session",
    val cwd: String = "",
    val provider_id: String = "",
    val model_id: String? = null,
    val permission_mode: String = "default",
    val effort: String? = null,
    val claude_session_id: String? = null,
    val status: String = "idle",
    val error: String? = null,
    val cost: Double = 0.0,
    val input_tokens: Long = 0,
    val output_tokens: Long = 0,
    val created_at: String = "",
    val updated_at: String = "",
    val archived_at: String? = null,
    /** Set while the Claude login's usage limit has this session stopped. */
    val pause: SessionPause? = null,
)

@Serializable
data class SessionPause(val until: String? = null, val message: String = "", val tries: Int = 0)

@Serializable
data class SessionEvent(
    val id: Long,
    val session_id: String,
    val kind: String,
    val data: JsonElement? = null,
    val created_at: String = "",
)

@Serializable
data class Approval(
    val id: String,
    val session_id: String,
    val tool: String,
    val input: JsonObject = JsonObject(emptyMap()),
    val title: String? = null,
    val reason: String? = null,
    val created_at: String = "",
)

@Serializable
data class SlashCommand(val name: String, val description: String = "", val argumentHint: String = "")

@Serializable
data class Me(val local: Boolean = false, val device: Device? = null)

@Serializable
data class Device(val id: String, val name: String)

@Serializable
data class Defaults(
    val providerId: String = "",
    val modelId: String? = null,
    val mode: String = "default",
    val effort: String? = null,
)

@Serializable
data class StateSnapshot(
    val version: String? = null,
    val projects: List<Project> = emptyList(),
    val tasks: List<BenchTask> = emptyList(),
    val sessions: List<Session> = emptyList(),
    val approvals: List<Approval> = emptyList(),
    val defaults: Defaults = Defaults(),
    val me: Me? = null,
)

@Serializable
data class SessionDetail(
    val session: Session,
    val events: List<SessionEvent> = emptyList(),
    val approvals: List<Approval> = emptyList(),
    val live: Boolean = false,
    val commands: List<SlashCommand>? = null,
)

@Serializable
data class TaskGit(
    val ahead: Int = 0,
    val behind: Int = 0,
    val files: Int = 0,
    val added: Int = 0,
    val removed: Int = 0,
    val merged: Boolean = false,
    val canMerge: CanMerge? = null,
    val dirty: List<String> = emptyList(),
    val conflicted: List<String> = emptyList(),
) {
    @Serializable data class CanMerge(val ok: Boolean = true, val reason: String? = null)
}

@Serializable
data class GitState(val branch: String? = null, val ahead: Int = 0, val behind: Int = 0, val dirty: List<String> = emptyList(), val conflicted: List<String> = emptyList(), val branches: List<GitBranch> = emptyList(), val hasRemote: Boolean = true)

@Serializable
data class GitBranch(val name: String, val remoteOnly: Boolean = false, val current: Boolean = false)

@Serializable
data class ModelInfo(val id: String, val name: String = "", val display_name: String? = null, val enabled: Boolean = true) {
    val label get() = display_name ?: name
}

@Serializable
data class ProviderInfo(val id: String, val name: String = "", val enabled: Boolean = true, val ready: Boolean = true, val reason: String? = null, val models: List<ModelInfo> = emptyList())

@Serializable
data class GitRepo(val name: String, val description: String = "", val private: Boolean = false, val updatedAt: String? = null)

/** One file in a task's diff, its hunks kept as lines. */
data class DiffFile(val path: String, val lines: List<String>, val status: String) {
    val added get() = lines.count { it.startsWith("+") }
    val removed get() = lines.count { it.startsWith("-") }

    companion object {
        fun parse(diff: String): List<DiffFile> {
            val out = mutableListOf<DiffFile>()
            var path: String? = null
            var status = "M"
            val lines = mutableListOf<String>()
            fun flush() { path?.let { out += DiffFile(it, lines.toList(), status) }; lines.clear(); status = "M" }
            for (l in diff.lines()) {
                when {
                    l.startsWith("diff --git ") -> { flush(); path = l.substringAfterLast(" b/") }
                    l.startsWith("new file mode") -> status = "A"
                    l.startsWith("deleted file mode") -> status = "D"
                    path == null || l.startsWith("index ") || l.startsWith("--- ") || l.startsWith("+++ ") -> Unit
                    else -> lines += l
                }
            }
            flush()
            return out
        }
    }
}

/** Reasoning effort, as src/service.mjs EFFORTS lists it. */
enum class Effort(val wire: String, val label: String) {
    Low("low", "Low"), Medium("medium", "Medium"), High("high", "High"), XHigh("xhigh", "Extra high"), Max("max", "Max");
    companion object { fun of(wire: String) = entries.firstOrNull { it.wire == wire } }
}

/** Permission modes, in the order the web picker shows them. */
enum class Mode(val wire: String, val label: String) {
    Default("default", "Ask before edits"),
    AcceptEdits("acceptEdits", "Accept edits"),
    Plan("plan", "Plan"),
    Auto("auto", "Auto"),
    Bypass("bypassPermissions", "Bypass");

    companion object {
        fun of(wire: String) = entries.firstOrNull { it.wire == wire } ?: Default
    }
}

/** What arrives over /api/ws, decoded by its `type`. */
sealed interface ServerMessage {
    data class Hello(val sessions: List<Session>, val approvals: List<Approval>) : ServerMessage
    data class SessionChanged(val session: Session) : ServerMessage
    data class SessionRemoved(val id: String) : ServerMessage
    data class TaskChanged(val task: BenchTask) : ServerMessage
    data class Event(val event: SessionEvent) : ServerMessage
    data class Delta(val sessionId: String, val text: String, val thinking: Boolean) : ServerMessage
    data class ApprovalAdded(val approval: Approval) : ServerMessage
    data class ApprovalDone(val id: String, val sessionId: String) : ServerMessage
    data class Suggestion(val sessionId: String, val text: String) : ServerMessage
    data class Commands(val sessionId: String, val commands: List<SlashCommand>) : ServerMessage
    data class Git(val projectId: String) : ServerMessage
    data object Pong : ServerMessage
}
