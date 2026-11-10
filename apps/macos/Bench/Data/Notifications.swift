import Foundation
import UserNotifications

/// Approvals as Mac notifications, with Allow and Deny right on the banner for a plain
/// permission - the native thing the web's push cannot do.
enum Notifications {
    static let category = "approval"
    static let allow = "allow"
    static let deny = "deny"
    static let reply = "reply"
    static let approvePlan = "approvePlan"

    static func setUp(delegate: UNUserNotificationCenterDelegate) {
        let c = UNUserNotificationCenter.current()
        c.delegate = delegate
        let actions = [
            UNNotificationAction(identifier: allow, title: "Allow", options: []),
            UNNotificationAction(identifier: deny, title: "Deny", options: [.destructive]),
        ]
        c.setNotificationCategories([
            UNNotificationCategory(identifier: category, actions: actions, intentIdentifiers: []),
            UNNotificationCategory(identifier: "question", actions: [
                UNTextInputNotificationAction(identifier: reply, title: "Answer", options: [], textInputButtonTitle: "Send", textInputPlaceholder: "Your answer"),
            ], intentIdentifiers: []),
            UNNotificationCategory(identifier: "plan", actions: [
                UNNotificationAction(identifier: approvePlan, title: "Approve", options: []),
            ], intentIdentifiers: []),
            UNNotificationCategory(identifier: "open", actions: [], intentIdentifiers: []),
        ])
        c.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    /// The Focus filter's projects: with a Focus on, banners only for these (by name).
    static var focusProjects: Set<String> {
        Set((UserDefaults.standard.string(forKey: "focus.projects") ?? "").split(separator: ",").map { $0.trimmingCharacters(in: .whitespaces).lowercased() }.filter { !$0.isEmpty })
    }

    static func allowed(project: String?) -> Bool {
        let f = focusProjects
        return f.isEmpty || f.contains((project ?? "Ask Bench").lowercased())
    }

    static func approval(_ a: Approval, sessionTitle: String, project: String? = nil) {
        guard allowed(project: project) else { return }
        let content = UNMutableNotificationContent()
        content.title = sessionTitle
        switch a.tool {
        case "AskUserQuestion": content.body = a.input["questions"].array.first?["question"].string ?? "Claude is asking a question"
        case "ExitPlanMode": content.body = "Claude has a plan"
        case "Bash": content.body = "Run: " + (a.input["command"].string ?? "a command")
        default: content.body = a.title ?? "Claude wants to use \(a.tool)"
        }
        content.sound = .default
        // Questions and plans need the window; a permission can be answered from the banner.
        content.categoryIdentifier = a.tool == "AskUserQuestion" ? "question" : a.tool == "ExitPlanMode" ? "plan" : category
        content.userInfo = ["approval": a.id, "session": a.session_id, "question": a.input["questions"].array.first?["question"].string ?? ""]
        content.threadIdentifier = a.session_id
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "approval-\(a.id)", content: content, trigger: nil))
    }

    /// A turn ended while you were elsewhere.
    static func finished(_ s: Session, project: String?) {
        guard allowed(project: project) else { return }
        let content = UNMutableNotificationContent()
        content.title = s.title
        content.body = s.status == "error" ? "Stopped with an error" + (s.error.map { ": \($0)" } ?? "") : "Finished"
        content.categoryIdentifier = "open"
        content.userInfo = ["session": s.id]
        content.threadIdentifier = s.id
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "turn-\(s.id)", content: content, trigger: nil))
    }

    static func clear(approvalId: String) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["approval-\(approvalId)"])
    }

    static func setBadge(_ n: Int) {
        UNUserNotificationCenter.current().setBadgeCount(n) { _ in }
    }
}
