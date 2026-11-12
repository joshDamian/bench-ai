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
    /// One per session for as long as its app tab is open: switching back to a tab finds
    /// the transcript as it was, kept current in the background, rather than loading it again.
    private static var kept: [String: SessionModel] = [:]

    static func of(sessionId: String, store: BenchStore) -> SessionModel {
        if let m = kept[sessionId], m.store === store { return m }
        let m = SessionModel(sessionId: sessionId, store: store)
        kept[sessionId] = m
        return m
    }

    /// Where the thread was scrolled when its tab was left: following the end, or the first
    /// block in view. Kept here because the model outlives the view between tab switches.
    var followingEnd = true
    var scrollAnchor: String?
    @ObservationIgnored var visible: Set<String> = []
    /// Each drawn block's top, in the scroll view's coordinates, and the scroll offset.
    @ObservationIgnored var tops: [String: CGFloat] = [:]
    @ObservationIgnored var offsetY: CGFloat = 0
    /// Whether the end of the thread is in view, read from the scroll geometry - unlike the
    /// end marker's appear and disappear, it doesn't flip when the whole view goes away.
    @ObservationIgnored var atEnd = true
    @ObservationIgnored var viewport: CGFloat = 0

    /// Stops following a session whose tab closed.
    static func release(_ sessionId: String) {
        kept.removeValue(forKey: sessionId)?.close()
    }

    let sessionId: String
    let store: BenchStore

    /// Held outside observation: views draw `rows`, which every change to this rebuilds.
    @ObservationIgnored private(set) var events: [SessionEvent] = [] {
        didSet { rebuild() }
    }
    private(set) var rows: [Row] = []
    /// The transcript grouped into turns, worked out with the rows, not on every redraw.
    private(set) var blocks: [Block] = []
    var streamText = ""
    var streamThinking = ""
    var loading = true
    var hasOlder = false
    var suggestion = ""
    /// The session's slash commands, for the composer's "/" list.
    var commands: [SlashCommand] = []
    /// Files for the composer's "@" list, for the query in `pathQuery`.
    var paths: [String] = []
    private var pathQuery: String?
    private var pathTask: Task<Void, Never>?

    /// What to call each `note:name` in `paths`.
    var noteTitles: [String: String] = [:]

    func lookUpPaths(_ q: String?) {
        guard q != pathQuery else { return }
        pathQuery = q
        pathTask?.cancel()
        guard let q else { paths = []; return }
        pathTask = Task { [weak self] in
            try? await Task.sleep(for: .milliseconds(90))
            guard let self, !Task.isCancelled else { return }
            let found: (paths: [String], titles: [String: String]) = (try? await self.store.api.paths(self.sessionId, query: q)) ?? (paths: [], titles: [:])
            if !Task.isCancelled && self.pathQuery == q { self.paths = Array(found.paths.prefix(8)); self.noteTitles = found.titles }
        }
    }
    var attachments: [Attachment] = []
    var busyApprovals: Set<String> = []
    /// When each event happened, by event id: for time dividers and hover times.
    private(set) var times: [Int: Date] = [:]
    /// Edit & resend: text for the composer to take.
    var editDraft: String?
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
            if let c = d.commands, !c.isEmpty { commands = c }
            events = Transcript.merge(events, d.events, suffix: after != nil)
            if after == nil { hasOlder = d.events.count >= page }
            if d.events.contains(where: { Transcript.finishing.contains($0.kind) }) { clearStream() }
        } catch {
            self.error = error.shown
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
            self.error = error.shown
        }
    }

    /// Rows are rebuilt off the main thread: a long session is thousands of events.
    private func rebuild() {
        rebuildTask?.cancel()
        let snapshot = events
        rebuildTask = Task.detached(priority: .userInitiated) { [weak self] in
            let rows = Transcript.rows(snapshot)
            let blocks = Transcript.blocks(rows)
            let f1 = ISO8601DateFormatter(); f1.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
            let f2 = ISO8601DateFormatter()
            var times: [Int: Date] = [:]
            for e in snapshot { if let c = e.created_at, let d = f1.date(from: c) ?? f2.date(from: c) { times[e.id] = d } }
            guard !Task.isCancelled else { return }
            await MainActor.run { self?.rows = rows; self?.blocks = blocks; self?.times = times }
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

    /// When a row or block (id "e123…") happened.
    func time(of id: String) -> Date? {
        guard id.hasPrefix("e") else { return nil }
        let digits = id.dropFirst().prefix { $0.isNumber }
        guard let n = Int(String(digits)) else { return nil }
        return times[n]
    }

    /// Retry: sends again the message that started this turn.
    func retry(turn id: String) {
        guard let i = blocks.firstIndex(where: { $0.id == id }) else { return }
        for b in blocks[..<i].reversed() {
            if case .user(let r) = b, case .user(_, let text, _) = r { Task { _ = await send(text) }; return }
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
            self.error = error.shown
            return false
        }
    }

    func interrupt() { Task { try? await store.api.interrupt(sessionId) } }

    func answer(_ a: Approval, _ body: [String: Any]) {
        busyApprovals.insert(a.id)
        Task {
            do { try await store.answer(a, body) } catch {
                busyApprovals.remove(a.id)
                self.error = error.shown
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
                    update(id) { $0.error = error.shown }
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
