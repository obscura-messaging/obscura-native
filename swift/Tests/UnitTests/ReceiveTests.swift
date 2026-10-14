import Foundation
import GRDB
import XCTest
@testable import ObscuraKit

final class ReceiveTests: XCTestCase {
    func testAnImmediateAcceptIsNotLostAndFailedSubmissionRollsBackOnlyPendingRequests() async throws {
        for acceptedBeforeFailure in [false, true] {
            let server = FakeObscuraServer()
            let sender = try await server.register(UUID().uuidString.lowercased())
            let peer = try await server.register(UUID().uuidString.lowercased())
            let client = try await authenticatedClient(sender, username: "sender")
            FakeServerProtocol.messageResponse.withLock { callback in
                callback = {
                    do {
                        let status = try client.db.read {
                            try String.fetchOne($0, sql: "SELECT status FROM friends WHERE user_id = ?", arguments: [peer.userId])
                        }
                        XCTAssertEqual(status, FriendStatus.pendingSent.rawValue, "request must be durable before submission")
                        if acceptedBeforeFailure {
                            try client.db.write {
                                try $0.execute(sql: "UPDATE friends SET status = ? WHERE user_id = ?",
                                               arguments: [FriendStatus.accepted.rawValue, peer.userId])
                            }
                        }
                    } catch { XCTFail("friend persistence failed: \(error)") }
                    return (500, Data("submission failed".utf8))
                }
            }
            do {
                try await client.addFriendByCode(FriendCode.encode(userId: peer.userId, username: "peer"))
                XCTFail("submission failure must throw")
            } catch { XCTAssertEqual((error as? ObscuraError)?.code, "SEND_FAILED") }
            let friend = try await client.friends.friend(peer.userId)
            XCTAssertEqual(friend?.status, acceptedBeforeFailure ? .accepted : nil)
            try await client.logout()
            server.close()
        }
    }

    func testIndependentDeviceExplicitReAddReceivesAcceptWithoutChangingAcceptedPeerRecord() async throws {
        let server = FakeObscuraServer()
        defer { server.close() }
        let userId = UUID().uuidString.lowercased()
        let old = try await server.register(userId)
        let fresh = try await server.register(userId)
        let peer = try await server.register(UUID().uuidString.lowercased())
        let peerClient = try await authenticatedClient(peer, username: "peer")
        let freshClient = try await authenticatedClient(fresh, username: "untrusted replacement")
        try await peerClient.friends.add(userId, username: "original label", status: .accepted)
        try await peerClient.devices.add(userId: userId, deviceId: old.deviceId)
        let before = try await peerClient.friends.friend(userId)
        let initial = try await freshClient.getFriends()
        XCTAssertTrue(initial.isEmpty)

        try await freshClient.addFriendByCode(peerClient.friendCode())
        let requested = try await freshClient.friends.friend(peer.userId)
        XCTAssertEqual(requested?.status, .pendingSent)
        let request = try XCTUnwrap(server.drain(peer).first)
        let acks = Locked(0)
        await peerClient.receive(envelope(request.message, sender: fresh)) { _ in acks.withLock { $0 += 1 } }
        let unchanged = try await peerClient.friends.friend(userId)
        XCTAssertEqual(unchanged, before)
        XCTAssertEqual(acks.withLock { $0 }, 1)
        let known = try await peerClient.devices.deviceIds(of: userId)
        XCTAssertEqual(Set(known), [old.deviceId, fresh.deviceId])
        let reply = try XCTUnwrap(server.drain(fresh).first)
        await freshClient.receive(envelope(reply.message, sender: peer)) { _ in }
        let accepted = try await freshClient.friends.friend(peer.userId)
        XCTAssertEqual(accepted?.status, .accepted)
        XCTAssertEqual(accepted?.username, "peer")

        var repeated = Obscura_Client_V1_ClientMessage()
        repeated.friendRequest.username = "second forged label"
        _ = await fresh.sender.send(to: peer.userId, try repeated.serializedData(), acceptedDeviceIds: [])
        let redelivery = try XCTUnwrap(server.drain(peer).first)
        await peerClient.receive(envelope(redelivery.message, sender: fresh)) { _ in }
        let repeatedReply = try XCTUnwrap(server.drain(fresh).first)
        await freshClient.receive(envelope(repeatedReply.message, sender: peer)) { _ in }
        let afterRepeated = try await peerClient.friends.friend(userId)
        let freshAfterRepeated = try await freshClient.friends.friend(peer.userId)
        XCTAssertEqual(afterRepeated, before)
        XCTAssertEqual(freshAfterRepeated, accepted)
        try await peerClient.logout()
        try await freshClient.logout()
    }

