import Foundation
import GRDB
import XCTest
@testable import ObscuraKit

/// The device directory: the only record of which devices each user has.
final class DeviceStoreTests: XCTestCase {
    private var devices: DeviceStore!
    private var friends: FriendStore!

    override func setUp() async throws {
        let db = try DatabaseQueue()
        devices = try DeviceStore(db: db)
        friends = try FriendStore(db: db)
    }

    private func assertDevices(of userId: String, _ expected: [Device],
                               file: StaticString = #filePath, line: UInt = #line) async throws {
        let actual = try await devices.devices(of: userId)
        XCTAssertEqual(actual, expected, file: file, line: line)
    }

    private func assertDeviceIds(of userId: String, _ expected: [String],
                                 file: StaticString = #filePath, line: UInt = #line) async throws {
        let actual = try await devices.devices(of: userId).map(\.deviceId)
        XCTAssertEqual(actual, expected, file: file, line: line)
    }

    private func announce(_ userId: String, _ deviceId: String, at timestamp: UInt64) async throws -> Bool {
        try await devices.applyAnnounce(userId, devices: [Device(userId: userId, deviceId: deviceId)], timestamp: timestamp)
    }

    func testDevicesAreListedPerUser() async throws {
        try await devices.add(Device(userId: "me", deviceId: "d1", name: "iPhone"))
        try await devices.add(Device(userId: "peer", deviceId: "d2"))

        try await assertDevices(of: "me", [Device(userId: "me", deviceId: "d1", name: "iPhone")])
        try await assertDeviceIds(of: "peer", ["d2"])
    }

    func testReplaceSwapsAUsersWholeListAndLeavesOthersAlone() async throws {
        try await devices.add(Device(userId: "me", deviceId: "old"))
        try await devices.add(Device(userId: "peer", deviceId: "p1"))

        try await devices.replace("me", devices: [Device(userId: "me", deviceId: "a"), Device(userId: "me", deviceId: "b")])

        try await assertDeviceIds(of: "me", ["a", "b"])
        try await assertDeviceIds(of: "peer", ["p1"])
    }

    func testADeviceClaimedForOneUserCannotBeTakenFromAnother() async throws {
        try await devices.add(Device(userId: "victim", deviceId: "d1"))
        try await friends.add("mallory", "mallory", status: .accepted)

        _ = try await announce("mallory", "d1", at: 1_000)

        try await assertDeviceIds(of: "victim", ["d1"])
        try await assertDeviceIds(of: "mallory", ["d1"])
    }

    func testAServerRefreshKeepsKnownNamesAndDropsDevicesTheServerNoLongerLists() async throws {
        try await devices.replace("peer", devices: [Device(userId: "peer", deviceId: "keep", name: "Phone"),
                                                    Device(userId: "peer", deviceId: "gone", name: "Tablet")])

        try await devices.replaceFromServer("peer", deviceIds: ["keep", "new"])

        try await assertDevices(of: "peer", [Device(userId: "peer", deviceId: "keep", name: "Phone"),
                                             Device(userId: "peer", deviceId: "new")])
    }

    func testAnnouncementsApplyOnlyToFriendsAndOnlyWhenNewer() async throws {
        let fromStranger = try await announce("stranger", "d", at: 1_000)
        XCTAssertFalse(fromStranger)
        try await assertDeviceIds(of: "stranger", [])

        try await friends.add("peer", "alice", status: .accepted)
        let newer = try await announce("peer", "v2", at: 2_000)
        let older = try await announce("peer", "v1", at: 1_000)
        let sameTime = try await announce("peer", "v1", at: 2_000)

        XCTAssertEqual([newer, older, sameTime], [true, false, false])
        try await assertDeviceIds(of: "peer", ["v2"])
    }

    func testAServerRefreshOutranksAnnouncementsSentBeforeIt() async throws {
        try await friends.add("peer", "alice", status: .accepted)
        try await devices.replaceFromServer("peer", deviceIds: ["current"])

        let stale = try await announce("peer", "stale", at: UInt64(Date().timeIntervalSince1970 * 1000) - 1_000)

        XCTAssertFalse(stale)
        try await assertDeviceIds(of: "peer", ["current"])
    }

    func testClearAllRemovesIdentityAndEveryDevice() async throws {
        await devices.storeIdentity(DeviceIdentity(deviceId: "device-1"))
        try await devices.add(Device(userId: "me", deviceId: "device-1"))

        await devices.clearAll()

        let identity = await devices.getIdentity()
        XCTAssertNil(identity)
        try await assertDeviceIds(of: "me", [])
    }
}
