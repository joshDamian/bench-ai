package ai.bench.app.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * What the transcript draws, built from stored events the way web/views/session.mjs does:
 * a tool call is drawn with its result, runs of calls fold into one step, and each turn's
 * end carries the reply text for Copy.
 */
sealed interface Row {
    val key: String

    data class User(override val key: String, val text: String, val attachments: List<String>) : Row
    data class Text(override val key: String, val text: String, val subagent: Boolean) : Row
    data class Thinking(override val key: String, val eventId: Long, val index: Int, val length: Int, val text: String?) : Row
    data class Tools(override val key: String, val calls: List<ToolCall>, val subagent: Boolean) : Row
    data class TurnEnd(override val key: String, val ok: Boolean, val label: String, val reply: String, val cost: String? = null) : Row
    data class System(override val key: String, val text: String, val tone: Tone = Tone.Muted) : Row
    data class Check(override val key: String, val ok: Boolean, val head: String, val command: String, val output: String) : Row
}

enum class Tone { Muted, Bad, Info }

data class ToolCall(val id: String, val name: String, val input: JsonObject, val result: ToolResult?) {
    val pending get() = result == null
    val failed get() = result?.isError == true
}

data class ToolResult(val text: String, val isError: Boolean)

object Transcript {
    private val FINISHING = setOf("assistant", "result", "error")

    /** Whether this event ends the text that was streaming. */
    fun finishesStream(kind: String) = kind in FINISHING

    /**
     * Folds a fetched snapshot into what is held: the snapshot is the truth for the ids it
     * covers, and held events newer than it (pushed while it was in flight) survive.
     */
    fun merge(held: List<SessionEvent>, fetched: List<SessionEvent>, suffix: Boolean = false): List<SessionEvent> {
        if (fetched.isEmpty()) return held
        if (suffix) {
            val have = held.mapTo(HashSet()) { it.id }
            val add = fetched.filter { it.id !in have }
            return if (add.isEmpty()) held else (held + add).sortedBy { it.id }
        }
        val last = fetched.last().id
        return (fetched + held.filter { it.id > last }).sortedBy { it.id }
    }

