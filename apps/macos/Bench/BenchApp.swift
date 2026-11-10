import SwiftUI
import UserNotifications

/// Holds the store for the saved server and swaps it when the server changes.
@MainActor
@Observable
final class AppModel {
    var store: BenchStore?
    /// What the current tab shows. Opening anything from anywhere - ⌘K, Spotlight, a
    /// notification, the sidebar - also shows its project in the rail, sidebar open or not.
    var selection: String? {
        didSet {
            guard selection != oldValue else { return }
            syncRail()
            // The tab strip and "open where you left off" follow too, whichever window is open.
            follow(oldValue, selection)
            if let v = selection { UserDefaults.standard.set(v, forKey: "lastSelection") }
        }
    }

    private func syncRail() {
        guard let v = selection, let store else { return }
        if let s = store.session(v) { rail = s.project_id.flatMap { store.project($0) != nil ? $0 : nil } ?? "ask" }
        else if v.hasPrefix("task:"), let t = store.task(String(v.dropFirst(5))), let p = t.project_id { rail = p }
        else if v.hasPrefix("project:") { rail = String(v.dropFirst(8)) }
        else if v.hasPrefix("files:") { rail = String(v.dropFirst(6)) }
    }
    var showNewSession = false
    /// The project the next New Session sheet starts on.
    var newSessionProject: String?
    var showAddProject = false
    var showQuickOpen = false
    /// ⌘J and ⇧⌘T: the session's inspector and terminal, remembered across sessions.
    var showInspector = UserDefaults.standard.object(forKey: "inspector") as? Bool ?? true {
        didSet { UserDefaults.standard.set(showInspector, forKey: "inspector") }
    }
    var showTerminal = false
    var showSidebar = true
    /// Asks the task on screen to open its ship sheet (⇧⌘M).
    var shipRequest = 0
    @ObservationIgnored let panel = ApprovalPanel()

    /// The one model, for App Intents, Services and the quick prompt.
    static weak var shared: AppModel?

    /// The main window's tabs: what each shows (a session, task, project or place), in order.
    /// The selected one is `selection`; picking something in the sidebar changes that tab.
    var tabs: [String] = UserDefaults.standard.stringArray(forKey: "tabs") ?? [] {
        didSet { UserDefaults.standard.set(tabs, forKey: "tabs") }
    }
    /// A second session beside the first, in the same window.
    var split: String?
    /// What the sidebar's list shows: "inbox", "ask", or a project's id (the rail's selection).
    var rail: String = UserDefaults.standard.string(forKey: "rail") ?? "inbox" {
        didSet { UserDefaults.standard.set(rail, forKey: "rail") }
    }

    /// Files dropped on a sidebar row, waiting for that session's composer to take them.
    var pendingAttachments: [String: [URL]] = [:]
    /// ⌘F in a session, ⌥⌘↑ / ⌥⌘↓ between turns, ⇧⌘A approve: counters the session on screen watches.
    var findRequest = 0
    var turnUp = 0
    var turnDown = 0
    var approveRequest = 0
    var stopRequest = 0

    init() {
        if let c = SavedServer.load() { use(c) }
        AppModel.shared = self
    }

    func use(_ c: ServerConfig?) {
        store?.stop()
        store = nil
        guard let c else { return }
        let s = BenchStore(config: c)
        s.onNewApproval = { [weak s, weak self] a in
            guard let s, let self else { return }
            // In front, the card is on screen. Elsewhere: the floating panel, or a banner.
            guard !NSApp.isActive else { return }
            if UserDefaults.standard.object(forKey: "alert.panel") as? Bool ?? true {
                self.panel.show(a, store: s, model: self)
            } else if UserDefaults.standard.object(forKey: "alert.needs") as? Bool ?? true {
                Notifications.approval(a, sessionTitle: s.session(a.session_id)?.title ?? "Session",
                                       project: s.project(s.session(a.session_id)?.project_id)?.name)
            }
        }
        s.onTurnEnded = { [weak s] ses in
            guard let s, !NSApp.isActive else { return }
            let errors = UserDefaults.standard.object(forKey: "alert.errors") as? Bool ?? true
            let done = UserDefaults.standard.object(forKey: "alert.finished") as? Bool ?? true
            if (ses.status == "error" && errors) || (ses.status != "error" && done) {
                Notifications.finished(ses, project: s.project(ses.project_id)?.name)
            }
        }
        s.onGlanceChange = { [weak s] in if let s { SystemReach.publish(s) } }
        store = s
        s.start()
    }

