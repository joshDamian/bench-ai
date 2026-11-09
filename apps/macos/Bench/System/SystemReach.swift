import AppIntents
import AppKit
import Carbon.HIToolbox
import CoreSpotlight
import SwiftUI
import UniformTypeIdentifiers
import WidgetKit

// MARK: - Glance: what the widget, Spotlight and the Dock show

/// What needs you and what is working, written where the widget can read it.
struct GlanceSnapshot: Codable {
    struct Item: Codable { let id: String; let title: String; let project: String; let since: Date? }
    let needs: Int
    let needsTitles: [String]
    let working: [Item]
    let updated: Date
}

enum SystemReach {
    static let appGroup = "group.ai.bench.app"
    private static var pending: DispatchWorkItem?

    static var snapshotURL: URL? {
        FileManager.default.containerURL(forSecurityApplicationGroupIdentifier: appGroup)?.appendingPathComponent("glance.json")
    }

    /// Debounced: a burst of socket updates is one write, one widget reload, one index pass.
    @MainActor static func publish(_ store: BenchStore) {
        pending?.cancel()
        let work = DispatchWorkItem { Task { @MainActor in write(store) } }
        pending = work
        DispatchQueue.main.asyncAfter(deadline: .now() + 1, execute: work)
    }

    @MainActor private static func write(_ store: BenchStore) {
        let working = store.sessions.filter { $0.status == "running" && $0.archived_at == nil }.map {
            GlanceSnapshot.Item(id: $0.id, title: $0.title, project: store.project($0.project_id)?.name ?? "Ask Bench", since: store.runningSince[$0.id])
        }
        let snap = GlanceSnapshot(needs: store.approvals.count,
                                  needsTitles: store.approvals.prefix(3).map { store.session($0.session_id)?.title ?? "Session" },
                                  working: working, updated: Date())
        if let url = snapshotURL, let data = try? JSONEncoder().encode(snap) {
            try? FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
            try? data.write(to: url, options: .atomic)
            WidgetCenter.shared.reloadAllTimelines()
        }
        Notifications.setBadge(store.approvals.count)
        index(store)
    }

    /// Sessions in Spotlight: type part of a title, Return opens it in Bench.
    @MainActor private static func index(_ store: BenchStore) {
        let items: [CSSearchableItem] = store.sessions.filter { $0.archived_at == nil }.prefix(200).map { s in
            let a = CSSearchableItemAttributeSet(contentType: .text)
            a.title = s.title
            a.contentDescription = [store.project(s.project_id)?.name ?? "Ask Bench", s.status == "running" ? "Working" : nil].compactMap { $0 }.joined(separator: " · ")
            a.keywords = ["bench", "claude", store.project(s.project_id)?.name ?? "ask"]
            return CSSearchableItem(uniqueIdentifier: s.id, domainIdentifier: "sessions", attributeSet: a)
        }
        let index = CSSearchableIndex.default()
        index.deleteSearchableItems(withDomainIdentifiers: ["sessions"]) { _ in index.indexSearchableItems(items) }
    }
}

// MARK: - Shortcuts, Siri and the Focus filter

/// The store for the paired server, for intents that run while the window is closed.
@MainActor
private func intentStore() throws -> BenchStore {
    if let s = AppModel.shared?.store { return s }
    throw IntentError.notPaired
}

enum IntentError: Error, CustomLocalizedStringResourceConvertible {
    case notPaired, noProject(String), noSession
    var localizedStringResource: LocalizedStringResource {
        switch self {
        case .notPaired: "Bench isn't paired with a server yet."
        case .noProject(let p): "There's no project called \(p)."
        case .noSession: "There's no session to send that to."
        }
    }
}

