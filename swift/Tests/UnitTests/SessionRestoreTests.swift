import Foundation
import XCTest
@testable import ObscuraKit

/// Restoring a stored session whose access token has expired, against each refresh outcome.
final class SessionRestoreTests: XCTestCase {
    override func setUp() {
        StubProtocol.outcome = .status(503)
        URLProtocol.registerClass(StubProtocol.self)
    }

    override func tearDown() {
        URLProtocol.unregisterClass(StubProtocol.self)
    }

    static func jwt(exp: Int) -> String {
        func encode(_ s: String) -> String {
            Data(s.utf8).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        return "\(encode(#"{"alg":"none"}"#)).\(encode("{\"exp\":\(exp),\"sub\":\"u1\",\"device_id\":\"d1\"}")).sig"
    }

    private static let stored = ["token": jwt(exp: 1), "refreshToken": "rt", "userId": "u1", "deviceId": "d1",
                                 "username": "alice"]

    private func client(_ storage: MemorySessionStorage, identity: Bool = true) throws -> ObscuraClient {
        let db = try ObscuraDatabase.inMemory()
        if identity { try PersistentSignalStore(db: db, logger: SilentLogger()).install(SignalKeys.generate()) }
        return try makeClient(apiURL: "https://\(StubProtocol.host)", database: db, storage: storage)
    }

    func testAServerErrorKeepsTheSessionForAnOfflineLaunch() async throws {
        let storage = MemorySessionStorage(Self.stored)
        let client = try client(storage)

        let restored = await client.restorePersistedSession()

        XCTAssertTrue(restored)
        XCTAssertEqual(client.authState, .authenticated)
        XCTAssertEqual(client.connectionState, .disconnected)
        XCTAssertEqual(storage.session?["refreshToken"], "rt")
    }

    func testAnUnreachableServerKeepsTheSession() async throws {
        StubProtocol.outcome = .offline
        let storage = MemorySessionStorage(Self.stored)
        let client = try client(storage)

        let restored = await client.restorePersistedSession()

        XCTAssertTrue(restored)
        XCTAssertEqual(client.authState, .authenticated)
        XCTAssertEqual(storage.session?["refreshToken"], "rt")
    }

    func testARejectedRefreshTokenSignsOutAndForgetsTheSession() async throws {
        StubProtocol.outcome = .status(401)
        let storage = MemorySessionStorage(Self.stored)
        let client = try client(storage)

        let restored = await client.restorePersistedSession()

        XCTAssertFalse(restored)
        XCTAssertEqual(client.authState, .loggedOut)
        XCTAssertNil(storage.session)
    }

    func testARefreshRotatesTheStoredTokens() async throws {
        let fresh = Self.jwt(exp: Int(Date().timeIntervalSince1970) + 3600)
        StubProtocol.outcome = .refreshed(token: fresh, refreshToken: "rt2")
        let storage = MemorySessionStorage(Self.stored)
        let client = try client(storage)

        _ = await client.restorePersistedSession()

        XCTAssertEqual(storage.session?["token"], fresh)
        XCTAssertEqual(storage.session?["refreshToken"], "rt2")
        XCTAssertEqual(Set(storage.session?.keys ?? [:].keys), ["token", "refreshToken", "userId", "deviceId", "username"])
    }

    func testConcurrentRefreshesShareOneRequest() async throws {
        let fresh = Self.jwt(exp: Int(Date().timeIntervalSince1970) + 3600)
        StubProtocol.outcome = .refreshed(token: fresh, refreshToken: "rt2")
        StubProtocol.requests.withLock { $0 = 0 }
        let client = try client(MemorySessionStorage(Self.stored))
        _ = await client.restorePersistedSession()
        StubProtocol.requests.withLock { $0 = 0 }

        async let a = client.refresh()
        async let b = client.refresh()
        async let c = client.refresh()
        let statuses = await [a, b, c]

        XCTAssertEqual(statuses, [.fresh, .fresh, .fresh])
        XCTAssertEqual(StubProtocol.requests.withLock { $0 }, 1)
    }

    func testAStoredSessionWithoutASignalIdentityIsNotRestored() async throws {
        let storage = MemorySessionStorage(Self.stored)
        let client = try client(storage, identity: false)

        let restored = await client.restorePersistedSession()

        XCTAssertFalse(restored)
        XCTAssertEqual(client.authState, .loggedOut)
        XCTAssertNil(storage.session)
    }

    func testAnIncompleteStoredSessionIsNotRestored() async throws {
        var partial = Self.stored
        partial["refreshToken"] = nil
        let storage = MemorySessionStorage(partial)
        let client = try client(storage)

        let restored = await client.restorePersistedSession()

        XCTAssertFalse(restored)
        XCTAssertNil(storage.session)
    }

    func testNothingStoredIsNotRestored() async throws {
        let restored = await (try client(MemorySessionStorage())).restorePersistedSession()
        XCTAssertFalse(restored)
    }

    func testLogoutClearsStorageAndKeepsLocalData() async throws {
        let storage = MemorySessionStorage(Self.stored)
        let client = try client(storage)
        _ = await client.restorePersistedSession()
        try await client.friends.add("peer", username: "bob", status: .accepted)

        await client.logout()

        XCTAssertEqual(client.authState, .loggedOut)
        XCTAssertNil(storage.session)
        let userId = await client.userId
        XCTAssertNil(userId)
        let friends = try await client.getFriends()
        XCTAssertEqual(friends.map(\.userId), ["peer"])
        XCTAssertTrue(try client.signalStore.hasIdentity())
    }

    func testWipeDeviceLogsOutAndDeletesLocalData() async throws {
        let storage = MemorySessionStorage(Self.stored)
        let client = try client(storage)
        _ = await client.restorePersistedSession()
        try await client.friends.add("peer", username: "bob", status: .accepted)

        try await client.wipeDevice()

        XCTAssertEqual(client.authState, .loggedOut)
        XCTAssertNil(storage.session)
        let friends = try await client.getFriends()
        XCTAssertEqual(friends, [])
        XCTAssertFalse(try client.signalStore.hasIdentity())
    }
}

final class StubProtocol: URLProtocol {
    enum Outcome {
        case status(Int)
        case offline
        case refreshed(token: String, refreshToken: String)
    }

    static let host = "restore-unit.invalid"
    nonisolated(unsafe) static var outcome = Outcome.status(503)
    static let requests = Locked(0)

    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == host }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        Self.requests.withLock { $0 += 1 }
        func respond(_ status: Int, _ body: Data) {
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                                                  headerFields: nil)!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: body)
            client?.urlProtocolDidFinishLoading(self)
        }
        switch Self.outcome {
        case .offline:
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
        case .status(let code):
            respond(code, Data("{}".utf8))
        case .refreshed(let token, let refreshToken):
            guard request.url?.path == "/v1/sessions/refresh" else { return respond(503, Data()) }
            // Slow enough that concurrent callers overlap.
            Thread.sleep(forTimeInterval: 0.2)
            respond(200, try! JSONSerialization.data(withJSONObject: ["token": token, "refreshToken": refreshToken]))
        }
    }
    override func stopLoading() {}
}
