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

/// One submission and the recipient user it addresses.
struct UserSubmission: Equatable {
    let userId: String
    let submission: Obscura_V1_SendMessageRequest.Submission
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

/// One request to one or more users: each submission's result, and each mismatched user's untargeted devices.
struct SubmitOutcome: Equatable {
    let submitted: [Submitted]
    let mismatches: [String: [String]]

    var missingDeviceIds: [String] { mismatches.values.flatMap { $0 } }

    init(submitted: [Submitted], mismatches: [String: [String]] = [:]) {
        self.submitted = submitted
        self.mismatches = mismatches
    }

    init(submitted: [Submitted], missingDeviceIds: [String]) {
        self.submitted = submitted
        self.mismatches = missingDeviceIds.isEmpty ? [:] : ["": missingDeviceIds]
    }
}

func submitOutcome(
    userIds: [String],
    submissions: [UserSubmission],
    response: Obscura_V1_SendMessageResponse
) throws -> SubmitOutcome {
    let ids = Set(submissions.map(\.submission.submissionID))
    let failures = response.failedSubmissions
    guard failures.allSatisfy({ ids.contains($0.submissionID) }),
          Set(failures.map(\.submissionID)).count == failures.count else {
        throw ObscuraError.protocolViolation("unknown or duplicate submission ids in send response")
    }
    let addressed = Set(userIds)
    var mismatches: [String: [String]] = [:]
    for mismatch in response.deviceMismatches {
        guard let user = uuidString(mismatch.userID), addressed.contains(user), mismatches[user] == nil else {
            throw ObscuraError.protocolViolation("device mismatch for a user this request did not address")
        }
        mismatches[user] = try mismatch.missingDeviceIds.map { bytes in
            guard let id = uuidString(bytes) else { throw ObscuraError.protocolViolation("missing device id is not a UUID") }
            return id
        }
    }
    let byId = Dictionary(uniqueKeysWithValues: failures.map { ($0.submissionID, $0) })
    let submitted = try submissions.map { item -> Submitted in
        let failure = byId[item.submission.submissionID]
        if failure?.errorCode == .deviceMismatch, mismatches[item.userId] == nil {
            throw ObscuraError.protocolViolation("device mismatch failure without a mismatch entry")
        }
        return Submitted(
            SubmissionResult(
                userId: item.userId,
                deviceId: uuidString(item.submission.deviceID),
                status: failure == nil ? .accepted : .rejected,
                error: failure.map { $0.errorMessage.isEmpty ? "\($0.errorCode)" : $0.errorMessage }
            ),
            failure: failure?.errorCode
        )
    }
    return SubmitOutcome(submitted: submitted, mismatches: mismatches)
}

func submitOutcome(
    userId: String,
    submissions: [Obscura_V1_SendMessageRequest.Submission],
    response: Obscura_V1_SendMessageResponse
) throws -> SubmitOutcome {
    try submitOutcome(
        userIds: [userId],
        submissions: submissions.map { UserSubmission(userId: userId, submission: $0) },
        response: response
    )
}
