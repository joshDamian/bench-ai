import XCTest
@testable import Bench

final class PairingTests: XCTestCase {
    func testReadsTheQrLink() {
        XCTAssertEqual(Pairing.parse("https://mac.tail1234.ts.net/#/pair/AbC_d-9"), PairLink(baseURL: "https://mac.tail1234.ts.net", code: "AbC_d-9"))
        XCTAssertEqual(Pairing.parse("http://100.64.1.2:4317/#/pair/xyz"), PairLink(baseURL: "http://100.64.1.2:4317", code: "xyz"))
    }

    func testRefusesWithoutACode() {
        XCTAssertNil(Pairing.parse("https://mac.ts.net"))
        XCTAssertNil(Pairing.parse(""))
    }

    func testReadsTheTokenFromTheCookie() {
        XCTAssertEqual(Pairing.token(fromCookie: "bench=tok%25; Path=/; HttpOnly"), "tok%")
        XCTAssertNil(Pairing.token(fromCookie: "other=1"))
    }

    func testLoopback() {
        XCTAssertTrue(Pairing.isLoopback("http://localhost:4317"))
        XCTAssertFalse(Pairing.isLoopback("https://mac.ts.net"))
    }
}

final class TranscriptTests: XCTestCase {
    private func ev(_ id: Int, _ kind: String, _ json: String) -> SessionEvent {
        SessionEvent(id: id, session_id: "s", kind: kind, data: try! JSONDecoder().decode(JSONValue.self, from: Data(json.utf8)), created_at: nil)
    }

    func testDrawsACallWithItsResultAndFoldsRuns() {
        let rows = Transcript.rows([
            ev(1, "user", #"{"text":"fix it","files":[{"name":"a.apk"}],"images":1}"#),
            ev(2, "assistant", #"{"content":[{"type":"text","text":"Looking."},{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"ls"}},{"type":"tool_use","id":"t2","name":"Edit","input":{"file_path":"/w/x.swift"}},{"type":"text","text":"Fixed."}]}"#),
            ev(3, "tool_result", #"{"results":[{"tool_use_id":"t1","content":"ok"},{"tool_use_id":"t2","content":[{"type":"text","text":"bad"}],"is_error":true}]}"#),
            ev(4, "result", #"{"is_error":false,"duration_ms":65000}"#),
        ])
        guard case .user(_, _, let chips) = rows[0] else { return XCTFail() }
        XCTAssertEqual(chips, ["1 image", "a.apk"])
        let calls = rows.compactMap { if case .tools(_, let c, _) = $0 { c } else { nil } }.first!
        XCTAssertEqual(calls.count, 2)
        XCTAssertEqual(calls[0].result?.text, "ok")
        XCTAssertTrue(calls[1].failed)
        XCTAssertEqual(Transcript.summary(calls), "2 steps · 1 command · 1 edit · 1 error")
        XCTAssertEqual(Transcript.headline(calls[1], cwd: "/w"), "Edit x.swift")
        guard case .turnEnd(_, _, let label, let reply) = rows.last! else { return XCTFail() }
        XCTAssertEqual(label, "Done · 1m 5s")
        XCTAssertEqual(reply, "Looking.\n\nFixed.")
    }

    func testMergeKeepsWhatArrivedDuringTheFetch() {
        let a = ev(1, "user", "{}"), b = ev(2, "assistant", "{}"), c = ev(3, "result", "{}")
        XCTAssertEqual(Transcript.merge([c], [a, b]).map(\.id), [1, 2, 3])
        XCTAssertEqual(Transcript.merge([a], [a, b], suffix: true).map(\.id), [1, 2])
    }

    func testParsesSocketFrames() {
        guard case .delta(let id, let text, let thinking)? = ServerMessage.parse(Data(#"{"type":"delta","session_id":"s1","text":"hi","thinking":true}"#.utf8)) else { return XCTFail() }
        XCTAssertEqual(id, "s1"); XCTAssertEqual(text, "hi"); XCTAssertTrue(thinking)
        XCTAssertNil(ServerMessage.parse(Data(#"{"type":"nope"}"#.utf8)))
    }
}
