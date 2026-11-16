import AppKit
import Foundation
import Observation
import UserNotifications

// MARK: - What this Mac shares with Bench

/// One thing this Mac lets Bench use. Shares are only ever added here, on the Mac: the server
/// and the phone can ask, never add. Kinds: a folder, an app, the screen, a command it can run.
struct Share: Codable, Identifiable, Hashable {
    var id: String
    var kind: String          // folder | app | screen | run
    var name: String
    var path: String?         // the folder, the app bundle, or the command
    var access: String        // read | write (folders), open (apps), look (screen), run
    var rule: String          // allow | ask

    var accessLabel: String {
        switch kind {
        case "folder": access == "write" ? "Read and write" : "Read only"
        case "app": "Open it and use it"
        case "screen": "Look only: screenshots"
        default: "Runs \(path.map { URL(fileURLWithPath: $0).lastPathComponent } ?? name)"
        }
    }

    var icon: String {
        switch kind {
        case "folder": "folder"
        case "app": "app"
        case "screen": "display"
        default: name.localizedCaseInsensitiveContains("android") ? "iphone.gen1" : "terminal"
        }
    }

    /// The path as the server shows it: ~ for home.
    var shownPath: String? { path.map { $0.replacingOccurrences(of: NSHomeDirectory(), with: "~") } }
}

/// Something the server asked this Mac to share; it waits here until the person decides.
struct ShareAsk: Identifiable, Hashable {
    let id: String
    let kind: String
    let path: String
    let access: String
    let why: String

    var title: String {
        switch kind {
        case "folder": "\(path) · \(access == "write" ? "read and write" : "read only")"
        case "screen": "Your screen · look only"
        case "app": "\(path) · open it and use it"
        default: "Run \(path)"
        }
    }
}

// MARK: - The link

/// This Mac's link to the server (/api/linked-devices/link): it says what is shared, answers the calls
/// sessions make into a share, and holds asks until the person decides them here.
@MainActor
@Observable
final class MacLink {
    static let kindOrder = ["folder", "app", "screen", "run"]

    private(set) var shares: [Share] = []
    private(set) var asks: [ShareAsk] = []
    private(set) var connected = false

    @ObservationIgnored private let api: BenchAPI
    @ObservationIgnored private var socket: URLSessionWebSocketTask?
    @ObservationIgnored private var started = false
    @ObservationIgnored private var retry = 0
    @ObservationIgnored private var ping: Timer?

    init(api: BenchAPI) {
        self.api = api
        if let d = UserDefaults.standard.data(forKey: "shares"), let s = try? JSONDecoder().decode([Share].self, from: d) { shares = s }
    }

    /// This Mac's id with the server: made once, kept.
    private var deviceID: String {
        if let id = UserDefaults.standard.string(forKey: "deviceID") { return id }
        let id = UUID().uuidString.lowercased()
        UserDefaults.standard.set(id, forKey: "deviceID")
        return id
    }

    func start() {
        guard !started else { return }
        started = true
        connect()
        ping = Timer.scheduledTimer(withTimeInterval: 25, repeats: true) { [weak self] _ in
            Task { @MainActor in self?.send(["type": "ping"]) }
        }
    }

    func stop() {
        started = false
        ping?.invalidate()
        socket?.cancel(with: .goingAway, reason: nil)
        socket = nil
        connected = false
    }

    private func connect() {
        guard started else { return }
        var r = api.request("/api/linked-devices/link")
        r.url = URL(string: api.socketURL.absoluteString.replacingOccurrences(of: "/api/ws", with: "/api/linked-devices/link"))
        let ws = api.session.webSocketTask(with: r)
        ws.maximumMessageSize = 32 * 1024 * 1024
        socket = ws
        ws.resume()
        receive(ws)
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? ""
        send(["type": "hello", "id": deviceID, "name": Host.current().localizedName ?? "Mac", "version": version, "shares": wire()])
    }

