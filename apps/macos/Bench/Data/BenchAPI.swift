import Foundation

/// A refusal from the server, in its own words.
struct BenchError: LocalizedError {
    let status: Int
    let message: String
    var errorDescription: String? { message }
    var unauthorized: Bool { status == 401 }
    var offline: Bool { status == 0 }
}

struct UploadRef: Hashable {
    let file: String
    let name: String
    let type: String
}

/// The HTTP half of the server's API (web/api.mjs). A paired Mac sends its token as a
/// Bearer header; a server on this Mac's loopback needs none.
final class BenchAPI: @unchecked Sendable {
    let config: ServerConfig
    let session: URLSession

    init(config: ServerConfig) {
        self.config = config
        let c = URLSessionConfiguration.default
        c.timeoutIntervalForRequest = 60
        c.waitsForConnectivity = false
        session = URLSession(configuration: c)
    }

    func request(_ path: String, method: String = "GET") -> URLRequest {
        var r = URLRequest(url: URL(string: config.baseURL + path)!)
        r.httpMethod = method
        if let t = config.token { r.setValue("Bearer \(t)", forHTTPHeaderField: "Authorization") }
        return r
    }

    @discardableResult
    func call(_ path: String, method: String = "GET", body: [String: Any]? = nil) async throws -> Data {
        var r = request(path, method: method)
        if let body {
            r.httpBody = try JSONSerialization.data(withJSONObject: body)
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        return try await send(r)
    }

    private func send(_ r: URLRequest) async throws -> Data {
        let data: Data, res: URLResponse
        do {
            (data, res) = try await session.data(for: r)
        } catch {
            throw BenchError(status: 0, message: "Can't reach the server: \(error.localizedDescription)")
        }
        let code = (res as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            let msg = (try? JSONDecoder().decode(JSONValue.self, from: data))?["error"].string
            throw BenchError(status: code, message: msg ?? "Request failed (\(code))")
        }
        return data
    }

    private func decode<T: Decodable>(_ t: T.Type, _ data: Data) throws -> T {
        do { return try JSONDecoder().decode(t, from: data) }
        catch { throw BenchError(status: 500, message: "Unexpected answer from the server: \(error.localizedDescription)") }
    }

    static func enc(_ s: String) -> String { s.addingPercentEncoding(withAllowedCharacters: .urlPathAllowed.subtracting(CharacterSet(charactersIn: "/?&=+"))) ?? s }

    // MARK: calls

    func state() async throws -> StateSnapshot { try decode(StateSnapshot.self, await call("/api/state")) }

    func session(_ id: String, after: Int? = nil) async throws -> SessionDetail {
        try decode(SessionDetail.self, await call("/api/sessions/\(Self.enc(id))" + (after.map { "?after=\($0)" } ?? "")))
    }

    func olderEvents(_ id: String, before: Int) async throws -> [SessionEvent] {
        try decode([SessionEvent].self, await call("/api/sessions/\(Self.enc(id))/events?before=\(before)"))
    }

    func thinking(_ id: String, event: Int) async throws -> [Int: String] {
        let v = try decode(JSONValue.self, await call("/api/sessions/\(Self.enc(id))/events/\(event)/thinking"))
        var out: [Int: String] = [:]
        for b in v["blocks"].array { if let i = b["index"].int { out[i] = b["thinking"].string ?? "" } }
        return out
    }

    func send(_ id: String, text: String, uploads: [UploadRef] = []) async throws {
        try await call("/api/sessions/\(Self.enc(id))/messages", method: "POST", body: [
            "text": text, "images": [Any](), "uploads": uploads.map { ["file": $0.file, "type": $0.type] },
        ])
    }

    func interrupt(_ id: String) async throws { try await call("/api/sessions/\(Self.enc(id))/interrupt", method: "POST", body: [:]) }
    func setMode(_ id: String, _ mode: Mode) async throws { try await call("/api/sessions/\(Self.enc(id))", method: "PATCH", body: ["mode": mode.rawValue]) }
    func rename(_ id: String, _ title: String) async throws { try await call("/api/sessions/\(Self.enc(id))", method: "PATCH", body: ["title": title]) }
    func archive(_ id: String, _ archived: Bool = true) async throws { try await call("/api/sessions/\(Self.enc(id))/archive", method: "POST", body: ["archived": archived]) }

    func createSession(project: String, task: String?, mode: Mode) async throws -> Session {
        var body: [String: Any] = ["projectId": project, "mode": mode.rawValue]
        if let task { body["taskId"] = task }
        return try decode(Session.self, await call("/api/sessions", method: "POST", body: body))
    }

    /// The server starts the task's first session and sends it the prompt.
    func createTask(project: String, title: String, prompt: String, mode: Mode) async throws -> Session {
        var body: [String: Any] = ["projectId": project, "title": title, "mode": mode.rawValue]
        if !prompt.isEmpty { body["prompt"] = prompt }
        return try decode(TaskCreated.self, await call("/api/tasks", method: "POST", body: body)).session
    }

    func answer(_ approvalId: String, _ body: [String: Any]) async throws {
        try await call("/api/approvals/\(Self.enc(approvalId))", method: "POST", body: body)
    }

    /// Streams a file from disk to a session's attachments as raw bytes.
    func upload(_ sessionId: String, file url: URL, progress: @escaping @Sendable (Double) -> Void) async throws -> UploadRef {
        let name = url.lastPathComponent
        let type = UTTypeName.of(url)
        var r = request("/api/sessions/\(Self.enc(sessionId))/uploads?name=\(Self.enc(name))&type=\(Self.enc(type))", method: "POST")
        r.setValue("application/octet-stream", forHTTPHeaderField: "Content-Type")
        let delegate = UploadProgress(progress)
        let (data, res) = try await session.upload(for: r, fromFile: url, delegate: delegate)
        let code = (res as? HTTPURLResponse)?.statusCode ?? 0
        let v = try? JSONDecoder().decode(JSONValue.self, from: data)
        guard (200..<300).contains(code), let file = v["file"].string else {
            throw BenchError(status: code, message: v["error"].string ?? "Upload failed (\(code))")
        }
        return UploadRef(file: file, name: v["name"].string ?? name, type: type)
    }

    var socketURL: URL {
        URL(string: config.baseURL.replacingOccurrences(of: "^http", with: "ws", options: .regularExpression) + "/api/ws")!
    }

    /// Trades a pairing code for a device token.
    static func pair(_ link: PairLink, name: String) async throws -> ServerConfig {
        var r = URLRequest(url: URL(string: link.baseURL + "/api/pair")!)
        r.httpMethod = "POST"
        r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        r.httpBody = try JSONSerialization.data(withJSONObject: ["code": link.code, "name": name])
        // The cookie is read from the header, not stored: this app sends the token itself.
        let s = URLSession(configuration: .ephemeral)
        let data: Data, res: URLResponse
        do { (data, res) = try await s.data(for: r) }
        catch { throw BenchError(status: 0, message: "Can't reach \(link.baseURL): \(error.localizedDescription)") }
        let http = res as? HTTPURLResponse
        guard let http, (200..<300).contains(http.statusCode) else {
            let msg = (try? JSONDecoder().decode(JSONValue.self, from: data))?["error"].string
            throw BenchError(status: http?.statusCode ?? 0, message: msg ?? "Pairing failed")
        }
        guard let cookie = http.value(forHTTPHeaderField: "Set-Cookie"), let token = Pairing.token(fromCookie: cookie) else {
            throw BenchError(status: 500, message: "The server paired but sent no token")
        }
        return ServerConfig(baseURL: link.baseURL, token: token)
    }

    /// Whether this address answers and already lets us in.
    static func ping(_ c: ServerConfig) async -> (reachable: Bool, allowed: Bool) {
        guard let data = try? await BenchAPI(config: c).call("/api/ping"), let v = try? JSONDecoder().decode(JSONValue.self, from: data) else { return (false, false) }
        // A loopback request is let in without being paired.
        return (true, v["paired"].bool || c.isLoopback)
    }
}

private final class UploadProgress: NSObject, URLSessionTaskDelegate, @unchecked Sendable {
    let report: @Sendable (Double) -> Void
    init(_ report: @escaping @Sendable (Double) -> Void) { self.report = report }
    func urlSession(_ session: URLSession, task: URLSessionTask, didSendBodyData bytesSent: Int64, totalBytesSent: Int64, totalBytesExpectedToSend: Int64) {
        if totalBytesExpectedToSend > 0 { report(Double(totalBytesSent) / Double(totalBytesExpectedToSend)) }
    }
}

import UniformTypeIdentifiers

enum UTTypeName {
    static func of(_ url: URL) -> String {
        UTType(filenameExtension: url.pathExtension)?.preferredMIMEType ?? "application/octet-stream"
    }
}