    func testAPendingReceivedRequestDoesNotReplyOrRelabel() async throws {
        let server = FakeObscuraServer()
        defer { server.close() }
        let sender = try await server.register(UUID().uuidString.lowercased())
        let receiver = try await server.register(UUID().uuidString.lowercased())
        let client = try await authenticatedClient(receiver, username: "receiver")
        try await client.friends.add(sender.userId, username: "first label", status: .pendingReceived)
        let original = try await client.friends.friend(sender.userId)
        var message = Obscura_Client_V1_ClientMessage()
        message.friendRequest.username = "replacement"
        _ = await sender.sender.send(to: receiver.userId, try message.serializedData(), acceptedDeviceIds: [])
        let delivery = try XCTUnwrap(server.drain(receiver).first)
        let acks = Locked(0)
        await client.receive(envelope(delivery.message, sender: sender)) { _ in acks.withLock { $0 += 1 } }
        XCTAssertEqual(acks.withLock { $0 }, 1)
        XCTAssertTrue(server.drain(sender).isEmpty)
        let unchanged = try await client.friends.friend(sender.userId)
        XCTAssertEqual(unchanged, original)
        try await client.logout()
    }

    private func authenticatedClient(_ device: FakeDevice, username: String) async throws -> ObscuraClient {
        let client = try makeClient(apiURL: FakeObscuraServer.baseURL, database: device.db)
        try await client.begin(Session(
            token: SessionRestoreTests.jwt(exp: Int(Date().timeIntervalSince1970) + 3600,
                                          userId: device.userId, deviceId: device.deviceId),
            refreshToken: "rt", userId: device.userId, deviceId: device.deviceId, username: username))
        return client
    }

    private func envelope(_ data: Data, sender: FakeDevice, id: Data = uuidBytes(UUID().uuidString)!) -> Envelope {
        Envelope(id: id, senderId: uuidBytes(sender.userId)!, senderDeviceId: uuidBytes(sender.deviceId)!, message: data)
    }

    private func encrypted(_ sender: FakeDevice, to receiver: FakeDevice, plaintext: Data? = nil) async throws -> Data {
        var message = Obscura_Client_V1_ClientMessage()
        message.appEntry.model = "opaque-model"
        message.appEntry.id = "entry"
        message.appEntry.data = Data([0, 255, 1])
        let results = await sender.sender.send(to: receiver.userId, try plaintext ?? message.serializedData(), acceptedDeviceIds: [])
        XCTAssertTrue(results.allSatisfy { $0.status == .accepted })
        return try XCTUnwrap(FakeObscuraServer.active?.drain(receiver).first?.message)
    }

    func testReceivePersistsAndLearnsStampedSenderBeforeAckAndAcksDuplicateWithoutSecondWake() async throws {
        let server = FakeObscuraServer()
        defer { server.close() }
        let sender = try await server.register(UUID().uuidString.lowercased())
        let receiver = try await server.register(UUID().uuidString.lowercased())
        let client = try makeClient(apiURL: FakeObscuraServer.baseURL, database: receiver.db)
        let data = try await encrypted(sender, to: receiver)
        let delivery = envelope(data, sender: sender)
        let ackCount = Locked(0)
        let wakes = Task {
            var values: [String] = []
            for await value in client.messageReceived { values.append(value) }
            return values
        }
        let acknowledge: @Sendable (Data) async throws -> Void = { id in
            XCTAssertEqual(id, delivery.id)
            let rows = try await client.inbox.peek(limit: 10)
            XCTAssertEqual(rows.count, 1)
            XCTAssertEqual(rows.first?.payload, Data([0, 255, 1]))
            XCTAssertEqual(rows.first?.senderUserId, sender.userId)
            XCTAssertEqual(rows.first?.senderDeviceId, sender.deviceId)
            let devices = try await client.devices.deviceIds(of: sender.userId)
            XCTAssertEqual(devices, [sender.deviceId])
            ackCount.withLock { $0 += 1 }
        }
        await client.receive(delivery, acknowledge: acknowledge)
        await client.receive(delivery, acknowledge: acknowledge)
        XCTAssertEqual(ackCount.withLock { $0 }, 2)
        wakes.cancel()
        let received = await wakes.value
        XCTAssertEqual(received, ["opaque-model"])
    }

    func testMalformedEnvelopeAndFailedDecryptNeitherAckNorLearn() async throws {
        let server = FakeObscuraServer()
        defer { server.close() }
        let sender = try await server.register(UUID().uuidString.lowercased())
        let client = try makeClient()
        let acks = Locked(0)
        let acknowledge: @Sendable (Data) async throws -> Void = { _ in acks.withLock { $0 += 1 } }
        await client.receive(envelope(Data([0xff]), sender: sender), acknowledge: acknowledge)
        let malformed = Envelope(id: Data([1]), senderId: uuidBytes(sender.userId)!,
                                 senderDeviceId: Data(), message: Data())
        await client.receive(malformed, acknowledge: acknowledge)
        XCTAssertEqual(acks.withLock { $0 }, 0)
        let devices = try await client.devices.deviceIds(of: sender.userId)
        let depth = try await client.inbox.depth()
        XCTAssertEqual(devices, [])
        XCTAssertEqual(depth, 0)
    }