    private func receive(_ ws: URLSessionWebSocketTask) {
        ws.receive { [weak self] result in
            Task { @MainActor in
                guard let self, self.socket === ws else { return }
                switch result {
                case .success(let msg):
                    if !self.connected { self.connected = true; self.retry = 0 }
                    var data: Data?
                    switch msg {
                    case .string(let s): data = Data(s.utf8)
                    case .data(let d): data = d
                    @unknown default: break
                    }
                    if let data, let m = try? JSONSerialization.jsonObject(with: data) as? [String: Any] { self.handle(m) }
                    self.receive(ws)
                case .failure:
                    self.socket = nil
                    self.connected = false
                    self.retry += 1
                    let wait = min(30.0, pow(2, Double(min(self.retry, 5))))
                    DispatchQueue.main.asyncAfter(deadline: .now() + wait) { [weak self] in self?.connect() }
                }
            }
        }
    }

    private func send(_ o: [String: Any]) {
        guard let socket, let d = try? JSONSerialization.data(withJSONObject: o), let s = String(data: d, encoding: .utf8) else { return }
        socket.send(.string(s)) { _ in }
    }

    private func wire() -> [[String: Any]] {
        shares.map { s in
            var o: [String: Any] = ["id": s.id, "kind": s.kind, "name": s.name, "access": s.access, "rule": s.rule]
            if let p = s.shownPath { o["path"] = p }
            return o
        }
    }

    private func save() {
        if let d = try? JSONEncoder().encode(shares) { UserDefaults.standard.set(d, forKey: "shares") }
        send(["type": "shares", "shares": wire()])
    }

    private func handle(_ m: [String: Any]) {
        switch m["type"] as? String {
        case "call":
            guard let id = m["id"] as? String else { return }
            let tool = m["tool"] as? String ?? ""
            let share = shares.first { $0.id == (m["share"] as? String) }
            let args = m["args"] as? [String: Any] ?? [:]
            Task.detached {
                var out: [String: Any] = ["type": "result", "id": id]
                do {
                    guard let share else { throw ShareError("That is no longer shared.") }
                    let r = try await ShareRunner.run(tool, share: share, args: args)
                    out["ok"] = true
                    if let img = r.image { out["image"] = img } else { out["text"] = r.text }
                } catch {
                    out["ok"] = false
                    out["error"] = (error as? ShareError)?.message ?? error.localizedDescription
                }
                await MainActor.run { self.send(out) }
            }
        case "ask":
            guard let a = m["ask"] as? [String: Any], let id = a["id"] as? String, !asks.contains(where: { $0.id == id }) else { return }
            let ask = ShareAsk(id: id, kind: a["kind"] as? String ?? "folder", path: a["path"] as? String ?? "", access: a["access"] as? String ?? "read", why: a["why"] as? String ?? "")
            asks.append(ask)
            let c = UNMutableNotificationContent()
            c.title = "Bench asks to use \(ask.kind == "screen" ? "your screen" : ask.path)"
            c.body = ask.why.isEmpty ? "Open Settings › Shared with Bench to decide." : ask.why
            c.userInfo = ["shareAsk": id]
            UNUserNotificationCenter.current().add(UNNotificationRequest(identifier: "ask-\(id)", content: c, trigger: nil))
        case "cancel_ask":
            asks.removeAll { $0.id == (m["id"] as? String) }
        default:
            break
        }
    }

    // MARK: what the person does here

    func add(_ s: Share) {
        shares.removeAll { $0.kind == s.kind && $0.path == s.path && s.kind != "screen" }
        if s.kind == "screen" { shares.removeAll { $0.kind == "screen" } }
        shares.append(s)
        save()
    }

    func remove(_ s: Share) {
        shares.removeAll { $0.id == s.id }
        save()
    }

    func setRule(_ s: Share, _ rule: String) {
        guard let i = shares.firstIndex(where: { $0.id == s.id }) else { return }
        shares[i].rule = rule
        save()
    }

