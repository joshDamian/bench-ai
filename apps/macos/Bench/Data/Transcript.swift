import Foundation

/// What the transcript draws, built from stored events the way web/views/session.mjs does:
/// a tool call is drawn with its result, runs of calls fold into one step, and each turn's
/// end carries the reply text for Copy.
enum Row: Identifiable, Hashable {
    case user(id: String, text: String, attachments: [String])
    case text(id: String, text: String, subagent: Bool)
    case thinking(id: String, eventId: Int, index: Int, length: Int, text: String?)
    case tools(id: String, calls: [ToolCall], subagent: Bool)
    case turnEnd(id: String, ok: Bool, label: String, reply: String, cost: String?)
    case system(id: String, text: String, tone: Tone)
    case check(id: String, ok: Bool, head: String, command: String, output: String)

    var id: String {
        switch self {
        case .user(let id, _, _), .text(let id, _, _), .thinking(let id, _, _, _, _), .tools(let id, _, _),
             .turnEnd(let id, _, _, _, _), .system(let id, _, _), .check(let id, _, _, _, _): id
        }
    }
}

enum Tone: Hashable { case muted, bad, info }

struct ToolResult: Hashable {
    let text: String
    let isError: Bool
}

struct ToolCall: Identifiable, Hashable {
    let id: String
    let name: String
    let input: JSONValue
    let result: ToolResult?
    var pending: Bool { result == nil }
    var failed: Bool { result?.isError == true }
}

enum Transcript {
    static let finishing: Set<String> = ["assistant", "result", "error"]

    /// The snapshot is the truth for the ids it covers; held events newer than it survive.
    static func merge(_ held: [SessionEvent], _ fetched: [SessionEvent], suffix: Bool = false) -> [SessionEvent] {
        guard let last = fetched.last?.id else { return held }
        if suffix {
            let have = Set(held.map(\.id))
            let add = fetched.filter { !have.contains($0.id) }
            return add.isEmpty ? held : (held + add).sorted { $0.id < $1.id }
        }
        return (fetched + held.filter { $0.id > last }).sorted { $0.id < $1.id }
    }