    func pair(_ c: ServerConfig) {
        SavedServer.save(c)
        use(c)
    }

    func unpair() {
        SavedServer.forget()
        use(nil)
    }

    /// Bumped to put the cursor in the project page's task box.
    var focusTaskBox = 0

    /// A new task in a project: its page, with the task box ready to type in.
    func startTask(in project: String) {
        // A project page already showing this project keeps its tab; otherwise a new one.
        if selection == "project:\(project)" { } else { openNew("project:\(project)") }
        focusTaskBox += 1
    }

    func newSession(in project: String? = nil) {
        newSessionProject = project
        showNewSession = true
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    var model: AppModel?

    func applicationDidFinishLaunching(_ notification: Notification) {
        Notifications.setUp(delegate: self)
        NSApp.servicesProvider = ServicesProvider()
        NSUpdateDynamicServices()
        Task { @MainActor in QuickPrompt.shared.registerHotKey() }
        // Tabs are Bench's own (in the main window); the system's window tabs would add a
        // second tab bar and "Show Tab Bar" / "Show All Tabs" to the View menu.
        NSWindow.allowsAutomaticWindowTabbing = false
    }

    /// Closing the window keeps Bench in the menu bar, if Settings say so.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        !(UserDefaults.standard.object(forKey: "keepRunning") as? Bool ?? true)
    }

    // Allow and Deny on the banner answer the approval without opening the window.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let sessionId = info["session"] as? String else { return }
        let approvalId = info["approval"] as? String ?? ""
        let question = info["question"] as? String ?? ""
        let typed = (response as? UNTextInputNotificationResponse)?.userText
        await MainActor.run {
            guard let model, let store = model.store else { return }
            switch response.actionIdentifier {
            case Notifications.allow, Notifications.deny:
                let allow = response.actionIdentifier == Notifications.allow
                Task { try? await store.api.answer(approvalId, allow ? ["allow": true] : ["allow": false, "message": ""]) }
            case Notifications.reply:
                Task { try? await store.api.answer(approvalId, ["allow": true, "answers": [question: typed ?? ""]]) }
            case Notifications.approvePlan:
                Task { try? await store.api.answer(approvalId, ["allow": true, "mode": "acceptEdits"]) }
            default:
                model.selection = sessionId
                NSApp.activate(ignoringOtherApps: true)
            }
        }
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification) async -> UNNotificationPresentationOptions {
        UserDefaults.standard.bool(forKey: "alert.sound") ? [.banner, .sound] : [.banner]
    }
}

@main
struct BenchMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var model = AppModel()

    init() { BenchFont.register() }
    @AppStorage("menuBar") private var menuBar = true

    var body: some Scene {
        WindowGroup("Bench", id: "main") {
            RootView()
                .environment(model)
                .themed()
                .onAppear { delegate.model = model }
                // bench:// links (the widget) bring this window forward instead of opening another.
                .handlesExternalEvents(preferring: ["*"], allowing: ["*"])
        }
        .windowStyle(.hiddenTitleBar)
        .defaultSize(width: 1320, height: 840)
        .commands { BenchCommands(model: model) }

        // A session in its own window (or tab, with Window › Merge All Windows). Windows come
        // back on relaunch showing the same session.
        WindowGroup("Session", id: "session", for: String.self) { $id in
            SessionWindow(sessionId: id)
                .environment(model)
                .themed()
        }
        .defaultSize(width: 860, height: 780)
        .windowToolbarStyle(.unifiedCompact(showsTitle: true))

        Window("Dashboard", id: "dashboard") {
            DashboardView()
                .environment(model)
                .themed()
        }
        .defaultSize(width: 1080, height: 760)

        // The menu bar: what needs you and what is running, answerable without the window.
        MenuBarExtra(isInserted: $menuBar) {
            MenuBarView()
                .environment(model)
                .themed()
        } label: {
            MenuBarLabel(model: model)
        }
        .menuBarExtraStyle(.window)

        Settings {
            SettingsView()
                .environment(model)
                .themed()
        }
    }
}

