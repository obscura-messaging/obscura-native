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
        let outcome = try submitOutcome(userId: "user", submissions: [a, b], response: response)
        let results = outcome.submitted.map(\.result)
        XCTAssertEqual(results.map(\.status), [.rejected, .accepted])
        XCTAssertEqual(outcome.submitted.map(\.failure), [.invalidDevice, nil])
        XCTAssertEqual(outcome.missingDeviceIds, [])
        XCTAssertEqual(results.first?.error, "Device not found")
        XCTAssertNil(results.last?.error)
        XCTAssertEqual(results.last?.deviceId, "02020202-0202-0202-0202-020202020202")
    }

    func testADeviceMismatchNamesTheMissingDevicesOfTheAddressedUser() throws {
        let userId = "03030303-0303-0303-0303-030303030303"
        let a = submission(1)
        var failure = Obscura_V1_SendMessageResponse.FailedSubmission()
        failure.submissionID = a.submissionID
        failure.errorCode = .deviceMismatch
        var mismatch = Obscura_V1_SendMessageResponse.DeviceMismatch()
        mismatch.userID = Data(repeating: 3, count: 16)
        mismatch.missingDeviceIds = [Data(repeating: 4, count: 16)]
        var response = Obscura_V1_SendMessageResponse()
        response.failedSubmissions = [failure]
        response.deviceMismatches = [mismatch]

        let outcome = try submitOutcome(userId: userId, submissions: [a], response: response)

        XCTAssertEqual(outcome.submitted.map(\.failure), [.deviceMismatch])
        XCTAssertEqual(outcome.submitted.map(\.result.status), [.rejected])
        XCTAssertEqual(outcome.missingDeviceIds, ["04040404-0404-0404-0404-040404040404"])
        XCTAssertThrowsError(try submitOutcome(userId: "another-user", submissions: [a], response: response))
        response.deviceMismatches[0].missingDeviceIds = [Data([1])]
        XCTAssertThrowsError(try submitOutcome(userId: userId, submissions: [a], response: response))
    }

    func testUnknownAndDuplicateResponseIDsAreNotSuccess() throws {
        let a = submission(1)
        var failure = Obscura_V1_SendMessageResponse.FailedSubmission()
        failure.submissionID = submission(2).submissionID
        var response = Obscura_V1_SendMessageResponse()
        response.failedSubmissions = [failure]
        XCTAssertThrowsError(try submitOutcome(userId: "user", submissions: [a], response: response))
        failure.submissionID = a.submissionID
        response.failedSubmissions = [failure, failure]
        XCTAssertThrowsError(try submitOutcome(userId: "user", submissions: [a], response: response))
    }

    func testScopedSubmitReadsHTTP200FailuresAndMalformedResponses() async throws {
        XCTAssertTrue(URLProtocol.registerClass(SubmissionResponseProtocol.self))
        defer { URLProtocol.unregisterClass(SubmissionResponseProtocol.self) }
        let api = try APIClient(baseURL: "https://submission-unit.invalid")
        let messenger = Messenger(api: api, store: try signalStoreWithIdentity())
        let a = submission(1)
        let rejected = await messenger.submit(userId: "user", submissions: [a]).submitted.map(\.result)
        XCTAssertEqual(rejected.map(\.status), [.rejected])
        let malformed = await messenger.submit(userId: "user", submissions: [submission(2)]).submitted.map(\.result)
        XCTAssertEqual(malformed.map(\.status), [.unknown])
        XCTAssertNotNil(malformed.first?.error)
    }

    func testDefinitiveHTTPRejectionsAreNotUnknownButServerAndTransportFailuresAre() async throws {
        XCTAssertTrue(URLProtocol.registerClass(SubmissionFailureProtocol.self))
        defer { URLProtocol.unregisterClass(SubmissionFailureProtocol.self) }
        let messenger = Messenger(api: try APIClient(baseURL: "https://submission-failure.invalid"),
                                  store: try signalStoreWithIdentity())
        for status in [400, 401, 403, 413, 500] {
            SubmissionFailureProtocol.status.withLock { $0 = status }
            let results = await messenger.submit(userId: "user", submissions: [submission(1), submission(2)]).submitted.map(\.result)
            XCTAssertEqual(results.map(\.status), Array(repeating: [400, 413].contains(status) ? .rejected : .unknown,
                                                         count: 2), "HTTP \(status)")
            XCTAssertTrue(results.allSatisfy { $0.error?.contains("\(status)") == true })
        }
        SubmissionFailureProtocol.status.withLock { $0 = nil }
        let transport = await messenger.submit(userId: "user", submissions: [submission(1)]).submitted.map(\.result)
        XCTAssertEqual(transport.map(\.status), [.unknown], "a lost response does not prove non-acceptance")
    }
}

private final class SubmissionFailureProtocol: URLProtocol {
    static let status = Locked<Int?>(nil)
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "submission-failure.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        guard let status = Self.status.withLock({ $0 }) else {
            client?.urlProtocol(self, didFailWithError: URLError(.networkConnectionLost))
            return
        }
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil,
                                                            headerFields: nil)!, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data("pre-processing failure \(status)".utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
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
