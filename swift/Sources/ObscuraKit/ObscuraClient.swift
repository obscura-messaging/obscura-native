import Foundation
import GRDB

public enum ConnectionState: String, Sendable {
    case disconnected, connecting, connected, reconnecting
}

public enum AuthState: String, Sendable {
    case loggedOut, authenticated
}

public enum LoginScenario: Sendable {
    /// Authenticated as the stored local device.
    case existingDevice
    /// No local device: call `loginAndProvision`.
    case newDevice
    /// The server does not know the stored local device: call `wipeDevice`, then `loginAndProvision`.
    case deviceMismatch
    case invalidCredentials
}

struct Session: Equatable, Sendable {
    var token: String
    var refreshToken: String
    let userId: String
    let deviceId: String
    let username: String

    init(token: String, refreshToken: String, userId: String, deviceId: String, username: String) {
        self.token = token
        self.refreshToken = refreshToken
        self.userId = userId
        self.deviceId = deviceId
        self.username = username
    }

    init?(stored: [String: String]) {
        guard let token = stored["token"], let refreshToken = stored["refreshToken"], let userId = stored["userId"],
              let deviceId = stored["deviceId"], let username = stored["username"],
              ![token, refreshToken, userId, deviceId, username].contains("") else { return nil }
        self.init(token: token, refreshToken: refreshToken, userId: userId, deviceId: deviceId, username: username)
    }

    var stored: [String: String] {
        ["token": token, "refreshToken": refreshToken, "userId": userId, "deviceId": deviceId, "username": username]
    }
}

enum TokenStatus: Equatable {
    case fresh, unavailable, rejected
}

