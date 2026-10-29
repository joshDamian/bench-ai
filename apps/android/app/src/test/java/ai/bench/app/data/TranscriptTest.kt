package ai.bench.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscriptTest {
    private fun ev(id: Long, kind: String, json: String) =
        SessionEvent(id, "s", kind, BenchJson.parseToJsonElement(json))

    @Test fun drawsACallWithItsResultAndFoldsRuns() {
        val events = listOf(
            ev(1, "user", """{"text":"fix it","files":[{"name":"a.apk"}],"images":1}"""),
            ev(2, "assistant", """{"content":[{"type":"text","text":"Looking."},
                {"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}},
                {"type":"tool_use","id":"t2","name":"Edit","input":{"file_path":"/w/x.kt"}},
                {"type":"text","text":"Fixed."}]}"""),
            ev(3, "tool_result", """{"results":[{"tool_use_id":"t1","content":"ok"},{"tool_use_id":"t2","content":[{"type":"text","text":"bad"}],"is_error":true}]}"""),
            ev(4, "result", """{"is_error":false,"duration_ms":65000}"""),
        )
        val rows = Transcript.rows(events, "/w")
        val user = rows[0] as Row.User
        assertEquals(listOf("1 image", "a.apk"), user.attachments)
        val tools = rows.filterIsInstance<Row.Tools>().single()
        assertEquals(2, tools.calls.size)
        assertEquals("ok", tools.calls[0].result?.text)
        assertTrue(tools.calls[1].failed)
        assertEquals("2 steps · 1 command · 1 edit · 1 error", Transcript.summary(tools.calls))
        assertEquals("Edit x.kt", Transcript.headline(tools.calls[1], "/w"))
        val end = rows.last() as Row.TurnEnd
        assertEquals("Done · 1m 5s", end.label)
        assertEquals("Looking.\n\nFixed.", end.reply)
    }

    @Test fun mergeKeepsWhatArrivedDuringTheFetch() {
        val a = ev(1, "user", "{}")
        val b = ev(2, "assistant", "{}")
        val c = ev(3, "result", "{}")
        assertEquals(listOf(1L, 2L, 3L), Transcript.merge(listOf(c), listOf(a, b)).map { it.id })
        assertEquals(listOf(1L, 2L), Transcript.merge(listOf(a), listOf(a, b), suffix = true).map { it.id })
    }
}
