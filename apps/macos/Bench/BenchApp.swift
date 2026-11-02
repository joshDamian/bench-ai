import SwiftUI
import UserNotifications

/// Holds the store for the saved server and swaps it when the server changes.
@MainActor
@Observable
final class AppModel {
    var store: BenchStore?
    var selection: String?
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
    /// Asks the task on screen to open its ship sheet (⇧⌘M).
    var shipRequest = 0
    @ObservationIgnored let panel = ApprovalPanel()

    init() {
        if let c = SavedServer.load() { use(c) }
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
                Notifications.approval(a, sessionTitle: s.session(a.session_id)?.title ?? "Session")
            }
        }
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

    func newSession(in project: String? = nil) {
        newSessionProject = project
        showNewSession = true
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    var model: AppModel?

    func applicationDidFinishLaunching(_ notification: Notification) {
        Notifications.setUp(delegate: self)
    }

    /// Closing the window keeps Bench in the menu bar, if Settings say so.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        !(UserDefaults.standard.object(forKey: "keepRunning") as? Bool ?? true)
    }

    // Allow and Deny on the banner answer the approval without opening the window.
    func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse) async {
        let info = response.notification.request.content.userInfo
        guard let approvalId = info["approval"] as? String, let sessionId = info["session"] as? String else { return }
        await MainActor.run {
            guard let model, let store = model.store else { return }
            switch response.actionIdentifier {
            case Notifications.allow, Notifications.deny:
                let allow = response.actionIdentifier == Notifications.allow
                Task { try? await store.api.answer(approvalId, allow ? ["allow": true] : ["allow": false, "message": ""]) }
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
    @AppStorage("menuBar") private var menuBar = true

    var body: some Scene {
        WindowGroup("Bench", id: "main") {
            RootView()
                .environment(model)
                .themed()
                .onAppear { delegate.model = model }
        }
        .defaultSize(width: 1320, height: 840)
        .commands { BenchCommands(model: model) }

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
            if n > 0 { Text("\(n)") }
        }
        .onChange(of: n) { _, v in Notifications.setBadge(v) }
    }
}

struct BenchCommands: Commands {
    let model: AppModel
    @Environment(\.openWindow) private var openWindow

    var body: some Commands {
        CommandGroup(after: .newItem) {
            Button("New Session…") { model.newSession() }
                .keyboardShortcut("n", modifiers: [.command])
            Button("Add Project…") { model.showAddProject = true }
                .keyboardShortcut("n", modifiers: [.command, .shift])
                .disabled(model.store == nil)
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
            Button(model.showInspector ? "Hide Inspector" : "Show Inspector") { model.showInspector.toggle() }
                .keyboardShortcut("j", modifiers: [.command])
            Button(model.showTerminal ? "Hide Terminal" : "Show Terminal") { model.showTerminal.toggle() }
                .keyboardShortcut("t", modifiers: [.command, .shift])
            Divider()
        }
        CommandMenu("Task") {
            Button("Merge or Ship…") { model.shipRequest += 1 }
                .keyboardShortcut("m", modifiers: [.command, .shift])
                .disabled(!(model.selection?.hasPrefix("task:") ?? false))
        }
    }
}
