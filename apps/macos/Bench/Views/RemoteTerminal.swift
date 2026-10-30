import SwiftUI
import SwiftTerm

/// A shell on the server, drawn by SwiftTerm. The socket carries raw output one way and
/// `{type:"input"}` / `{type:"resize"}` frames the other (src/terminal.mjs).
@MainActor
final class RemoteShell: NSObject, TerminalViewDelegate {
    let view = TerminalView(frame: .zero)
    private let api: BenchAPI
    private let query: String
    private var socket: URLSessionWebSocketTask?
    var onState: ((String?) -> Void)?

    init(api: BenchAPI, query: String) {
        self.api = api
        self.query = query
        super.init()
        view.terminalDelegate = self
        view.font = NSFont.monospacedSystemFont(ofSize: 12.5, weight: .regular)
    }

    func connect() {
        socket?.cancel(with: .goingAway, reason: nil)
        let ws = api.session.webSocketTask(with: api.terminalRequest(query))
        socket = ws
        ws.resume()
        onState?(nil)
        receive(ws)
        let t = view.getTerminal()
        sendFrame(["type": "resize", "cols": t.cols, "rows": t.rows])
    }

    func close() { socket?.cancel(with: .goingAway, reason: nil); socket = nil }

    private func receive(_ ws: URLSessionWebSocketTask) {
        ws.receive { [weak self] result in
            Task { @MainActor in
                guard let self, ws === self.socket else { return }
                switch result {
                case .success(let m):
                    switch m {
                    case .string(let s): self.view.feed(text: s)
                    case .data(let d): self.view.feed(byteArray: ArraySlice([UInt8](d)))
                    @unknown default: break
                    }
                    self.receive(ws)
                case .failure:
                    self.onState?("The shell disconnected.")
                }
            }
        }
    }

    private func sendFrame(_ obj: [String: Any]) {
        guard let socket, let d = try? JSONSerialization.data(withJSONObject: obj), let s = String(data: d, encoding: .utf8) else { return }
        socket.send(.string(s)) { _ in }
    }

    // TerminalViewDelegate
    nonisolated func send(source: TerminalView, data: ArraySlice<UInt8>) {
        let text = String(decoding: data, as: UTF8.self)
        Task { @MainActor in self.sendFrame(["type": "input", "data": text]) }
    }
    nonisolated func sizeChanged(source: TerminalView, newCols: Int, newRows: Int) {
        Task { @MainActor in self.sendFrame(["type": "resize", "cols": newCols, "rows": newRows]) }
    }
    nonisolated func setTerminalTitle(source: TerminalView, title: String) {}
    nonisolated func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {}
    nonisolated func scrolled(source: TerminalView, position: Double) {}
    nonisolated func requestOpenLink(source: TerminalView, link: String, params: [String: String]) {
        if let u = URL(string: link) { Task { @MainActor in NSWorkspace.shared.open(u) } }
    }
    nonisolated func bell(source: TerminalView) { Task { @MainActor in NSSound.beep() } }
    nonisolated func clipboardCopy(source: TerminalView, content: Data) {
        Task { @MainActor in
            NSPasteboard.general.clearContents()
            NSPasteboard.general.setString(String(decoding: content, as: UTF8.self), forType: .string)
        }
    }
    nonisolated func iTermContent(source: TerminalView, content: ArraySlice<UInt8>) {}
    nonisolated func rangeChanged(source: TerminalView, startY: Int, endY: Int) {}
}

private struct TerminalHost: NSViewRepresentable {
    let shell: RemoteShell
    func makeNSView(context: Context) -> TerminalView { shell.view }
    func updateNSView(_ v: TerminalView, context: Context) {}
}

/// The Terminal tab: one shell per task (or project), kept on the server between visits.
struct RemoteTerminalView: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let query: String
    let place: String
    @State private var shell: RemoteShell?
    @State private var problem: String?

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: Space.sm) {
                Circle().fill(problem == nil ? theme.colors.good : theme.colors.warn).frame(width: 6, height: 6)
                Text(problem ?? place).lineLimit(1)
                    .font(.system(size: TypeScale.xs, design: problem == nil ? .monospaced : .default))
                Spacer()
                if problem != nil { Button("Reconnect") { shell?.connect() }.controlSize(.small) }
            }
            .foregroundStyle(theme.colors.muted)
            .padding(.horizontal, Space.md)
            .frame(height: 28)
            Divider().overlay(theme.colors.line)
            if let shell {
                TerminalHost(shell: shell)
                    .padding(Space.sm)
            } else {
                Spacer()
            }
        }
        .background(theme.colors.wellBg, in: RoundedRectangle(cornerRadius: theme.radiusSm))
        .overlay(RoundedRectangle(cornerRadius: theme.radiusSm).strokeBorder(theme.colors.line))
        .padding(Space.lg)
        .onAppear {
            let s = RemoteShell(api: store.api, query: query)
            s.onState = { problem = $0 }
            s.view.nativeBackgroundColor = NSColor(theme.colors.wellBg)
            s.view.nativeForegroundColor = NSColor(theme.colors.wellText)
            shell = s
            s.connect()
        }
        .onDisappear { shell?.close() }
    }
}
