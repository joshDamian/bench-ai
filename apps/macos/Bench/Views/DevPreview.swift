import AppKit
import SwiftUI
import WebKit

/// A page a session's dev server serves, as the preview shows it: a port on the server's
/// machine and a path on it.
struct PreviewTarget: Equatable, Hashable {
    let port: Int
    var path: String

    var label: String { "localhost:\(port)\(path == "/" ? "" : path)" }
    var schemeURL: URL { URL(string: "bench-preview://p\(port)\(path.hasPrefix("/") ? path : "/" + path)")! }

    /// A link Claude wrote to one of the server's own ports: localhost, the loopback
    /// address, or the server's own host name. Anything else is the open web.
    static func from(_ url: URL, serverHost: String?) -> PreviewTarget? {
        guard ["http", "https"].contains(url.scheme?.lowercased() ?? ""), let host = url.host?.lowercased(), let port = url.port else { return nil }
        let local = ["localhost", "127.0.0.1", "0.0.0.0", "::1", "[::1]"].contains(host) || host.hasSuffix(".localhost")
            || (serverHost.map { $0.lowercased() == host } ?? false)
        guard local else { return nil }
        var path = url.path.isEmpty ? "/" : url.path
        if let q = url.query { path += "?\(q)" }
        return PreviewTarget(port: port, path: path)
    }

    /// The localhost links in a reply, one per port.
    static func links(in text: String, serverHost: String?) -> [PreviewTarget] {
        guard let re = try? NSRegularExpression(pattern: #"https?://[A-Za-z0-9.\-\[\]:]+:\d{2,5}[^\s)\]`"'<>]*"#) else { return [] }
        var seen = Set<Int>(), out: [PreviewTarget] = []
        for m in re.matches(in: text, range: NSRange(text.startIndex..., in: text)) {
            guard let r = Range(m.range, in: text), let u = URL(string: String(text[r]).trimmingCharacters(in: CharacterSet(charactersIn: ".,;:"))),
                  let t = from(u, serverHost: serverHost), seen.insert(t.port).inserted else { continue }
            out.append(t)
        }
        return out
    }
}

/// The preview beside the conversation: a bar to go back, reload and see where you are,
/// and the page itself, fetched through Bench's own connection to the server.
struct PreviewPanel: View {
    @Environment(\.theme) private var theme
    let files: SessionFiles
    let target: PreviewTarget
    var onStart: (Int) -> Void
    @State private var web = PreviewWeb()

    var body: some View {
        VStack(spacing: 0) {
            bar
            Rectangle().fill(theme.colors.fill(0.08)).frame(height: 1)
            PreviewWebView(web: web, api: files.api, sessionId: files.sessionId, start: target, onStart: onStart)
        }
        .background(theme.colors.bg)
        .background {
            Button("") { web.view?.reload() }.keyboardShortcut("r", modifiers: .command).hidden()
            Button("") { if files.full { files.full = false } else { files.page = nil } }.keyboardShortcut(.cancelAction).hidden()
            Button("") { files.full.toggle() }.keyboardShortcut(.return, modifiers: [.command, .shift]).hidden()
        }
    }

    private var bar: some View {
        HStack(spacing: 4) {
            icon("chevron.left", "Back") { web.view?.goBack() }.disabled(!web.canGoBack)
            icon("chevron.right", "Forward") { web.view?.goForward() }.disabled(!web.canGoForward)
            icon("arrow.clockwise", "Reload (⌘R)") { web.view?.reload() }
            HStack(spacing: 8) {
                Circle().fill(web.down ? theme.colors.muted : theme.colors.good).frame(width: 7, height: 7)
                Text(web.current?.label ?? target.label).font(BenchFont.mono(12)).lineLimit(1).truncationMode(.middle)
                    .textSelection(.enabled)
                Spacer(minLength: 4)
                if web.loading { ProgressView().controlSize(.mini) }
                Text("via Bench").font(.system(size: 11)).foregroundStyle(theme.colors.muted)
            }
            .padding(.horizontal, 10).frame(height: 30)
            .background(theme.colors.fill(0.07), in: RoundedRectangle(cornerRadius: 8))
            .padding(.horizontal, 4)
            Button("Open in Browser") { openInBrowser() }.buttonStyle(TonalButton(height: 26))
            icon(files.full ? "arrow.down.right.and.arrow.up.left" : "arrow.up.left.and.arrow.down.right",
                 files.full ? "Show the Conversation (⇧⌘↩)" : "Full View (⇧⌘↩)") { files.full.toggle() }
            icon("xmark", "Close") { files.page = nil; files.full = false }
        }
        .foregroundStyle(theme.colors.muted)
        .padding(.horizontal, 10)
        .frame(height: 54)
    }

