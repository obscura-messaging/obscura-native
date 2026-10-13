import Foundation
import XCTest
@testable import ObscuraKit

final class FriendRemovalTests: XCTestCase {
    func testRemovingAFriendForgetsTheFriendshipAndDevicesAndTheirAnnouncementsStopApplying() async throws {
        let client = try ObscuraClient(apiURL: "https://obscura.invalid", logger: NoOpLogger())
        try await client.friends.add("peer", "alice", status: .accepted)
        try await client.devices.add(Device(userId: "peer", deviceId: "dev-a"))

        try await client.removeFriend("peer")
        let applied = try await client.devices.applyAnnounce("peer", devices: [Device(userId: "peer", deviceId: "dev-b")],
                                                             timestamp: 1_800_000_000_000)

        let friend = await client.friends.getFriend("peer")
        let devices = try await client.devices.devices(of: "peer")
        XCTAssertNil(friend)
        XCTAssertFalse(applied)
        XCTAssertEqual(devices, [])
    }
}
