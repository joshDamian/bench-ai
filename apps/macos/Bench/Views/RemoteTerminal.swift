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
    /// The title the shell sets (usually user@host: folder), for the tab's label.
    var onTitle: ((String) -> Void)?

    init(api: BenchAPI, query: String) {
        self.api = api
        self.query = query
        super.init()
        view.terminalDelegate = self
        view.font = BenchFont.monoNS(13)
    }

    func connect() {
        socket?.cancel(with: .goingAway, reason: nil)
        let ws = api.session.webSocketTask(with: api.terminalRequest(query))
        socket = ws
        ws.resume()
        onState?(nil)
        receive(ws)
    }

    func close() { socket?.cancel(with: .goingAway, reason: nil); socket = nil }

    /// Ends the shell on the server, not just this connection to it: closing a tab.
    func end() {
        sendFrame(["type": "close"])
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) { [weak self] in self?.close() }
    }

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
    nonisolated func setTerminalTitle(source: TerminalView, title: String) {
        Task { @MainActor in self.onTitle?(title) }
    }
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

/// The terminal panel: tabs of shells in one place (a session's folder, a task's worktree,
/// a project's checkout). Each tab is its own shell on the server and keeps running until
/// its tab is closed; the panel's × only hides it.
struct RemoteTerminalView: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    let query: String
    let place: String
    /// Hides the panel (the shells keep running).
    var onClose: (() -> Void)?
    @State private var tabs: [String] = []
    @State private var current = ""
    @State private var shells: [String: RemoteShell] = [:]
    @State private var titles: [String: String] = [:]
    @State private var problems: [String: String] = [:]

    private var memoryKey: String { "terminalTabs.\(query)" }

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 2) {
                        ForEach(Array(tabs.enumerated()), id: \.element) { i, id in tab(id, index: i) }
                    }
                }
                Button(action: addTab) {
                    Image(systemName: "plus").font(.system(size: 10, weight: .semibold)).frame(width: 22, height: 22).contentShape(Rectangle())
                }
                .buttonStyle(.plain).help("New Terminal Tab (⌃⇧T)")
                .keyboardShortcut("t", modifiers: [.control, .shift])
                if let p = problems[current] {
                    Text(p).font(.system(size: TypeScale.xs)).foregroundStyle(theme.colors.warn).lineLimit(1)
                    Button("Reconnect") { shells[current]?.connect() }.controlSize(.small)
                }
                if let onClose {
                    Button(action: onClose) {
                        Image(systemName: "xmark").font(.system(size: 9, weight: .bold)).frame(width: 22, height: 22).contentShape(Rectangle())
                    }
                    .buttonStyle(.plain).help("Hide Terminal (⇧⌘T)")
                    .accessibilityLabel("Hide terminal")
                }
            }
            .foregroundStyle(theme.colors.muted)
            .padding(.leading, 14).padding(.trailing, 10)
            .frame(height: 38)
            ZStack {
                ForEach(tabs, id: \.self) { id in
                    if let shell = shells[id] {
                        TerminalHost(shell: shell)
                            .padding(.horizontal, 20).padding(.top, 8).padding(.bottom, 12)
                            .opacity(id == current ? 1 : 0)
                            .allowsHitTesting(id == current)
                    }
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .background(theme.colors.bg)
        // The split's divider stops at the sidebar card's 8pt margin; this carries it on to
        // the card, as the header and tab lines do.
        .overlay(alignment: .topLeading) {
            Rectangle().fill(Color(nsColor: .separatorColor)).frame(width: 8, height: 1)
                .offset(x: -8, y: -1)
                .allowsHitTesting(false)
        }
        .onAppear {
            let saved = UserDefaults.standard.stringArray(forKey: memoryKey) ?? []
            tabs = saved.isEmpty ? ["1"] : saved
            current = UserDefaults.standard.string(forKey: memoryKey + ".current").flatMap { tabs.contains($0) ? $0 : nil } ?? tabs[0]
            for id in tabs { connect(id) }
        }
        .onDisappear { shells.values.forEach { $0.close() } }
        .onChange(of: tabs) { _, t in UserDefaults.standard.set(t, forKey: memoryKey) }
        .onChange(of: current) { _, c in
            UserDefaults.standard.set(c, forKey: memoryKey + ".current")
            if let v = shells[c]?.view { DispatchQueue.main.async { v.window?.makeFirstResponder(v) } }
        }
    }

    private func tab(_ id: String, index: Int) -> some View {
        let on = id == current
        return HStack(spacing: 6) {
            Circle().fill(problems[id] == nil ? theme.colors.good : theme.colors.warn).frame(width: 5, height: 5)
            Text(label(id, index: index)).lineLimit(1).truncationMode(.head)
                .font(BenchFont.mono(TypeScale.xs))
            if tabs.count > 1 {
                Button { closeTab(id) } label: { Image(systemName: "xmark").font(.system(size: 7, weight: .bold)).frame(width: 14, height: 14) }
                    .buttonStyle(.plain).opacity(on ? 1 : 0.5)
                    .help("Close Tab (ends its shell)")
                    .accessibilityLabel("Close terminal tab")
            }
        }
        .foregroundStyle(on ? theme.colors.text : theme.colors.muted)
        .padding(.horizontal, 8).frame(maxWidth: 220, minHeight: 22)
        .background(on ? theme.colors.fill(0.1) : .clear, in: RoundedRectangle(cornerRadius: 6))
        .contentShape(Rectangle())
        .onTapGesture { current = id }
        .contextMenu { Button("Close Tab") { closeTab(id) }.disabled(tabs.count < 2) }
    }

    /// What the shell calls itself, without the "user@host:" part; the place for a new one.
    private func label(_ id: String, index: Int) -> String {
        guard let t = titles[id], !t.isEmpty else { return index == 0 ? place : "\(place) · \(index + 1)" }
        if let colon = t.firstIndex(of: ":"), t[..<colon].contains("@") { return String(t[t.index(after: colon)...]).trimmingCharacters(in: .whitespaces) }
        return t
    }

    private func connect(_ id: String) {
        let s = RemoteShell(api: store.api, query: "\(query)&tab=\(id)")
        s.onState = { problems[id] = $0 }
        s.onTitle = { titles[id] = $0 }
        let pal = TerminalPalette.of(dark: theme.dark, bg: theme.colors.bg)
        s.view.nativeBackgroundColor = NSColor(pal.bg)
        s.view.nativeForegroundColor = NSColor(pal.fg)
        s.view.caretColor = NSColor(pal.cursor)
        s.view.selectedTextBackgroundColor = NSColor(pal.selection)
        s.view.installColors(pal.ansi.map { v in
            SwiftTerm.Color(red8: UInt16((v >> 16) & 0xff), green8: UInt16((v >> 8) & 0xff), blue8: UInt16(v & 0xff))
        })
        shells[id] = s
        s.connect()
    }

    private func addTab() {
        let next = String((tabs.compactMap(Int.init).max() ?? 0) + 1)
        tabs.append(next)
        connect(next)
        current = next
    }

    private func closeTab(_ id: String) {
        guard tabs.count > 1, let i = tabs.firstIndex(of: id) else { return }
        shells[id]?.end()
        shells[id] = nil
        titles[id] = nil
        problems[id] = nil
        tabs.remove(at: i)
        if current == id { current = tabs[min(i, tabs.count - 1)] }
    }
}
