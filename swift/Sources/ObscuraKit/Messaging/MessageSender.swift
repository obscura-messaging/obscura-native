import Foundation

/// The one path every outgoing message takes to a user's devices.
///
/// Targets are the user's devices in the `DeviceStore` directory, never this device. A user with no
/// listed device is fetched whole; otherwise a bundle is fetched only for a target without a session,
/// one device at a time, because every bundle fetch consumes a one-time prekey. The server requires
/// each request to cover the named user's devices exactly: a mismatch names the missing devices, which
/// are added and reached in the next round, and a device the server does not know is dropped.
actor MessageSender {
    static let maxRounds = 3

    private let messenger: Messenger
    private let devices: DeviceStore
    private let api: APIClient
    private let logger: ObscuraLogger
    private let selfDeviceId: String

    // Sends to one user are serialized so concurrent first contacts share one bundle fetch.
    private var tails: [String: Task<[SubmissionResult], Never>] = [:]

    init(messenger: Messenger, devices: DeviceStore, api: APIClient, logger: ObscuraLogger, selfDeviceId: String) {
        self.messenger = messenger
        self.devices = devices
        self.api = api
        self.logger = logger
        self.selfDeviceId = selfDeviceId
    }

    /// Encrypts serialized `ClientMessage` bytes to `userId`'s devices and reports each one. Devices
    /// in `acceptedDeviceIds` are reported accepted; they are sent again only when another device of
    /// the user needs the message, because the server takes a user's devices all or none.
    func send(to userId: String, _ clientMessage: Data, acceptedDeviceIds: Set<String>) async -> [SubmissionResult] {
        let previous = tails[userId]
        let task = Task {
            _ = await previous?.value
            return await self.attempt(userId, clientMessage, acceptedDeviceIds)
        }
        tails[userId] = task
        let results = await task.value
        if tails[userId] == task { tails[userId] = nil }
        return results
    }

    /// For control messages that must reach the user: throws unless some device accepted.
    func sendOrThrow(to userId: String, _ clientMessage: Data) async throws {
        let results = await send(to: userId, clientMessage, acceptedDeviceIds: [])
        guard results.contains(where: { $0.status == .accepted }) else {
            throw ObscuraError.sendFailed(results.map { $0.error ?? $0.status.rawValue }.joined(separator: "; "))
        }
    }

    private struct Round {
        let results: [SubmissionResult]
        /// False when the directory changed under this round; the next round re-reads it.
        let settled: Bool
    }

    private func attempt(_ userId: String, _ data: Data, _ accepted: Set<String>) async -> [SubmissionResult] {
        var results: [SubmissionResult] = []
        do {
            for _ in 0..<Self.maxRounds {
                let round = try await self.round(userId, data, accepted)
                results = round.results
                if round.settled { break }
            }
        } catch {
            logger.sessionEstablishFailed(userId: userId, reason: "\(error)")
            return [rejected(userId, nil, error)]
        }
        return results.map { result in
            guard let deviceId = result.deviceId, accepted.contains(deviceId) else { return result }
            return SubmissionResult(userId: userId, deviceId: deviceId, status: .accepted)
        }
    }

    /// Every request names `userId`, so the server reports its devices this one does not target, even
    /// when it targets none of them.
    private func round(_ userId: String, _ data: Data, _ accepted: Set<String>) async throws -> Round {
        var listed = try await devices.deviceIds(of: userId)
        if listed.isEmpty {
            try await establish(userId, try fetchAll(userId))
            listed = try await devices.deviceIds(of: userId)
        }
        let isSelf = listed.contains(selfDeviceId)
        let targets = listed.filter { $0 != selfDeviceId }
        if !targets.isEmpty, targets.allSatisfy(accepted.contains) {
            return Round(results: targets.map { SubmissionResult(userId: userId, deviceId: $0, status: .accepted) },
                         settled: true)
        }

        var gone: [String] = []
        for deviceId in targets {
            guard try await !messenger.hasSession(deviceId) else { continue }
            do {
                try await establish(userId, [try await api.fetchPreKeyBundle(userId, deviceId: deviceId)])
            } catch ObscuraError.http(status: 404, body: _) {
                gone.append(deviceId)
            }
        }
        try await devices.remove(userId: userId, deviceIds: gone)

        var submissions: [Obscura_V1_SendMessageRequest.Submission] = []
        for deviceId in targets where !gone.contains(deviceId) {
            submissions.append(try await messenger.prepareSubmission(deviceId: deviceId, clientMessage: data))
        }
        let outcome = await messenger.submit(userId: userId, submissions: submissions)
        let invalid = outcome.submitted.filter { $0.failure == .invalidDevice }.compactMap(\.result.deviceId)
        try await devices.remove(userId: userId, deviceIds: invalid)
        let kept = outcome.submitted.filter { $0.failure != .invalidDevice }.map(\.result)
        if !outcome.missingDeviceIds.isEmpty {
            try await devices.add(userId: userId, deviceIds: outcome.missingDeviceIds)
            let mismatch = rejected(userId, nil, "devices not covered: \(outcome.missingDeviceIds.joined(separator: ", "))")
            return Round(results: kept.isEmpty ? [mismatch] : kept, settled: false)
        }
        if kept.isEmpty { return Round(results: isSelf ? [] : [noDevices(userId)], settled: true) }
        return Round(results: kept, settled: true)
    }

    /// Consumes one one-time prekey on every device of `userId`. 404 means the user has no devices.
    private func fetchAll(_ userId: String) async throws -> [PreKeyBundleResponse] {
        do {
            return try await api.fetchPreKeyBundles(userId).filter { $0.deviceId != selfDeviceId }
        } catch ObscuraError.http(status: 404, body: _) {
            return []
        }
    }

    private func establish(_ userId: String, _ bundles: [PreKeyBundleResponse]) async throws {
        try await messenger.establishSessions(userId: userId, bundles)
    }

    private func noDevices(_ userId: String) -> SubmissionResult {
        rejected(userId, nil, "no devices found")
    }

    private func rejected(_ userId: String, _ deviceId: String?, _ error: Any) -> SubmissionResult {
        SubmissionResult(userId: userId, deviceId: deviceId, status: .rejected, error: "\(error)")
    }
}
