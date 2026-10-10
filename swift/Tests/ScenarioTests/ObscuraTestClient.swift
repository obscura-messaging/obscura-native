import Foundation
@testable import ObscuraKit

/// Thin test wrapper around ObscuraClient.
/// Provides convenience methods for scenario tests.
/// All real logic lives in ObscuraClient — this just adds test helpers.
public class ObscuraTestClient {
    public let client: ObscuraClient
    public let username: String
    public let password: String

    // Passthrough to client
    public var userId: String? { client.userId }
    public var deviceId: String? { client.deviceId }
    public var token: String? { client.token }
    public var friends: FriendStore { client.friends }
    public var devices: DeviceStore { client.devices }
    public var api: APIClient { client.api }
    public var gateway: GatewayConnection { client.gateway }

    /// Send a raw protobuf ClientMessage to a user (for tests that build custom message types)
    public func sendRaw(to userId: String, _ messageData: Data) async throws {
        try await client.sendSerializedClientMessage(to: userId, data: messageData)
        await rateLimitDelay()
    }

    private init(client: ObscuraClient, username: String, password: String) {
        self.client = client
        self.username = username
        self.password = password
    }

    // MARK: - Test Convenience

    /// Register a new test user. Returns ready-to-use client.
    public static func register(
        _ username: String? = nil,
        _ password: String = {
            #if DEBUG
            return "testpass123456"
            #else
            fatalError("ObscuraTestClient must not be used in release builds")
            #endif
        }(),
        apiURL: String = ProcessInfo.processInfo.environment["OBSCURA_TEST_API"] ?? "https://obscura.barrelmaker.dev"
    ) async throws -> ObscuraTestClient {
        let name = username ?? "test_\(Int.random(in: 100000...999999))"
        let client = try ObscuraClient(apiURL: apiURL)
        try await client.register(name, password)
        await rateLimitDelay()
        return ObscuraTestClient(client: client, username: name, password: password)
    }

    /// Login and provision a new device (device linking).
    public static func loginAndProvision(
        _ username: String,
        _ password: String = {
            #if DEBUG
            return "testpass123456"
            #else
            fatalError("ObscuraTestClient must not be used in release builds")
            #endif
        }(),
        deviceName: String = "Device 2",
        apiURL: String = ProcessInfo.processInfo.environment["OBSCURA_TEST_API"] ?? "https://obscura.barrelmaker.dev"
    ) async throws -> ObscuraTestClient {
        let client = try ObscuraClient(apiURL: apiURL)
        try await client.loginAndProvision(username, password, deviceName: deviceName)
        await rateLimitDelay()
        return ObscuraTestClient(client: client, username: username, password: password)
    }

    // MARK: - Passthrough to client operations

    public func connectWebSocket() async throws {
        try await client.connect()
        await rateLimitDelay()
    }

    public func disconnectWebSocket() {
        client.disconnect()
    }

    public func befriend(_ userId: String, username: String = "") async throws {
        try await client.befriend(userId, username: username)
        await rateLimitDelay()
    }

    public func acceptFriend(_ userId: String, username: String = "") async throws {
        try await client.acceptFriend(userId)
        await rateLimitDelay()
    }

    public func waitForMessage(timeout: TimeInterval = 10) async throws -> MessageWakeEvent {
        return try await client.waitForMessage(timeout: timeout)
    }

    // MARK: - Compound Helpers

    /// Full friend handshake: A befriends B, B accepts. Both must be connected.
    /// Returns after both sides are ACCEPTED in their stores.
    public static func becomeFriends(_ a: ObscuraTestClient, _ b: ObscuraTestClient) async throws {
        try await a.befriend(b.userId!, username: b.username)
        _ = try await b.waitForMessage(timeout: 10) // FRIEND_REQUEST
        try await b.acceptFriend(a.userId!, username: a.username)
        _ = try await a.waitForMessage(timeout: 10) // FRIEND_ACCEPT
    }

    /// Register two users, connect both, complete friend handshake.
    /// Returns (userA, userB) ready for messaging.
    public static func registerPairAndBecomeFriends() async throws -> (ObscuraTestClient, ObscuraTestClient) {
        let a = try await ObscuraTestClient.register()
        await rateLimitDelay()
        let b = try await ObscuraTestClient.register()
        await rateLimitDelay()

        try await a.connectWebSocket()
        try await b.connectWebSocket()
        await rateLimitDelay()

        try await becomeFriends(a, b)
        return (a, b)
    }
}