    /// The person decided an ask: approved becomes a share (asking first, until they say otherwise).
    func answer(_ a: ShareAsk, approved: Bool) {
        asks.removeAll { $0.id == a.id }
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: ["ask-\(a.id)"])
        if approved {
            let path = (a.path as NSString).expandingTildeInPath
            switch a.kind {
            case "screen": add(Share(id: "screen", kind: "screen", name: "Screen", path: nil, access: "look", rule: "ask"))
            case "app":
                let app = ShareRunner.findApp(a.path) ?? path
                add(Share(id: Self.slug(a.path), kind: "app", name: URL(fileURLWithPath: app).deletingPathExtension().lastPathComponent, path: app, access: "open", rule: "ask"))
            case "run":
                let cmd = ShareRunner.which(a.path) ?? path
                add(Share(id: Self.slug(a.path), kind: "run", name: a.path == "adb" ? "Android emulators" : a.path, path: cmd, access: "run", rule: "ask"))
            default:
                add(Share(id: Self.slug(URL(fileURLWithPath: path).lastPathComponent), kind: "folder", name: URL(fileURLWithPath: path).lastPathComponent, path: path, access: a.access == "write" ? "write" : "read", rule: "ask"))
            }
        }
        send(["type": "answer", "id": a.id, "approved": approved])
    }

    /// A short id the server and sessions use: "kron", "xcode", "adb", unique among the shares.
    static func slug(_ s: String) -> String {
        let base = s.lowercased().replacingOccurrences(of: "[^a-z0-9]+", with: "-", options: .regularExpression).trimmingCharacters(in: CharacterSet(charactersIn: "-"))
        return base.isEmpty ? UUID().uuidString.prefix(6).lowercased() : base
    }

    func uniqueID(_ s: String) -> String {
        let base = Self.slug(s)
        var id = base, n = 2
        while shares.contains(where: { $0.id == id }) { id = "\(base)-\(n)"; n += 1 }
        return id
    }
}

// MARK: - Doing a call

struct ShareError: Error { let message: String; init(_ m: String) { message = m } }

enum ShareRunner {
    struct Out { var text = ""; var image: String? }

