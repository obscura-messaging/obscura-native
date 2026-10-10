import XCTest
@testable import ObscuraKit

/// Tests for session management and attachment crypto.
final class NewMethodTests: XCTestCase {

    // MARK: - hasSession

    func testHasSession() async throws {
        let alice = try await ObscuraTestClient.register()
        XCTAssertTrue(alice.client.hasSession, "Should have session after register")

        let fresh = try ObscuraClient(apiURL: TestServer.apiURL)
        XCTAssertFalse(fresh.hasSession, "Fresh client should not have session")
    }

    // MARK: - restoreSession

    func testRestoreSession() async throws {
        let alice = try await ObscuraTestClient.register()
        let savedToken = alice.token!
        let savedRefreshToken = alice.client.refreshToken
        let savedUserId = alice.userId!
        let savedDeviceId = alice.deviceId!
        let savedUsername = alice.username
        await rateLimitDelay()

        let restored = try ObscuraClient(apiURL: TestServer.apiURL)
        await restored.restoreSession(
            token: savedToken, refreshToken: savedRefreshToken,
            userId: savedUserId, deviceId: savedDeviceId,
            username: savedUsername
        )

        XCTAssertTrue(restored.hasSession)
        XCTAssertEqual(restored.userId, savedUserId)
        XCTAssertEqual(restored.deviceId, savedDeviceId)
        XCTAssertEqual(restored.username, savedUsername)
        XCTAssertEqual(restored.authState, .authenticated)
    }

    // MARK: - ensureFreshToken

    func testEnsureFreshToken() async throws {
        let alice = try await ObscuraTestClient.register()
        await rateLimitDelay()

        let result = await alice.client.ensureFreshToken()
        XCTAssertTrue(result, "Should return true for a fresh token")

        let fresh = try ObscuraClient(apiURL: TestServer.apiURL)
        let noResult = await fresh.ensureFreshToken()
        XCTAssertFalse(noResult, "Should return false with no token")
    }

    // MARK: - loginAndProvision

    func testLoginAndProvision() async throws {
        let alice = try await ObscuraTestClient.register()
        let aliceUserId = alice.userId!
        await rateLimitDelay()

        let device2 = try await ObscuraTestClient.loginAndProvision(alice.username)

        XCTAssertNotNil(device2.token)
        XCTAssertEqual(device2.userId, aliceUserId, "Same user ID")
        XCTAssertNotNil(device2.deviceId)
        XCTAssertNotEqual(device2.deviceId, alice.deviceId, "Different device IDs")
        XCTAssertTrue(device2.client.hasSession)
        XCTAssertEqual(device2.client.authState, .pendingApproval, "Alice's first device can approve it")
    }

    func testLoginAndProvisionAuthenticatesSoleDevice() async throws {
        let username = "test_\(Int.random(in: 100000...999999))"
        let password = "testpass123456"
        _ = try await ObscuraClient.registerAccount(username, password, apiURL: TestServer.apiURL)
        await authRateLimitDelay()

        let device = try ObscuraClient(apiURL: TestServer.apiURL)
        try await device.loginAndProvision(username, password, deviceName: "Only Device")
        XCTAssertEqual(device.authState, .authenticated, "No other device exists to approve it")
    }

    // MARK: - login

    func testLoginScenarios() async throws {
        let alice = try await ObscuraTestClient.register()
        let aliceUserId = alice.userId!
        await rateLimitDelay()

        let scenario = try await alice.client.login(alice.username, alice.password)
        XCTAssertEqual(scenario, .existingDevice)
        XCTAssertEqual(alice.client.authState, .authenticated)
        XCTAssertEqual(alice.userId, aliceUserId)
        await rateLimitDelay()

        let fresh = try ObscuraClient(apiURL: TestServer.apiURL)
        let wrongPassword = try await fresh.login(alice.username, alice.password + "x")
        XCTAssertEqual(wrongPassword, .invalidCredentials)
        await rateLimitDelay()

        let unknownUser = try await fresh.login("test_none_\(Int.random(in: 100000...999999))", alice.password)
        XCTAssertEqual(unknownUser, .invalidCredentials)
        XCTAssertEqual(fresh.authState, .loggedOut)
        XCTAssertNil(fresh.userId)
    }

    // MARK: - AttachmentCrypto unit test

    func testAttachmentCryptoRoundTrip() throws {
        let plaintext = Data("hello world this is a test of AES-256-GCM encryption".utf8)
        let encrypted = try AttachmentCrypto.encrypt(plaintext)

        XCTAssertEqual(encrypted.contentKey.count, 32)
        XCTAssertEqual(encrypted.nonce.count, 12)
        XCTAssertNotEqual(encrypted.ciphertext, plaintext)

        let decrypted1 = try AttachmentCrypto.decrypt(encrypted.ciphertext, contentKey: encrypted.contentKey, nonce: encrypted.nonce)
        XCTAssertEqual(decrypted1, plaintext)

        var tampered = encrypted.ciphertext
        tampered[tampered.startIndex] ^= 0x01
        XCTAssertThrowsError(try AttachmentCrypto.decrypt(
            tampered, contentKey: encrypted.contentKey, nonce: encrypted.nonce))
    }
}
