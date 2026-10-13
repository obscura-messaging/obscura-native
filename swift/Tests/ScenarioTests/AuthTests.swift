import XCTest
@testable import ObscuraKit

final class AuthTests: XCTestCase {
    func testRegisterAuthenticatesAndPersistsExactlyTheSessionKeys() async throws {
        let storage = MemorySessionStorage()
        let alice = try await TestUser.register(makeClient(storage: storage))

        XCTAssertEqual(alice.client.authState, .authenticated)
        XCTAssertNotEqual(alice.deviceId, alice.userId)
        let stored = try XCTUnwrap(storage.session)
        XCTAssertEqual(Set(stored.keys), ["token", "refreshToken", "userId", "deviceId", "username"])
        XCTAssertEqual(stored["userId"], alice.userId)
        XCTAssertEqual(stored["deviceId"], alice.deviceId)
        XCTAssertEqual(stored["username"], alice.username)
        let localDeviceId = try await alice.client.devices.localDeviceId()
        XCTAssertEqual(localDeviceId, alice.deviceId)
    }

    func testLoginScenarios() async throws {
        let alice = try await TestUser.register()
        await alice.client.logout()

        let existing = try await alice.client.login(alice.username, TestServer.password)
        XCTAssertEqual(existing, .existingDevice)
        XCTAssertEqual(alice.client.authState, .authenticated)
        let userId = await alice.client.userId
        XCTAssertEqual(userId, alice.userId)

        let fresh = try makeClient()
        let newDevice = try await fresh.login(alice.username, TestServer.password)
        let wrongPassword = try await fresh.login(alice.username, TestServer.password + "x")
        let unknownUser = try await fresh.login(TestServer.username(), TestServer.password)
        XCTAssertEqual(newDevice, .newDevice)
        XCTAssertEqual(wrongPassword, .invalidCredentials)
        XCTAssertEqual(unknownUser, .invalidCredentials)
        XCTAssertEqual(fresh.authState, .loggedOut)

        // Alice's device is not one of Bob's.
        let bob = try await TestUser.register()
        await bob.client.logout()
        await alice.client.logout()
        let mismatch = try await alice.client.login(bob.username, TestServer.password)
        XCTAssertEqual(mismatch, .deviceMismatch)
        XCTAssertEqual(alice.client.authState, .loggedOut)
    }

    func testLoginAndProvisionAddsAnAuthenticatedDevice() async throws {
        let alice = try await TestUser.register()
        let laptop = try await TestUser.newDevice(of: alice)

        XCTAssertEqual(laptop.client.authState, .authenticated)
        XCTAssertEqual(laptop.userId, alice.userId)
        XCTAssertNotEqual(laptop.deviceId, alice.deviceId)
    }

    func testLogoutRevokesTheRefreshTokenAndClearsTheSession() async throws {
        let storage = MemorySessionStorage()
        let alice = try await TestUser.register(makeClient(storage: storage))
        let refreshToken = try XCTUnwrap(storage.session?["refreshToken"])
        try await alice.client.connect()

        await alice.client.logout()

        XCTAssertEqual(alice.client.authState, .loggedOut)
        XCTAssertEqual(alice.client.connectionState, .disconnected)
        XCTAssertNil(storage.session)
        do {
            _ = try await alice.client.api.refreshSession(refreshToken)
            XCTFail("a revoked refresh token must be rejected")
        } catch let ObscuraError.http(status, _) {
            XCTAssertTrue([401, 403].contains(status), "status \(status)")
        }
    }

    func testARefreshRotatesThePersistedTokenAndKeepsDeviceScope() async throws {
        let storage = MemorySessionStorage()
        let alice = try await TestUser.register(makeClient(storage: storage))
        let first = try XCTUnwrap(storage.session?["refreshToken"])

        let status = await alice.client.refresh()

        XCTAssertEqual(status, .fresh)
        let rotated = try XCTUnwrap(storage.session?["refreshToken"])
        XCTAssertNotEqual(rotated, first, "the single-use refresh token is rotated and re-persisted")
        XCTAssertEqual(storage.session?["token"].flatMap(APIClient.deviceId), alice.deviceId)
        try await alice.client.connect()
        XCTAssertEqual(alice.client.connectionState, .connected, "a device-scoped token can still fetch a gateway ticket")
        await alice.client.disconnect()
    }

    func testRestoringAPersistedSessionAfterARestartReconnects() async throws {
        let dir = try temporaryDirectory()
        defer { try? FileManager.default.removeItem(atPath: dir) }
        let storage = MemorySessionStorage()
        let alice = try await TestUser.register(makeClient(directory: dir, storage: storage))
        await alice.client.disconnect()

        let restarted = try makeClient(directory: dir, storage: storage)
        let restored = await restarted.restorePersistedSession()

        XCTAssertTrue(restored)
        XCTAssertEqual(restarted.authState, .authenticated)
        XCTAssertEqual(restarted.connectionState, .connected)
        let deviceId = await restarted.deviceId
        XCTAssertEqual(deviceId, alice.deviceId)
        await restarted.disconnect()
    }
}
