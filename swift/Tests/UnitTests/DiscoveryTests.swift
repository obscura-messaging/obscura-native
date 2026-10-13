import Foundation
import XCTest
@testable import ObscuraKit

final class DiscoveryTests: XCTestCase {
    func testCancellationDuringOwnMetadataFetchIsNotConvertedToUnknownSelfSync() async throws {
        let server = FakeObscuraServer()
        defer { server.close() }
        let own = try await server.register(UUID().uuidString.lowercased())
        let client = try await client(own)
        let started = expectation(description: "own directory request suspended")
        FakeServerProtocol.onOwnMetadata.withLock { $0 = { started.fulfill() } }
        let send = Task {
            try await client.send(to: [], modelKey: "m", entryId: "cancelled", sentAt: 1,
                                  payload: Data(), acceptedDeviceIds: [])
        }
        await fulfillment(of: [started], timeout: 3)
        send.cancel()
        do {
            _ = try await send.value
            XCTFail("cancelled send must throw, not produce unknown selfSync")
        } catch { XCTAssertTrue(error is CancellationError) }
        try await client.logout()
    }

    func testOwnMetadataFailureDoesNotPreventRecipientAcceptanceAndIsReportedInSelfSync() async throws {
        let server = FakeObscuraServer()
        defer { server.close() }
        let own = try await server.register(UUID().uuidString.lowercased())
        let peer = try await server.register(UUID().uuidString.lowercased())
        let client = try await client(own)
        server.failOwnDirectory(status: 500)

        let report = try await client.send(to: [peer.userId], modelKey: "m", entryId: "delivered", sentAt: 1,
                                           payload: Data(), acceptedDeviceIds: [])

        XCTAssertEqual(report.recipients.map(\.status), [.accepted])
        XCTAssertEqual(report.selfSync.map(\.status), [.unknown])
        XCTAssertEqual(report.selfSync.first?.userId, own.userId)
        XCTAssertNil(report.selfSync.first?.deviceId)
        XCTAssertTrue(report.selfSync.first?.error?.contains("500") == true)
        let received = try await peer.receive()
        XCTAssertEqual(received.first?.message.appEntry.id, "delivered")
        XCTAssertEqual(server.bundleFetches(own.userId), 0)
        XCTAssertEqual(server.ownDirectoryFetches(own.userId), 1)
        XCTAssertEqual(server.ownDirectoryFetches(peer.userId), 0)
        do { try await client.connect(); XCTFail("connect metadata failure must throw") }
        catch { XCTAssertEqual(error as? ObscuraError, .http(status: 500, body: "metadata failed")) }
        XCTAssertEqual(client.connectionState, .disconnected)
        XCTAssertTrue(client.getDebugLog().contains { $0.contains("gateway connect failed") })
        try await client.logout()
    }

    func testOwnDirectoryPersistenceFailureIsRejectedWithoutInvalidatingAcceptedPeers() async throws {
        let server = FakeObscuraServer()
        defer { server.close() }
        let own = try await server.register(UUID().uuidString.lowercased())
        let peer = try await server.register(UUID().uuidString.lowercased())
        let client = try await client(own)
        try await client.db.write {
            try $0.execute(sql: """
                CREATE TRIGGER fail_own_directory BEFORE INSERT ON devices
                WHEN NEW.user_id = '\(own.userId)'
                BEGIN SELECT RAISE(ABORT, 'own directory disk failed'); END
                """)
        }
        let report = try await client.send(to: [peer.userId], modelKey: "m", entryId: "accepted", sentAt: 1,
                                           payload: Data(), acceptedDeviceIds: [])
        XCTAssertEqual(report.recipients.map(\.status), [.accepted])
        XCTAssertEqual(report.selfSync.map(\.status), [.rejected])
        XCTAssertTrue(report.selfSync.first?.error?.contains("own directory disk failed") == true)
        XCTAssertEqual(server.bundleFetches(own.userId), 0)
        let received = try await peer.receive()
        XCTAssertEqual(received.first?.message.appEntry.id, "accepted")
        try await client.logout()
    }

    private func client(_ device: FakeDevice) async throws -> ObscuraClient {
        let client = try makeClient(apiURL: FakeObscuraServer.baseURL, database: device.db)
        try await client.devices.setLocalDeviceId(device.deviceId)
        try await client.begin(Session(
            token: SessionRestoreTests.jwt(exp: Int(Date().timeIntervalSince1970) + 3600,
                                          userId: device.userId, deviceId: device.deviceId),
            refreshToken: "rt", userId: device.userId, deviceId: device.deviceId, username: "owner"))
        return client
    }

