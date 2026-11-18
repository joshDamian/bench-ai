import AppKit
import SwiftUI

/// Bench's browser, the Chromium on the server, in a Mac window (the "Taking over Bench's
/// browser" board): tabs, back, an address bar, and the page, live. Clicks, scrolling and typing
/// go to the server's browser over /api/browser/view, the same take-over the phone has. When a
/// session waits for you on a site (a sign-in), a bar says which and offers Done or Skip.
@MainActor
final class BrowserWindowController {
    static let shared = BrowserWindowController()
    private var window: NSWindow?

    func show(store: BenchStore) {
        if let w = window { w.makeKeyAndOrderFront(nil); NSApp.activate(ignoringOtherApps: true); return }
        let root = BrowserView(store: store).themed()
        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1100, height: 760), styleMask: [.titled, .closable, .resizable, .miniaturizable, .fullSizeContentView], backing: .buffered, defer: false)
        w.title = "Bench’s browser"
        w.titlebarAppearsTransparent = true
        w.titleVisibility = .hidden
        w.isReleasedWhenClosed = false
        w.contentView = NSHostingView(rootView: root)
        w.center()
        w.setFrameAutosaveName("BenchBrowser")
        NotificationCenter.default.addObserver(forName: NSWindow.willCloseNotification, object: w, queue: .main) { [weak self] _ in
            MainActor.assumeIsolated { self?.window = nil }
        }
        window = w
        w.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }
}

/// A tab as the server lists it.
struct BrowserTab: Identifiable, Equatable { let id: String; let title: String; let current: Bool }

/// The live link to the server's browser.
@MainActor @Observable
final class BrowserLink {
    var frame: NSImage?
    var url = ""
    var tabs: [BrowserTab] = []
    var closed: String?
    private var task: URLSessionWebSocketTask?
    private var lastSize: CGSize = .zero

    func open(_ api: BenchAPI) {
        var r = api.request("/api/browser/view")
        r.url = URL(string: api.config.baseURL.replacingOccurrences(of: "^http", with: "ws", options: .regularExpression) + "/api/browser/view")
        let t = URLSession.shared.webSocketTask(with: r)
        t.maximumMessageSize = 32 * 1024 * 1024
        task = t
        t.resume()
        receive(t)
        if lastSize != .zero { size(lastSize) }
    }

    func close() { task?.cancel(with: .normalClosure, reason: nil); task = nil }

    private func receive(_ t: URLSessionWebSocketTask) {
        t.receive { [weak self] result in
            Task { @MainActor in
                guard let self, self.task === t else { return }
                switch result {
                case .failure:
                    if self.closed == nil { self.closed = "The browser couldn’t be reached. Is the server on the Go version?" }
                case .success(let m):
                    switch m {
                    case .string(let s): self.handle(s)
                    // A frame is binary JPEG: a still taken when the page settles is the
                    // screen's resolution, the frames while it moves are smaller and quick.
                    case .data(let d): if !d.isEmpty { self.frame = NSImage(data: d) }
                    @unknown default: break
                    }
                    self.receive(t)
                }
            }
        }
    }

    private func handle(_ s: String) {
        guard let d = s.data(using: .utf8), let v = try? JSONDecoder().decode(JSONValue.self, from: d) else { return }
        switch v["type"].string {
        case "frame":
            if let b = v["data"].string, let data = Data(base64Encoded: b) { frame = NSImage(data: data) }
        case "url":
            url = v["url"].string ?? ""
        case "tabs":
            tabs = v["tabs"].array.compactMap { t in
                guard let id = t["id"].string else { return nil }
                let u = t["url"].string ?? ""
                var title = t["title"].string ?? ""
                if title.isEmpty { title = u.replacingOccurrences(of: "https://", with: "").replacingOccurrences(of: "http://", with: "").components(separatedBy: "/").first ?? "" }
                if title.isEmpty || u == "about:blank" { title = "New tab" }
                return BrowserTab(id: id, title: title, current: t["current"].bool)
            }
        case "error":
            closed = v["message"].string ?? "The browser closed."
        default: break
        }
    }

    func send(_ o: [String: Any]) {
        guard let task, let d = try? JSONSerialization.data(withJSONObject: o), let s = String(data: d, encoding: .utf8) else { return }
        task.send(.string(s)) { _ in }
    }

