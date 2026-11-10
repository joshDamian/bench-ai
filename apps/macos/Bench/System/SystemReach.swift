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
    private static var pending: DispatchWorkItem?

    /// ~/Library/Application Support/Bench/glance.json, which the widget may read.
    static var snapshotURL: URL? {
        FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask).first?
            .appendingPathComponent("Bench", isDirectory: true).appendingPathComponent("glance.json")
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

// MARK: - Quick prompt: a shortcut from anywhere

/// A small floating box that starts a session without switching apps.
@MainActor
final class QuickPrompt {
    static let shared = QuickPrompt()
    private var panel: NSPanel?
    private var hotKey: EventHotKeyRef?
    private var handlerInstalled = false

    /// The shortcut, as Carbon key code and modifiers: ⌃⌥Space unless Settings says otherwise.
    struct Shortcut: Equatable {
        var keyCode: Int
        var modifiers: Int
        var label: String

        static let standard = Shortcut(keyCode: kVK_Space, modifiers: controlKey | optionKey, label: "⌃⌥Space")

        static var current: Shortcut {
            let d = UserDefaults.standard
            guard let label = d.string(forKey: "quickPromptLabel"), d.object(forKey: "quickPromptKey") != nil else { return .standard }
            return Shortcut(keyCode: d.integer(forKey: "quickPromptKey"), modifiers: d.integer(forKey: "quickPromptModifiers"), label: label)
        }

        func save() {
            let d = UserDefaults.standard
            d.set(keyCode, forKey: "quickPromptKey"); d.set(modifiers, forKey: "quickPromptModifiers"); d.set(label, forKey: "quickPromptLabel")
        }
    }

    /// The shortcut's label for menus and Settings.
    static var label: String { Shortcut.current.label }

    /// Registers the saved shortcut, unless Settings turned the quick prompt off. Returns
    /// false when another app already holds that combination.
    @discardableResult
    func registerHotKey() -> Bool {
        guard UserDefaults.standard.object(forKey: "quickPrompt") as? Bool ?? true else { return true }
        unregisterHotKey()
        if !handlerInstalled {
            var spec = EventTypeSpec(eventClass: OSType(kEventClassKeyboard), eventKind: UInt32(kEventHotKeyPressed))
            InstallEventHandler(GetApplicationEventTarget(), { _, _, _ in
                Task { @MainActor in QuickPrompt.shared.toggle() }
                return noErr
            }, 1, &spec, nil, nil)
            handlerInstalled = true
        }
        let s = Shortcut.current
        let id = EventHotKeyID(signature: OSType(0x424E4348), id: 1) // "BNCH"
        let status = RegisterEventHotKey(UInt32(s.keyCode), UInt32(s.modifiers), id, GetApplicationEventTarget(), 0, &hotKey)
        if status != noErr { hotKey = nil }
        return status == noErr
    }

    /// Saves a new shortcut and registers it; on a clash the old one is put back.
    func use(_ s: Shortcut) -> Bool {
        let old = Shortcut.current
        s.save()
        if registerHotKey() { return true }
        old.save()
        registerHotKey()
        return false
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
        let host = NSHostingView(rootView: QuickPromptView(prefill: prefill, close: { [weak self] in self?.close() })
            .environment(model).themed())
        host.wantsLayer = true
        p.contentView = host
        p.setContentSize(host.fittingSize.width > 0 ? NSSize(width: 600, height: host.fittingSize.height) : NSSize(width: 600, height: 120))
        if let screen = NSScreen.main {
            let f = screen.visibleFrame
            p.setFrameOrigin(NSPoint(x: f.midX - 300, y: f.maxY - f.height * 0.3))
        }
        NSApp.activate(ignoringOtherApps: true)
        p.makeKeyAndOrderFront(nil)
    }

    func close() { panel?.orderOut(nil) }

