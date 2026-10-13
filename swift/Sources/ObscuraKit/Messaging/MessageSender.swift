import Foundation

/// The one path every outgoing message takes to a user's devices.
///
/// Devices come from the ``DeviceStore`` directory and existing Signal sessions are reused.
/// Fetching a user's prekey bundles consumes a one-time prekey on every one of their devices, so it
/// happens only when the directory cannot be used as it is:
///
/// - the user has no listed devices;
/// - a target device has no session (a newly announced device, or sessions cleared by takeover);
/// - the server rejects a listed device as nonexistent. Then the send retries the devices the
///   server lists now, once.
///
/// A refresh builds sessions only for devices that have none and replaces the user's directory
/// entry. A device whose bundle cannot be processed is left out, so it cannot cause a fetch on
/// every later send. This device is never a target.
actor MessageSender {
    private let messenger: Messenger
    private let devices: DeviceStore
    private let api: APIClient
    private let selfDeviceId: String?

    // Sends to one user are serialized, so concurrent first contacts share one refresh. Actors
    // are reentrant across `await`, so the serialization is explicit.
    private var tails: [String: Task<[SubmissionResult], Never>] = [:]

    init(messenger: Messenger, devices: DeviceStore, api: APIClient, selfDeviceId: String?) {
        self.messenger = messenger
        self.devices = devices
        self.api = api
        self.selfDeviceId = selfDeviceId
    }

    /// Encrypt serialized `ClientMessage` bytes to `userId`'s devices and report each one. Devices
    /// in `acceptedDeviceIds` are reported accepted without being sent again. `only` narrows the
    /// targets.
    func send(to userId: String, _ clientMessageData: Data,
              acceptedDeviceIds: Set<String> = [], only: Set<String>? = nil) async -> [SubmissionResult] {
        let previous = tails[userId]
        let task = Task {
            _ = await previous?.value
            return await self.attempt(userId, clientMessageData, acceptedDeviceIds, only)
        }
        tails[userId] = task
        let results = await task.value
        if tails[userId] == task { tails[userId] = nil }
        return results
    }

    /// ``send(to:_:acceptedDeviceIds:only:)``, failing when no device accepted. For control
    /// messages that must reach the user.
    func sendOrThrow(to userId: String, _ clientMessageData: Data, only: Set<String>? = nil) async throws {
        let results = await send(to: userId, clientMessageData, only: only)
        guard results.contains(where: { $0.status == .accepted }) else {
            throw ObscuraClient.ObscuraError.sendFailed(results.map { $0.error ?? $0.status.rawValue }.joined(separator: "; "))
        }
    }

    private struct Resolved {
        let deviceIds: [String]
        let failures: [SubmissionResult]
    }

    private func attempt(_ userId: String, _ data: Data, _ accepted: Set<String>, _ only: Set<String>?) async -> [SubmissionResult] {
        func targets(_ ids: [String]) -> [String] {
            ids.filter { $0 != selfDeviceId && (only?.contains($0) ?? true) }
        }
        let listed: [String]
        do {
            listed = try await devices.devices(of: userId).map(\.deviceId)
        } catch {
            return [rejected(userId, nil, error)]
        }
        var refreshed = listed.isEmpty
        for id in targets(listed) where !refreshed && !accepted.contains(id) {
            refreshed = !((try? await messenger.hasSession(id)) ?? false)
        }

        let resolved: Resolved
        if refreshed {
            switch await refresh(userId) {
            case .success(let fresh): resolved = fresh
            case .failure(let error): return [rejected(userId, nil, error)]
            }
        } else {
            resolved = Resolved(deviceIds: listed, failures: [])
        }
        if resolved.deviceIds.isEmpty && resolved.failures.isEmpty {
            return [rejected(userId, nil, "No devices found")]
        }

        let firstTargets = targets(resolved.deviceIds)
        let submitted = await submit(userId, firstTargets, data, accepted)
        let firstPass = resolved.failures + submitted.map(\.result)
        let missing = Set(submitted.filter(\.deviceMissing).compactMap(\.result.deviceId))
        guard !refreshed, !missing.isEmpty, case .success(let fresh) = await refresh(userId) else {
            return firstPass
        }
        let gone = missing.subtracting(fresh.deviceIds)
        let attempted = Set(firstTargets)
        let added = targets(fresh.deviceIds).filter { !attempted.contains($0) }
        return firstPass.filter { $0.deviceId.map { !gone.contains($0) } ?? true }
            + fresh.failures
            + (await submit(userId, added, data, accepted)).map(\.result)
    }

    private func refresh(_ userId: String) async -> Result<Resolved, Error> {
        do {
            let bundles: [PreKeyBundleResponse]
            do {
                bundles = try await api.fetchPreKeyBundles(userId)
            } catch let error as APIClient.APIError where error.status == 404 {
                bundles = []
            }
            await rateLimitDelay()
            var failures: [SubmissionResult] = []
            for bundle in bundles where bundle.deviceId != selfDeviceId {
                do {
                    try await messenger.establishSession(bundle)
                } catch {
                    failures.append(rejected(userId, bundle.deviceId, error))
                }
            }
            let failed = Set(failures.compactMap(\.deviceId))
            let deviceIds = bundles.map(\.deviceId).filter { !failed.contains($0) }
            try await devices.replaceFromServer(userId, deviceIds: deviceIds)
            return .success(Resolved(deviceIds: deviceIds, failures: failures))
        } catch {
            return .failure(error)
        }
    }

    private func submit(_ userId: String, _ deviceIds: [String], _ data: Data, _ accepted: Set<String>) async -> [Submitted] {
        var results: [Submitted] = []
        var submissions: [Obscura_V1_SendMessageRequest.Submission] = []
        for id in deviceIds {
            if accepted.contains(id) {
                results.append(Submitted(SubmissionResult(userId: userId, deviceId: id, status: .accepted)))
                continue
            }
            do {
                submissions.append(try await messenger.prepareSubmission(deviceId: id, clientMessageData: data))
            } catch {
                results.append(Submitted(rejected(userId, id, error)))
            }
        }
        return results + (await messenger.submit(userId: userId, submissions: submissions))
    }

    private func rejected(_ userId: String, _ deviceId: String?, _ error: Any) -> SubmissionResult {
        SubmissionResult(userId: userId, deviceId: deviceId, status: .rejected, error: "\(error)")
    }
}
