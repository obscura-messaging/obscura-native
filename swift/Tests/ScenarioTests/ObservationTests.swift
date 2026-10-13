import XCTest
@testable import ObscuraKit

/// Test canonical store observation and its payload-free bridge-facing wake event.
final class ObservationTests: XCTestCase {

    func testAggregateFriendEventIsPayloadFreeAndHostPullsCurrentRows() async throws {
        let client = try ObscuraClient(apiURL: "https://example.com", logger: NoOpLogger())
        let initialWake = expectation(description: "initial friends wake")
        let changedWake = expectation(description: "changed friends wake")

        let task = Task {
            var wakeCount = 0
            for await event in client.observeEvents() {
                guard case .friendsChanged = event else { continue }
                wakeCount += 1
                if wakeCount == 1 {
                    initialWake.fulfill()
                } else {
                    changedWake.fulfill()
                    break
                }
            }
        }

        await fulfillment(of: [initialWake], timeout: 5)
        try await client.friends.add("alice-id", "alice", status: .accepted)
        await fulfillment(of: [changedWake], timeout: 5)
        task.cancel()

        let friends = await client.getFriends()
        XCTAssertEqual(friends.map(\.userId), ["alice-id"])
    }

    func testDebugLogIsPulledRatherThanEmitted() throws {
        let client = try ObscuraClient(apiURL: "https://example.com", logger: NoOpLogger())
        client.logger.log("pull-only-marker")

        XCTAssertTrue(client.getDebugLog().contains("pull-only-marker"))
    }

    // MARK: - Friends observation

    func testFriendsObservationEmitsOnAdd() async throws {
        let actor = try FriendStore()

        var emitted: [[Friend]] = []
        let expectation = XCTestExpectation(description: "stream emits")

        let task = Task {
            for await friends in actor.observeAccepted().values {
                emitted.append(friends)
                if emitted.count >= 2 { // initial + after add
                    expectation.fulfill()
                    break
                }
            }
        }

        // Wait for initial emission (empty)
        try await Task.sleep(nanoseconds: 100_000_000) // 100ms

        // Add a friend — should trigger second emission
        try await actor.add("alice-id", "alice", status: .accepted)

        await fulfillment(of: [expectation], timeout: 5)
        task.cancel()

        // First emission: empty (initial state)
        XCTAssertEqual(emitted[0].count, 0)
        // Second emission: 1 friend (after add)
        XCTAssertEqual(emitted[1].count, 1)
        XCTAssertEqual(emitted[1][0].username, "alice")
    }

    func testFriendsObservationEmitsOnStatusChange() async throws {
        let actor = try FriendStore()

        // Add a pending friend first
        try await actor.add("bob-id", "bob", status: .pendingReceived)

        var emitted: [[Friend]] = []
        let expectation = XCTestExpectation(description: "accepted emits")

        let task = Task {
            for await friends in actor.observeAccepted().values {
                emitted.append(friends)
                if emitted.count >= 2 {
                    expectation.fulfill()
                    break
                }
            }
        }

        try await Task.sleep(nanoseconds: 100_000_000)

        // Update status to accepted — should trigger emission
        try await actor.updateStatus("bob-id", .accepted)

        await fulfillment(of: [expectation], timeout: 5)
        task.cancel()

        XCTAssertEqual(emitted[0].count, 0, "Initially no accepted friends")
        XCTAssertEqual(emitted[1].count, 1, "After accept, one friend")
    }
}
