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
    data class TurnEnd(override val key: String, val ok: Boolean, val label: String, val reply: String) : Row
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
        for (e in events) {
            val d = e.data
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
                    d["error"].str()?.let { out += Row.System("e${e.id}-err", "API error: $it", Tone.Bad) }
                    val run = ArrayList<ToolCall>()
                    fun flush(i: Int) {
                        if (run.isNotEmpty()) out += Row.Tools("e${e.id}-t$i", run.toList(), sub)
                        run.clear()
                    }
                    d["content"].arr().orEmpty().forEachIndexed { i, b ->
                        when (b["type"].str()) {
                            "tool_use" -> {
                                val id = b["id"].str() ?: "$i"
                                run += ToolCall(id, b["name"].str() ?: "tool", b["input"].obj() ?: JsonObject(emptyMap()), results[id])
                            }
                            "text" -> {
                                flush(i)
                                val t = b["text"].str().orEmpty()
                                if (t.isNotBlank()) {
                                    out += Row.Text("e${e.id}-$i", t, sub)
                                    if (!sub) reply.append(if (reply.isEmpty()) "" else "\n\n").append(t.trim())
                                }
                            }
                            "thinking" -> {
                                flush(i)
                                val truncated = b["truncated"].bool()
                                val text = if (truncated) null else b["thinking"].str()
                                val len = b["length"].long()?.toInt() ?: text?.length ?: 0
                                out += Row.Thinking("e${e.id}-$i", e.id, i, len, text)
                            }
                            else -> flush(i)
                        }
                    }
                    flush(Int.MAX_VALUE)
                }
                "result" -> {
                    val isError = d["is_error"].bool()
                    val label = buildString {
                        append(
                            when {
                                !isError -> "Done"
                                d["subtype"].str() == "error_during_execution" -> "Stopped"
                                else -> "Failed" + (d["text"].str()?.let { ": $it" } ?: "")
                            },
                        )
                        d["duration_ms"].long()?.let { append(" · ").append(Format.duration(it)) }
                    }
                    out += Row.TurnEnd("e${e.id}", !isError, label, reply.toString())
                    reply.clear()
                }
                "init" -> out += Row.System(
                    "e${e.id}",
                    listOfNotNull("Claude Code ${d["version"].str().orEmpty()}".trim(), d["model"].str(), d["permissionMode"].str()?.let { Mode.of(it).label })
                        .joinToString(" · "),
                )
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
            "Task", "Agent" -> "Subagent: ${i["description"].str() ?: i["subagent_type"].str().orEmpty()}"
            "WebFetch" -> "Fetch ${i["url"].str().orEmpty()}"
            "WebSearch" -> "Search ${i["query"].str().orEmpty()}"
            else -> if (c.name.startsWith("mcp__bench__")) "Bench: " + c.name.removePrefix("mcp__bench__").replace('_', ' ') else c.name
        }
    }

    /** "6 steps · 3 commands · 2 edits · 1 error" for a folded run. */
    fun summary(calls: List<ToolCall>): String {
        val parts = mutableListOf("${calls.size} steps")
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