    private func icon(_ name: String, _ help: String, _ action: @escaping () -> Void) -> some View {
        Button(action: action) { Image(systemName: name).font(.system(size: 12)).frame(width: 28, height: 28).contentShape(Rectangle()) }
            .buttonStyle(.plain).help(help).accessibilityLabel(help)
    }

    /// The same page in the person's browser, at the server's own address and port.
    private func openInBrowser() {
        let t = web.current ?? target
        let host = URL(string: files.api.config.baseURL)?.host ?? "localhost"
        if let u = URL(string: "http://\(host):\(t.port)\(t.path)") { NSWorkspace.shared.open(u) }
    }
}

/// What the web view is doing, for the bar above it.
@MainActor
@Observable
final class PreviewWeb {
    weak var view: WKWebView?
    var current: PreviewTarget?
    var canGoBack = false
    var canGoForward = false
    var loading = false
    var down = false
}

struct PreviewWebView: NSViewRepresentable {
    let web: PreviewWeb
    let api: BenchAPI
    let sessionId: String
    let start: PreviewTarget
    let onStart: (Int) -> Void

    func makeCoordinator() -> Coordinator { Coordinator(web: web, onStart: onStart) }

    func makeNSView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.setURLSchemeHandler(PreviewScheme(api: api, sessionId: sessionId, web: web), forURLScheme: "bench-preview")
        config.userContentController.add(context.coordinator, name: "bench")
        config.userContentController.add(context.coordinator.sockets, name: "benchSocket")
        config.userContentController.addUserScript(WKUserScript(source: PreviewSockets.shim, injectionTime: .atDocumentStart, forMainFrameOnly: false))
        config.websiteDataStore = .nonPersistent()
        let v = WKWebView(frame: .zero, configuration: config)
        v.navigationDelegate = context.coordinator
        context.coordinator.sockets.attach(v, api: api, sessionId: sessionId)
        v.allowsBackForwardNavigationGestures = true
        context.coordinator.observe(v)
        web.view = v
        v.load(URLRequest(url: start.schemeURL))
        context.coordinator.started = start
        return v
    }

    func updateNSView(_ v: WKWebView, context: Context) {
        context.coordinator.onStart = onStart
        // A different link from the conversation: go there.
        if context.coordinator.started != start {
            context.coordinator.started = start
            v.load(URLRequest(url: start.schemeURL))
        }
    }

    static func dismantleNSView(_ v: WKWebView, coordinator: Coordinator) {
        v.configuration.userContentController.removeScriptMessageHandler(forName: "bench")
        v.configuration.userContentController.removeScriptMessageHandler(forName: "benchSocket")
        coordinator.sockets.closeAll()
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate, WKScriptMessageHandler {
        let web: PreviewWeb
        let sockets = PreviewSockets()
        var onStart: (Int) -> Void
        var started: PreviewTarget?
        private var watches: [NSKeyValueObservation] = []

        init(web: PreviewWeb, onStart: @escaping (Int) -> Void) { self.web = web; self.onStart = onStart }

        func observe(_ v: WKWebView) {
            let update: (WKWebView) -> Void = { [weak self] v in
                Task { @MainActor in
                    guard let self else { return }
                    self.web.canGoBack = v.canGoBack
                    self.web.canGoForward = v.canGoForward
                    self.web.loading = v.isLoading
                    if let u = v.url, let host = u.host, host.hasPrefix("p"), let port = Int(host.dropFirst()) {
                        var path = u.path.isEmpty ? "/" : u.path
                        if let q = u.query { path += "?\(q)" }
                        self.web.current = PreviewTarget(port: port, path: path)
                    }
                }
            }
            watches = [
                v.observe(\.url) { v, _ in update(v) },
                v.observe(\.isLoading) { v, _ in update(v) },
                v.observe(\.canGoBack) { v, _ in update(v) },
            ]
        }

        /// Links off the server, to the open web, go to the person's browser.
        func webView(_ w: WKWebView, decidePolicyFor a: WKNavigationAction) async -> WKNavigationActionPolicy {
            guard let u = a.request.url, u.scheme != "bench-preview", u.scheme != "about", u.scheme != "data", u.scheme != "blob" else { return .allow }
            if a.targetFrame?.isMainFrame ?? true { NSWorkspace.shared.open(u); return .cancel }
            return .allow
        }

        func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
            if let port = (m.body as? [String: Any])?["start"] as? Int { onStart(port) }
        }
    }
}