struct StartSessionIntent: AppIntent {
    static var title: LocalizedStringResource = "Start a Bench Session"
    static var description = IntentDescription("Starts a session in a project and sends Claude your message.")
    @Parameter(title: "Message") var message: String
    @Parameter(title: "Project", description: "A project's name; leave empty to ask Bench") var project: String?
    @Parameter(title: "Open Bench", default: false) var open: Bool

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let store = try intentStore()
        var projectId: String?
        if let name = project, !name.isEmpty {
            guard let p = store.projects.first(where: { $0.name.localizedCaseInsensitiveCompare(name) == .orderedSame }) else { throw IntentError.noProject(name) }
            projectId = p.id
        }
        let s = try await store.api.createSession(project: projectId, task: nil, mode: .default)
        try await store.api.send(s.id, text: message)
        if open { AppModel.shared?.selection = s.id; NSApp.activate(ignoringOtherApps: true) }
        return .result(dialog: "Started \(s.title).")
    }
}

struct SendToSessionIntent: AppIntent {
    static var title: LocalizedStringResource = "Send to a Bench Session"
    static var description = IntentDescription("Sends a message to a session, or to the one you used last.")
    @Parameter(title: "Message") var message: String
    @Parameter(title: "Session", description: "Part of a session's title; empty means the latest") var session: String?

    @MainActor
    func perform() async throws -> some IntentResult & ProvidesDialog {
        let store = try intentStore()
        let open = store.sessions.filter { $0.archived_at == nil }.sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
        let target = session.flatMap { q in q.isEmpty ? nil : open.first { $0.title.localizedCaseInsensitiveContains(q) } } ?? open.first
        guard let target else { throw IntentError.noSession }
        try await store.api.send(target.id, text: message)
        return .result(dialog: "Sent to \(target.title).")
    }
}

struct WhatsRunningIntent: AppIntent {
    static var title: LocalizedStringResource = "What's Running in Bench"
    static var description = IntentDescription("Says what needs you and which sessions Claude is working in.")

    @MainActor
    func perform() async throws -> some IntentResult & ReturnsValue<String> & ProvidesDialog {
        let store = try intentStore()
        let working = store.sessions.filter { $0.status == "running" && $0.archived_at == nil }
        var parts: [String] = []
        parts.append(store.approvals.isEmpty ? "Nothing needs you." : "\(store.approvals.count) need\(store.approvals.count == 1 ? "s" : "") you.")
        parts.append(working.isEmpty ? "Claude isn't working on anything." : "Working on " + working.prefix(3).map(\.title).joined(separator: ", ") + (working.count > 3 ? " and \(working.count - 3) more." : "."))
        let text = parts.joined(separator: " ")
        return .result(value: text, dialog: "\(text)")
    }
}

struct BenchShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: WhatsRunningIntent(), phrases: ["What's running in \(.applicationName)", "Check \(.applicationName)"],
                    shortTitle: "What's Running", systemImageName: "bolt.horizontal.circle")
        AppShortcut(intent: StartSessionIntent(), phrases: ["Start a \(.applicationName) session"],
                    shortTitle: "Start Session", systemImageName: "plus.bubble")
        AppShortcut(intent: SendToSessionIntent(), phrases: ["Send to \(.applicationName)"],
                    shortTitle: "Send to Session", systemImageName: "paperplane")
    }
}

/// Focus filter: with this Focus on, Bench only notifies for these projects.
struct BenchFocusFilter: SetFocusFilterIntent {
    static var title: LocalizedStringResource = "Bench Projects"
    static var description = IntentDescription("Only notify for the projects you name while this Focus is on.")
    @Parameter(title: "Projects", description: "Project names, separated by commas", default: "") var projects: String

    var displayRepresentation: DisplayRepresentation {
        DisplayRepresentation(title: projects.isEmpty ? "All projects" : "\(projects)")
    }

    func perform() async throws -> some IntentResult {
        UserDefaults.standard.set(projects, forKey: "focus.projects")
        return .result()
    }
}

// MARK: - Services menu: "Send to Bench" from any app's selected text

final class ServicesProvider: NSObject {
    @objc func sendToBench(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let text = pboard.string(forType: .string) ?? ""
        Task { @MainActor in QuickPrompt.shared.show(prefill: text) }
    }
}

// MARK: - Quick prompt: ⌥Space from anywhere

/// A small floating box that starts a session without switching apps.
@MainActor
final class QuickPrompt {
    static let shared = QuickPrompt()
    private var panel: NSPanel?
    private var hotKey: EventHotKeyRef?