    func testConnectRefreshesOwnMetadataWithoutConsumingPrekeys() async throws {
        let server = FakeObscuraServer()
        defer { server.close() }
        let userId = UUID().uuidString.lowercased()
        let phone = try await server.register(userId)
        let laptop = try await server.register(userId)
        let client = try await client(phone)
        do { try await client.connect(); XCTFail("fake gateway is unavailable") } catch {}
        let ids = try await client.devices.deviceIds(of: userId)
        XCTAssertEqual(Set(ids), [phone.deviceId, laptop.deviceId])
        XCTAssertEqual(server.ownDirectoryFetches(userId), 1)
        XCTAssertEqual(server.bundleFetches(userId), 0)
        XCTAssertEqual(phone.remainingPreKeys, 100)
        XCTAssertEqual(laptop.remainingPreKeys, 100)
        try await client.logout()
    }

    func testEveryAppBatchDiscoversNewOwnDevicesAndRetainsAcceptedEvidenceAndSessions() async throws {
        let server = FakeObscuraServer()
        defer { server.close() }
        let userId = UUID().uuidString.lowercased()
        let phone = try await server.register(userId)
        let client = try await client(phone)
        let first = try await client.send(to: [userId, userId], modelKey: "m", entryId: "one", sentAt: 1,
                                          payload: Data(), acceptedDeviceIds: [])
        XCTAssertEqual(first.recipients, [])
        XCTAssertEqual(first.selfSync, [])
        XCTAssertEqual(server.bundleFetches(userId), 0)

        let laptop = try await server.register(userId)
        let second = try await client.send(to: [], modelKey: "m", entryId: "two", sentAt: 2,
                                           payload: Data([42]), acceptedDeviceIds: [])
        XCTAssertEqual(second.selfSync.map(\.deviceId), [laptop.deviceId])
        XCTAssertEqual(second.selfSync.map(\.status), [.accepted])
        XCTAssertEqual(server.bundleFetches(userId), 1)
        let received = try await laptop.receive()
        XCTAssertEqual(received.first?.message.appEntry.data, Data([42]))

        let retry = try await client.send(to: [], modelKey: "m", entryId: "two", sentAt: 2,
                                          payload: Data([42]), acceptedDeviceIds: [laptop.deviceId])
        XCTAssertEqual(retry.selfSync.map(\.status), [.accepted])
        XCTAssertEqual(server.bundleFetches(userId), 1)
        XCTAssertEqual(server.ownDirectoryFetches(userId), 3)
        XCTAssertTrue(server.drain(laptop).isEmpty)

        _ = try await client.send(to: [], modelKey: "m", entryId: "three", sentAt: 3,
                                  payload: Data(), acceptedDeviceIds: [])
        XCTAssertEqual(server.bundleFetches(userId), 1)
        XCTAssertEqual(server.ownDirectoryFetches(userId), 4)
        try await client.logout()
    }

    func testPeerDevicesAreLearnedAdditivelyFromAuthenticatedReceiveAndReuseTheIncomingSession() async throws {
        let server = FakeObscuraServer()
        defer { server.close() }
        let peerUserId = UUID().uuidString.lowercased()
        let oldPeer = try await server.register(peerUserId)
        let newPeer = try await server.register(peerUserId)
        let own = try await server.register(UUID().uuidString.lowercased())
        let client = try await client(own)
        try await client.devices.add(userId: peerUserId, deviceId: oldPeer.deviceId)
        var message = Obscura_Client_V1_ClientMessage()
        message.appEntry.model = "m"
        _ = await newPeer.sender.send(to: own.userId, try message.serializedData(), acceptedDeviceIds: [])
        let delivery = try XCTUnwrap(server.drain(own).first)
        await client.receive(Envelope(id: uuidBytes(UUID().uuidString)!, senderId: uuidBytes(peerUserId)!,
                                      senderDeviceId: uuidBytes(newPeer.deviceId)!, message: delivery.message)) { _ in }
        let known = try await client.devices.deviceIds(of: peerUserId)
        XCTAssertEqual(Set(known), [oldPeer.deviceId, newPeer.deviceId])
        let report = try await client.send(to: [peerUserId], modelKey: "m", entryId: "reply", sentAt: 1,
                                           payload: Data(), acceptedDeviceIds: [oldPeer.deviceId])
        XCTAssertEqual(report.recipients.map(\.status), [.accepted, .accepted])
        XCTAssertEqual(server.bundleFetches(peerUserId), 0)
        XCTAssertEqual(server.ownDirectoryFetches(peerUserId), 0)
        let reply = try await newPeer.receive()
        XCTAssertEqual(reply.first?.message.appEntry.id, "reply")
        try await client.logout()
    }
}
