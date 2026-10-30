import Foundation

/// Where the server is and how this Mac proves itself to it.
struct ServerConfig: Equatable {
    var baseURL: String
    var token: String?

    /// A server on this Mac's loopback answers its own machine without a token.
    var isLoopback: Bool { Pairing.isLoopback(baseURL) }
}

struct PairLink: Equatable {
    let baseURL: String
    let code: String
}

enum Pairing {
    /// Reads `https://host/#/pair/CODE`, the link `bench pair` prints and its QR holds.
    static func parse(_ input: String, code separate: String? = nil) -> PairLink? {
        var raw = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return nil }
        if !raw.contains("://") { raw = "https://" + raw }
        guard let url = URLComponents(string: raw), let host = url.host, let scheme = url.scheme?.lowercased(),
              scheme == "http" || scheme == "https" else { return nil }
        let port = url.port.map { ":\($0)" } ?? ""
        let base = "\(scheme)://\(host)\(port)"
        var code: String?
        if let fragment = url.fragment, let r = fragment.range(of: #"^/?pair/([A-Za-z0-9_-]+)"#, options: .regularExpression) {
            code = String(fragment[r]).components(separatedBy: "pair/").last
        }
        if code == nil, let s = separate?.trimmingCharacters(in: .whitespaces), !s.isEmpty { code = s }
        guard let code else { return nil }
        return PairLink(baseURL: base, code: code)
    }

    /// A bare address, for a server with no pairing in front of it (this Mac's own).
    static func normalize(_ input: String) -> String? {
        var raw = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !raw.isEmpty else { return nil }
        if !raw.contains("://") { raw = (raw.hasPrefix("localhost") || raw.hasPrefix("127.") ? "http://" : "https://") + raw }
        guard let url = URLComponents(string: raw), let host = url.host, let scheme = url.scheme else { return nil }
        return "\(scheme)://\(host)\(url.port.map { ":\($0)" } ?? "")"
    }

    /// The token from `Set-Cookie: bench=...`.
    static func token(fromCookie header: String) -> String? {
        guard let r = header.range(of: #"(?:^|[;,]\s*)(?:bench|aic)=([^;]+)"#, options: .regularExpression) else { return nil }
        let pair = header[r].trimmingCharacters(in: CharacterSet(charactersIn: ";, "))
        guard let value = pair.split(separator: "=", maxSplits: 1).last else { return nil }
        return String(value).removingPercentEncoding ?? String(value)
    }

    static func isLoopback(_ base: String) -> Bool {
        guard let host = URLComponents(string: base)?.host else { return false }
        return ["localhost", "127.0.0.1", "::1", "[::1]"].contains(host)
    }
}
