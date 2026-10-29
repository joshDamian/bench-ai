import Foundation
import Observation

struct Attachment: Identifiable, Hashable {
    let id = UUID()
    let name: String
    var progress: Double = 0
    var ref: UploadRef?
    var error: String?
}

/// One open session: its transcript, the text streaming in, and what is being attached.
@MainActor
@Observable
final class SessionModel {
    let sessionId: String
    let store: BenchStore

    private(set) var events: [SessionEvent] = [] { didSet { rebuild() } }
    private(set) var rows: [Row] = []
    var streamText = ""
    var streamThinking = ""
    var loading = true
    var hasOlder = false
    var suggestion = ""
    var attachments: [Attachment] = []
    var busyApprovals: Set<String> = []
    var thinking: [String: String] = [:]
    var error: String?

    @ObservationIgnored private var key: UUID?
    @ObservationIgnored private var pendingText = ""
    @ObservationIgnored private var pendingThinking = ""
    @ObservationIgnored private var flushScheduled = false
    @ObservationIgnored private var rebuildTask: Task<Void, Never>?

    /// The server's page size for a transcript read (src/store.mjs listEvents).
    private let page = 400

    init(sessionId: String, store: BenchStore) {
        self.sessionId = sessionId
        self.store = store
    }

    var session: Session? { store.session(sessionId) }
    var approvals: [Approval] { store.approvals.filter { $0.session_id == sessionId } }

    func open() {
        guard key == nil else { return }
        key = store.watch(sessionId, { [weak self] m in self?.handle(m) }, onReconnect: { [weak self] in
            guard let self else { return }
            Task { await self.load(after: self.events.last?.id) }
        })
        Task { await load(after: nil) }
    }

    func close() {
        if let key { store.unwatch(sessionId, key: key) }
        key = nil
    }

    private func load(after: Int?) async {
        do {
            let d = try await store.api.session(sessionId, after: after)
            store.apply(d.session)
            events = Transcript.merge(events, d.events, suffix: after != nil)
            if after == nil { hasOlder = d.events.count >= page }
            if d.events.contains(where: { Transcript.finishing.contains($0.kind) }) { clearStream() }
        } catch {
            self.error = error.localizedDescription
        }
        loading = false
    }

    func loadOlder() async {
        guard let first = events.first?.id else { return }
        do {
            let older = try await store.api.olderEvents(sessionId, before: first)
            hasOlder = older.count >= page
            var seen = Set<Int>()
            events = (older + events).filter { seen.insert($0.id).inserted }.sorted { $0.id < $1.id }
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Rows are rebuilt off the main thread: a long session is thousands of events.
    private func rebuild() {
        rebuildTask?.cancel()
        let snapshot = events
        rebuildTask = Task.detached(priority: .userInitiated) { [weak self] in
            let rows = Transcript.rows(snapshot)
            guard !Task.isCancelled else { return }
            await MainActor.run { self?.rows = rows }
        }
    }

    private func handle(_ m: ServerMessage) {
        switch m {
        case .event(let e) where e.session_id == sessionId:
            if e.kind == "user" || e.kind == "assistant" { suggestion = "" }
            if !events.contains(where: { $0.id == e.id }) { events.append(e) }
            if Transcript.finishing.contains(e.kind) { clearStream() }
        case .delta(let id, let text, let isThinking) where id == sessionId:
            if isThinking { pendingThinking += text } else { pendingText += text }
            // Deltas arrive per token; they are drawn at most every 50ms.
            guard !flushScheduled else { return }
            flushScheduled = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 0.05) { [weak self] in
                Task { @MainActor in self?.flush() }
            }
        case .suggestion(let id, let text) where id == sessionId:
            suggestion = text
        case .approvalDone(let id, _):
            busyApprovals.remove(id)
        default:
            break
        }
    }

    private func flush() {
        flushScheduled = false
        streamText += pendingText
        streamThinking += pendingThinking
        pendingText = ""
        pendingThinking = ""
    }

    private func clearStream() {
        pendingText = ""
        pendingThinking = ""
        streamText = ""
        streamThinking = ""
    }

    func openThinking(id: String, eventId: Int, index: Int) {
        guard thinking[id] == nil else { return }
        thinking[id] = ""
        Task {
            let blocks = try? await store.api.thinking(sessionId, event: eventId)
            thinking[id] = blocks?[index] ?? "That reasoning could not be loaded."
        }
    }

    // MARK: actions

    func send(_ text: String) async -> Bool {
        if attachments.contains(where: { $0.ref == nil && $0.error == nil }) {
            error = "Wait for the upload to finish"
            return false
        }
        let refs = attachments.compactMap(\.ref)
        guard !text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || !refs.isEmpty else { return false }
        do {
            try await store.api.send(sessionId, text: text, uploads: refs)
            attachments = []
            return true
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }

    func interrupt() { Task { try? await store.api.interrupt(sessionId) } }

    func answer(_ a: Approval, _ body: [String: Any]) {
        busyApprovals.insert(a.id)
        Task {
            do { try await store.answer(a, body) } catch {
                busyApprovals.remove(a.id)
                self.error = error.localizedDescription
            }
        }
    }

    func attach(_ urls: [URL]) {
        for url in urls {
            let a = Attachment(name: url.lastPathComponent)
            attachments.append(a)
            let id = a.id
            let api = store.api
            let sid = sessionId
            Task {
                let scoped = url.startAccessingSecurityScopedResource()
                defer { if scoped { url.stopAccessingSecurityScopedResource() } }
                do {
                    let ref = try await api.upload(sid, file: url) { p in
                        Task { @MainActor [weak self] in self?.update(id) { $0.progress = p } }
                    }
                    update(id) { $0.ref = ref; $0.progress = 1 }
                } catch {
                    update(id) { $0.error = error.localizedDescription }
                }
            }
        }
    }

    func remove(_ id: UUID) { attachments.removeAll { $0.id == id } }

    private func update(_ id: UUID, _ change: (inout Attachment) -> Void) {
        guard let i = attachments.firstIndex(where: { $0.id == id }) else { return }
        change(&attachments[i])
    }
}
