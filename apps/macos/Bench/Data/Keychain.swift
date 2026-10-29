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

    static func load() -> ServerConfig? {
        guard let base = UserDefaults.standard.string(forKey: key) else { return nil }
        return ServerConfig(baseURL: base, token: Keychain.read(account: base))
    }

    static func save(_ c: ServerConfig) {
        UserDefaults.standard.set(c.baseURL, forKey: key)
        if let t = c.token { Keychain.write(t, account: c.baseURL) } else { Keychain.delete(account: c.baseURL) }
    }

    static func forget() {
        if let base = UserDefaults.standard.string(forKey: key) { Keychain.delete(account: base) }
        UserDefaults.standard.removeObject(forKey: key)
    }
}
