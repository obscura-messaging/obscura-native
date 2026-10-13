import Foundation
import XCTest
@testable import ObscuraKit

/// Restoring a stored session whose access token has expired, against each refresh outcome.
final class SessionRestoreTests: XCTestCase {
    private final class MemoryStorage: SessionStorage {
        var data: [String: Any]?
        init(_ data: [String: Any]?) { self.data = data }
        func save(_ data: [String: Any]) { self.data = data }
        func load() -> [String: Any]? { data }
        func clear() { data = nil }
    }

    override func setUp() {
        RefreshProtocol.outcome = .status(503)
        URLProtocol.registerClass(RefreshProtocol.self)
    }

    override func tearDown() {
        URLProtocol.unregisterClass(RefreshProtocol.self)
    }

    private static func jwt(exp: Int) -> String {
        func encode(_ s: String) -> String {
            Data(s.utf8).base64EncodedString()
                .replacingOccurrences(of: "+", with: "-").replacingOccurrences(of: "/", with: "_")
                .replacingOccurrences(of: "=", with: "")
        }
        return "\(encode(#"{"alg":"none"}"#)).\(encode("{\"exp\":\(exp)}")).sig"
    }

    private func restore() async throws -> (ObscuraClient, MemoryStorage) {
        let storage = MemoryStorage(["token": Self.jwt(exp: 1), "refreshToken": "rt", "userId": "u1",
                                     "deviceId": "d1", "username": "alice"])
        let client = try ObscuraClient(apiURL: "https://\(RefreshProtocol.host)", logger: NoOpLogger())
        client.sessionStorage = storage
        try await client.restorePersistedSession()
        return (client, storage)
    }

    func testAServerErrorKeepsTheSessionForAnOfflineLaunch() async throws {
        let (client, storage) = try await restore()

        XCTAssertEqual(client.authState, .authenticated)
        XCTAssertEqual(storage.data?["refreshToken"] as? String, "rt")
    }

    func testAnUnreachableServerKeepsTheSession() async throws {
        RefreshProtocol.outcome = .offline
        let (client, storage) = try await restore()

        XCTAssertEqual(client.authState, .authenticated)
        XCTAssertEqual(storage.data?["refreshToken"] as? String, "rt")
    }

    func testARejectedRefreshTokenSignsOutAndForgetsTheSession() async throws {
        RefreshProtocol.outcome = .status(401)
        let storage = MemoryStorage(["token": Self.jwt(exp: 1), "refreshToken": "rt", "userId": "u1",
                                     "deviceId": "d1", "username": "alice"])
        let client = try ObscuraClient(apiURL: "https://\(RefreshProtocol.host)", logger: NoOpLogger())
        client.sessionStorage = storage

        do {
            try await client.restorePersistedSession()
            XCTFail("a rejected refresh token must not restore")
        } catch {}

        XCTAssertEqual(client.authState, .loggedOut)
        XCTAssertNil(storage.data)
    }

    func testASessionWithNoRefreshTokenIsRejected() async throws {
        let client = try ObscuraClient(apiURL: "https://\(RefreshProtocol.host)", logger: NoOpLogger())
        await client.restoreSession(token: Self.jwt(exp: 1), refreshToken: nil, userId: "u1",
                                    deviceId: "d1", username: "alice")

        let status = await client.ensureFreshToken()

        XCTAssertEqual(status, .rejected)
        XCTAssertEqual(client.authState, .loggedOut)
    }
}

private final class RefreshProtocol: URLProtocol {
    enum Outcome { case status(Int), offline }
    static let host = "restore-unit.invalid"
    nonisolated(unsafe) static var outcome = Outcome.status(503)

    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == host }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        switch Self.outcome {
        case .offline:
            client?.urlProtocol(self, didFailWithError: URLError(.notConnectedToInternet))
        case .status(let code):
            client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: code, httpVersion: nil,
                                                                  headerFields: nil)!, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: Data("{}".utf8))
            client?.urlProtocolDidFinishLoading(self)
        }
    }
    override func stopLoading() {}
}
