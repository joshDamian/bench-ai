import AppKit
import Foundation
import Observation

/// The lists the app is drawn from, kept current by one socket, as web/api.mjs's store is.
@MainActor
@Observable
final class BenchStore {
    let api: BenchAPI

    var ready = false
    var connected = false
    /// The socket was open and has gone, as opposed to still being dialled.
    var dropped = false
    var unauthorized = false
    var error: String?
    var projects: [Project] = []
    var tasks: [BenchTask] = []
    var sessions: [Session] = []
    var approvals: [Approval] = []
    var defaults = Defaults()
    var providers: [ProviderInfo] = []
    var me: Me?
    var version: String?

    /// Session-scoped traffic, for whichever session views are open.
    @ObservationIgnored private var listeners: [UUID: (ServerMessage) -> Void] = [:]
    @ObservationIgnored private var reconnectListeners: [UUID: () -> Void] = [:]
    @ObservationIgnored var onNewApproval: ((Approval) -> Void)?
    @ObservationIgnored private var watched: [String: Int] = [:]
    @ObservationIgnored private var socket: URLSessionWebSocketTask?
    @ObservationIgnored private var retry = 0
    @ObservationIgnored private var started = false
    @ObservationIgnored private var wakeObserver: Any?
    @ObservationIgnored private var pingTimer: Timer?

    init(config: ServerConfig) {
        api = BenchAPI(config: config)
    }

    var needsYouCount: Int { approvals.count }
    /// "Opus · high effort": the session's model and effort, in words.
    func modelLine(_ s: Session) -> String {
        let m = providers.flatMap(\.models).first { $0.id == s.model_id }
        let p = providers.first { $0.id == s.provider_id }
        let name = m?.label ?? (p.map { $0.name } ?? "Default model")
        guard let e = s.effort, let eff = Effort(rawValue: e) else { return name }
        return "\(name) · \(eff.label.lowercased()) effort"
    }
    func session(_ id: String) -> Session? { sessions.first { $0.id == id } }
    func project(_ id: String?) -> Project? { projects.first { $0.id == id } }
    func task(_ id: String?) -> BenchTask? { tasks.first { $0.id == id } }