/// Fetches every request the preview makes through Bench's server, which passes it on to
/// the dev server on that machine. One origin per port, so the page's own absolute paths
/// and fetches stay on it.
final class PreviewScheme: NSObject, WKURLSchemeHandler {
    let api: BenchAPI
    let sessionId: String
    weak var web: PreviewWeb?
    private var running: [ObjectIdentifier: URLSessionDataTask] = [:]
    private let lock = NSLock()

    init(api: BenchAPI, sessionId: String, web: PreviewWeb) { self.api = api; self.sessionId = sessionId; self.web = web }

    func webView(_ webView: WKWebView, start task: any WKURLSchemeTask) {
        guard let url = task.request.url, let host = url.host, host.hasPrefix("p"), let port = Int(host.dropFirst()) else {
            task.didFailWithError(URLError(.badURL)); return
        }
        var path = url.path.isEmpty ? "/" : url.path
        if let q = url.query { path += "?\(q)" }
        var r = api.request("/api/sessions/\(BenchAPI.enc(sessionId))/preview/\(port)\(path.hasPrefix("/") ? path : "/" + path)",
                            method: task.request.httpMethod ?? "GET")
        r.httpBody = task.request.httpBody
        for (k, v) in task.request.allHTTPHeaderFields ?? [:] where !["host", "authorization", "cookie"].contains(k.lowercased()) {
            r.setValue(v, forHTTPHeaderField: k)
        }
        let id = ObjectIdentifier(task)
        let isMain = task.request.mainDocumentURL == url
        let dt = URLSession.shared.dataTask(with: r) { [weak self] data, response, error in
            // WebKit wants its scheme tasks answered on the main thread.
            DispatchQueue.main.async { self?.finish(task, id: id, url: url, port: port, isMain: isMain, data: data, response: response, error: error) }
        }
        lock.lock(); running[id] = dt; lock.unlock()
        dt.resume()
    }

    private func finish(_ task: any WKURLSchemeTask, id: ObjectIdentifier, url: URL, port: Int, isMain: Bool, data: Data?, response: URLResponse?, error: Error?) {
        do {
            self.lock.lock()
            let live = self.running.removeValue(forKey: id) != nil
            self.lock.unlock()
            guard live else { return }
            let http = response as? HTTPURLResponse
            if let error {
                task.didFailWithError(error)
                return
            }
            // The page itself, and nothing answering: say so, with a way to start it.
            if isMain, http?.statusCode == 502 {
                let html = Self.downPage(port: port)
                let res = HTTPURLResponse(url: url, statusCode: 200, httpVersion: "HTTP/1.1", headerFields: ["Content-Type": "text/html; charset=utf-8"])!
                task.didReceive(res); task.didReceive(Data(html.utf8)); task.didFinish()
                Task { @MainActor in self.web?.down = true }
                return
            }
            if isMain { Task { @MainActor in self.web?.down = false } }
            var headers = http?.allHeaderFields as? [String: String] ?? [:]
            headers.removeValue(forKey: "Content-Encoding")
            headers.removeValue(forKey: "Content-Length")
            let res = HTTPURLResponse(url: url, statusCode: http?.statusCode ?? 200, httpVersion: "HTTP/1.1", headerFields: headers)!
            task.didReceive(res)
            task.didReceive(data ?? Data())
            task.didFinish()
        }
    }

    func webView(_ webView: WKWebView, stop task: any WKURLSchemeTask) {
        lock.lock()
        let dt = running.removeValue(forKey: ObjectIdentifier(task))
        lock.unlock()
        dt?.cancel()
    }

