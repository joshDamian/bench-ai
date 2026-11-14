import Foundation
import Security

/// The device token lives in the login keychain, not in defaults.
enum Keychain {
    private static let service = "ai.bench.app.token"

    static func read(account: String) -> String? {
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let d = out as? Data else { return nil }
        return String(data: d, encoding: .utf8)
    }

    static func write(_ value: String, account: String) {
        delete(account: account)
        let q: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]
        SecItemAdd(q as CFDictionary, nil)
    }

    static func delete(account: String) {
        let q: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        SecItemDelete(q as CFDictionary)
    }
}

/// The saved server: its address in defaults, its token in the keychain.
enum SavedServer {
    private static let key = "serverURL"
    /// Every server this Mac has paired with, so you can switch between them (Node and Go, say).
    private static let listKey = "servers"

    static func load() -> ServerConfig? {
        guard let base = UserDefaults.standard.string(forKey: key) else { return nil }
        return ServerConfig(baseURL: base, token: Keychain.read(account: base))
    }

    /// The saved servers' addresses, the current one included.
    static func all() -> [String] {
        var list = UserDefaults.standard.stringArray(forKey: listKey) ?? []
        if let cur = UserDefaults.standard.string(forKey: key), !list.contains(cur) { list.insert(cur, at: 0) }
        return list
    }

    static func config(_ base: String) -> ServerConfig { ServerConfig(baseURL: base, token: Keychain.read(account: base)) }

    static func save(_ c: ServerConfig) {
        UserDefaults.standard.set(c.baseURL, forKey: key)
        var list = all()
        if !list.contains(c.baseURL) { list.append(c.baseURL) }
        UserDefaults.standard.set(list, forKey: listKey)
        if let t = c.token { Keychain.write(t, account: c.baseURL) } else { Keychain.delete(account: c.baseURL) }
    }

    /// Forgets the current server and returns the next saved one, if any.
    @discardableResult
    static func forget() -> ServerConfig? {
        guard let base = UserDefaults.standard.string(forKey: key) else { return nil }
        Keychain.delete(account: base)
        let rest = all().filter { $0 != base }
        UserDefaults.standard.set(rest, forKey: listKey)
        UserDefaults.standard.removeObject(forKey: key)
        guard let next = rest.first else { return nil }
        UserDefaults.standard.set(next, forKey: key)
        return config(next)
    }

    static func setCurrent(_ base: String) { UserDefaults.standard.set(base, forKey: key) }
}