    static func run(_ tool: String, share: Share, args: [String: Any]) async throws -> Out {
        switch (tool, share.kind) {
        case ("list", "folder"):
            let dir = try inside(share, args["path"] as? String ?? "")
            let items = try FileManager.default.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.isDirectoryKey, .fileSizeKey], options: [.skipsHiddenFiles])
            let lines = items.sorted { $0.lastPathComponent < $1.lastPathComponent }.prefix(500).map { u -> String in
                let v = try? u.resourceValues(forKeys: [.isDirectoryKey, .fileSizeKey])
                return v?.isDirectory == true ? u.lastPathComponent + "/" : "\(u.lastPathComponent)  \(v?.fileSize ?? 0) bytes"
            }
            return Out(text: lines.isEmpty ? "(empty)" : lines.joined(separator: "\n"))
        case ("read", "folder"):
            let f = try inside(share, args["path"] as? String ?? "")
            let d = try Data(contentsOf: f)
            guard d.count <= 256 * 1024 else { throw ShareError("That file is \(d.count / 1024) KB; files up to 256 KB can be read.") }
            guard let s = String(data: d, encoding: .utf8) else { throw ShareError("That is not a text file.") }
            return Out(text: s)
        case ("write", "folder"):
            guard share.access == "write" else { throw ShareError("\(share.name) is shared read only.") }
            let f = try inside(share, args["path"] as? String ?? "")
            try FileManager.default.createDirectory(at: f.deletingLastPathComponent(), withIntermediateDirectories: true)
            try Data((args["content"] as? String ?? "").utf8).write(to: f, options: .atomic)
            return Out(text: "Wrote \(f.path.replacingOccurrences(of: NSHomeDirectory(), with: "~")).")
        case ("screenshot", "screen"):
            let tmp = FileManager.default.temporaryDirectory.appendingPathComponent("bench-shot-\(UUID().uuidString).png")
            defer { try? FileManager.default.removeItem(at: tmp) }
            _ = try await exec("/usr/sbin/screencapture", ["-x", "-t", "png", tmp.path], timeout: 20)
            guard let d = try? Data(contentsOf: tmp) else { throw ShareError("The screenshot failed. Allow Bench under System Settings › Privacy & Security › Screen Recording.") }
            return Out(image: d.base64EncodedString())
        case ("open", "app"):
            guard let app = share.path else { throw ShareError("This app is missing.") }
            var a = ["-a", app]
            if let f = args["file"] as? String, !f.isEmpty { a.append((f as NSString).expandingTildeInPath) }
            _ = try await exec("/usr/bin/open", a, timeout: 30)
            return Out(text: "Opened \(share.name).")
        case ("run", "run"):
            guard let cmd = share.path else { throw ShareError("This command is missing.") }
            let a = (args["args"] as? [Any] ?? []).map { "\($0)" }
            return Out(text: try await exec(cmd, a, timeout: 120))
        default:
            throw ShareError("\(share.name) is shared as \(share.accessLabel.lowercased()); that call does not apply to it.")
        }
    }

    /// A path inside a shared folder, refused if it leads outside it (by .. or by a link).
    static func inside(_ share: Share, _ rel: String) throws -> URL {
        guard let rootPath = share.path else { throw ShareError("This folder is missing.") }
        let root = URL(fileURLWithPath: (rootPath as NSString).expandingTildeInPath).resolvingSymlinksInPath().standardizedFileURL
        let expanded = (rel as NSString).expandingTildeInPath
        let target = (expanded.hasPrefix("/") ? URL(fileURLWithPath: expanded) : root.appendingPathComponent(rel)).resolvingSymlinksInPath().standardizedFileURL
        guard target.path == root.path || target.path.hasPrefix(root.path + "/") else { throw ShareError("That is outside \(share.shownPath ?? share.name), which is all that is shared.") }
        return target
    }

    /// Runs a program directly (no shell), with a time limit, and returns its output's tail.
    static func exec(_ path: String, _ args: [String], timeout: TimeInterval) async throws -> String {
        try await withCheckedThrowingContinuation { cont in
            let p = Process()
            p.executableURL = URL(fileURLWithPath: path)
            p.arguments = args
            var env = ProcessInfo.processInfo.environment
            env["PATH"] = extraPath + ":" + (env["PATH"] ?? "/usr/bin:/bin")
            p.environment = env
            let pipe = Pipe()
            p.standardOutput = pipe
            p.standardError = pipe
            let buffer = OutputBuffer()
            pipe.fileHandleForReading.readabilityHandler = { h in buffer.append(h.availableData) }
            p.terminationHandler = { proc in
                pipe.fileHandleForReading.readabilityHandler = nil
                buffer.append(pipe.fileHandleForReading.readDataToEndOfFile())
                let text = buffer.text
                cont.resume(returning: (proc.terminationStatus == 0 ? "" : "Exit \(proc.terminationStatus)\n") + (text.isEmpty ? "(no output)" : text))
            }
            do { try p.run() } catch { cont.resume(throwing: ShareError("Could not run \(path): \(error.localizedDescription)")); return }
            DispatchQueue.global().asyncAfter(deadline: .now() + timeout) { if p.isRunning { p.terminate() } }
        }
    }

    static let extraPath = [NSHomeDirectory() + "/Library/Android/sdk/platform-tools", NSHomeDirectory() + "/Library/Android/sdk/emulator", "/opt/homebrew/bin", "/usr/local/bin"].joined(separator: ":")

    /// A command's full path, as the person's shell would find it, or nil.
    static func which(_ name: String) -> String? {
        let n = (name as NSString).expandingTildeInPath
        if n.hasPrefix("/") { return FileManager.default.isExecutableFile(atPath: n) ? n : nil }
        for dir in (extraPath + ":/usr/bin:/bin:/usr/sbin").split(separator: ":") {
            let p = "\(dir)/\(n)"
            if FileManager.default.isExecutableFile(atPath: p) { return p }
        }
        return nil
    }

    /// An app's bundle path from a name like "Xcode", or nil.
    static func findApp(_ name: String) -> String? {
        let n = name.hasSuffix(".app") ? name : name + ".app"
        for dir in ["/Applications", "/System/Applications", NSHomeDirectory() + "/Applications"] {
            let p = "\(dir)/\(n)"
            if FileManager.default.fileExists(atPath: p) { return p }
        }
        return nil
    }
}

/// Output kept to its last 20,000 characters, filled from the pipe's own thread.
private final class OutputBuffer: @unchecked Sendable {
    private var data = Data()
    private let lock = NSLock()
    func append(_ d: Data) {
        lock.lock(); defer { lock.unlock() }
        data.append(d)
        if data.count > 40_000 { data = data.suffix(20_000) }
    }
    var text: String {
        lock.lock(); defer { lock.unlock() }
        return String(decoding: data.suffix(20_000), as: UTF8.self)
    }
}