    static func rows(_ events: [SessionEvent]) -> [Row] {
        var results: [String: ToolResult] = [:]
        for e in events where e.kind == "tool_result" {
            for r in e.data["results"].array {
                guard let id = r["tool_use_id"].string else { continue }
                results[id] = ToolResult(text: resultText(r["content"]), isError: r["is_error"].bool)
            }
        }
        var out: [Row] = []
        var reply: [String] = []
        // Everything Claude did between two pieces of prose - tool calls and thinking,
        // which Claude Code sends as a message each - folds into one run.
        var run: [ToolCall] = []
        var runId = ""
        var runSub = false
        func flush() {
            if !run.isEmpty { out.append(.tools(id: runId, calls: run, subagent: runSub)) }
            run = []
        }
        func step(_ c: ToolCall, _ id: String, _ sub: Bool) {
            if run.isEmpty { runId = id; runSub = sub }
            run.append(c)
        }
        for e in events {
            let d = e.data
            if e.kind != "assistant" && e.kind != "tool_result" { flush() }
            switch e.kind {
            case "user":
                reply = []
                var chips: [String] = []
                if let n = d["images"].int, n > 0 { chips.append("\(n) image\(n == 1 ? "" : "s")") }
                chips += d["files"].array.compactMap { $0["name"].string }
                out.append(.user(id: "e\(e.id)", text: d["text"].string ?? "", attachments: chips))
            case "assistant":
                let sub = d["parent"].string != nil
                if let err = d["error"].string { flush(); out.append(.system(id: "e\(e.id)-err", text: "API error: \(err)", tone: .bad)) }
                for (i, b) in d["content"].array.enumerated() {
                    switch b["type"].string {
                    case "tool_use":
                        let id = b["id"].string ?? "\(i)"
                        step(ToolCall(id: id, name: b["name"].string ?? "tool", input: b["input"] ?? .object([:]), result: results[id]), "e\(e.id)-t\(i)", sub)
                    case "text":
                        let t0 = b["text"].string ?? ""
                        if !t0.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty { flush() }
                        let t = b["text"].string ?? ""
                        if !t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            out.append(.text(id: "e\(e.id)-\(i)", text: t, subagent: sub))
                            if !sub { reply.append(t.trimmingCharacters(in: .whitespacesAndNewlines)) }
                        }
                    case "thinking":
                        // A step like any other: folded with the run, its length as its result.
                        let len = b["length"].int ?? b["thinking"].string?.count ?? 0
                        step(ToolCall(id: "think-\(e.id)-\(i)", name: "Thinking", input: .object(["length": .number(Double(len)), "event": .number(Double(e.id)), "index": .number(Double(i))]), result: ToolResult(text: "", isError: false)), "e\(e.id)-t\(i)", sub)
                    default:
                        break
                    }
                }
            case "result":
                let isError = d["is_error"].bool
                var label = !isError ? "Worked" : d["subtype"].string == "error_during_execution" ? "Stopped" : "Failed" + (d["text"].string.map { ": \($0)" } ?? "")
                if let ms = d["duration_ms"].int { label += (isError ? " · " : " for ") + Format.duration(ms) }
                let cost = d["cost"].number.flatMap { $0 > 0 ? Format.cost($0) : nil }
                out.append(.turnEnd(id: "e\(e.id)", ok: !isError, label: label, reply: reply.joined(separator: "\n\n"), cost: cost))
                reply = []
            case "init":
                break // which Claude Code started is not news in the transcript
            case "notice":
                out.append(.system(id: "e\(e.id)", text: d["text"].string ?? "", tone: .muted))
            case "loop":
                out.append(.system(id: "e\(e.id)", text: d["text"].string ?? "", tone: .info))
            case "log":
                out.append(.system(id: "e\(e.id)", text: "Logged: \(d["decision"].string ?? "")", tone: .info))
            case "check":
                let ok = d["ok"].bool
                let head = ok ? "Check passed" : d["timedOut"].bool ? "Check timed out" : "Check failed (exit \(d["code"].int.map(String.init) ?? "?"))"
                let output = d["output"].string ?? ""
                out.append(.check(id: "e\(e.id)", ok: ok, head: head, command: d["command"].string ?? "", output: output.isEmpty ? "(no output)" : output))
            case "error":
                out.append(.system(id: "e\(e.id)", text: d["message"].string ?? "", tone: .bad))
            default:
                break
            }
        }
        flush()
        return out
    }

    static func resultText(_ c: JSONValue?) -> String {
        guard let c else { return "" }
        if let s = c.string { return s }
        if case .array(let a) = c { return a.map { $0["type"].string == "text" ? ($0["text"].string ?? "") : "[\($0["type"].string ?? "")]" }.joined(separator: "\n") }
        return c.pretty
    }

    static func rel(_ path: String?, cwd: String) -> String {
        let p = path ?? ""
        return !cwd.isEmpty && p.hasPrefix(cwd + "/") ? String(p.dropFirst(cwd.count + 1)) : p
    }

    static func headline(_ c: ToolCall, cwd: String) -> String {
        let i = c.input
        switch c.name {
        case "Bash": return "$ " + (i["command"].string ?? "")
        case "Edit", "MultiEdit": return "Edit " + rel(i["file_path"].string, cwd: cwd)
        case "Write": return "Write " + rel(i["file_path"].string, cwd: cwd)
        case "Read": return "Read " + rel(i["file_path"].string, cwd: cwd)
        case "Glob", "Grep": return "\(c.name) \(i["pattern"].string ?? "")" + (i["path"].string.map { " in " + rel($0, cwd: cwd) } ?? "")
        case "TodoWrite": return "Todos"
        case "Thinking": return "Thinking" + ((i["length"].int ?? 0) > 0 ? " · \(i["length"].int!) characters" : "")
        case "Task", "Agent": return "Subagent: " + (i["description"].string ?? i["subagent_type"].string ?? "")
        case "WebFetch": return "Fetch " + (i["url"].string ?? "")
        case "WebSearch": return "Search " + (i["query"].string ?? "")
        default:
            return c.name.hasPrefix("mcp__bench__") ? "Bench: " + c.name.dropFirst(12).replacingOccurrences(of: "_", with: " ") : c.name
        }
    }

    static func summary(_ all: [ToolCall]) -> String {
        let work = all.filter { $0.name != "Thinking" }
        var parts = [work.isEmpty ? "Thought" : "\(work.count) step\(work.count == 1 ? "" : "s")"]
        let calls = work
        let cmds = calls.filter { $0.name == "Bash" }.count
        let edits = calls.filter { ["Edit", "MultiEdit", "Write", "NotebookEdit"].contains($0.name) }.count
        let failed = calls.filter(\.failed).count
        if cmds > 0 { parts.append("\(cmds) command\(cmds == 1 ? "" : "s")") }
        if edits > 0 { parts.append("\(edits) edit\(edits == 1 ? "" : "s")") }
        if failed > 0 { parts.append("\(failed) error\(failed == 1 ? "" : "s")") }
        return parts.joined(separator: " · ")
    }
}