    fun rows(events: List<SessionEvent>, cwd: String): List<Row> {
        val results = HashMap<String, ToolResult>()
        for (e in events) if (e.kind == "tool_result") {
            for (r in e.data["results"].arr().orEmpty()) {
                val id = r["tool_use_id"].str() ?: continue
                results[id] = ToolResult(resultText(r["content"]), r["is_error"].bool())
            }
        }
        val out = ArrayList<Row>()
        val reply = StringBuilder()
        // Everything Claude did between two pieces of prose - tool calls and thinking,
        // which Claude Code sends as a message each - folds into one run.
        val run = ArrayList<ToolCall>()
        var runKey = ""
        var runSub = false
        fun flush() {
            if (run.isNotEmpty()) out += Row.Tools(runKey, run.toList(), runSub)
            run.clear()
        }
        fun step(c: ToolCall, key: String, sub: Boolean) {
            if (run.isEmpty()) { runKey = key; runSub = sub }
            run += c
        }
        for (e in events) {
            val d = e.data
            if (e.kind != "assistant" && e.kind != "tool_result") flush()
            when (e.kind) {
                "user" -> {
                    reply.clear()
                    val files = d["files"].arr().orEmpty().mapNotNull { it["name"].str() }
                    val images = d["images"].long()?.toInt() ?: 0
                    val chips = buildList {
                        if (images > 0) add("$images image${if (images == 1) "" else "s"}")
                        addAll(files)
                    }
                    out += Row.User("e${e.id}", d["text"].str().orEmpty(), chips)
                }
                "assistant" -> {
                    val sub = d["parent"] != null && d["parent"].str() != null
                    d["error"].str()?.let { flush(); out += Row.System("e${e.id}-err", "API error: $it", Tone.Bad) }
                    d["content"].arr().orEmpty().forEachIndexed { i, b ->
                        when (b["type"].str()) {
                            "tool_use" -> {
                                val id = b["id"].str() ?: "$i"
                                step(ToolCall(id, b["name"].str() ?: "tool", b["input"].obj() ?: JsonObject(emptyMap()), results[id]), "e${e.id}-t$i", sub)
                            }
                            "text" -> {
                                val t = b["text"].str().orEmpty()
                                if (t.isNotBlank()) {
                                    flush()
                                    out += Row.Text("e${e.id}-$i", t, sub)
                                    if (!sub) reply.append(if (reply.isEmpty()) "" else "\n\n").append(t.trim())
                                }
                            }
                            "thinking" -> {
                                // A step like any other: folded with the run.
                                val len = b["length"].long()?.toInt() ?: b["thinking"].str()?.length ?: 0
                                val input = kotlinx.serialization.json.buildJsonObject {
                                    put("length", kotlinx.serialization.json.JsonPrimitive(len))
                                    put("event", kotlinx.serialization.json.JsonPrimitive(e.id))
                                    put("index", kotlinx.serialization.json.JsonPrimitive(i))
                                }
                                step(ToolCall("think-${e.id}-$i", "Thinking", input, ToolResult("", false)), "e${e.id}-t$i", sub)
                            }
                            else -> Unit
                        }
                    }
                }
                "result" -> {
                    val isError = d["is_error"].bool()
                    val label = buildString {
                        append(
                            when {
                                !isError -> "Worked"
                                d["subtype"].str() == "error_during_execution" -> "Stopped"
                                else -> "Failed" + (d["text"].str()?.let { ": $it" } ?: "")
                            },
                        )
                        d["duration_ms"].long()?.let { append(if (isError) " · " else " for ").append(Format.duration(it)) }
                    }
                    val cost = (d["cost"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toDoubleOrNull()?.takeIf { it > 0 }?.let { Format.cost(it) }
                    out += Row.TurnEnd("e${e.id}", !isError, label, reply.toString(), cost)
                    reply.clear()
                }
                "init" -> Unit // which Claude Code started is not news in the transcript
                "notice", "loop" -> out += Row.System("e${e.id}", d["text"].str().orEmpty(), if (e.kind == "loop") Tone.Info else Tone.Muted)
                "log" -> out += Row.System("e${e.id}", "Logged: ${d["decision"].str().orEmpty()}", Tone.Info)
                "check" -> {
                    val ok = d["ok"].bool()
                    val head = when {
                        ok -> "Check passed"
                        d["timedOut"].bool() -> "Check timed out"
                        else -> "Check failed (exit ${d["code"].long() ?: "?"})"
                    }
                    out += Row.Check("e${e.id}", ok, head, d["command"].str().orEmpty(), d["output"].str().orEmpty().ifBlank { "(no output)" })
                }
                "error" -> out += Row.System("e${e.id}", d["message"].str().orEmpty(), Tone.Bad)
            }
        }
        flush()
        return out
    }

    fun resultText(c: JsonElement?): String = when {
        c == null -> ""
        c.str() != null -> c.str()!!
        c.arr() != null -> c.arr()!!.joinToString("\n") { b -> if (b["type"].str() == "text") b["text"].str().orEmpty() else "[${b["type"].str()}]" }
        else -> c.pretty()
    }

    /** A path relative to the session's directory, which every tool call shares. */
    fun rel(path: String?, cwd: String): String {
        val p = path.orEmpty()
        return if (cwd.isNotEmpty() && p.startsWith("$cwd/")) p.removePrefix("$cwd/") else p
    }

    /** One line saying what a call did. */
    fun headline(c: ToolCall, cwd: String): String {
        val i = c.input
        return when (c.name) {
            "Bash" -> "$ ${i["command"].str().orEmpty()}"
            "Edit", "MultiEdit" -> "Edit ${rel(i["file_path"].str(), cwd)}"
            "Write" -> "Write ${rel(i["file_path"].str(), cwd)}"
            "Read" -> "Read ${rel(i["file_path"].str(), cwd)}"
            "Glob", "Grep" -> "${c.name} ${i["pattern"].str().orEmpty()}" + (i["path"].str()?.let { " in ${rel(it, cwd)}" } ?: "")
            "TodoWrite" -> "Todos"
            "Thinking" -> "Thinking" + (i["length"].long()?.takeIf { it > 0 }?.let { " · $it characters" } ?: "")
            "Task", "Agent" -> "Subagent: ${i["description"].str() ?: i["subagent_type"].str().orEmpty()}"
            "WebFetch" -> "Fetch ${i["url"].str().orEmpty()}"
            "WebSearch" -> "Search ${i["query"].str().orEmpty()}"
            else -> if (c.name.startsWith("mcp__bench__")) "Bench: " + c.name.removePrefix("mcp__bench__").replace('_', ' ') else c.name
        }
    }

    /** "6 steps · 3 commands · 2 edits · 1 error" for a folded run. */
    fun summary(all: List<ToolCall>): String {
        val calls = all.filter { it.name != "Thinking" }
        val parts = mutableListOf(if (calls.isEmpty()) "Thought" else "${calls.size} step${if (calls.size == 1) "" else "s"}")
        val cmds = calls.count { it.name == "Bash" }
        val edits = calls.count { it.name in setOf("Edit", "MultiEdit", "Write", "NotebookEdit") }
        val failed = calls.count { it.failed }
        if (cmds > 0) parts += "$cmds command${if (cmds == 1) "" else "s"}"
        if (edits > 0) parts += "$edits edit${if (edits == 1) "" else "s"}"
        if (failed > 0) parts += "$failed error${if (failed == 1) "" else "s"}"
        return parts.joinToString(" · ")
    }
}

object Format {
    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 1 -> "${ms}ms"
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }

    fun cost(usd: Double): String = if (usd < 0.01 && usd > 0) "<$0.01" else "$" + String.format(java.util.Locale.US, "%.2f", usd)

    /** "now", "5m", "3h", "2d" since an ISO time. */
    fun ago(iso: String, now: Long = System.currentTimeMillis()): String {
        val t = runCatching { java.time.Instant.parse(iso).toEpochMilli() }.getOrNull() ?: return ""
        val m = (now - t) / 60000
        return when {
            m < 1 -> "now"
            m < 60 -> "${m}m"
            m < 60 * 24 -> "${m / 60}h"
            else -> "${m / (60 * 24)}d"
        }
    }
}

/** One block as the designs draw it: your message, or a turn folded to one line with its reply and cost. */
sealed interface Block {
    val key: String
    data class User(val row: Row.User) : Block { override val key get() = row.key }
    data class Note(val row: Row) : Block { override val key get() = row.key }
    data class Turn(override val key: String, val steps: List<Row>, val reply: String, val label: String?, val ok: Boolean, val cost: String?) : Block {
        val done get() = label != null
        val stepCount get() = steps.sumOf { r -> when (r) { is Row.Tools -> r.calls.size; is Row.Thinking -> 1; else -> 0 } }
        val current get() = steps.asReversed().firstNotNullOfOrNull { r -> (r as? Row.Tools)?.calls?.lastOrNull { it.pending } }
        /** Prose written so far in a turn still running. */
        val prose get() = if (done) emptyList() else steps.mapNotNull { (it as? Row.Text)?.takeIf { t -> !t.subagent }?.text }
    }
}

fun blocks(rows: List<Row>): List<Block> {
    val out = mutableListOf<Block>()
    val steps = mutableListOf<Row>()
    fun flush(end: Row.TurnEnd?) {
        if (steps.isEmpty() && end == null) return
        val key = steps.firstOrNull()?.key ?: end!!.key
        if (end != null) {
            val kept = if (end.reply.isEmpty()) steps.toList() else steps.filterNot { it is Row.Text && !it.subagent }
            out += Block.Turn(key, kept, end.reply, end.label, end.ok, end.cost)
        } else out += Block.Turn(key, steps.toList(), "", null, true, null)
        steps.clear()
    }
    for (r in rows) when {
        r is Row.User -> { flush(null); out += Block.User(r) }
        r is Row.TurnEnd -> flush(r)
        r is Row.System && r.tone == Tone.Bad -> { flush(null); out += Block.Note(r) }
        r is Row.Check -> { flush(null); out += Block.Note(r) }
        else -> steps += r
    }
    flush(null)
    return out
}