struct MenuBarLabel: View {
    let model: AppModel
    var body: some View {
        let n = model.store?.needsYouCount ?? 0
        let running = model.store?.sessions.contains { $0.status == "running" && $0.archived_at == nil } ?? false
        HStack(spacing: 2) {
            Image(systemName: n > 0 ? "exclamationmark.bubble.fill" : running ? "bolt.horizontal.circle.fill" : "rectangle.split.3x1")
                .symbolEffect(.pulse, options: .repeating, isActive: running && n == 0)
            if n > 0 { Text("\(n)") }
        }
        .onChange(of: n) { _, v in Notifications.setBadge(v) }
    }
}

struct BenchCommands: Commands {
    let model: AppModel
    @Environment(\.openWindow) private var openWindow

    private var selectedSession: String? {
        guard let id = model.selection, model.store?.session(id) != nil else { return nil }
        return id
    }
    private var recent: [Session] {
        (model.store?.sessions ?? []).filter { $0.archived_at == nil }.sorted { ($0.updated_at ?? "") > ($1.updated_at ?? "") }
    }

    var body: some Commands {
        CommandGroup(after: .newItem) {
            // With a project in the rail, ⌘N is a task in it; elsewhere, a session.
            Button(model.store?.project(model.rail) != nil ? "New Task" : "New Session…") {
                if let p = model.store?.project(model.rail) { model.startTask(in: p.id) } else { model.newSession() }
            }
            .keyboardShortcut("n", modifiers: [.command])
            Button("Add Project…") { model.showAddProject = true }
                .keyboardShortcut("n", modifiers: [.command, .shift])
                .disabled(model.store == nil)
            Button("New Tab") { model.openTab() }
                .keyboardShortcut("t", modifiers: [.command])
                .disabled(model.store == nil)
            // ⌘W closes a tab while the main window has more than one; otherwise the window.
            Button(model.tabs.count > 1 ? "Close Tab" : "Close Window") {
                if model.mainIsKey && model.tabs.count > 1 { model.closeTab() } else { NSApp.keyWindow?.performClose(nil) }
            }
            .keyboardShortcut("w", modifiers: [.command])
            Divider()
            Button("Show Next Tab") { model.cycleTab(1) }
                .keyboardShortcut("]", modifiers: [.command, .shift])
                .disabled(model.tabs.count < 2)
            Button("Show Previous Tab") { model.cycleTab(-1) }
                .keyboardShortcut("[", modifiers: [.command, .shift])
                .disabled(model.tabs.count < 2)
            Button("Next Tab") { model.cycleTab(1) }
                .keyboardShortcut(.tab, modifiers: [.control])
                .disabled(model.tabs.count < 2)
            // ⌘\ puts the last session tab you used beside this one, or closes the split.
            Button(model.split == nil ? "Split with Previous Tab" : "Close Split") {
                if model.split != nil { model.split = nil }
                else if let other = model.tabs.last(where: { $0 != model.selection && model.store?.session($0) != nil }) { model.split = other }
            }
            .keyboardShortcut("\\", modifiers: [.command])
            .disabled(model.split == nil && !model.tabs.contains { $0 != model.selection && model.store?.session($0) != nil })
        }
        CommandMenu("Go") {
            Button("Go Anywhere…") { model.showQuickOpen = true }
                .keyboardShortcut("k", modifiers: [.command])
                .disabled(model.store == nil)
            Divider()
            Button("Needs You") { model.selection = Place.needs.rawValue }.keyboardShortcut("1", modifiers: [.command])
            Button("Running") { model.selection = Place.running.rawValue }.keyboardShortcut("2", modifiers: [.command])
            Button("All Sessions") { model.selection = Place.all.rawValue }.keyboardShortcut("3", modifiers: [.command])
            Divider()
            Button("Dashboard") { openWindow(id: "dashboard") }.keyboardShortcut("d", modifiers: [.command, .shift])
        }
        CommandGroup(after: .sidebar) {
            Button(model.showSidebar ? "Hide Sidebar" : "Show Sidebar") { withAnimation(.snappy) { model.showSidebar.toggle() } }
                .keyboardShortcut("s", modifiers: [.command, .control])
            Button(model.showInspector ? "Hide Inspector" : "Show Inspector") { model.showInspector.toggle() }
                .keyboardShortcut("j", modifiers: [.command])
            Button(model.showTerminal ? "Hide Terminal" : "Show Terminal") { model.showTerminal.toggle() }
                .keyboardShortcut("t", modifiers: [.command, .shift])
            Divider()
        }
        CommandGroup(after: .pasteboard) {
            Divider()
            Button("Find in Conversation…") { model.findRequest += 1 }
                .keyboardShortcut("f", modifiers: [.command])
        }
        CommandMenu("Session") {
            Button("Approve Request") { model.approveRequest += 1 }
                .keyboardShortcut("a", modifiers: [.command, .shift])
                .disabled((model.store?.approvals.isEmpty ?? true))
            Button("Stop Claude") { model.stopRequest += 1 }
                .keyboardShortcut(".", modifiers: [.command])
            Divider()
            Button("Previous Turn") { model.turnUp += 1 }
                .keyboardShortcut(.upArrow, modifiers: [.command, .option])
            Button("Next Turn") { model.turnDown += 1 }
                .keyboardShortcut(.downArrow, modifiers: [.command, .option])
            Divider()
            Button("Open in New Window") { if let id = selectedSession { openWindow(id: "session", value: id) } }
                .keyboardShortcut("o", modifiers: [.command, .option])
                .disabled(selectedSession == nil)
            Button("Quick Prompt (\(QuickPrompt.label))") { QuickPrompt.shared.show() }
            Divider()
            // ⌥⌘1–9: the nine most recent sessions, as the sidebar orders them.
            ForEach(Array(recent.prefix(9).enumerated()), id: \.element.id) { i, s in
                Button(s.title) { model.selection = s.id }
                    .keyboardShortcut(KeyEquivalent(Character("\(i + 1)")), modifiers: [.command, .option])
            }
        }
        CommandMenu("Projects") {
            Button("Inbox") { model.rail = "inbox" }.keyboardShortcut("0", modifiers: [.control])
            ForEach(Array((model.store?.projects ?? []).prefix(9).enumerated()), id: \.element.id) { i, p in
                Button(p.name) { model.rail = p.id }
                    .keyboardShortcut(KeyEquivalent(Character("\(i + 1)")), modifiers: [.control])
            }
            Button("Ask Bench") { model.rail = "ask" }
            Divider()
            Button("Previous in Rail") { model.stepRail(-1) }.keyboardShortcut(.upArrow, modifiers: [.control])
            Button("Next in Rail") { model.stepRail(1) }.keyboardShortcut(.downArrow, modifiers: [.control])
            Divider()
            Button("Browse Files") { if let p = model.store?.project(model.rail) { model.openTab("files:\(p.id)") } }
                .keyboardShortcut("f", modifiers: [.control, .shift])
                .disabled(model.store?.project(model.rail) == nil)
        }
        CommandMenu("Task") {
            Button("Merge or Ship…") { model.shipRequest += 1 }
                .keyboardShortcut("m", modifiers: [.command, .shift])
                .disabled(!(model.selection?.hasPrefix("task:") ?? false))
        }
    }
}


/// One session on its own: the same view as in the main window, without the sidebar.
struct SessionWindow: View {
    @Environment(AppModel.self) private var model
    @Environment(\.theme) private var theme
    let sessionId: String?

    var body: some View {
        Group {
            if let store = model.store, let id = sessionId, store.session(id) != nil {
                SessionView(store: store, sessionId: id, showsTitle: false).id(id)
                    .navigationTitle(store.session(id)?.title ?? "Session")
                    .navigationSubtitle(store.project(store.session(id)?.project_id)?.name ?? "Ask Bench")
            } else if model.store?.ready == false {
                ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                Text("This session isn't available.").foregroundStyle(theme.colors.muted)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .frame(minWidth: 560, minHeight: 480)
        .background(theme.colors.bg)
    }
}
