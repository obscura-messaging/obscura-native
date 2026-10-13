import Foundation
import XCTest
@testable import ObscuraKit

/// The send path against real Signal peers on an in-process server. Every bundle fetch consumes a
/// one-time prekey on each of the user's devices, so the fetch counts here are the prekey cost.
final class MessageSenderTests: XCTestCase {
    private var server: FakeObscuraServer!
    private var alice: FakeDevice!
    private var bob: FakeDevice!

    override func setUp() async throws {
        server = FakeObscuraServer()
        alice = try await server.register(user())
        bob = try await server.register(user())
    }

    override func tearDown() {
        server.close()
    }

    private func user() -> String { UUID().uuidString.lowercased() }

    private func entry(_ id: String) -> Data {
        var message = Obscura_Client_V1_ClientMessage()
        message.appEntry.model = "m"
        message.appEntry.id = id
        return try! message.serializedData()
    }

    private func send(_ from: FakeDevice, to: FakeDevice, _ id: String, accepted: Set<String> = []) async -> [SubmissionResult] {
        await from.sender.send(to: to.userId, entry(id), acceptedDeviceIds: accepted)
    }

    private func accepted(_ device: FakeDevice) -> SubmissionResult {
        SubmissionResult(userId: device.userId, deviceId: device.deviceId, status: .accepted)
    }

    private func ids(_ received: [Received]) -> [String] { received.map(\.message.appEntry.id) }

    func testRepeatedSendsFetchBundlesOnceAndReuseTheSession() async throws {
        for i in 0..<3 {
            let results = await send(alice, to: bob, "e\(i)")
            XCTAssertEqual(results, [accepted(bob)])
        }

        let received = try await bob.receive()
        XCTAssertEqual(ids(received), ["e0", "e1", "e2"])
        // Unacknowledged sessions keep sending prekey messages, all from the one session.
        XCTAssertEqual(Set(received.compactMap(\.baseKey).map { $0.serialize() }).count, 1)
        XCTAssertEqual(server.bundleFetches(bob.userId), 1)
        XCTAssertEqual(bob.remainingPreKeys, 99)
    }

    func testARefreshNeverRebuildsAnEstablishedSession() async throws {
        _ = await send(alice, to: bob, "hello")
        _ = try await bob.receive()

        // Bob has Alice's session from her prekey message but has never listed her devices.
        let reply = await send(bob, to: alice, "reply")
        XCTAssertEqual(reply, [accepted(alice)])
        XCTAssertEqual(server.bundleFetches(alice.userId), 1)
        let aliceGot = try await alice.receive().map(\.type)
        XCTAssertEqual(aliceGot, [.encryptedMessage])

        _ = await send(alice, to: bob, "after-reply")
        let bobGot = try await bob.receive().map(\.type)
        XCTAssertEqual(bobGot, [.encryptedMessage])
        XCTAssertEqual(server.bundleFetches(bob.userId), 1)
    }

    func testANewlyListedDeviceWithoutASessionCostsOneFetchAndKeepsExistingSessions() async throws {
        _ = await send(alice, to: bob, "first")
        let firstKey = try await bob.receive().first?.baseKey
        let bobTablet = try await server.register(bob.userId)
        try await alice.directory.replace(bob.userId, devices: [
            Device(userId: bob.userId, deviceId: bob.deviceId), Device(userId: bob.userId, deviceId: bobTablet.deviceId),
        ])

        let results = await send(alice, to: bob, "second")
        XCTAssertEqual(Set(results.map(\.deviceId)), [bob.deviceId, bobTablet.deviceId])
        XCTAssertTrue(results.allSatisfy { $0.status == .accepted })

        let secondKey = try await bob.receive().first?.baseKey
        XCTAssertEqual(secondKey, firstKey)
        let tabletGot = try await bobTablet.receive().map(\.type)
        XCTAssertEqual(tabletGot, [.prekeyMessage])
        XCTAssertEqual(server.bundleFetches(bob.userId), 2)
    }

    func testADeviceTheServerNoLongerHasIsDroppedAndItsReplacementReachedInTheSameSend() async throws {
        _ = await send(alice, to: bob, "first")
        server.remove(bob)
        let bobNew = try await server.register(bob.userId)

        let second = await send(alice, to: bob, "second")
        XCTAssertEqual(second, [accepted(bobNew)])
        let newDeviceGot = ids(try await bobNew.receive())
        XCTAssertEqual(newDeviceGot, ["second"])
        let listed = try await alice.directory.devices(of: bob.userId).map(\.deviceId)
        XCTAssertEqual(listed, [bobNew.deviceId])

        _ = await send(alice, to: bob, "third")
        XCTAssertEqual(server.bundleFetches(bob.userId), 2)
    }