    /// ⌥Space by default; Settings can turn it off.
    func registerHotKey() {
        guard UserDefaults.standard.object(forKey: "quickPrompt") as? Bool ?? true, hotKey == nil else { return }
        var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
        InstallEventHandler(GetApplicationEventTarget(), { _, _, _ in
            Task { @MainActor in QuickPrompt.shared.toggle() }
            return noErr
        }, 1, &spec, nil, nil)
        let id = EventHotKeyID(signature: OSType(0x424E4348), id: 1) // "BNCH"
        RegisterEventHotKey(UInt32(kVK_Space), UInt32(optionKey), id, GetApplicationEventTarget(), 0, &hotKey)
    }

    func unregisterHotKey() {
        if let hotKey { UnregisterEventHotKey(hotKey) }
        hotKey = nil
    }

    func toggle() { if panel?.isVisible == true { close() } else { show() } }

    func show(prefill: String = "") {
        guard let model = AppModel.shared else { return }
        let p = panel ?? makePanel()
        panel = p
        p.contentView = NSHostingView(rootView: QuickPromptView(prefill: prefill, close: { [weak self] in self?.close() })
            .environment(model).themed())
        p.setContentSize(NSSize(width: 560, height: 132))
        if let screen = NSScreen.main {
            let f = screen.visibleFrame
            p.setFrameOrigin(NSPoint(x: f.midX - 280, y: f.maxY - f.height * 0.28))
        }
        NSApp.activate(ignoringOtherApps: true)
        p.makeKeyAndOrderFront(nil)
    }

    func close() { panel?.orderOut(nil) }

    private func makePanel() -> NSPanel {
        let p = NSPanel(contentRect: .zero, styleMask: [.titled, .fullSizeContentView, .nonactivatingPanel], backing: .buffered, defer: false)
        p.titleVisibility = .hidden
        p.titlebarAppearsTransparent = true
        p.isMovableByWindowBackground = true
        p.level = .floating
        p.hidesOnDeactivate = true
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        p.standardWindowButton(.closeButton)?.isHidden = true
        p.standardWindowButton(.miniaturizeButton)?.isHidden = true
        p.standardWindowButton(.zoomButton)?.isHidden = true
        return p
    }
}

struct QuickPromptView: View {
    @Environment(AppModel.self) private var app
    @Environment(\.theme) private var theme
    let prefill: String
    let close: () -> Void
    @State private var text = ""
    @State private var project: String = ""
    @State private var sending = false
    @FocusState private var focused: Bool
    @AppStorage("quickPrompt.project") private var lastProject = ""

    var body: some View {
        let projects = app.store?.projects ?? []
        VStack(alignment: .leading, spacing: 10) {
            TextField("", text: $text, prompt: Text("Ask Claude to…"), axis: .vertical)
                .textFieldStyle(.plain)
                .font(.system(size: 17))
                .lineLimit(1...4)
                .focused($focused)
                .onSubmit(send)
                .onKeyPress(.escape) { close(); return .handled }
            HStack(spacing: Space.sm) {
                Picker("", selection: $project) {
                    Text("Ask Bench").tag("")
                    ForEach(projects) { p in Text(p.name).tag(p.id) }
                }
                .labelsHidden()
                .fixedSize()
                Spacer()
                Text("Return to start · Esc to close").font(.system(size: 11.5)).foregroundStyle(theme.colors.muted)
                if sending { ProgressView().controlSize(.small) }
            }
        }
        .padding(18)
        .frame(width: 560)
        .background(theme.colors.surface2)
        .onAppear {
            text = prefill
            project = projects.contains { $0.id == lastProject } ? lastProject : ""
            focused = true
        }
    }

    private func send() {
        let message = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !message.isEmpty, let store = app.store, !sending else { return }
        sending = true
        lastProject = project
        Task {
            defer { sending = false }
            do {
                let s = try await store.api.createSession(project: project.isEmpty ? nil : project, task: nil, mode: .default)
                try await store.api.send(s.id, text: message)
                text = ""
                close()
            } catch {
                NSSound.beep()
            }
        }
    }
}
