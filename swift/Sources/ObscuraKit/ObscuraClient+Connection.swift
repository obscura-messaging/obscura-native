import Foundation

extension ObscuraClient {
    /// Returns once the gateway socket is open. Joins a connect in flight; cuts short a pending
    /// reconnect backoff. Safe to call on every app foreground.
    public func connect() async throws {
        guard session != nil else { throw ObscuraError.notAuthenticated }
        if connection.value == .connected { return }
        if let connectTask { return try await connectTask.value }
        reconnectTask?.cancel()
        reconnectTask = nil
        let task = Task { try await self.openSocket() }
        connectTask = task
        defer { if connectTask == task { connectTask = nil } }
        try await task.value
    }

    /// Closes the socket and stops reconnecting.
    public func disconnect() {
        connectionGeneration += 1
        recovering = false
        reconnectAttempts = 0
        connectTask?.cancel()
        connectTask = nil
        reconnectTask?.cancel()
        reconnectTask = nil
        socket?.close()
        socket = nil
        connection.set(.disconnected)
    }

    private func openSocket() async throws {
        let generation = connectionGeneration
        connection.set(recovering ? .reconnecting : .connecting)
        do {
            guard await freshToken() != .rejected else { throw ObscuraError.notAuthenticated }
            let socket = try await GatewaySocket.open(gatewayURL(ticket: api.fetchGatewayTicket()), logger: logger)
            guard generation == connectionGeneration, session != nil else {
                socket.close()
                throw CancellationError()
            }
            install(socket)
        } catch {
            logger.log("gateway connect failed: \(error)")
            if generation == connectionGeneration {
                if recovering && session != nil { scheduleReconnect() } else { connection.set(.disconnected) }
            }
            throw error
        }
    }

    private func gatewayURL(ticket: String) throws -> URL {
        guard var components = URLComponents(url: api.baseURL.appendingPathComponent("v1/gateway"), resolvingAgainstBaseURL: false) else {
            throw ObscuraError.invalidArgument("apiURL \(api.baseURL)")
        }
        components.scheme = components.scheme == "https" ? "wss" : "ws"
        components.queryItems = [URLQueryItem(name: "ticket", value: ticket)]
        guard let url = components.url else { throw ObscuraError.protocolViolation("gateway ticket is not URL-safe") }
        return url
    }

    private func install(_ socket: GatewaySocket) {
        self.socket = socket
        recovering = false
        reconnectAttempts = 0
        connection.set(.connected)
        Task { [weak self] in
            do {
                for try await event in socket.events {
                    await self?.handle(event, from: socket)
                }
            } catch {
                self?.logger.log("gateway closed: \(error)")
            }
            await self?.socketEnded(socket)
        }
    }

    private func socketEnded(_ ended: GatewaySocket) {
        guard socket === ended else { return }
        socket = nil
        recovering = true
        scheduleReconnect()
    }

    /// 1 s doubling to 30 s.
    private func scheduleReconnect() {
        let delay = min(UInt64(1) << UInt64(min(reconnectAttempts, 5)), 30)
        reconnectAttempts += 1
        connection.set(.reconnecting)
        logger.log("gateway reconnect in \(delay)s (attempt \(reconnectAttempts))")
        let generation = connectionGeneration
        reconnectTask = Task { [weak self] in
            guard (try? await Task.sleep(nanoseconds: delay * 1_000_000_000)) != nil else { return }
            await self?.reconnect(generation)
        }
    }

    private func reconnect(_ generation: Int) async {
        guard generation == connectionGeneration else { return }
        reconnectTask = nil
        // A failure has already been logged and the next attempt scheduled.
        _ = try? await connect()
    }

    // MARK: - Receive

    private func handle(_ event: GatewayEvent, from socket: GatewaySocket) async {
        switch event {
        case .preKeyStatus(let count, let threshold):
            if count < threshold { replenishPreKeys() }
        case .envelope(let envelope):
            await receive(envelope, from: socket)
        }
    }

