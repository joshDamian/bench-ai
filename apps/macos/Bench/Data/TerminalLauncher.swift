import AppKit

/// Any session opens as `claude --resume` in a real terminal - on this Mac when the
/// server is this Mac, and as a command to paste when it is somewhere else.
enum TerminalLauncher {
    static func command(_ s: Session) -> String? {
        guard let id = s.claude_session_id else { return nil }
        let cwd = s.cwd.map { "cd \(quote($0)) && " } ?? ""
        return "\(cwd)claude --resume \(id)"
    }

    @MainActor
    static func open(_ s: Session, store: BenchStore) {
        guard let cmd = command(s) else { return }
        guard store.api.config.isLoopback else {
            // The session lives on another machine; the command is for a shell there.
            copy(s)
            return
        }
        let app = NSWorkspace.shared.urlForApplication(withBundleIdentifier: "com.googlecode.iterm2") != nil ? "iTerm" : "Terminal"
        let escaped = cmd.replacingOccurrences(of: "\\", with: "\\\\").replacingOccurrences(of: "\"", with: "\\\"")
        let script = app == "iTerm"
            ? "tell application \"iTerm\"\nactivate\nset w to (create window with default profile)\ntell current session of w to write text \"\(escaped)\"\nend tell"
            : "tell application \"Terminal\"\nactivate\ndo script \"\(escaped)\"\nend tell"
        var err: NSDictionary?
        NSAppleScript(source: script)?.executeAndReturnError(&err)
        if err != nil { copy(s) }
    }

    static func copy(_ s: Session) {
        guard let cmd = command(s) else { return }
        NSPasteboard.general.clearContents()
        NSPasteboard.general.setString(cmd, forType: .string)
    }

    private static func quote(_ p: String) -> String { "'" + p.replacingOccurrences(of: "'", with: "'\\''") + "'" }
}