public actor ObscuraClient {
    static let tokenExpiryMargin: TimeInterval = 60
    static let preKeyMinimum = 20
    static let preKeyReplenishCount: UInt32 = 50

    public nonisolated let inbox: InboxStore
    public nonisolated let entries: EntryStore
    /// Model key of each newly inboxed `APP_ENTRY`. Single consumer; the inbox is the delivery path,
    /// so the oldest keys are dropped when 100 are unread.
    public nonisolated let messageReceived: AsyncStream<String>

    nonisolated let api: APIClient
    nonisolated let db: DatabaseQueue
    nonisolated let friends: FriendStore
    nonisolated let devices: DeviceStore
    nonisolated let signalStore: PersistentSignalStore
    nonisolated let messenger: Messenger
    nonisolated let logger: RecordingLogger
    nonisolated let deviceName: String
    nonisolated let sessionStorage: SessionStorage
    nonisolated let typing = TypingTracker()
    nonisolated let auth = StateStream(AuthState.loggedOut)
    nonisolated let connection = StateStream(ConnectionState.disconnected)
    nonisolated let messageContinuation: AsyncStream<String>.Continuation

    var session: Session?
    var sender: MessageSender?
    var typingSentAt: [String: Date] = [:]

    var refreshTask: Task<TokenStatus, Never>?
    var refreshLoop: Task<Void, Never>?
    var refreshFailures = 0
    var replenishTask: Task<Void, Never>?

    var socket: GatewaySocket?
    var connectTask: Task<Void, Error>?
    var reconnectTask: Task<Void, Never>?
    var reconnectAttempts = 0
    /// Set by an unrequested drop: until a connect succeeds, every failed attempt schedules the next.
    var recovering = false
    /// Bumped by `disconnect`, so an attempt that outlives it discards its socket.
    var connectionGeneration = 0

    public init(apiURL: String, dataDirectory: String, userId: String, keychainAccessGroup: String?, deviceName: String,
                sessionStorage: SessionStorage, logger: ObscuraLogger) throws {
        let protection = [FileAttributeKey.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        try FileManager.default.createDirectory(atPath: dataDirectory, withIntermediateDirectories: true, attributes: protection)
        let path = (dataDirectory as NSString).appendingPathComponent("obscura.sqlite")
        let key = try DatabaseSecret.getOrCreate(userId: userId, accessGroup: keychainAccessGroup)
        let db = try ObscuraDatabase.open(path: path, key: key)
        try FileManager.default.setAttributes(protection, ofItemAtPath: path)
        try self.init(apiURL: apiURL, database: db, deviceName: deviceName, sessionStorage: sessionStorage, logger: logger)
    }

    init(apiURL: String, database: DatabaseQueue, deviceName: String, sessionStorage: SessionStorage,
         logger: ObscuraLogger) throws {
        let recording = RecordingLogger(logger)
        self.logger = recording
        self.api = try APIClient(baseURL: apiURL)
        self.db = database
        self.deviceName = deviceName
        self.sessionStorage = sessionStorage
        self.friends = FriendStore(db: database)
        self.devices = DeviceStore(db: database)
        self.inbox = InboxStore(db: database, logger: recording)
        self.entries = EntryStore(db: database)
        self.signalStore = PersistentSignalStore(db: database, logger: recording)
        self.messenger = Messenger(api: api, store: signalStore)
        (messageReceived, messageContinuation) = AsyncStream.makeStream(bufferingPolicy: .bufferingNewest(100))
    }

    deinit {
        refreshLoop?.cancel()
        reconnectTask?.cancel()
        connectTask?.cancel()
        replenishTask?.cancel()
        socket?.close()
        messageContinuation.finish()
    }

    // MARK: - Observation

    public nonisolated var authState: AuthState { auth.value }
    public nonisolated func observeAuthState() -> AsyncStream<AuthState> { auth.stream() }

    public nonisolated var connectionState: ConnectionState { connection.value }
    public nonisolated func observeConnectionState() -> AsyncStream<ConnectionState> { connection.stream() }

    /// Yields on subscribe and after every change to the friend list; read it with `getFriends()`.
    public nonisolated func observeFriendsChanged() -> AsyncStream<Void> { friends.changes(logger: logger) }

    /// Newest first.
    public nonisolated func getDebugLog() -> [String] { logger.snapshot() }

    public var userId: String? { session?.userId }
    public var username: String? { session?.username }
    public var deviceId: String? { session?.deviceId }

    func requireSession() throws -> Session {
        guard let session else { throw ObscuraError.notAuthenticated }
        return session
    }

    // MARK: - Auth

    public func register(_ username: String, _ password: String) async throws {
        let account = try await api.registerUser(username, password)
        try await provision(username: username, userToken: account.token)
    }

    /// Only `.existingDevice` changes the client's state.
    public func login(_ username: String, _ password: String) async throws -> LoginScenario {
        let localDeviceId = try await devices.localDeviceId()
        if let localDeviceId, try signalStore.hasIdentity() {
            do {
                let response = try await api.login(username, password, deviceId: localDeviceId)
                // The server answers an unknown device id with a user-scoped token, not an error.
                guard APIClient.deviceId(response.token) == localDeviceId else { return .deviceMismatch }
                try await begin(session(response, username: username))
                return .existingDevice
            } catch ObscuraError.http(let status, _) where status == 401 || status == 403 {
                // Classified by the user-scoped login below.
            }
        }
        do {
            _ = try await api.login(username, password, deviceId: nil)
        } catch ObscuraError.http(let status, _) where status == 401 || status == 403 {
            return .invalidCredentials
        }
        return localDeviceId == nil ? .newDevice : .deviceMismatch
    }

    public func loginAndProvision(_ username: String, _ password: String) async throws {
        let account = try await api.login(username, password, deviceId: nil)
        try await provision(username: username, userToken: account.token)
    }

    private func provision(username: String, userToken: String) async throws {
        await api.setToken(userToken)
        let keys = try SignalKeys.generate()
        let response = try await api.provisionDevice(name: deviceName, keys: keys.upload())
        let session = try session(response, username: username)
        try signalStore.install(keys)
        try await devices.setLocalDeviceId(session.deviceId)
        try await devices.add(userId: session.userId, deviceId: session.deviceId)
        await begin(session)
    }

    private func session(_ response: AuthResponse, username: String) throws -> Session {
        guard let userId = APIClient.userId(response.token), let deviceId = APIClient.deviceId(response.token),
              let refreshToken = response.refreshToken else {
            throw ObscuraError.protocolViolation("device token lacks a user id, a device id or a refresh token")
        }
        return Session(token: response.token, refreshToken: refreshToken, userId: userId, deviceId: deviceId, username: username)
    }

    func begin(_ session: Session) async {
        await api.setToken(session.token)
        self.session = session
        sender = MessageSender(messenger: messenger, devices: devices, api: api, logger: logger, selfDeviceId: session.deviceId)
        sessionStorage.save(session.stored)
        refreshFailures = 0
        startRefreshLoop()
        auth.set(.authenticated)
    }

    /// Keeps local data. Revoking the refresh token on the server is best effort.
    public func logout() async {
        let ended = session
        disconnect()
        refreshLoop?.cancel()
        refreshLoop = nil
        replenishTask?.cancel()
        replenishTask = nil
        session = nil
        sender = nil
        await api.setToken(nil)
        sessionStorage.clear()
        typing.clear()
        typingSentAt = [:]
        auth.set(.loggedOut)
        if let ended {
            do {
                try await api.logout(ended.refreshToken, token: ended.token)
            } catch {
                logger.log("refresh token revocation failed: \(error)")
            }
        }
    }

    /// `logout()`, then deletes every piece of local kit data.
    public func wipeDevice() async throws {
        await logout()
        try await ObscuraDatabase.wipe(db)
    }

    /// True when a stored session was restored; the client is then authenticated and connected if
    /// the server was reachable. False when nothing usable is stored or the refresh token was
    /// rejected.
    public func restorePersistedSession() async -> Bool {
        guard let stored = sessionStorage.load() else { return false }
        let hasIdentity: Bool
        do {
            hasIdentity = try signalStore.hasIdentity()
        } catch {
            logger.databaseError(store: "signal", operation: "hasIdentity", reason: "\(error)")
            return false
        }
        guard hasIdentity, let session = Session(stored: stored) else {
            sessionStorage.clear()
            return false
        }
        await begin(session)
        guard await freshToken() != .rejected else { return false }
        do {
            try await connect()
        } catch {
            logger.log("restored session offline: \(error)")
        }
        return authState == .authenticated
    }

    // MARK: - Token refresh

    func freshToken() async -> TokenStatus {
        guard let session else { return .rejected }
        if let expiry = APIClient.expiry(session.token), expiry - Date().timeIntervalSince1970 > Self.tokenExpiryMargin {
            return .fresh
        }
        return await refresh()
    }

    /// Concurrent callers share one refresh: the refresh token is single-use.
    func refresh() async -> TokenStatus {
        if let refreshTask { return await refreshTask.value }
        let task = Task { await self.performRefresh() }
        refreshTask = task
        let status = await task.value
        refreshTask = nil
        return status
    }

    private func performRefresh() async -> TokenStatus {
        guard let current = session else { return .rejected }
        do {
            let response = try await api.refreshSession(current.refreshToken)
            guard var updated = session, updated.refreshToken == current.refreshToken else {
                return session == nil ? .rejected : .fresh
            }
            updated.token = response.token
            updated.refreshToken = response.refreshToken ?? updated.refreshToken
            session = updated
            await api.setToken(updated.token)
            sessionStorage.save(updated.stored)
            refreshFailures = 0
            return .fresh
        } catch ObscuraError.http(let status, _) where status == 401 || status == 403 {
            logger.tokenRefreshFailed(attempt: refreshFailures + 1, reason: "refresh token rejected (\(status))")
            await logout()
            return .rejected
        } catch {
            refreshFailures += 1
            logger.tokenRefreshFailed(attempt: refreshFailures, reason: "\(error)")
            return .unavailable
        }
    }

    private func startRefreshLoop() {
        refreshLoop?.cancel()
        refreshLoop = Task { [weak self] in
            while let delay = await self?.nextRefreshDelay() {
                guard (try? await Task.sleep(nanoseconds: UInt64(delay * 1_000_000_000))) != nil else { return }
                _ = await self?.refresh()
            }
        }
    }

    /// 80% of the token's remaining lifetime (at least 5 s); after failures, 5 s doubling to 5 min.
    private func nextRefreshDelay() -> TimeInterval? {
        guard let session else { return nil }
        if refreshFailures > 0 { return min(5 * pow(2, Double(min(refreshFailures - 1, 10))), 300) }
        let remaining = (APIClient.expiry(session.token) ?? 0) - Date().timeIntervalSince1970
        return max(remaining * 0.8, 5)
    }
}
