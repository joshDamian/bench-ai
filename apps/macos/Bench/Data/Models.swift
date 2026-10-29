import Foundation

// The wire contract, mirrored from src/contract.d.mts. Only the fields this client reads
// are declared; unknown keys are ignored, so a server that grows a field never breaks the app.

struct Project: Codable, Identifiable, Hashable {
    let id: String
    var name: String
    var path: String?
}

struct BenchTask: Codable, Identifiable, Hashable {
    let id: String
    let project_id: String?
    var title: String
    var branch: String?
    var base_branch: String?
    var state: String
    var check_status: String?
}

struct Session: Codable, Identifiable, Hashable {
    let id: String
    /// Nil for a session outside any project: the server's "ask" sessions.
    let project_id: String?
    var task_id: String?
    var title: String
    var cwd: String?
    var permission_mode: String?
    var claude_session_id: String?
    var status: String
    var error: String?
    var cost: Double?
    var created_at: String?
    var updated_at: String?
    var archived_at: String?

    var mode: Mode { Mode(rawValue: permission_mode ?? "default") ?? .default }
}

struct SessionEvent: Codable, Identifiable, Hashable {
    let id: Int
    let session_id: String
    let kind: String
    let data: JSONValue?
    let created_at: String?
}

struct Approval: Codable, Identifiable, Hashable {
    let id: String
    let session_id: String
    let tool: String
    let input: JSONValue?
    let title: String?
    let reason: String?
}

struct SlashCommand: Codable, Hashable {
    let name: String
    let description: String?
}

struct Me: Codable, Hashable {
    let local: Bool?
    let device: Device?
}

struct Device: Codable, Hashable {
    let id: String
    let name: String
}

struct Defaults: Codable, Hashable {
    var providerId: String?
    var mode: String?
}

/// Read list by list and item by item: one record this client cannot read (a newer
/// server's shape) is skipped, not the reason nothing shows.
struct StateSnapshot: Decodable {
    let version: String?
    let projects: [Project]
    let tasks: [BenchTask]
    let sessions: [Session]
    let approvals: [Approval]
    let defaults: Defaults?
    let me: Me?

    enum CodingKeys: String, CodingKey { case version, projects, tasks, sessions, approvals, defaults, me }

    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        version = try? c.decodeIfPresent(String.self, forKey: .version)
        projects = (try? c.decodeIfPresent(Lossy<Project>.self, forKey: .projects))?.items ?? []
        tasks = (try? c.decodeIfPresent(Lossy<BenchTask>.self, forKey: .tasks))?.items ?? []
        sessions = (try? c.decodeIfPresent(Lossy<Session>.self, forKey: .sessions))?.items ?? []
        approvals = (try? c.decodeIfPresent(Lossy<Approval>.self, forKey: .approvals))?.items ?? []
        defaults = try? c.decodeIfPresent(Defaults.self, forKey: .defaults)
        me = try? c.decodeIfPresent(Me.self, forKey: .me)
    }
}

/// An array whose unreadable elements are dropped instead of failing the whole array.
struct Lossy<T: Decodable>: Decodable {
    let items: [T]
    init(from decoder: Decoder) throws {
        var c = try decoder.unkeyedContainer()
        var out: [T] = []
        while !c.isAtEnd {
            if let v = try? c.decode(T.self) { out.append(v) } else { _ = try? c.decode(JSONValue.self) }
        }
        items = out
    }
}

struct SessionDetail: Codable {
    let session: Session
    let events: [SessionEvent]
    let approvals: [Approval]?
    let commands: [SlashCommand]?
}

struct TaskCreated: Codable {
    let task: BenchTask
    let session: Session
}

/// Permission modes, in the order the web picker shows them.
enum Mode: String, CaseIterable, Identifiable {
    case `default`, acceptEdits, plan, auto, bypassPermissions
    var id: String { rawValue }
    var label: String {
        switch self {
        case .default: "Ask before edits"
        case .acceptEdits: "Accept edits"
        case .plan: "Plan"
        case .auto: "Auto"
        case .bypassPermissions: "Bypass"
        }
    }
}

/// What arrives over /api/ws.
enum ServerMessage {
    case hello(sessions: [Session], approvals: [Approval])
    case session(Session)
    case sessionRemoved(String)
    case task(BenchTask)
    case event(SessionEvent)
    case delta(sessionId: String, text: String, thinking: Bool)
    case approval(Approval)
    case approvalDone(id: String, sessionId: String)
    case suggestion(sessionId: String, text: String)
    case pong

    static func parse(_ data: Data) -> ServerMessage? {
        let dec = JSONDecoder()
        guard let raw = try? dec.decode(JSONValue.self, from: data), let type = raw["type"].string else { return nil }
        func field<T: Decodable>(_ key: String, _: T.Type) -> T? {
            guard let v = raw[key], let d = try? JSONEncoder().encode(v) else { return nil }
            return try? dec.decode(T.self, from: d)
        }
        switch type {
        case "hello": return .hello(sessions: field("sessions", Lossy<Session>.self)?.items ?? [], approvals: field("approvals", Lossy<Approval>.self)?.items ?? [])
        case "session": return field("session", Session.self).map(ServerMessage.session)
        case "session-removed": return raw["id"].string.map(ServerMessage.sessionRemoved)
        case "task": return field("task", BenchTask.self).map(ServerMessage.task)
        case "event": return field("event", SessionEvent.self).map(ServerMessage.event)
        case "delta":
            guard let id = raw["session_id"].string else { return nil }
            return .delta(sessionId: id, text: raw["text"].string ?? "", thinking: raw["thinking"].bool)
        case "approval": return field("approval", Approval.self).map(ServerMessage.approval)
        case "approval-done":
            guard let id = raw["id"].string else { return nil }
            return .approvalDone(id: id, sessionId: raw["session_id"].string ?? "")
        case "suggestion":
            guard let id = raw["session_id"].string else { return nil }
            return .suggestion(sessionId: id, text: raw["text"].string ?? "")
        case "pong": return .pong
        default: return nil
        }
    }
}
