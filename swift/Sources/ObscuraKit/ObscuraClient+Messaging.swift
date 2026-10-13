import Foundation

extension ObscuraClient {
    // MARK: - Send

    /// Sends an entry to every device of the named users and to this user's other devices. This
    /// device gets nothing, so the app stores its own copy in `entries`.
    public func send(to recipientUserIds: [String], modelKey: String, entryId: String, sentAt: UInt64, payload: Data,
                     acceptedDeviceIds: Set<String>) async throws -> SendReport {
        var message = Obscura_Client_V1_ClientMessage()
        message.appEntry.model = modelKey
        message.appEntry.id = entryId
        message.appEntry.timestamp = sentAt
        message.appEntry.data = payload
        let data = try message.serializedData()

        let (userId, sender) = try await sendContext()
        let generation = sessionGeneration
        var recipients: [SubmissionResult] = []
        for recipient in Self.distinct(recipientUserIds, excluding: userId) {
            try Task.checkCancellation()
            guard generation == sessionGeneration else { throw CancellationError() }
            recipients += await sender.send(to: recipient, data, acceptedDeviceIds: acceptedDeviceIds)
        }
        try Task.checkCancellation()
        guard generation == sessionGeneration else { throw CancellationError() }
        let selfSync: [SubmissionResult]
        do {
            let deviceIds = try await ownDeviceIds()
            do {
                try await devices.replace(userId: userId, deviceIds: deviceIds)
                guard generation == sessionGeneration else { throw CancellationError() }
                selfSync = await sender.send(to: userId, data, acceptedDeviceIds: acceptedDeviceIds)
            } catch {
                guard generation == sessionGeneration, !Task.isCancelled, !(error is CancellationError) else {
                    throw CancellationError()
                }
                selfSync = [SubmissionResult(userId: userId, deviceId: nil, status: .rejected, error: "\(error)")]
            }
        } catch {
            guard generation == sessionGeneration, !Task.isCancelled, !(error is CancellationError) else {
                throw CancellationError()
            }
            selfSync = [SubmissionResult(userId: userId, deviceId: nil, status: .unknown, error: "\(error)")]
        }
        try Task.checkCancellation()
        guard generation == sessionGeneration else { throw CancellationError() }
        return SendReport(recipients: recipients, selfSync: selfSync)
    }

    /// Every send refreshes a near-expiry token once, up front.
    func sendContext() async throws -> (userId: String, sender: MessageSender) {
        try Task.checkCancellation()
        _ = try requireSession()
        let generation = sessionGeneration
        _ = try await freshToken()
        guard generation == sessionGeneration else { throw CancellationError() }
        guard let session, let sender else { throw ObscuraError.notAuthenticated }
        return (session.userId, sender)
    }

    func refreshOwnDirectory() async throws {
        let current = try requireSession()
        try await devices.replace(userId: current.userId, deviceIds: ownDeviceIds())
    }

    private func ownDeviceIds() async throws -> [String] {
        _ = try requireSession()
        let generation = sessionGeneration
        let deviceIds = try await api.fetchOwnDeviceIds()
        guard generation == sessionGeneration else { throw CancellationError() }
        return deviceIds
    }

    func sendControl(_ message: Obscura_Client_V1_ClientMessage, to userId: String) async throws {
        try await sendContext().sender.sendOrThrow(to: userId, message.serializedData())
    }

    static func distinct(_ userIds: [String], excluding excluded: String) -> [String] {
        var seen: Set<String> = [excluded]
        return userIds.filter { seen.insert($0).inserted }
    }

    // MARK: - Friends

    public func addFriendByCode(_ code: String) async throws {
        let decoded = try FriendCode.decode(code)
        try await befriend(decoded.userId, username: decoded.username)
    }

    public func friendCode() throws -> String {
        let session = try requireSession()
        return FriendCode.encode(userId: session.userId, username: session.username)
    }

    func befriend(_ userId: String, username: String) async throws {
        let me = try requireSession()
        let existing = try await friends.friend(userId)?.status
        switch existing {
        case .accepted?: return
        case .pendingReceived?: return try await acceptFriend(userId)
        case .pendingSent?, nil: break
        }
        var message = Obscura_Client_V1_ClientMessage()
        message.friendRequest.username = me.username
        if existing == nil { try await friends.add(userId, username: username, status: .pendingSent) }
        do {
            try await sendControl(message, to: userId)
        } catch {
            if existing == nil { try await friends.removePendingSent(userId) }
            throw error
        }
    }

    public func acceptFriend(_ userId: String) async throws {
        try await sendFriendAccept(to: userId)
        try await friends.updateStatus(userId, .accepted)
    }

    func sendFriendAccept(to userId: String) async throws {
        var message = Obscura_Client_V1_ClientMessage()
        message.friendAccept = Obscura_Client_V1_FriendAccept()
        try await sendControl(message, to: userId)
    }

    /// Forgets the friend and their devices locally. Nothing is sent to them.
    public func removeFriend(_ userId: String) async throws {
        try await friends.remove(userId)
    }

    public func getFriends() async throws -> [Friend] {
        try await friends.all()
    }

    // MARK: - Typing

    /// At most one send per 2 s per (context, state). Delivery failures are logged, never thrown.
    public func sendTyping(to recipientUserIds: [String], contextId: String, state: TypingState) async throws {
        guard !contextId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            throw ObscuraError.invalidArgument("blank contextId")
        }
        _ = try requireSession()
        let key = "\(state.rawValue):\(contextId)"
        let now = Date()
        if let last = typingSentAt[key], now.timeIntervalSince(last) < 2 { return }
        typingSentAt[key] = now

        var message = Obscura_Client_V1_ClientMessage()
        message.typingSignal.contextID = contextId
        message.typingSignal.state = WireCodec.encodeTypingState(state)
        let data = try message.serializedData()

        let (userId, sender) = try await sendContext()
        for recipient in Self.distinct(recipientUserIds, excluding: userId) {
            do {
                try await sender.sendOrThrow(to: recipient, data)
            } catch {
                logger.log("typing signal to \(recipient) failed: \(error)")
            }
        }
    }

    /// The distinct user ids typing in `contextId`, sorted. Yields on subscribe and on every change.
    public nonisolated func observeTyping(contextId: String) -> AsyncStream<[String]> {
        typing.observe(contextId: contextId)
    }

    // MARK: - Attachments and push

    /// Encrypts and uploads; the server only sees ciphertext. The app sends the result in an entry.
    public func uploadAttachment(_ plaintext: Data) async throws -> UploadedAttachment {
        _ = try requireSession()
        let encrypted = try AttachmentCrypto.encrypt(plaintext)
        let response = try await api.uploadAttachment(encrypted.ciphertext)
        return UploadedAttachment(id: response.id, contentKey: encrypted.contentKey, nonce: encrypted.nonce)
    }

    public func downloadDecryptedAttachment(id: String, contentKey: Data, nonce: Data) async throws -> Data {
        _ = try requireSession()
        return try AttachmentCrypto.decrypt(await api.fetchAttachment(id), contentKey: contentKey, nonce: nonce)
    }

    public func registerPushToken(_ token: String) async throws {
        _ = try requireSession()
        try await api.registerPushToken(token)
    }
}