    /// Borderless and see-through: the SwiftUI view draws the rounded material card itself.
    private final class KeyPanel: NSPanel {
        override var canBecomeKey: Bool { true }
        override var canBecomeMain: Bool { false }
    }

    private func makePanel() -> NSPanel {
        let p = KeyPanel(contentRect: .zero, styleMask: [.borderless, .nonactivatingPanel, .fullSizeContentView], backing: .buffered, defer: false)
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = true
        p.isMovableByWindowBackground = true
        p.level = .floating
        p.hidesOnDeactivate = true
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        // A click anywhere else, in Bench's own windows too, takes focus away: close then.
        NotificationCenter.default.addObserver(forName: NSWindow.didResignKeyNotification, object: p, queue: .main) { _ in
            Task { @MainActor in QuickPrompt.shared.close() }
        }
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
        let projectName = projects.first { $0.id == project }?.name
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 10) {
                Image(systemName: "sparkle").font(.system(size: 15, weight: .semibold)).foregroundStyle(theme.colors.accent)
                    .padding(.top, 3)
                TextField("", text: $text, prompt: Text("Ask Claude to…").foregroundStyle(theme.colors.muted), axis: .vertical)
                    .textFieldStyle(.plain)
                    .font(.system(size: 18))
                    .foregroundStyle(theme.colors.text)
                    .lineLimit(1...5)
                    .focused($focused)
                    .onSubmit(send)
                    .onKeyPress(.escape) { close(); return .handled }
                if sending { ProgressView().controlSize(.small).padding(.top, 3) }
            }
            Rectangle().fill(theme.colors.fill(0.08)).frame(height: 1)
            HStack(spacing: 8) {
                Menu {
                    Button { project = "" } label: { Label("Ask Bench", systemImage: "bubble.left") }
                    if !projects.isEmpty { Divider() }
                    ForEach(projects) { p in
                        Button { project = p.id } label: { Label(p.name, systemImage: "folder") }
                    }
                } label: {
                    HStack(spacing: 5) {
                        Image(systemName: project.isEmpty ? "bubble.left" : "folder").font(.system(size: 11))
                        Text(projectName ?? "Ask Bench").font(.system(size: 12.5, weight: .medium))
                        Image(systemName: "chevron.up.chevron.down").font(.system(size: 8, weight: .semibold))
                    }
                    .foregroundStyle(theme.colors.text)
                    .padding(.horizontal, 10).frame(height: 26)
                    .background(theme.colors.fill(0.08), in: Capsule())
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                .fixedSize()
                .accessibilityLabel("Where: \(projectName ?? "Ask Bench")")
                Text(projectName.map { "New session in \($0)" } ?? "New Ask Bench session")
                    .font(.system(size: 12)).foregroundStyle(theme.colors.muted).lineLimit(1)
                Spacer(minLength: 8)
                Keycap(label: "↩")
                Text("start").font(.system(size: 11.5)).foregroundStyle(theme.colors.muted)
                Keycap(label: "esc")
                Text("close").font(.system(size: 11.5)).foregroundStyle(theme.colors.muted)
            }
        }
        .padding(.horizontal, 18).padding(.top, 16).padding(.bottom, 12)
        .frame(width: 600, alignment: .leading)
        .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 16))
        .background(theme.colors.surface2.opacity(0.75), in: RoundedRectangle(cornerRadius: 16))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(theme.colors.fill(0.14)))
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

/// A key drawn as a small cap, for the quick prompt's hints.
private struct Keycap: View {
    @Environment(\.theme) private var theme
    let label: String
    var body: some View {
        Text(label).font(.system(size: 10.5, weight: .medium)).foregroundStyle(theme.colors.muted)
            .padding(.horizontal, 5).frame(minWidth: 18, minHeight: 17)
            .background(theme.colors.fill(0.08), in: RoundedRectangle(cornerRadius: 4))
            .overlay(RoundedRectangle(cornerRadius: 4).strokeBorder(theme.colors.fill(0.12)))
    }
}
