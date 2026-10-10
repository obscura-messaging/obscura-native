import XCTest
@testable import ObscuraKit

/// Unit tests for device linking — link code generation, parsing, validation, challenge verification.
/// Pure crypto, no server, no network.
final class DeviceLinkTests: XCTestCase {

    // MARK: - Base58

    func testBase58_roundTrip() {
        let original = "Hello, World!"
        let encoded = Base58.encode(Array(original.utf8))
        XCTAssertFalse(encoded.isEmpty)

        let decoded = Base58.decode(encoded)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(String(bytes: decoded!, encoding: .utf8), original)
    }

    func testBase58_emptyInput() {
        XCTAssertEqual(Base58.encode([]), "")
        XCTAssertEqual(Base58.decode(""), [])
    }

    func testBase58_leadingZeros() {
        // Leading zero bytes should produce leading '1' chars
        let input: [UInt8] = [0, 0, 1, 2, 3]
        let encoded = Base58.encode(input)
        XCTAssertTrue(encoded.hasPrefix("11"))

        let decoded = Base58.decode(encoded)
        XCTAssertEqual(decoded, input)
    }

    func testBase58_invalidCharReturnsNil() {
        let result = Base58.decode("0OIl") // chars not in Base58 alphabet
        XCTAssertNil(result)
    }

    func testBase58_jsonRoundTrip() {
        let json = #"{"key":"value","num":42}"#
        let encoded = Base58.encodeString(Data(json.utf8))
        let decoded = Base58.decodeString(encoded)
        XCTAssertNotNil(decoded)
        XCTAssertEqual(String(data: decoded!, encoding: .utf8), json)
    }

    // MARK: - Link Code Generation

    func testGenerateLinkCode_notEmpty() {
        let code = DeviceLink.generateLinkCode(deviceId: "device-123")
        XCTAssertFalse(code.isEmpty)
    }

    func testGenerateLinkCode_parsesBack() {
        let code = DeviceLink.generateLinkCode(deviceId: "device-123")

        let parsed = DeviceLink.parseLinkCode(code)
        XCTAssertNotNil(parsed)
        XCTAssertEqual(parsed?.deviceId, "device-123")
    }

    func testGenerateLinkCode_hasFreshTimestamp() {
        let beforeMs = UInt64(Date().timeIntervalSince1970 * 1000)

        let code = DeviceLink.generateLinkCode(deviceId: "d1")

        let parsed = DeviceLink.parseLinkCode(code)!
        let afterMs = UInt64(Date().timeIntervalSince1970 * 1000)

        XCTAssertGreaterThanOrEqual(parsed.timestamp, beforeMs)
        XCTAssertLessThanOrEqual(parsed.timestamp, afterMs)
    }

    func testGenerateLinkCode_challengeIs16Bytes() {
        let code = DeviceLink.generateLinkCode(deviceId: "d1")

        let parsed = DeviceLink.parseLinkCode(code)!
        let challenge = DeviceLink.extractChallenge(parsed)
        XCTAssertNotNil(challenge)
        XCTAssertEqual(challenge!.count, 16)
    }

    func testGenerateLinkCode_uniqueChallenges() {
        let code1 = DeviceLink.generateLinkCode(deviceId: "d1")
        let code2 = DeviceLink.generateLinkCode(deviceId: "d1")

        let c1 = DeviceLink.extractChallenge(DeviceLink.parseLinkCode(code1)!)
        let c2 = DeviceLink.extractChallenge(DeviceLink.parseLinkCode(code2)!)
        XCTAssertNotEqual(c1, c2, "Each link code should have a unique challenge")
    }

    // MARK: - Validation

    func testValidate_freshCodeIsValid() {
        let code = DeviceLink.generateLinkCode(deviceId: "d1")

        if case .valid(let parsed) = DeviceLink.validateLinkCode(code) {
            XCTAssertEqual(parsed.deviceId, "d1")
        } else {
            XCTFail("Fresh code should be valid")
        }
    }

    /// A code stamped ten minutes ago is expired under the real five-minute `defaultMaxAge`.
    ///
    /// An explicit past timestamp avoids timing races and tests the real threshold.
    func testValidate_expiredCodeRejected() {
        let tenMinutesAgo = UInt64(Date().timeIntervalSince1970 * 1000) - 10 * 60 * 1000
        let code = Self.encodeLinkCode(DeviceLink.LinkCode(
            deviceId: "d1",
            challenge: Data(repeating: 0xAB, count: 16).base64EncodedString(),
            timestamp: tenMinutesAgo
        ))

        if case .expired = DeviceLink.validateLinkCode(code) {
            // Expected
        } else {
            XCTFail("A code stamped 10 minutes ago must be expired under the 5-minute default")
        }
    }

    /// **A future-dated code must not crash the scanner.** `validateLinkCode` computed
    /// `now - code.timestamp`, and `code.timestamp` comes straight out of a scanned QR payload with
    /// nothing authenticated yet — so a code stamped in the future made that `UInt64` subtraction
    /// go negative and **trap**. A trap is not catchable: scanning a hostile QR code killed the app.
    /// Reaching the assertion at all is the test.
    func testValidate_futureDatedCodeIsTreatedAsFreshRatherThanTrapping() {
        let code = Self.encodeLinkCode(DeviceLink.LinkCode(
            deviceId: "d1",
            challenge: Data(repeating: 0xAB, count: 16).base64EncodedString(),
            timestamp: UInt64.max
        ))

        if case .valid = DeviceLink.validateLinkCode(code) {
            // Expected: treat as fresh rather than reject.
        } else {
            XCTFail("A future-dated code must validate, not trap and not be rejected")
        }
    }

    /// Encode a `LinkCode` the same way `generateLinkCode` does, but with a caller-chosen timestamp.
    /// The generator always stamps `now`, which makes expiry untestable without sleeping.
    private static func encodeLinkCode(_ code: DeviceLink.LinkCode) -> String {
        guard let json = try? JSONEncoder().encode(code) else { return "" }
        return Base58.encodeString(json)
    }

    func testValidate_garbageInputRejected() {
        if case .invalid = DeviceLink.validateLinkCode("not-a-valid-code") {
            // Expected
        } else {
            XCTFail("Garbage input should be invalid")
        }
    }

    // MARK: - Challenge Verification

    func testVerifyChallenge_matching() {
        let challenge = Data([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16])
        XCTAssertTrue(DeviceLink.verifyChallenge(expected: challenge, received: challenge))
    }

    func testVerifyChallenge_mismatch() {
        let expected = Data([1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16])
        var tampered = expected
        tampered[0] = 99
        XCTAssertFalse(DeviceLink.verifyChallenge(expected: expected, received: tampered))
    }

    func testVerifyChallenge_differentLengths() {
        let a = Data([1, 2, 3])
        let b = Data([1, 2, 3, 4])
        XCTAssertFalse(DeviceLink.verifyChallenge(expected: a, received: b))
    }
}