    func start() {
        guard !started else { return }
        started = true
        Task { await refresh() }
        connect()
        // A Mac that slept has a socket that only looks open.
        wakeObserver = NSWorkspace.shared.notificationCenter.addObserver(forName: NSWorkspace.didWakeNotification, object: nil, queue: .main) { [weak self] _ in
            Task { @MainActor in self?.reconnectNow() }
        }
        pingTimer = Timer.scheduledTimer(withTimeInterval: 20, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.checkAlive() }
        }
    }

    func stop() {
        started = false
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        pingTimer?.invalidate()
        if let o = wakeObserver { NSWorkspace.shared.notificationCenter.removeObserver(o) }
    }

    func refresh() async {
        do {
            let s = try await api.state()
            projects = s.projects
            tasks = s.tasks
            sessions = s.sessions
            approvals = s.approvals
            defaults = s.defaults ?? Defaults()
            me = s.me
            version = s.version
            if let p = try? await api.providers() { providers = p }
            ready = true
            unauthorized = false
            error = nil
        } catch let e as BenchError {
            ready = true
            unauthorized = e.unauthorized
            error = e.unauthorized ? nil : e.message
        } catch {
            ready = true
            self.error = error.localizedDescription
        }
    }

    // MARK: the socket

    private func connect() {
        guard started else { return }
        var r = api.request("/api/ws")
        r.url = api.socketURL
        let ws = api.session.webSocketTask(with: r)
        socket = ws
        ws.resume()
        receive(ws)
        // URLSession reports an open socket by the first frame succeeding; the watch list
        // is that frame, and the server answers a fresh socket with `hello`.
        sendWatch()
    }

    private func receive(_ ws: URLSessionWebSocketTask) {
        ws.receive { [weak self] result in
            Task { @MainActor in
                guard let self, self.socket === ws else { return }
                switch result {
                case .success(let msg):
                    if !self.connected { self.opened() }
                    switch msg {
                    case .string(let s): if let m = ServerMessage.parse(Data(s.utf8)) { self.handle(m) }
                    case .data(let d): if let m = ServerMessage.parse(d) { self.handle(m) }
                    @unknown default: break
                    }
                    self.receive(ws)
                case .failure:
                    self.droppedSocket(ws)
                }
            }
        }
    }

    private func opened() {
        retry = 0
        connected = true
        dropped = false
        Task {
            await refresh()
            reconnectListeners.values.forEach { $0() }
        }
    }

    private func droppedSocket(_ ws: URLSessionWebSocketTask) {
        guard socket === ws else { return }
        socket = nil
        connected = false
        dropped = true
        if let http = ws.response as? HTTPURLResponse, http.statusCode == 401 { unauthorized = true; return }
        guard started else { return }
        retry = min(retry + 1, 5)
        let delay = 0.5 * pow(2, Double(retry))
        DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
            Task { @MainActor in if self?.socket == nil { self?.connect() } }
        }
    }

    /// Asks the socket to prove it is alive, and replaces it if it does not.
    private func checkAlive() {
        guard let ws = socket else { return reconnectNow() }
        ws.sendPing { [weak self] err in
            if err != nil { Task { @MainActor in self?.replace() } }
        }
    }

    func reconnectNow() {
        guard started else { return }
        if socket == nil { retry = 0; connect() } else { checkAlive() }
    }

    private func replace() {
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        retry = 0
        connect()
    }

    private func sendWatch() {
        let body: [String: Any] = ["type": "watch", "ids": Array(watched.keys)]
        guard let d = try? JSONSerialization.data(withJSONObject: body), let s = String(data: d, encoding: .utf8) else { return }
        socket?.send(.string(s)) { [weak self] err in
            if err == nil { Task { @MainActor in if self?.connected == false { self?.opened() } } }
        }
    }

    /// The server sends a session's transcript traffic only while it is watched.
    func watch(_ sessionId: String, _ fn: @escaping (ServerMessage) -> Void, onReconnect: @escaping () -> Void) -> UUID {
        let key = UUID()
        listeners[key] = fn
        reconnectListeners[key] = onReconnect
        watched[sessionId, default: 0] += 1
        sendWatch()
        return key
    }

    func unwatch(_ sessionId: String, key: UUID) {
        listeners[key] = nil
        reconnectListeners[key] = nil
        if let n = watched[sessionId] { watched[sessionId] = n <= 1 ? nil : n - 1 }
        sendWatch()
    }

    private func handle(_ m: ServerMessage) {
        switch m {
        case .hello(let s, let a):
            sessions = s
            approvals = a
        case .session(let s):
            if let i = sessions.firstIndex(where: { $0.id == s.id }) { sessions[i] = s } else { sessions.insert(s, at: 0) }
        case .sessionRemoved(let id):
            sessions.removeAll { $0.id == id }
        case .task(let t):
            if let i = tasks.firstIndex(where: { $0.id == t.id }) { tasks[i] = t } else { tasks.insert(t, at: 0) }
        case .approval(let a):
            let isNew = !approvals.contains { $0.id == a.id }
            approvals.removeAll { $0.id == a.id }
            approvals.append(a)
            if isNew { onNewApproval?(a) }
        case .approvalDone(let id, _):
            approvals.removeAll { $0.id == id }
            Notifications.clear(approvalId: id)
            listeners.values.forEach { $0(m) }
        case .event, .delta, .suggestion:
            listeners.values.forEach { $0(m) }
        case .pong:
            break
        }
    }

    // MARK: writes

    func apply(_ s: Session) {
        if let i = sessions.firstIndex(where: { $0.id == s.id }) { sessions[i] = s } else { sessions.insert(s, at: 0) }
    }

    func answer(_ a: Approval, _ body: [String: Any]) async throws {
        try await api.answer(a.id, body)
        approvals.removeAll { $0.id == a.id }
    }
}
