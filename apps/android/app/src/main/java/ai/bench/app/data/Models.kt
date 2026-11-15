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
    /** Whether Bench resumes it when the limit lifts; null follows the default. */
    val auto_resume: Boolean? = null,
    /** Spend by model, subagents included. */
    val model_usage: kotlinx.serialization.json.JsonObject? = null,
    /** When the person last looked at it; Home lists what changed after it. */
    val seen_at: String? = null,
    /** Kept on Home whatever its state. */
    val pinned: Boolean = false,
    /** The schedule that started it, if one did. */
    val schedule_id: String? = null,
    /** The most one query of an unattended run may spend, in USD. */
    val budget: Double? = null,
    /** It stopped at its budget and waits for the person. */
    val capped: Boolean = false,
    /** The "Then: …" steps its last reply offered. */
    val next_steps: List<String>? = null,
) {
    /** Changed since the person last looked: a reply, a finish, an error. */
    val unseen get() = seen_at == null || updated_at > seen_at
}

/** Something a session made for the person (GET /api/reports): a report, a page, or email drafts. */
@Serializable
data class Report(
    val session_id: String,
    val path: String,
    val name: String,
    /** report (.md), page (.html), drafts (.drafts.json) or file. */
    val kind: String,
    val title: String,
    val size: Long = 0,
    val modified: String = "",
    val seen: Boolean = false,
    val drafts: Int? = null,
    val unsent: Int? = null,
    /** The schedule whose run made it. */
    val schedule_id: String? = null,
    /** A board: how many entries its last run marked new. */
    val new: Int? = null,
)

/** Who the person is, read by every session (GET /api/about). */
@Serializable
data class About(
    val text: String? = null,
    /** A draft made from a CV, used only once saved. */
    val draft: String? = null,
    val include: Boolean = false,
    val path: String = "",
    val changed_by: ChangedBy? = null,
) {
    @Serializable
    data class ChangedBy(val session_id: String, val title: String? = null)
}

/** Caps for unattended runs, and today's spend (GET /api/spending). */
@Serializable
data class Spending(
    val daily_cap: Double = 5.0,
    val run_cap: Double = 2.0,
    val paused: Boolean = false,
    val today: Double = 0.0,
    val runs_today: Int = 0,
)

/** The browser on the server (GET /api/browser). */
@Serializable
data class BrowserStatus(val installed: Boolean = false, val running: Boolean = false, val sites: List<String> = emptyList())

/** Can reach (GET /api/reach): what Bench can use beyond its own folders. */
/** A device that shares part of itself with Bench (GET /api/devices): the person's Mac. Go server only. */
@Serializable
data class LinkedDevice(
    val id: String,
    val name: String,
    val version: String? = null,
    val online: Boolean = false,
    val last_seen: String? = null,
    val shares: List<Share> = emptyList(),
    val asks: List<DeviceAsk> = emptyList(),
) {
    /** kind: folder, app, screen or run; rule: allow or ask. Set on the device only. */
    @Serializable
    data class Share(val id: String, val kind: String, val name: String = "", val path: String? = null, val access: String = "", val rule: String = "ask")
}

/** Something Bench asked a device to share, waiting there for the person to decide. */
@Serializable
data class DeviceAsk(val id: String, val device_id: String, val kind: String, val path: String = "", val access: String = "", val why: String = "", val session_id: String? = null, val created_at: String = "")

@Serializable
data class Reach(val server: List<Item> = emptyList(), val accounts: List<Item> = emptyList(), val browser: BrowserStatus? = null, val devices: List<Item> = emptyList()) {
    @Serializable
    data class Item(val key: String, val name: String, val detail: String = "")
}

/** One rule: what Bench may do, asks about, or never does (GET /api/rules). value: allow, ask or never. */
@Serializable
data class Rule(val key: String, val label: String, val value: String)

/** A session that starts on its own (GET /api/schedules). days: 0 Sunday .. 6 Saturday. */
@Serializable
data class Schedule(
    val id: String,
    val title: String,
    val project_id: String,
    val prompt: String,
    val days: String,
    val time: String,
    val tz: String,
    val mode: String? = null,
    val model_id: String? = null,
    val provider_id: String? = null,
    val effort: String? = null,
    val enabled: Boolean = true,
    val last_run_at: String? = null,
    val next_run_at: String? = null,
    /** Keeps one board up to date instead of a report per run. */
    val keep_board: Boolean = false,
    /** Runs when this page changes instead of at a time. */
    val watch: Watch? = null,
    /** When the watched page was last looked at. */
    val checked_at: String? = null,
)

/** What Bench did (GET /api/activity): runs and other doings, newest first. Go server only. */
@Serializable
data class ActivityFeed(val items: List<ActivityItem> = emptyList(), val unseen: Int = 0, val seen_at: String? = null)

@Serializable
data class ActivityItem(
    val at: String,
    val kind: String = "run",
    val title: String = "",
    val by: String? = null,
    val detail: String? = null,
    val session_id: String? = null,
    val cost: Double = 0.0,
    val badge: String? = null,
    val undo: Boolean = false,
    val id: Long? = null,
)

/** Housekeeping (GET /api/housekeeping): what Bench tidies on its own. Go server only. */
@Serializable
data class Housekeeping(
    val archive_idle: Boolean = true,
    val prune_branches: Boolean = true,
    val free_disk: Boolean = true,
    val keep_board: Boolean = true,
    val freed: Freed? = null,
    val archived: Int = 0,
) {
    @Serializable
    data class Freed(val bytes: Double = 0.0, val at: String = "")
}

/** The archive (GET /api/archive): what was put away and can come back. */
@Serializable
data class ArchiveList(val sessions: List<Item> = emptyList(), val schedules: List<Item> = emptyList()) {
    @Serializable
    data class Item(val id: String, val title: String = "", val archived_at: String? = null, val project_id: String? = null)
}

/** A saved prompt with blanks, run again from New (GET /api/recipes). */
@Serializable
data class Recipe(val id: String, val title: String, val prompt: String, val project_id: String? = null, val blanks: List<String> = emptyList())

/** What a new schedule starts with when it comes from a session's Keep watching. */
data class SchedulePrefill(val title: String, val prompt: String, val projectId: String?)

@Serializable
data class Watch(val url: String, val every_hours: Int = 6)

/** "linkedin.com/jobs" from "https://www.linkedin.com/jobs/": a page's address as the rows show it. */
fun pageName(url: String): String = url.removePrefix("https://").removePrefix("http://").removePrefix("www.").trimEnd('/')

/** A schedule's own page (GET /api/schedules/:id): its runs and the board it keeps. */
@Serializable
data class ScheduleDetail(val schedule: Schedule, val runs: List<Run> = emptyList(), val board: Board? = null) {
    @Serializable
    data class Run(val id: String, val title: String = "", val status: String = "idle", val cost: Double = 0.0, val capped: Boolean = false, val created_at: String = "", val archived: Boolean = false)
    @Serializable
    data class Board(val session_id: String? = null, val path: String, val name: String, val title: String = "", val new: Int = 0, val history: List<String> = emptyList())
}

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
    /** A device linked, went offline, or changed what it shares or what waits on it. */
    data object DevicesChanged : ServerMessage
}
