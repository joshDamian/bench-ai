package ai.bench.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PairingTest {
    @Test fun readsTheQrLink() {
        assertEquals(
            PairLink("https://mac.tail1234.ts.net", "AbC_d-9"),
            Pairing.parse("https://mac.tail1234.ts.net/#/pair/AbC_d-9"),
        )
    }

    @Test fun keepsAPort() {
        assertEquals(PairLink("http://100.64.1.2:4317", "xyz"), Pairing.parse("http://100.64.1.2:4317/#/pair/xyz"))
    }

    @Test fun takesASeparateCodeForABareAddress() {
        assertEquals(PairLink("https://mac.ts.net", "code1"), Pairing.parse("mac.ts.net", "code1"))
    }

    @Test fun refusesWithoutACode() {
        assertNull(Pairing.parse("https://mac.ts.net"))
        assertNull(Pairing.parse(""))
        assertNull(Pairing.parse("ftp://x/#/pair/a"))
    }

    @Test fun readsTheTokenFromTheCookie() {
        assertEquals("tok%", Pairing.tokenFromCookies(listOf("bench=tok%25; Path=/; HttpOnly")))
        assertNull(Pairing.tokenFromCookies(listOf("other=1")))
    }

    @Test fun parsesSocketFrames() {
        val m = parseServerMessage("""{"type":"delta","session_id":"s1","text":"hi","thinking":true}""")
        assertEquals(ServerMessage.Delta("s1", "hi", true), m)
        assertNull(parseServerMessage("""{"type":"unknown"}"""))
        val e = parseServerMessage("""{"type":"event","event":{"id":3,"session_id":"s","kind":"user","data":{"text":"x"},"extra":1}}""")
        assertEquals(3L, (e as ServerMessage.Event).event.id)
    }
}