    /// The window's page area, in points: pages lay out at its width.
    func size(_ s: CGSize) {
        lastSize = s
        // binaryFrames: this app reads JPEG frames as binary. An older app that doesn't say
        // so keeps the base64 text frames it knows.
        send(["type": "size", "width": Int(s.width), "height": Int(s.height), "binaryFrames": true])
    }
}

struct BrowserView: View {
    @Environment(\.theme) private var theme
    let store: BenchStore
    @State private var link = BrowserLink()
    @State private var address = ""
    @FocusState private var addressFocused: Bool

    /// A session waiting for you on a site: the take-over question it asked.
    private var waiting: Approval? {
        store.approvals.first { $0.tool == "AskUserQuestion" && $0.input["questions"].array.first?["header"].string?.lowercased() == "take over" }
    }

    var body: some View {
        VStack(spacing: 0) {
            // Tabs, beside the traffic lights.
            HStack(alignment: .bottom, spacing: 2) {
                ForEach(link.tabs) { t in
                    Button { if !t.current { link.send(["type": "tab", "id": t.id]) } } label: {
                        HStack(spacing: 7) {
                            RoundedRectangle(cornerRadius: 3).fill(theme.colors.fill(0.18)).frame(width: 12, height: 12)
                            Text(t.title).font(.system(size: 12)).lineLimit(1)
                        }
                        .foregroundStyle(t.current ? theme.colors.text : theme.colors.muted)
                        .padding(.horizontal, 12)
                        .frame(maxWidth: 200, minHeight: 30)
                        .background(t.current ? theme.colors.bg : .clear, in: UnevenRoundedRectangle(topLeadingRadius: 8, topTrailingRadius: 8))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                Button { link.send(["type": "new_tab"]) } label: { Image(systemName: "plus").font(.system(size: 12)).frame(width: 28, height: 28) }
                    .buttonStyle(.plain).foregroundStyle(theme.colors.muted).help("New tab")
                Spacer()
            }
            .padding(.leading, 84).padding(.trailing, 12)
            .frame(height: 40)
            .background(theme.colors.fill(0.04))

            // Back, the address, and whose browser this is.
            HStack(spacing: 10) {
                Button { link.send(["type": "back"]) } label: { Image(systemName: "chevron.left").frame(width: 24, height: 24) }
                    .buttonStyle(.plain).foregroundStyle(theme.colors.muted).help("Back")
                HStack(spacing: 8) {
                    Image(systemName: "lock").font(.system(size: 11)).foregroundStyle(theme.colors.muted)
                    TextField("", text: $address, prompt: Text("Search or type an address"))
                        .textFieldStyle(.plain).font(.system(size: 13))
                        .focused($addressFocused)
                        .onSubmit {
                            let a = address.trimmingCharacters(in: .whitespaces)
                            if !a.isEmpty { link.send(["type": "go", "url": a]) }
                            addressFocused = false
                        }
                }
                .padding(.horizontal, 12).frame(height: 30)
                .background(theme.colors.fill(0.07), in: RoundedRectangle(cornerRadius: 8))
                Text("Bench’s browser on your server").font(.system(size: 12)).foregroundStyle(theme.colors.muted)
            }
            .padding(.horizontal, 12).frame(height: 44)
            .overlay(alignment: .bottom) { Rectangle().fill(theme.colors.fill(0.08)).frame(height: 1) }

            if let a = waiting {
                let q = a.input["questions"].array.first?["question"].string ?? "A session is waiting for you here."
                HStack(spacing: 12) {
                    Circle().fill(theme.colors.warn).frame(width: 8, height: 8)
                    Text(q).font(.system(size: 13)).lineLimit(2)
                    Spacer(minLength: 12)
                    Button("Done") { answer(a, "Done") }.buttonStyle(CapsuleButton(kind: .primary))
                    Button("Skip this site") { answer(a, "Skip this site") }.buttonStyle(CapsuleButton(kind: .outline))
                }
                .padding(.horizontal, 16).frame(minHeight: 50)
                .background(theme.colors.warn.opacity(0.10))
                .overlay(alignment: .bottom) { Rectangle().fill(theme.colors.warn.opacity(0.25)).frame(height: 1) }
            }

            ZStack {
                Color.white
                if let img = link.frame {
                    BrowserSurface(image: img, link: link)
                } else if let why = link.closed {
                    Text(why).font(.system(size: 14)).foregroundStyle(.black.opacity(0.6)).padding(40)
                } else {
                    ProgressView().controlSize(.small)
                }
                if let why = link.closed, link.frame != nil {
                    VStack { Spacer(); Text(why).font(.system(size: 12.5)).padding(10).background(.regularMaterial, in: Capsule()).padding(16) }
                }
            }
            .background(GeometryReader { g in Color.clear.onAppear { link.size(g.size) }.onChange(of: g.size) { _, s in link.size(s) } })
        }
        .frame(minWidth: 720, minHeight: 480)
        .background(theme.colors.bg)
        .onAppear { link.open(store.api) }
        .onDisappear { link.close() }
        .onChange(of: link.url) { _, u in if !addressFocused { address = u } }
    }

    private func answer(_ a: Approval, _ choice: String) {
        let q = a.input["questions"].array.first?["question"].string ?? ""
        Task { try? await store.answer(a, ["allow": true, "answers": [q: choice]]) }
    }
}

/// The page, drawn from the server's frames, taking the mouse and keyboard and sending them on:
/// a click as a tap (where on the page, as a fraction), the wheel as a scroll, typing as text,
/// and Return, Delete and Tab as keys.
struct BrowserSurface: NSViewRepresentable {
    let image: NSImage
    let link: BrowserLink

    func makeNSView(context: Context) -> SurfaceView { let v = SurfaceView(); v.link = link; v.image = image; return v }
    func updateNSView(_ v: SurfaceView, context: Context) { v.link = link; v.image = image; v.needsDisplay = true }

    final class SurfaceView: NSView {
        var link: BrowserLink?
        var image: NSImage?
        private var wheel: CGFloat = 0
        private var wheelFlushPending = false
        override var acceptsFirstResponder: Bool { true }
        override var isFlipped: Bool { true }

        /// Where the frame is drawn: the full width, its own aspect.
        private var drawn: NSRect {
            guard let img = image, img.size.width > 0 else { return bounds }
            let h = bounds.width * img.size.height / img.size.width
            return NSRect(x: 0, y: 0, width: bounds.width, height: min(h, bounds.height))
        }

        override func draw(_ dirty: NSRect) {
            image?.draw(in: drawn, from: .zero, operation: .copy, fraction: 1, respectFlipped: true, hints: [.interpolation: NSImageInterpolation.high])
        }

        override func mouseDown(with e: NSEvent) {
            window?.makeFirstResponder(self)
            let p = convert(e.locationInWindow, from: nil)
            let r = drawn
            guard r.contains(p), r.width > 0, r.height > 0 else { return }
            MainActor.assumeIsolated { link?.send(["type": "tap", "x": Double(p.x / r.width), "y": Double(p.y / r.height)]) }
        }

        override func scrollWheel(with e: NSEvent) {
            // Trackpad events arrive far faster than the page can be streamed. Gather them
            // and send at most one a frame, so scrolling is continuous instead of moving in
            // 24-point steps, each a round trip behind the hand.
            wheel += e.scrollingDeltaY
            guard !wheelFlushPending, bounds.height > 0 else { return }
            wheelFlushPending = true
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.0 / 60.0) { [weak self] in
                guard let self else { return }
                self.wheelFlushPending = false
                let dy = -self.wheel / self.bounds.height
                self.wheel = 0
                if abs(dy) > 0.0005 { MainActor.assumeIsolated { self.link?.send(["type": "scroll", "dy": Double(dy)]) } }
            }
        }

        override func keyDown(with e: NSEvent) {
            let key: String? = switch e.keyCode {
            case 36, 76: "Enter"
            case 51: "Backspace"
            case 48: "Tab"
            default: nil
            }
            MainActor.assumeIsolated {
                if let key { link?.send(["type": "key", "key": key]); return }
                if e.modifierFlags.contains(.command) { return }
                if let t = e.characters, !t.isEmpty, t.unicodeScalars.allSatisfy({ $0.value >= 32 && $0.value != 127 && !(0xF700...0xF8FF).contains($0.value) }) {
                    link?.send(["type": "text", "text": t])
                }
            }
        }

        /// ⌘V pastes the Mac's clipboard into the page.
        override func performKeyEquivalent(with e: NSEvent) -> Bool {
            if e.modifierFlags.contains(.command), e.charactersIgnoringModifiers == "v", window?.firstResponder === self,
               let s = NSPasteboard.general.string(forType: .string) {
                MainActor.assumeIsolated { link?.send(["type": "text", "text": s]) }
                return true
            }
            return super.performKeyEquivalent(with: e)
        }
    }
}
