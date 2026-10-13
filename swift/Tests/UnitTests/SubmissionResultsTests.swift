import Foundation
import XCTest
@testable import ObscuraKit

final class SubmissionResultsTests: XCTestCase {
    private func submission(_ value: UInt8) -> Obscura_V1_SendMessageRequest.Submission {
        var submission = Obscura_V1_SendMessageRequest.Submission()
        submission.submissionID = Data(repeating: value, count: 16)
        submission.deviceID = Data(repeating: value, count: 16)
        submission.message = Data([value])
        return submission
    }

    func testPartialRejectionIsScopedToItsSubmission() throws {
        let a = submission(1)
        let b = submission(2)
        var failure = Obscura_V1_SendMessageResponse.FailedSubmission()
        failure.submissionID = a.submissionID
        failure.errorCode = .invalidDevice
        failure.errorMessage = "Device not found"
        var response = Obscura_V1_SendMessageResponse()
        response.failedSubmissions = [failure]
        let results = try submissionResults(userId: "user", submissions: [a, b], response: response)
        XCTAssertEqual(results.map(\.status), [.rejected, .accepted])
        XCTAssertEqual(results.first?.error, "Device not found")
        XCTAssertNil(results.last?.error)
        XCTAssertEqual(results.last?.deviceId, "02020202-0202-0202-0202-020202020202")
    }

    func testUnknownAndDuplicateResponseIDsAreNotSuccess() throws {
        let a = submission(1)
        var failure = Obscura_V1_SendMessageResponse.FailedSubmission()
        failure.submissionID = submission(2).submissionID
        var response = Obscura_V1_SendMessageResponse()
        response.failedSubmissions = [failure]
        XCTAssertThrowsError(try submissionResults(userId: "user", submissions: [a], response: response))
        failure.submissionID = a.submissionID
        response.failedSubmissions = [failure, failure]
        XCTAssertThrowsError(try submissionResults(userId: "user", submissions: [a], response: response))
    }

    func testScopedSubmitReadsHTTP200FailuresAndMalformedResponses() async throws {
        XCTAssertTrue(URLProtocol.registerClass(SubmissionResponseProtocol.self))
        defer { URLProtocol.unregisterClass(SubmissionResponseProtocol.self) }
        let api = APIClient(baseURL: "https://submission-unit.invalid")
        let messenger = Messenger(api: api, store: try PersistentSignalStore())
        let a = submission(1)
        let rejected = try await messenger.submit(userId: "user", submissions: [a])
        XCTAssertEqual(rejected.map(\.status), [.rejected])
        let malformed = try await messenger.submit(userId: "user", submissions: [submission(2)])
        XCTAssertEqual(malformed.map(\.status), [.unknown])
        XCTAssertNotNil(malformed.first?.error)
    }
}

private final class SubmissionResponseProtocol: URLProtocol {
    override class func canInit(with request: URLRequest) -> Bool {
        request.url?.host == "submission-unit.invalid"
    }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil,
                                       headerFields: ["Content-Type": "application/x-protobuf"])!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        // Rejection for submission 1; submission 2 receives an unrelated ID and must remain uncertain.
        client?.urlProtocol(self, didLoad: Data([0x0a, 0x14, 0x0a, 0x10] +
            Array(repeating: UInt8(1), count: 16) + [0x10, 0x01]))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
