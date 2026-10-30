import Foundation
import UserNotifications

/// Approvals as Mac notifications, with Allow and Deny right on the banner for a plain
/// permission - the native thing the web's push cannot do.
enum Notifications {
    static let category = "approval"
    static let allow = "allow"
    static let deny = "deny"

    static func setUp(delegate: UNUserNotificationCenterDelegate) {
        let c = UNUserNotificationCenter.current()
        c.delegate = delegate
        let actions = [
            UNNotificationAction(identifier: allow, title: "Allow", options: []),
            UNNotificationAction(identifier: deny, title: "Deny", options: [.destructive]),
        ]
        c.setNotificationCategories([
            UNNotificationCategory(identifier: category, actions: actions, intentIdentifiers: []),
            UNNotificationCategory(identifier: "open", actions: [], intentIdentifiers: []),
        ])
        c.requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in }
    }

    static func approval(_ a: Approval, sessionTitle: String) {
        let content = UNMutableNotificationContent()
        content.title = sessionTitle
        switch a.tool {
        case "AskUserQuestion": content.body = "Claude is asking a question"
        case "ExitPlanMode": content.body = "Claude has a plan"
        case "Bash": content.body = "Run: " + (a.input["command"].string ?? "a command")
        default: content.body = a.title ?? "Claude wants to use \(a.tool)"
        }
        content.sound = .default
        // Questions and plans need the window; a permission can be answered from the banner.
        content.categoryIdentifier = ["AskUserQuestion", "ExitPlanMode"].contains(a.tool) ? "open" : category
        content.userInfo = ["approval": a.id, "session": a.session_id]
        content.threadIdentifier = a.session_id
        UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "approval-\(a.id)", content: content, trigger: nil))
    }

    static func clear(approvalId: String) {
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["approval-\(approvalId)"])
    }

    static func setBadge(_ n: Int) {
        UNUserNotificationCenter.current().setBadgeCount(n) { _ in }
    }
}
