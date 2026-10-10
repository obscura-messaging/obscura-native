import Foundation
import Security

/// Manages the SQLCipher database encryption key per user.
/// iOS equivalent of Kotlin's DatabaseSecretProvider.
///
/// Pattern (matches Signal):
/// 1. Generate a 32-byte random key (high entropy)
/// 2. Store in iOS Keychain (kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly)
/// 3. On launch, fetch from Keychain and pass to SQLCipher via GRDB's usePassphrase()
///
/// KDF iterations are set to 1 since the key is already 256 bits of entropy (same as Signal).
public enum DatabaseSecret {

    /// Get or create a 32-byte encryption key for a specific user.
    /// Stored in the iOS Keychain, scoped by userId.
    ///
    /// - Parameter accessGroup: a shared keychain access group
    ///   (e.g. `"$(AppIdentifierPrefix)com.example.shared"`). Pass `nil` — the default — to keep the
    ///   item in the app's own default group.
    ///
    ///   A Notification Service Extension runs under a different bundle id, so it can only read
    ///   the database key from a shared access group. A keychain item cannot be moved between
    ///   groups in place. See `docs/NSE_PREREQUISITES.md`.
    public static func getOrCreate(userId: String, accessGroup: String? = nil) -> Data {
        let service = "dev.barrelmaker.obscura.kit.dbsecret"
        let account = "db_key_\(userId)"

        // Try to load existing key
        var query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
        ]
        if let accessGroup { query[kSecAttrAccessGroup as String] = accessGroup }
        var result: AnyObject?
        if SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
           let data = result as? Data, data.count == 32 {
            return data
        }

        // Generate new 32-byte random key
        var key = Data(count: 32)
        key.withUnsafeMutableBytes { buffer in
            _ = SecRandomCopyBytes(kSecRandomDefault, 32, buffer.baseAddress!)
        }

        // Store in Keychain
        var addQuery: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: account,
            kSecValueData as String: key,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly,
        ]
        if let accessGroup { addQuery[kSecAttrAccessGroup as String] = accessGroup }
        SecItemDelete(query as CFDictionary) // remove stale if any
        SecItemAdd(addQuery as CFDictionary, nil)

        return key
    }
}
