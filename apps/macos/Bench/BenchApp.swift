import SwiftUI
import UserNotifications

/// Holds the store for the saved server and swaps it when the server changes.
@MainActor
@Observable
final class AppModel {
    var store: BenchStore?
    var selection: String?
    var showNewSession = false
    var showQuickOpen = false

    init() {
        if let c = SavedServer.load() { use(c) }
    }

    func use(_ c: ServerConfig?) {
        store?.stop()
        store = nil
        guard let c else { return }
        let s = BenchStore(config: c)
        s.onNewApproval = { [weak s] a in
            guard let s else { return }
            // Banner only when Bench is not the app in front: in front, the card is on screen.
            if !NSApp.isActive { Notifications.approval(a, sessionTitle: s.session(a.session_id)?.title ?? "Session") }
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
}

final class AppDelegate: NSObject, NSApplicationDelegate, UNUserNotificationCenterDelegate {
    var model: AppModel?

    func applicationDidFinishLaunching(_ notification: Notification) {
        Notifications.setUp(delegate: self)
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
        [.banner, .sound]
    }
}

@main
struct BenchMacApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @State private var model = AppModel()

    var body: some Scene {
        WindowGroup("Bench", id: "main") {
            RootView()
                .environment(model)
                .themed()
                .onAppear { delegate.model = model }
        }
        .defaultSize(width: 1180, height: 780)
        .commands { BenchCommands(model: model) }

        // The menu bar: what needs you and what is working, answerable without the window.
        MenuBarExtra {
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
                .frame(width: 520)
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
    var body: some Commands {
        CommandGroup(after: .newItem) {
            Button("New Session…") { model.showNewSession = true }
                .keyboardShortcut("n", modifiers: [.command])
                .disabled(model.store == nil)
            Button("Go to Session…") { model.showQuickOpen = true }
                .keyboardShortcut("k", modifiers: [.command])
                .disabled(model.store == nil)
        }
    }
}
