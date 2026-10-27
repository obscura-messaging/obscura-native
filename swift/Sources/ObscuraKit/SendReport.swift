import Foundation

public enum SubmissionStatus: String, Codable, Sendable {
    case accepted, rejected, unknown
}

public struct SubmissionResult: Codable, Sendable, Equatable {
    public let userId: String
    public let deviceId: String?
    public let status: SubmissionStatus
    public let error: String?

    init(userId: String, deviceId: String?, status: SubmissionStatus, error: String? = nil) {
        self.userId = userId
        self.deviceId = deviceId
        self.status = status
        self.error = error
    }
}

public struct SendReport: Codable, Sendable, Equatable {
    public let recipients: [SubmissionResult]
    public let selfSync: [SubmissionResult]
}

/// A submission's result and, when it failed, the server's error code.
struct Submitted: Equatable {
    let result: SubmissionResult
    let failure: Obscura_V1_SendMessageResponse.ErrorCode?

    init(_ result: SubmissionResult, failure: Obscura_V1_SendMessageResponse.ErrorCode? = nil) {
        self.result = result
        self.failure = failure
    }
}

/// One request to one user: each submission's result, and the user's current devices it did not target.
struct SubmitOutcome: Equatable {
    let submitted: [Submitted]
    let missingDeviceIds: [String]
}

func submitOutcome(
    userId: String,
    submissions: [Obscura_V1_SendMessageRequest.Submission],
    response: Obscura_V1_SendMessageResponse
) throws -> SubmitOutcome {
    let ids = Set(submissions.map(\.submissionID))
    let failures = response.failedSubmissions
    guard failures.allSatisfy({ ids.contains($0.submissionID) }),
          Set(failures.map(\.submissionID)).count == failures.count else {
        throw ObscuraError.protocolViolation("unknown or duplicate submission ids in send response")
    }
    guard response.deviceMismatches.allSatisfy({ uuidString($0.userID) == userId }) else {
        throw ObscuraError.protocolViolation("device mismatch for a user this request did not address")
    }
    let missing = try response.deviceMismatches.flatMap(\.missingDeviceIds).map { bytes -> String in
        guard let id = uuidString(bytes) else { throw ObscuraError.protocolViolation("missing device id is not a UUID") }
        return id
    }
    let byId = Dictionary(uniqueKeysWithValues: failures.map { ($0.submissionID, $0) })
    let submitted = submissions.map { submission in
        let failure = byId[submission.submissionID]
        return Submitted(
            SubmissionResult(
                userId: userId,
                deviceId: uuidString(submission.deviceID),
                status: failure == nil ? .accepted : .rejected,
                error: failure.map { $0.errorMessage.isEmpty ? "\($0.errorCode)" : $0.errorMessage }
            ),
            failure: failure?.errorCode
        )
    }
    return SubmitOutcome(submitted: submitted, missingDeviceIds: missing)
}
