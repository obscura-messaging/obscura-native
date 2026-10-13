import Foundation

public enum SubmissionStatus: String, Codable, Sendable {
    case accepted, rejected, unknown
}

public struct SubmissionResult: Codable, Sendable, Equatable {
    public let userId: String
    public let deviceId: String?
    public let status: SubmissionStatus
    public let error: String?

    public init(userId: String, deviceId: String?, status: SubmissionStatus, error: String? = nil) {
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

func submissionResults(
    userId: String,
    submissions: [Obscura_V1_SendMessageRequest.Submission],
    response: Obscura_V1_SendMessageResponse
) throws -> [SubmissionResult] {
    let ids = Set(submissions.map(\.submissionID))
    let failures = response.failedSubmissions
    guard failures.allSatisfy({ ids.contains($0.submissionID) }),
          Set(failures.map(\.submissionID)).count == failures.count else {
        throw Messenger.MessengerError.invalidResponse("Unknown or duplicate submission IDs")
    }
    let byId = Dictionary(uniqueKeysWithValues: failures.map { ($0.submissionID, $0) })
    return submissions.map { submission in
        let failure = byId[submission.submissionID]
        return SubmissionResult(
            userId: userId,
            deviceId: uuidFromBytes(submission.deviceID),
            status: failure == nil ? .accepted : .rejected,
            error: failure.map { $0.errorMessage.isEmpty ? "\($0.errorCode)" : $0.errorMessage }
        )
    }
}

func uuidFromBytes(_ data: Data) -> String {
    let hex = data.map { String(format: "%02x", $0) }.joined()
    return [hex.prefix(8), hex.dropFirst(8).prefix(4), hex.dropFirst(12).prefix(4),
            hex.dropFirst(16).prefix(4), hex.dropFirst(20)].joined(separator: "-")
}
