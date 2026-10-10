import Foundation
import CryptoKit
import LibSignalClient

/// Device linking via QR code or copyable code.
/// New device generates a link code, existing device validates and approves.
/// Mirrors src/v2/device/link.js
public struct DeviceLink {

    /// Data contained in a link code.
    public struct LinkCode: Codable, Sendable {
        public let deviceId: String
        public let challenge: String          // base64 (16 random bytes)
        public let timestamp: UInt64

        /// Maximum age before a link code expires (default 5 minutes).
        public static let defaultMaxAge: UInt64 = 5 * 60 * 1000
    }

    /// Result of validating a link code.
    public enum ValidationResult: Sendable {
        case valid(LinkCode)
        case expired
        case invalid(String)
    }

    // MARK: - Generate (new device creates this)

    /// Generate a link code for device linking.
    /// The new device displays this as a QR code or copyable text.
    public static func generateLinkCode(deviceId: String) -> String {
        // 16 random bytes for challenge
        var challengeBytes = [UInt8](repeating: 0, count: 16)
        for i in 0..<16 { challengeBytes[i] = UInt8.random(in: 0...255) }

        let code = LinkCode(
            deviceId: deviceId,
            challenge: Data(challengeBytes).base64EncodedString(),
            timestamp: UInt64(Date().timeIntervalSince1970 * 1000)
        )

        guard let jsonData = try? JSONEncoder().encode(code) else { return "" }
        return Base58.encodeString(jsonData)
    }

    // MARK: - Parse (existing device reads this)

    /// Parse a link code string back into structured data.
    public static func parseLinkCode(_ codeString: String) -> LinkCode? {
        guard let jsonData = Base58.decodeString(codeString) else { return nil }
        return try? JSONDecoder().decode(LinkCode.self, from: jsonData)
    }

    // MARK: - Validate

    /// Validate a link code: check format, required fields, and expiry.
    public static func validateLinkCode(_ codeString: String, maxAge: UInt64 = LinkCode.defaultMaxAge) -> ValidationResult {
        guard let code = parseLinkCode(codeString) else {
            return .invalid("Could not parse link code")
        }

        // Check required fields
        guard !code.deviceId.isEmpty,
              !code.challenge.isEmpty else {
            return .invalid("Missing required fields")
        }

        // Check expiry.
        //
        // ⚠️ The subtraction MUST be guarded, never written as `now - code.timestamp > maxAge`.
        // `code.timestamp` comes out of a SCANNED QR payload — it is whatever the other device put
        // there, and this function is reached before anything has been authenticated. A future-dated
        // code makes that expression negative, and `UInt64` subtraction **traps**. A trap is not
        // catchable, so nothing upstream can contain it: scanning a hostile QR code kills the app.
        //
        // Compare first and subtract only on the branch where the subtraction is defined.
        // A future-dated code is treated as fresh rather than rejected.
        let now = UInt64(Date().timeIntervalSince1970 * 1000)
        if code.timestamp < now && now - code.timestamp > maxAge {
            return .expired
        }

        return .valid(code)
    }

    // MARK: - Challenge Verification

    /// Constant-time challenge comparison (prevents timing attacks).
    public static func verifyChallenge(expected: Data, received: Data) -> Bool {
        guard expected.count == received.count else { return false }
        return constantTimeEqual(expected, received)
    }

    /// Extract the challenge bytes from a parsed link code.
    public static func extractChallenge(_ code: LinkCode) -> Data? {
        Data(base64Encoded: code.challenge)
    }
}

// MARK: - Base58 Encoding

/// Base58 encoding/decoding using Bitcoin alphabet (no 0, O, I, l).
/// Mirrors src/v2/crypto/base58.js
public struct Base58 {
    private static let alphabet = Array("123456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz")
    private static let base = 58

    /// Encode raw bytes to Base58 string.
    public static func encode(_ bytes: [UInt8]) -> String {
        if bytes.isEmpty { return "" }

        // Count leading zeros
        var leadingZeros = 0
        for byte in bytes {
            if byte == 0 { leadingZeros += 1 }
            else { break }
        }

        // Convert bytes to a big number (using [UInt8] arithmetic)
        var digits = [0]  // Base58 digits, stored in reverse
        for byte in bytes {
            var carry = Int(byte)
            for i in 0..<digits.count {
                carry += digits[i] * 256
                digits[i] = carry % base
                carry /= base
            }
            while carry > 0 {
                digits.append(carry % base)
                carry /= base
            }
        }

        // Build result
        var result = String(repeating: "1", count: leadingZeros)
        for digit in digits.reversed() {
            result.append(alphabet[digit])
        }
        return result
    }

    /// Decode Base58 string to raw bytes.
    public static func decode(_ string: String) -> [UInt8]? {
        if string.isEmpty { return [] }

        // Count leading '1's
        var leadingOnes = 0
        for char in string {
            if char == "1" { leadingOnes += 1 }
            else { break }
        }

        // Build alphabet map
        var alphabetMap = [Character: Int]()
        for (i, c) in alphabet.enumerated() {
            alphabetMap[c] = i
        }

        // Convert base58 to bytes
        var bytes = [0]  // byte values, stored in reverse
        for char in string {
            guard let value = alphabetMap[char] else { return nil }
            var carry = value
            for i in 0..<bytes.count {
                carry += bytes[i] * base
                bytes[i] = carry % 256
                carry /= 256
            }
            while carry > 0 {
                bytes.append(carry % 256)
                carry /= 256
            }
        }

        // Build result with leading zeros
        var result = [UInt8](repeating: 0, count: leadingOnes)
        for byte in bytes.reversed() {
            result.append(UInt8(byte))
        }
        return result
    }

    /// Encode a JSON-serialized Data to Base58 string.
    public static func encodeString(_ data: Data) -> String {
        encode(Array(data))
    }

    /// Decode a Base58 string to Data.
    public static func decodeString(_ string: String) -> Data? {
        guard let bytes = decode(string) else { return nil }
        return Data(bytes)
    }
}