    static func downPage(port: Int) -> String {
        """
        <!doctype html><html><head><meta charset="utf-8"><meta name="color-scheme" content="light dark">
        <style>
        body{margin:0;height:100vh;display:flex;flex-direction:column;align-items:center;justify-content:center;gap:10px;
             font:13px -apple-system,system-ui;color:#80858f;background:transparent}
        h1{font-size:15px;font-weight:600;margin:0;color:CanvasText}
        div{display:flex;gap:8px;margin-top:6px}
        button{font:500 12px -apple-system,system-ui;border:0;border-radius:7px;padding:6px 12px;background:rgba(127,127,127,.18);color:CanvasText}
        button.p{background:#6b52dc;color:#fff}
        </style></head><body>
        <h1>Nothing is listening on :\(port)</h1>
        <p style="margin:0">The dev server isn't running, or it's on another port.</p>
        <div><button onclick="location.reload()">Retry</button>
        <button class="p" onclick="window.webkit.messageHandlers.bench.postMessage({start: \(port)})">Ask Claude to start it</button></div>
        </body></html>
        """
    }
}

/// Chips under a reply for the dev servers it links to, each opening the preview.
struct PreviewChips: View {
    @Environment(SessionFiles.self) private var files
    @Environment(\.theme) private var theme
    let reply: String

    var body: some View {
        let host = URL(string: files.api.config.baseURL)?.host
        let found = PreviewTarget.links(in: reply, serverHost: host)
        if !found.isEmpty {
            FlowLayout(spacing: Space.sm) {
                ForEach(found, id: \.port) { t in
                    let on = files.page?.port == t.port
                    Button { files.open(t) } label: {
                        HStack(spacing: 8) {
                            Image(systemName: "dot.radiowaves.left.and.right").font(.system(size: 11)).foregroundStyle(theme.colors.good)
                            Text("localhost:\(t.port)").font(BenchFont.mono(12))
                            Text("Preview").font(.system(size: 11.5)).foregroundStyle(theme.colors.muted)
                        }
                        .padding(.horizontal, 12).frame(height: 30)
                        .background(on ? theme.colors.accent.opacity(0.12) : theme.colors.fill(0.05), in: RoundedRectangle(cornerRadius: 9))
                        .overlay(RoundedRectangle(cornerRadius: 9).strokeBorder(on ? theme.colors.accent.opacity(0.4) : theme.colors.fill(0.06)))
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .help("Open \(t.label) here")
                }
            }
        }
    }
}

/// WebSockets for the preview - a dev server's live reload - carried by the app. The page's
/// WebSocket is swapped for a stand-in that hands each socket to the app, which opens it
/// to Bench's server with the login, and passes messages back and forth.
@MainActor
final class PreviewSockets: NSObject, WKScriptMessageHandler {
    private weak var view: WKWebView?
    private var api: BenchAPI?
    private var sessionId = ""
    private var open: [Int: URLSessionWebSocketTask] = [:]

    func attach(_ v: WKWebView, api: BenchAPI, sessionId: String) { view = v; self.api = api; self.sessionId = sessionId }

    func closeAll() { open.values.forEach { $0.cancel(with: .goingAway, reason: nil) }; open = [:] }

    func userContentController(_ c: WKUserContentController, didReceive m: WKScriptMessage) {
        guard let body = m.body as? [String: Any], let op = body["op"] as? String, let id = body["id"] as? Int else { return }
        switch op {
        case "open":
            guard let api, let port = body["port"] as? Int, let path = body["path"] as? String else { return }
            var r = api.request("/api/sessions/\(BenchAPI.enc(sessionId))/preview/\(port)\(path.hasPrefix("/") ? path : "/" + path)")
            r.url = URL(string: r.url!.absoluteString.replacingOccurrences(of: "^http", with: "ws", options: .regularExpression))
            if let p = body["protocols"] as? [String], !p.isEmpty { r.setValue(p.joined(separator: ", "), forHTTPHeaderField: "Sec-WebSocket-Protocol") }
            let task = URLSession.shared.webSocketTask(with: r)
            open[id] = task
            task.resume()
            emit(id, "open", nil)
            receive(id, task)
        case "send":
            guard let task = open[id] else { return }
            if let t = body["text"] as? String { task.send(.string(t)) { _ in } }
            else if let b = body["b64"] as? String, let d = Data(base64Encoded: b) { task.send(.data(d)) { _ in } }
        case "close":
            open.removeValue(forKey: id)?.cancel(with: .normalClosure, reason: nil)
        default: break
        }
    }

