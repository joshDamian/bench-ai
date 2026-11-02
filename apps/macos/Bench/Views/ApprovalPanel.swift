import AppKit
import SwiftUI

/// The small panel that floats over whatever app is in front when Claude needs you, so a
/// request can be answered without switching to Bench. It never takes focus from the app
/// you're in; ⌘↩ and ⌘⌫ work once you click it.
@MainActor
final class ApprovalPanel {
    private var panel: NSPanel?

    func show(_ a: Approval, store: BenchStore, model: AppModel) {
        let root = ApprovalPanelView(approvalId: a.id, store: store, model: model) { [weak self] in self?.hide() }
            .environment(model)
            .themed()
        let host = NSHostingView(rootView: AnyView(root))
        let p = panel ?? makePanel()
        p.contentView = host
        let size = host.fittingSize
        if let screen = NSScreen.main {
            let f = screen.visibleFrame
            p.setFrame(NSRect(x: f.maxX - size.width - 16, y: f.maxY - size.height - 16, width: size.width, height: size.height), display: true)
        }
        panel = p
        p.orderFrontRegardless()
    }

    func hide() { panel?.orderOut(nil) }

    private func makePanel() -> NSPanel {
        let p = NSPanel(contentRect: NSRect(x: 0, y: 0, width: 360, height: 200),
                        styleMask: [.nonactivatingPanel, .borderless, .fullSizeContentView], backing: .buffered, defer: true)
        p.isFloatingPanel = true
        p.level = .floating
        p.hidesOnDeactivate = false
        p.isOpaque = false
        p.backgroundColor = .clear
        p.hasShadow = true
        p.collectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary]
        p.isMovableByWindowBackground = true
        return p
    }
}

private struct ApprovalPanelView: View {
    @Environment(\.theme) private var theme
    let approvalId: String
    let store: BenchStore
    let model: AppModel
    let close: () -> Void

    var body: some View {
        let a = store.approvals.first { $0.id == approvalId }
        VStack(alignment: .leading, spacing: 10) {
            if let a {
                let s = store.session(a.session_id)
                HStack(spacing: Space.sm) {
                    Circle().fill(theme.colors.warn).frame(width: 8, height: 8)
                    Text(store.project(s?.project_id)?.name ?? "Ask Bench").font(.system(size: 11.5)).foregroundStyle(theme.colors.muted).lineLimit(1)
                    Spacer()
                    Button("Open") { open(a.session_id) }.buttonStyle(.plain).font(.system(size: 11.5)).foregroundStyle(theme.colors.muted)
                    Button { close() } label: { Image(systemName: "xmark").font(.system(size: 10, weight: .semibold)) }
                        .buttonStyle(.plain).foregroundStyle(theme.colors.muted).help("Hide")
                }
                Text(PanelText.title(a)).font(.system(size: 13.5, weight: .semibold))
                if let detail = PanelText.detail(a, cwd: s?.cwd ?? "") {
                    Text(detail)
                        .font(.system(size: 12, design: .monospaced))
                        .foregroundStyle(theme.colors.wellText)
                        .lineLimit(4)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 10).padding(.vertical, Space.sm)
                        .background(theme.colors.wellBg, in: RoundedRectangle(cornerRadius: 9))
                }
                if PanelText.answerable(a) {
                    HStack(spacing: 6) {
                        Button { answer(a, ["allow": false, "message": ""]) } label: { HStack(spacing: 6) { Text("Deny"); KeyHint("⌘⌫") } }
                            .buttonStyle(TonalButton(danger: true, height: 28))
                            .keyboardShortcut(.delete, modifiers: .command)
                        Spacer()
                        Button { answer(a, ["allow": true]) } label: { HStack(spacing: 6) { Text("Allow"); KeyHint("⌘↩") } }
                            .buttonStyle(PrimaryButton(height: 28))
                            .keyboardShortcut(.return, modifiers: .command)
                    }
                } else {
                    Button("Answer in Bench") { open(a.session_id) }.buttonStyle(PrimaryButton(height: 28))
                }
            }
        }
        .padding(14)
        .frame(width: 340)
        .background(theme.colors.surface2, in: RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(theme.colors.fill(0.12)))
        .onChange(of: a == nil) { _, gone in if gone { close() } }
    }

    private func answer(_ a: Approval, _ body: [String: Any]) {
        Task { try? await store.answer(a, body); close() }
    }

    private func open(_ sessionId: String) {
        model.selection = sessionId
        NSApp.activate(ignoringOtherApps: true)
        close()
    }
}

/// How a request reads in small places: the panel and the menu bar.
enum PanelText {
    static func title(_ a: Approval) -> String {
        a.tool == "ExitPlanMode" ? "Claude has a plan" : a.tool == "AskUserQuestion" ? "Claude is asking" : ApprovalCard.ask(a)
    }
    static func detail(_ a: Approval, cwd: String) -> String? {
        if a.tool == "Bash" { return a.input["command"].string }
        if let p = a.input["file_path"].string { return Transcript.rel(p, cwd: cwd) }
        if let u = a.input["url"].string { return u }
        return nil
    }
    static func answerable(_ a: Approval) -> Bool { a.tool != "ExitPlanMode" && a.tool != "AskUserQuestion" }
}