    /// Persist, then ack. Anything that fails before the ack leaves the envelope on the server.
    private func receive(_ envelope: Envelope, from socket: GatewaySocket) async {
        guard let envelopeId = uuidString(envelope.id), let senderUserId = uuidString(envelope.senderId),
              let senderDeviceId = uuidString(envelope.senderDeviceId) else {
            logger.decryptFailed(sourceUserId: uuidString(envelope.senderId) ?? "unknown", reason:
                "malformed envelope (id \(envelope.id.count) bytes, sender \(envelope.senderId.count), "
                + "sender device \(envelope.senderDeviceId.count)); not acked")
            return
        }
        let message: Obscura_Client_V1_ClientMessage
        do {
            let encrypted = try Obscura_Client_V1_EncryptedMessage(serializedBytes: envelope.message)
            message = try Obscura_Client_V1_ClientMessage(
                serializedBytes: await messenger.decrypt(senderDeviceUuid: senderDeviceId, encrypted))
        } catch {
            logger.decryptFailed(sourceUserId: senderUserId, reason: "\(error)")
            return
        }
        do {
            if let modelKey = try await route(message, envelopeId: envelopeId, senderUserId: senderUserId,
                                              senderDeviceId: senderDeviceId) {
                messageContinuation.yield(modelKey)
            }
        } catch {
            logger.databaseError(store: "receive", operation: WireCodec.messageKind(message.payload), reason: "\(error)")
            return
        }
        replenishPreKeysIfLow()
        do {
            try await socket.acknowledge(envelope.id)
        } catch {
            logger.ackFailed(envelopeId: envelopeId, reason: "\(error)")
        }
    }

    /// Returns the model key of a newly inboxed `APP_ENTRY`.
    func route(_ message: Obscura_Client_V1_ClientMessage, envelopeId: String, senderUserId: String,
                       senderDeviceId: String) async throws -> String? {
        switch message.payload {
        case .appEntry(let entry)?:
            let inserted = try await inbox.put(InboxInsert(
                envelopeId: envelopeId, kind: "APP_ENTRY", senderUserId: senderUserId, senderDeviceId: senderDeviceId,
                modelKey: entry.model, entryId: entry.id, sentAt: Self.clampFuture(entry.timestamp), payload: entry.data))
            return inserted ? entry.model : nil
        case nil:
            // An arm this kit does not know. Inboxed whole rather than destroyed.
            _ = try await inbox.put(InboxInsert(
                envelopeId: envelopeId, kind: "UNKNOWN", senderUserId: senderUserId, senderDeviceId: senderDeviceId,
                modelKey: nil, entryId: nil, sentAt: nil, payload: message.serializedData()))
            return nil
        case .friendRequest(let request)?:
            try await receivedFriendRequest(from: senderUserId, username: request.username)
            return nil
        case .friendAccept?:
            try await receivedFriendAccept(from: senderUserId)
            return nil
        case .typingSignal(let signal)?:
            receivedTyping(signal, senderUserId: senderUserId, senderDeviceId: senderDeviceId)
            return nil
        }
    }

    /// A far-future timestamp would win every later merge.
    static func clampFuture(_ sentAt: UInt64) -> UInt64 {
        min(sentAt, UInt64(Date().timeIntervalSince1970 * 1000) + 60_000)
    }

    /// The payload username names a stranger only; a known peer's request changes nothing, except
    /// that crossed requests are mutual consent.
    private func receivedFriendRequest(from userId: String, username: String) async throws {
        switch try await friends.friend(userId)?.status {
        case nil: try await friends.add(userId, username: username, status: .pendingReceived)
        case .pendingSent?: try await acceptFriend(userId)
        case .pendingReceived?, .accepted?: break
        }
    }

    /// Only a request this user sent can be accepted.
    private func receivedFriendAccept(from userId: String) async throws {
        guard try await friends.friend(userId)?.status == .pendingSent else {
            logger.log("ignoring unsolicited FRIEND_ACCEPT from \(userId)")
            return
        }
        try await friends.updateStatus(userId, .accepted)
    }

    private func receivedTyping(_ signal: Obscura_Client_V1_TypingSignal, senderUserId: String, senderDeviceId: String) {
        guard !signal.contextID.isEmpty, let state = WireCodec.decodeTypingState(signal.state) else { return }
        switch state {
        case .started: typing.started(contextId: signal.contextID, userId: senderUserId, deviceId: senderDeviceId)
        case .stopped: typing.stopped(contextId: signal.contextID, deviceId: senderDeviceId)
        }
    }

    // MARK: - Prekeys

    private func replenishPreKeysIfLow() {
        do {
            if try signalStore.preKeyCount() < Self.preKeyMinimum { replenishPreKeys() }
        } catch {
            logger.databaseError(store: "signal", operation: "preKeyCount", reason: "\(error)")
        }
    }

    private func replenishPreKeys() {
        guard replenishTask == nil else { return }
        replenishTask = Task { await self.performReplenish() }
    }

    private func performReplenish() async {
        defer { replenishTask = nil }
        do {
            try await api.uploadDeviceKeys(signalStore.addPreKeys(Self.preKeyReplenishCount))
        } catch {
            logger.preKeyReplenishFailed(reason: "\(error)")
        }
    }
}
