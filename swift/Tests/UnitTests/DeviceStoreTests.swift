import Foundation
import XCTest
@testable import ObscuraKit

final class DeviceStoreTests: XCTestCase {
    private var devices: DeviceStore!
    private var friends: FriendStore!

    override func setUp() async throws {
        let db = try ObscuraDatabase.inMemory()
        devices = DeviceStore(db: db)
        friends = FriendStore(db: db)
    }

    func testDevicesAreListedPerUser() async throws {
        try await devices.add(userId: "me", deviceId: "d1")
        try await devices.add(userId: "peer", deviceId: "d2")

        let mine = try await devices.deviceIds(of: "me")
        let theirs = try await devices.deviceIds(of: "peer")
        XCTAssertEqual(mine, ["d1"])
        XCTAssertEqual(theirs, ["d2"])
    }

    func testReplaceSwapsAUsersWholeListAndLeavesOthersAlone() async throws {
        try await devices.add(userId: "me", deviceId: "old")
        try await devices.add(userId: "peer", deviceId: "p1")

        try await devices.replace(userId: "me", deviceIds: ["b", "a", "a"])

        let mine = try await devices.deviceIds(of: "me")
        let theirs = try await devices.deviceIds(of: "peer")
        XCTAssertEqual(mine, ["a", "b"])
        XCTAssertEqual(theirs, ["p1"])
    }

    func testTheLocalDeviceIdIsSingular() async throws {
        let none = try await devices.localDeviceId()
        XCTAssertNil(none)

        try await devices.setLocalDeviceId("first")
        try await devices.setLocalDeviceId("second")

        let local = try await devices.localDeviceId()
        XCTAssertEqual(local, "second")
    }

    func testRemovingAFriendForgetsTheirDevices() async throws {
        try await friends.add("peer", username: "alice", status: .accepted)
        try await devices.add(userId: "peer", deviceId: "dev-a")
        try await devices.add(userId: "other", deviceId: "dev-b")

        try await friends.remove("peer")

        let friend = try await friends.friend("peer")
        let peerDevices = try await devices.deviceIds(of: "peer")
        let otherDevices = try await devices.deviceIds(of: "other")
        XCTAssertNil(friend)
        XCTAssertEqual(peerDevices, [])
        XCTAssertEqual(otherDevices, ["dev-b"])
    }
}