    private func receive(_ id: Int, _ task: URLSessionWebSocketTask) {
        task.receive { [weak self] result in
            Task { @MainActor in
                guard let self, self.open[id] === task else { return }
                switch result {
                case .success(.string(let t)): self.emit(id, "message", ["text": t]); self.receive(id, task)
                case .success(.data(let d)): self.emit(id, "message", ["b64": d.base64EncodedString()]); self.receive(id, task)
                case .success: self.receive(id, task)
                case .failure:
                    self.open[id] = nil
                    self.emit(id, "close", ["code": Int(task.closeCode.rawValue)])
                }
            }
        }
    }

    private func emit(_ id: Int, _ kind: String, _ data: [String: Any]?) {
        let json = (try? JSONSerialization.data(withJSONObject: data ?? [:])).flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        view?.evaluateJavaScript("window.__benchSocket && window.__benchSocket(\(id), '\(kind)', \(json))")
    }

    /// Replaces WebSocket for sockets to the dev server; any other address keeps the real one.
    static let shim = #"""
    (() => {
      const Real = window.WebSocket;
      const live = new Map();
      let next = 1;
      const portOf = (u) => {
        if (/^p\d+$/.test(u.hostname)) return Number(u.hostname.slice(1));
        if (['localhost', '127.0.0.1', '0.0.0.0', '[::1]'].includes(u.hostname) && u.port) return Number(u.port);
        return null;
      };
      class BenchSocket extends EventTarget {
        constructor(url, protocols) {
          super();
          let u;
          try { u = new URL(String(url).replace(/:\/(?=\/|$)/, '/'), location.href); } catch (e) { u = null; }
          const port = u && (portOf(u) ?? (/^p\d+$/.test(location.hostname) && !u.port ? Number(location.hostname.slice(1)) : null));
          if (!port) return new Real(url, protocols);
          this.url = String(url); this.readyState = 0; this.protocol = ''; this.extensions = ''; this.binaryType = 'blob'; this.bufferedAmount = 0;
          this.onopen = this.onmessage = this.onclose = this.onerror = null;
          this._id = next++;
          live.set(this._id, this);
          const list = protocols == null ? [] : (Array.isArray(protocols) ? protocols : [protocols]);
          window.webkit.messageHandlers.benchSocket.postMessage({ op: 'open', id: this._id, port, path: u.pathname + u.search, protocols: list });
        }
        send(data) {
          if (this.readyState !== 1) return;
          const id = this._id;
          if (typeof data === 'string') return window.webkit.messageHandlers.benchSocket.postMessage({ op: 'send', id, text: data });
          const buf = data instanceof ArrayBuffer ? data : (data.buffer || null);
          if (buf) { let s = ''; new Uint8Array(buf).forEach((b) => (s += String.fromCharCode(b))); window.webkit.messageHandlers.benchSocket.postMessage({ op: 'send', id, b64: btoa(s) }); }
        }
        close() {
          if (this.readyState > 1) return;
          this.readyState = 2;
          window.webkit.messageHandlers.benchSocket.postMessage({ op: 'close', id: this._id });
          this._fire('close', { code: 1000 });
        }
        _fire(kind, d) {
          let ev;
          if (kind === 'message') {
            let data = d.text;
            if (d.b64 != null) { const bin = atob(d.b64), a = new Uint8Array(bin.length); for (let i = 0; i < bin.length; i++) a[i] = bin.charCodeAt(i); data = this.binaryType === 'arraybuffer' ? a.buffer : new Blob([a]); }
            ev = new MessageEvent('message', { data });
          } else if (kind === 'close') {
            this.readyState = 3; live.delete(this._id);
            ev = new CloseEvent('close', { code: d.code || 1006, wasClean: d.code === 1000 });
          } else {
            if (kind === 'open') this.readyState = 1;
            ev = new Event(kind);
          }
          const h = this['on' + kind];
          if (typeof h === 'function') h.call(this, ev);
          this.dispatchEvent(ev);
        }
      }
      BenchSocket.CONNECTING = 0; BenchSocket.OPEN = 1; BenchSocket.CLOSING = 2; BenchSocket.CLOSED = 3;
      window.WebSocket = BenchSocket;
      window.__benchSocket = (id, kind, d) => { const s = live.get(id); if (s) s._fire(kind, d || {}); };
    })();
    """#
}
