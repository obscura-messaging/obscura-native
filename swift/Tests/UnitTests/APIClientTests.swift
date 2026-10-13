import Foundation
import XCTest
@testable import ObscuraKit

final class APIClientTests: XCTestCase {
    override func setUp() {
        RetryProtocol.responses.withLock { $0 = [] }
        URLProtocol.registerClass(RetryProtocol.self)
    }

    override func tearDown() {
        URLProtocol.unregisterClass(RetryProtocol.self)
    }

    func testRetryAfterIsClampedToOneToTenSeconds() {
        XCTAssertEqual(APIClient.retryDelay(retryAfter: nil), 1)
        XCTAssertEqual(APIClient.retryDelay(retryAfter: "0"), 1)
        XCTAssertEqual(APIClient.retryDelay(retryAfter: "soon"), 1)
        XCTAssertEqual(APIClient.retryDelay(retryAfter: "3"), 3)
        XCTAssertEqual(APIClient.retryDelay(retryAfter: "120"), 10)
    }

    func testA429OrA503IsRetriedUntilItSucceeds() async throws {
        RetryProtocol.responses.withLock { $0 = [(429, "0"), (503, nil), (200, nil)] }
        let api = try APIClient(baseURL: "https://\(RetryProtocol.host)")

        let ticket = try await api.fetchGatewayTicket()

        XCTAssertEqual(ticket, "t")
        XCTAssertEqual(RetryProtocol.responses.withLock { $0.count }, 0)
    }

    func testRetriesStopAfterTwoAndTheErrorCarriesStatusAndBody() async throws {
        RetryProtocol.responses.withLock { $0 = [(429, nil), (429, nil), (429, nil), (200, nil)] }
        let api = try APIClient(baseURL: "https://\(RetryProtocol.host)")

        do {
            _ = try await api.fetchGatewayTicket()
            XCTFail("a third 429 must surface")
        } catch {
            XCTAssertEqual(error as? ObscuraError, .http(status: 429, body: "slow down"))
        }
        XCTAssertEqual(RetryProtocol.responses.withLock { $0.count }, 1)
    }

    func testOtherFailuresAreNotRetried() async throws {
        RetryProtocol.responses.withLock { $0 = [(500, nil), (200, nil)] }
        let api = try APIClient(baseURL: "https://\(RetryProtocol.host)")

        do {
            _ = try await api.fetchGatewayTicket()
            XCTFail("a 500 must surface")
        } catch {
            XCTAssertEqual((error as? ObscuraError)?.code, "HTTP_ERROR")
        }
        XCTAssertEqual(RetryProtocol.responses.withLock { $0.count }, 1)
    }

    func testCleartextIsRefusedExceptOnLoopback() {
        XCTAssertThrowsError(try APIClient(baseURL: "http://example.com"))
        XCTAssertNoThrow(try APIClient(baseURL: "http://localhost:3000"))
        XCTAssertNoThrow(try APIClient(baseURL: "http://127.0.0.1:3000"))
        XCTAssertNoThrow(try APIClient(baseURL: "http://[::1]:3000"))
        XCTAssertNoThrow(try APIClient(baseURL: "HTTPS://EXAMPLE.COM"))
        for url in ["http://localhost.evil.test", "http://127.0.0.1.evil.test", "http://localhost@evil.test",
                    "http://127.0.0.10", "https://", "ftp://localhost", "https://user:pass@example.com"] {
            XCTAssertThrowsError(try APIClient(baseURL: url), url)
        }
    }
}

private final class RetryProtocol: URLProtocol {
    static let host = "retry-unit.invalid"
    static let responses = Locked<[(status: Int, retryAfter: String?)]>([])

    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == host }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let (status, retryAfter) = Self.responses.withLock { $0.removeFirst() }
        let headers = retryAfter.map { ["Retry-After": $0] } ?? [:]
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                                              headerFields: headers)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data(status == 200 ? #"{"ticket":"t"}"#.utf8 : "slow down".utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