    func testSelfSyncNeverTargetsThisDeviceAndASingleDeviceFetchesNothing() async throws {
        try await alice.directory.add(Device(userId: alice.userId, deviceId: alice.deviceId))

        let results = await send(alice, to: alice, "note")
        XCTAssertEqual(results, [])
        XCTAssertEqual(server.bundleFetches(alice.userId), 0)
        let echoed = try await alice.receive().count
        XCTAssertEqual(echoed, 0)
    }

    func testSelfSyncReachesTheUsersOtherDevices() async throws {
        let alicePhone = try await server.register(alice.userId)

        let results = await send(alice, to: alice, "note")
        XCTAssertEqual(results, [accepted(alicePhone)])
        let phoneGot = ids(try await alicePhone.receive())
        XCTAssertEqual(phoneGot, ["note"])
        let echoed = try await alice.receive().count
        XCTAssertEqual(echoed, 0)
    }

    func testConcurrentFirstSendsShareOneFetch() async throws {
        let (alice, bob) = (alice!, bob!)
        let results = await withTaskGroup(of: [SubmissionResult].self) { group in
            for i in 1...5 { group.addTask { await alice.sender.send(to: bob.userId, self.entry("c\(i)")) } }
            return await group.reduce(into: []) { $0.append($1) }
        }

        XCTAssertTrue(results.allSatisfy { $0 == [accepted(bob)] })
        XCTAssertEqual(server.bundleFetches(bob.userId), 1)
        let delivered = try await bob.receive().count
        XCTAssertEqual(delivered, 5)
    }

    func testAUserWithNoDevicesIsRejected() async throws {
        let ghost = user()
        let results = await alice.sender.send(to: ghost, entry("lost"))

        XCTAssertEqual(results.count, 1)
        XCTAssertEqual(results.first?.userId, ghost)
        XCTAssertNil(results.first?.deviceId)
        XCTAssertEqual(results.first?.status, .rejected)
    }

    func testDevicesWithAcceptedEvidenceAreNotResentAndNeedNoSession() async throws {
        try await alice.directory.add(Device(userId: bob.userId, deviceId: bob.deviceId))

        let results = await send(alice, to: bob, "retry", accepted: [bob.deviceId])
        XCTAssertEqual(results, [accepted(bob)])
        XCTAssertEqual(server.bundleFetches(bob.userId), 0)
        let resent = try await bob.receive().count
        XCTAssertEqual(resent, 0)
    }

    func testAnUnusableBundleIsRejectedOnceAndDoesNotCauseAFetchOnEverySend() async throws {
        let broken = try await server.register(bob.userId, corruptBundle: true)

        let first = await send(alice, to: bob, "one")
        XCTAssertEqual(first.first { $0.deviceId == bob.deviceId }, accepted(bob))
        XCTAssertEqual(first.first { $0.deviceId == broken.deviceId }?.status, .rejected)

        let second = await send(alice, to: bob, "two")
        XCTAssertEqual(second, [accepted(bob)])
        XCTAssertEqual(server.bundleFetches(bob.userId), 1)
    }

    func testAChangedIdentityIsAcceptedReportedAndReachedWithAFreshSession() async throws {
        _ = await send(alice, to: bob, "before")
        _ = try await bob.receive()

        try bob.rekey()
        try alice.store.deleteAllSessions()

        let after = await send(alice, to: bob, "after")
        XCTAssertEqual(after, [accepted(bob)])
        let bobGot = ids(try await bob.receive())
        XCTAssertEqual(bobGot, ["after"])
        XCTAssertEqual(alice.logger.snapshot().filter { $0.hasPrefix("identity changed") },
                       ["identity changed for \(bob.deviceId).1"])
        XCTAssertEqual(server.bundleFetches(bob.userId), 2)
    }

    func testAPeersNewIdentityInAnIncomingPrekeyMessageReplacesTheOldSession() async throws {
        _ = await send(alice, to: bob, "before")
        _ = try await bob.receive()
        _ = await send(bob, to: alice, "ack")
        _ = try await alice.receive()

        try bob.rekey()
        try bob.store.deleteAllSessions()
        _ = await send(bob, to: alice, "from-new-identity")
        let aliceGot = ids(try await alice.receive())
        XCTAssertEqual(aliceGot, ["from-new-identity"])

        _ = await send(alice, to: bob, "to-new-identity")
        let bobGot = ids(try await bob.receive())
        XCTAssertEqual(bobGot, ["to-new-identity"])
        XCTAssertEqual(server.bundleFetches(bob.userId), 1)
    }
}
