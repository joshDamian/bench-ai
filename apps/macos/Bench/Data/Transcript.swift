import Foundation

/// What the transcript draws, built from stored events the way web/views/session.mjs does:
/// a tool call is drawn with its result, runs of calls fold into one step, and each turn's
/// end carries the reply text for Copy.
enum Row: Identifiable, Hashable {
    case user(id: String, text: String, attachments: [String])
    case text(id: String, text: String, subagent: Bool)
    case thinking(id: String, eventId: Int, index: Int, length: Int, text: String?)
    case tools(id: String, calls: [ToolCall], subagent: Bool)
    case turnEnd(id: String, ok: Bool, label: String, reply: String)
    case system(id: String, text: String, tone: Tone)
    case check(id: String, ok: Bool, head: String, command: String, output: String)

    var id: String {
        switch self {
        case .user(let id, _, _), .text(let id, _, _), .thinking(let id, _, _, _, _), .tools(let id, _, _),
             .turnEnd(let id, _, _, _), .system(let id, _, _), .check(let id, _, _, _, _): id
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
        for e in events {
            let d = e.data
            switch e.kind {
            case "user":
                reply = []
                var chips: [String] = []
                if let n = d["images"].int, n > 0 { chips.append("\(n) image\(n == 1 ? "" : "s")") }
                chips += d["files"].array.compactMap { $0["name"].string }
                out.append(.user(id: "e\(e.id)", text: d["text"].string ?? "", attachments: chips))
            case "assistant":
                let sub = d["parent"].string != nil
                if let err = d["error"].string { out.append(.system(id: "e\(e.id)-err", text: "API error: \(err)", tone: .bad)) }
                var run: [ToolCall] = []
                func flush(_ i: Int) {
                    if !run.isEmpty { out.append(.tools(id: "e\(e.id)-t\(i)", calls: run, subagent: sub)) }
                    run = []
                }
                for (i, b) in d["content"].array.enumerated() {
                    switch b["type"].string {
                    case "tool_use":
                        let id = b["id"].string ?? "\(i)"
                        run.append(ToolCall(id: id, name: b["name"].string ?? "tool", input: b["input"] ?? .object([:]), result: results[id]))
                    case "text":
                        flush(i)
                        let t = b["text"].string ?? ""
                        if !t.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                            out.append(.text(id: "e\(e.id)-\(i)", text: t, subagent: sub))
                            if !sub { reply.append(t.trimmingCharacters(in: .whitespacesAndNewlines)) }
                        }
                    case "thinking":
                        flush(i)
                        let text = b["truncated"].bool ? nil : b["thinking"].string
                        out.append(.thinking(id: "e\(e.id)-\(i)", eventId: e.id, index: i, length: b["length"].int ?? text?.count ?? 0, text: text))
                    default:
                        flush(i)
                    }
                }
                flush(Int.max)
            case "result":
                let isError = d["is_error"].bool
                var label = !isError ? "Done" : d["subtype"].string == "error_during_execution" ? "Stopped" : "Failed" + (d["text"].string.map { ": \($0)" } ?? "")
                if let ms = d["duration_ms"].int { label += " · " + Format.duration(ms) }
                out.append(.turnEnd(id: "e\(e.id)", ok: !isError, label: label, reply: reply.joined(separator: "\n\n")))
                reply = []
            case "init":
                let parts = ["Claude Code \(d["version"].string ?? "")".trimmingCharacters(in: .whitespaces), d["model"].string, d["permissionMode"].string.flatMap { Mode(rawValue: $0)?.label }]
                out.append(.system(id: "e\(e.id)", text: parts.compactMap { $0 }.joined(separator: " · "), tone: .muted))
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
        case "Task", "Agent": return "Subagent: " + (i["description"].string ?? i["subagent_type"].string ?? "")
        case "WebFetch": return "Fetch " + (i["url"].string ?? "")
        case "WebSearch": return "Search " + (i["query"].string ?? "")
        default:
            return c.name.hasPrefix("mcp__bench__") ? "Bench: " + c.name.dropFirst(12).replacingOccurrences(of: "_", with: " ") : c.name
        }
    }

    static func summary(_ calls: [ToolCall]) -> String {
        var parts = ["\(calls.count) steps"]
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
