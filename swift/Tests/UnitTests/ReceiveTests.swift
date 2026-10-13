import Foundation
import GRDB
import XCTest
@testable import ObscuraKit

final class ReceiveTests: XCTestCase {
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

    func testSuccessfulSignalDecryptLearnsTheSenderEvenWhenContentIsMalformedButDoesNotAck() async throws {
        let server = FakeObscuraServer()
        defer { server.close() }
        let sender = try await server.register(UUID().uuidString.lowercased())
        let receiver = try await server.register(UUID().uuidString.lowercased())
        let client = try makeClient(apiURL: FakeObscuraServer.baseURL, database: receiver.db)
        let data = try await encrypted(sender, to: receiver, plaintext: Data([0xff]))
        let acks = Locked(0)
        await client.receive(envelope(data, sender: sender)) { _ in acks.withLock { $0 += 1 } }
        XCTAssertEqual(acks.withLock { $0 }, 0)
        let known = try await client.devices.deviceIds(of: sender.userId)
        XCTAssertEqual(known, [sender.deviceId])
        let depth = try await client.inbox.depth()
        XCTAssertEqual(depth, 0)
    }

    func testDirectoryOrInboxPersistenceFailurePreventsAck() async throws {
        for table in ["devices", "inbox_rows"] {
            let server = FakeObscuraServer()
            let sender = try await server.register(UUID().uuidString.lowercased())
            let receiver = try await server.register(UUID().uuidString.lowercased())
            let client = try makeClient(apiURL: FakeObscuraServer.baseURL, database: receiver.db)
            let data = try await encrypted(sender, to: receiver)
            try await receiver.db.write {
                try $0.execute(sql: "CREATE TRIGGER fail_write BEFORE INSERT ON \(table) BEGIN SELECT RAISE(ABORT, 'disk failed'); END")
            }
            let acks = Locked(0)
            await client.receive(envelope(data, sender: sender)) { _ in acks.withLock { $0 += 1 } }
            XCTAssertEqual(acks.withLock { $0 }, 0, table)
            server.close()
        }
    }
}