enum Format {
    static func duration(_ ms: Int) -> String {
        let s = ms / 1000
        switch s {
        case ..<1: return "\(ms)ms"
        case ..<60: return "\(s)s"
        case ..<3600: return "\(s / 60)m \(s % 60)s"
        default: return "\(s / 3600)h \((s % 3600) / 60)m"
        }
    }

    static func cost(_ usd: Double) -> String { usd > 0 && usd < 0.01 ? "<$0.01" : String(format: "$%.2f", usd) }

    private static let iso: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    static func ago(_ s: String?, now: Date = Date()) -> String {
        guard let s, let t = iso.date(from: s) ?? ISO8601DateFormatter().date(from: s) else { return "" }
        let m = Int(now.timeIntervalSince(t) / 60)
        switch m {
        case ..<1: return "now"
        case ..<60: return "\(m)m"
        case ..<(60 * 24): return "\(m / 60)h"
        default: return "\(m / (60 * 24))d"
        }
    }
}

/// One block of the conversation as the design draws it: your message, or one turn of
/// Claude's work folded to a line, its reply, and what it cost.
enum Block: Identifiable {
    case user(Row)
    case turn(id: String, steps: [Row], reply: String, label: String?, ok: Bool, cost: String?)
    case note(Row)

    var id: String {
        switch self {
        case .user(let r), .note(let r): r.id
        case .turn(let id, _, _, _, _, _): id
        }
    }
}

extension Transcript {
    static func blocks(_ rows: [Row]) -> [Block] {
        var out: [Block] = []
        var steps: [Row] = []
        func flush(_ end: Row?) {
            guard !steps.isEmpty || end != nil else { return }
            let id = steps.first?.id ?? end!.id
            if case .turnEnd(_, let ok, let label, let reply, let cost) = end {
                // The reply is shown below the fold; the prose it is made of stays out of the steps.
                let kept = reply.isEmpty ? steps : steps.filter { if case .text(_, _, false) = $0 { false } else { true } }
                out.append(.turn(id: id, steps: kept, reply: reply, label: label, ok: ok, cost: cost))
            } else {
                out.append(.turn(id: id, steps: steps, reply: "", label: nil, ok: true, cost: nil))
            }
            steps = []
        }
        for r in rows {
            switch r {
            case .user: flush(nil); out.append(.user(r))
            case .turnEnd: flush(r)
            case .system(_, _, let tone) where tone == .bad: flush(nil); out.append(.note(r))
            case .check: flush(nil); out.append(.note(r))
            default: steps.append(r)
            }
        }
        flush(nil)
        return out
    }

    /// "14 steps": every tool call and thought in a turn.
    static func stepCount(_ rows: [Row]) -> Int {
        rows.reduce(0) { n, r in
            switch r {
            case .tools(_, let c, _): n + c.count
            case .thinking: n + 1
            default: n
            }
        }
    }
}