    func testAuthenticatedPlaintextThatIsNotAClientMessageIsAckedAndDropped() async throws {
        let server = FakeObscuraServer()
        defer { server.close() }
        let sender = try await server.register(UUID().uuidString.lowercased())
        let receiver = try await server.register(UUID().uuidString.lowercased())
        let client = try makeClient(apiURL: FakeObscuraServer.baseURL, database: receiver.db)
        let data = try await encrypted(sender, to: receiver, plaintext: Data([0xff]))
        let acks = Locked(0)
        await client.receive(envelope(data, sender: sender)) { _ in acks.withLock { $0 += 1 } }
        XCTAssertEqual(acks.withLock { $0 }, 1)
        let known = try await client.devices.deviceIds(of: sender.userId)
        XCTAssertEqual(known, [sender.deviceId])
        let depth = try await client.inbox.depth()
        XCTAssertEqual(depth, 0)
    }

    func testDirectoryOrInboxPersistenceFailureRollsBackSignalAndRedeliveryThenAcks() async throws {
        for table in ["devices", "inbox_rows"] {
            let server = FakeObscuraServer()
            let sender = try await server.register(UUID().uuidString.lowercased())
            let receiver = try await server.register(UUID().uuidString.lowercased())
            let client = try makeClient(apiURL: FakeObscuraServer.baseURL, database: receiver.db)
            let data = try await encrypted(sender, to: receiver)
            let delivery = envelope(data, sender: sender)
            let originalPreKeys = try receiver.store.preKeyCount()
            try await receiver.db.write {
                try $0.execute(sql: "CREATE TRIGGER fail_write BEFORE INSERT ON \(table) BEGIN SELECT RAISE(ABORT, 'disk failed'); END")
            }
            let acks = Locked(0)
            await client.receive(delivery) { _ in acks.withLock { $0 += 1 } }
            XCTAssertEqual(acks.withLock { $0 }, 0, table)
            XCTAssertEqual(try receiver.store.preKeyCount(), originalPreKeys, "failed persistence must not consume the private OPK")
            let sessionAfterFailure = try await client.messenger.hasSession(sender.deviceId)
            XCTAssertFalse(sessionAfterFailure, "failed persistence must not commit the new Signal session")
            let failedDepth = try await client.inbox.depth()
            XCTAssertEqual(failedDepth, 0)
            try await receiver.db.write { try $0.execute(sql: "DROP TRIGGER fail_write") }
            await client.receive(delivery) { _ in acks.withLock { $0 += 1 } }
            XCTAssertEqual(acks.withLock { $0 }, 1, table)
            XCTAssertEqual(try receiver.store.preKeyCount(), originalPreKeys - 1)
            let recovered = try await client.inbox.peek(limit: 10)
            XCTAssertEqual(recovered.map(\.entryId), ["entry"])
            await client.receive(delivery) { _ in acks.withLock { $0 += 1 } }
            XCTAssertEqual(acks.withLock { $0 }, 2, "persisted duplicates may ACK without decrypting twice")
            server.close()
        }
    }

    func testAnEstablishedRatchetAlsoRollsBackOnInboxFailureAndSurvivesClientRecreation() async throws {
        let server = FakeObscuraServer()
        defer { server.close() }
        let sender = try await server.register(UUID().uuidString.lowercased())
        let receiver = try await server.register(UUID().uuidString.lowercased())
        let client = try makeClient(apiURL: FakeObscuraServer.baseURL, database: receiver.db)
        let first = try await encrypted(sender, to: receiver)
        await client.receive(envelope(first, sender: sender)) { _ in }
        var reply = Obscura_Client_V1_ClientMessage()
        reply.appEntry.model = "ack"
        reply.appEntry.id = "reply"
        _ = await receiver.sender.send(to: sender.userId, try reply.serializedData(), acceptedDeviceIds: [])
        _ = try await sender.receive()
        let next = try await encrypted(sender, to: receiver)
        let encryptedMessage = try Obscura_Client_V1_EncryptedMessage(serializedBytes: next)
        XCTAssertEqual(encryptedMessage.type, .encryptedMessage)
        let delivery = envelope(next, sender: sender)
        let originalSession = try await receiver.db.read {
            try Data.fetchOne($0, sql: "SELECT record FROM signal_sessions WHERE address = ?",
                              arguments: ["\(sender.deviceId).1"])
        }
        try await receiver.db.write {
            try $0.execute(sql: "CREATE TRIGGER fail_inbox BEFORE INSERT ON inbox_rows BEGIN SELECT RAISE(ABORT, 'disk failed'); END")
        }
        let acks = Locked(0)
        await client.receive(delivery) { _ in acks.withLock { $0 += 1 } }
        XCTAssertEqual(acks.withLock { $0 }, 0)
        let failedSession = try await receiver.db.read {
            try Data.fetchOne($0, sql: "SELECT record FROM signal_sessions WHERE address = ?",
                              arguments: ["\(sender.deviceId).1"])
        }
        XCTAssertEqual(failedSession, originalSession)
        try await receiver.db.write { try $0.execute(sql: "DROP TRIGGER fail_inbox") }
        let restarted = try makeClient(apiURL: FakeObscuraServer.baseURL, database: receiver.db)
        await restarted.receive(delivery) { _ in acks.withLock { $0 += 1 } }
        XCTAssertEqual(acks.withLock { $0 }, 1)
        let rows = try await restarted.inbox.peek(limit: 10)
        XCTAssertEqual(rows.count, 2)
    }
}
