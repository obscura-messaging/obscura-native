import Foundation
import GRDB
import LibSignalClient
import SwiftProtobuf
#if os(iOS)
import UIKit
#endif

// MARK: - Public Types

public enum ConnectionState: String, Sendable {
    case disconnected, connecting, connected, reconnecting
}

public enum AuthState: String, Sendable {
    case loggedOut, authenticated, pendingApproval
}

/// Result of a login attempt — tells the app what to do next.
public enum LoginScenario: Sendable {
    case existingDevice       // Known device, session restored. Call connect().
    case newDevice            // New device, needs link approval from existing device.
    case onlyDevice           // Lost local data but no other devices exist. Re-provision directly, no linking.
    case deviceMismatch       // DB exists but stored device doesn't match server. Re-provision needed.
    case invalidCredentials   // Wrong password.
    case userNotFound         // Username doesn't exist.
}

public struct MessageWakeEvent: Sendable {
    public let type: String  // app-facing message kind, e.g. "APP_ENTRY"; "" if unset
    let username: String
    let sourceUserId: String
    let senderDeviceId: String?
    let timestamp: UInt64
    /// For APP_ENTRY messages: the opaque model key; nil for non-sync types.
    public let model: String?
    /// Test-only access to the decoded wire message. Applications drain the durable inbox.
    let rawBytes: Data
}

private actor ProcessedEnvelopeTracker {
    private var count: UInt64 = 0
    private var lastProcessedAt = Date.distantPast

    func snapshot() -> (count: UInt64, lastProcessedAt: Date) {
        (count, lastProcessedAt)
    }

    func record() {
        lastProcessedAt = Date()
        count += 1
    }
}

private actor PushDrainCoordinator {
    private var nextID: UInt64 = 0
    private var inFlight: (id: UInt64, task: Task<Int, Never>)?

    func run(_ operation: @escaping () async -> Int) async -> Int {
        if let existing = inFlight {
            return await existing.task.value
        }

        nextID += 1
        let id = nextID
        let task = Task { await operation() }
        inFlight = (id, task)

        let result = await task.value
        if inFlight?.id == id {
            inFlight = nil
        }
        return result
    }
}

private actor ConnectionCoordinator {
    private var locked = false
    private var waiters: [CheckedContinuation<Void, Never>] = []

    func run(_ operation: @escaping () async throws -> Void) async throws {
        await acquire()
        defer { release() }
        try await operation()
    }

    private func acquire() async {
        if !locked {
            locked = true
            return
        }
        await withCheckedContinuation { waiters.append($0) }
    }

    private func release() {
        if waiters.isEmpty {
            locked = false
        } else {
            waiters.removeFirst().resume()
        }
    }
}

internal func shouldForceReconnectAfterPush(
    processed: UInt64,
    startedConnected: Bool,
    lastProcessedAt: Date,
    now: Date,
    recentActivityWindow: TimeInterval
) -> Bool {
    guard processed == 0, startedConnected else { return false }
    return now.timeIntervalSince(lastProcessedAt) > recentActivityWindow
}

// MARK: - ObscuraClient

/// ObscuraClient — the unified facade.
/// This is the public API that both SwiftUI views and XCTests call.
/// All high-level operations live here. Views never touch messenger/gateway directly.
public class ObscuraClient {

    // MARK: - Domain Actors (always initialized, never nil)

    public let api: APIClient
    public let friends: FriendStore
    public let devices: DeviceStore
    public let gateway: GatewayConnection

    /// The durable inbox (`KIT_API.md` §3), and the only place an inbound APP_ENTRY
    /// lands.
    public let inbox: InboxStore

    /// Raw storage for application entries (`KIT_API.md` §8.1) — the other half of the
    /// thin kit's app-facing surface. `inbox` is how messages arrive; this is where the app keeps
    /// what it made of them.
    ///
    /// It stores and returns rows. It does not merge them, expire them, or decide who they go to —
    /// the app owns all three (`NATIVE_CONTRACT.md` §0.4).
    public let entries: EntryStore

    // Messenger is initialized after register/login with real keys
    private var _messenger: Messenger?
    public private(set) var persistentSignalStore: PersistentSignalStore?

    private let recordingLogger: RecordingLogger

    /// Security logger — assignments replace the forwarding destination while retaining the
    /// bounded pull-based debug log.
    public var logger: ObscuraLogger {
        get { recordingLogger }
        set { recordingLogger.setDelegate(newValue) }
    }

    /// Session storage — kit persists session internally. Set before register/login.
    public var sessionStorage: SessionStorage?

    /// Fired whenever the access/refresh token is rotated by a refresh, so the
    /// host can re-persist the session. Refresh tokens are single-use — without
    /// re-persisting, a restored session uses a consumed refresh token and gets
    /// a 401 (broadcasts fail, reconnect fails). See `refreshTokenNow()`.
    public var onSessionChanged: (() -> Void)?

    /// Attachment cache — decrypted bytes cached in the encrypted DB.
    private var attachmentCache: AttachmentCache?

    // MARK: - Observable State

    private var _connectionState: ConnectionState = .disconnected {
        didSet {
            if _connectionState != oldValue {
                for c in connectionContinuations { c.yield(_connectionState) }
            }
        }
    }
    private var _authState: AuthState = .loggedOut {
        didSet {
            if _authState != oldValue {
                for c in authContinuations { c.yield(_authState) }
                // Auto-persist session when authenticated
                if _authState == .authenticated { persistSession() }
            }
        }
    }

    private var connectionContinuations: [AsyncStream<ConnectionState>.Continuation] = []
    private var authContinuations: [AsyncStream<AuthState>.Continuation] = []

    /// Connection state — current value
    public var connectionState: ConnectionState { _connectionState }
    public var authState: AuthState { _authState }

    /// Observe connection state changes. Push-based, no polling.
    public func observeConnectionState() -> AsyncStream<ConnectionState> {
        AsyncStream { continuation in
            continuation.yield(_connectionState)
            connectionContinuations.append(continuation)
            continuation.onTermination = { [weak self] _ in
                self?.connectionContinuations.removeAll { $0 as AnyObject === continuation as AnyObject }
            }
        }
    }

    /// Observe auth state changes. Push-based, no polling.
    public func observeAuthState() -> AsyncStream<AuthState> {
        AsyncStream { continuation in
            continuation.yield(_authState)
            authContinuations.append(continuation)
            continuation.onTermination = { [weak self] _ in
                self?.authContinuations.removeAll { $0 as AnyObject === continuation as AnyObject }
            }
        }
    }

    private var authFailedContinuations: [AsyncStream<String>.Continuation] = []

    /// Observe hard auth failures (token refresh exhausted its retry budget).
    /// Distinct from `observeAuthState` going to `.loggedOut`: this is an event,
    /// not a state, and carries a reason. Does not replay — only fires live.
    public func observeAuthFailed() -> AsyncStream<String> {
        AsyncStream { continuation in
            authFailedContinuations.append(continuation)
            continuation.onTermination = { [weak self] _ in
                self?.authFailedContinuations.removeAll { $0 as AnyObject === continuation as AnyObject }
            }
        }
    }

    private func emitAuthFailed(_ reason: String) {
        for c in authFailedContinuations { c.yield(reason) }
    }

    /// Buffered message queue for waitForMessage
    private var messageQueue: [MessageWakeEvent] = []
    private let processedEnvelopes = ProcessedEnvelopeTracker()
    private let pushDrainCoordinator = PushDrainCoordinator()
    private let connectionCoordinator = ConnectionCoordinator()

    /// Events stream — every received message after routing (multi-observer)
    private var eventContinuations: [AsyncStream<MessageWakeEvent>.Continuation] = []

    public func events() -> AsyncStream<MessageWakeEvent> {
        AsyncStream { continuation in
            eventContinuations.append(continuation)
            continuation.onTermination = { [weak self] _ in
                self?.eventContinuations.removeAll { $0 as AnyObject === continuation as AnyObject }
            }
        }
    }

    private func emit(_ message: MessageWakeEvent) {
        // Push to stream subscribers
        for c in eventContinuations { c.yield(message) }
        // Push to queue — waitForMessage polls this
        if messageQueue.count >= 1000 { messageQueue.removeFirst() }
        messageQueue.append(message)
    }

    // MARK: - Auth State

    public private(set) var token: String?
    public private(set) var refreshToken: String?
    public private(set) var userId: String?
    public private(set) var username: String?
    public private(set) var deviceId: String?
    public private(set) var identityKeyPair: IdentityKeyPair?
    public private(set) var registrationId: UInt32?

    // Background tasks
    private var envelopeTask: Task<Void, Never>?
    private var tokenRefreshTask: Task<Void, Never>?
    private var reconnectTask: Task<Void, Never>?

    // In-flight refresh dedup — see refreshTokenNow(). Lock-guarded because
    // callers arrive from multiple concurrent tasks.
    private let refreshLock = NSLock()
    private var refreshInFlight: Task<Bool, Error>?

    // Reconnection state (matches JS client)
    private var shouldReconnect = false
    private var reconnectAttempts = 0
    private static let reconnectDelayMs: UInt64 = 1_000
    private static let reconnectMaxDelayMs: UInt64 = 30_000
    /// Keep the single reconnect retry inside the push path's tight OS time budget.
    private static let pushDrainReconnectRetryNanos: UInt64 = 250_000_000
    private static let pushDrainRecentActivityWindow: TimeInterval = 10
    private static let pingIntervalSeconds: TimeInterval = 30

    // Decrypt rate limiting: track failures per sender
    private var decryptFailures: [String: (count: Int, windowStart: Date)] = [:]
    private let maxDecryptFailures = 10
    private let decryptFailureWindow: TimeInterval = 60

    // Prekey replenishment (matches Kotlin pattern)
    private let prekeyMinCount = 20
    private let prekeyReplenishCount: UInt32 = 50

    // Signal key generation constants (shared by register, loginAndProvision, takeoverDevice)
    private static let initialPreKeyCount: UInt32 = 100
    private static let maxRegistrationId: UInt32 = 16380
    private static let signedPreKeyId: UInt32 = 1

    // Token refresh buffer — refresh if expiring within this many seconds
    private static let tokenExpiryBufferSeconds: Double = 60

    // MARK: - Init

    /// The shared database — nil for in-memory (tests), file-backed for production.
    private let sharedDb: DatabaseQueue?

    /// In-memory client (tests). All state lost on dealloc.
    public init(apiURL: String, logger: ObscuraLogger = PrintLogger()) throws {
        let recordingLogger = RecordingLogger(delegate: logger)
        self.recordingLogger = recordingLogger
        self.sharedDb = nil
        self.api = APIClient(baseURL: apiURL)
        self.friends = try FriendStore()
        self.devices = try DeviceStore()
        self.inbox = try InboxStore(onDiscard: Self.discardLogger(recordingLogger))
        self.entries = try EntryStore()
        self.gateway = GatewayConnection(api: api, logger: recordingLogger)
    }

    /// File-backed client (production). All state persists to `dataDirectory/obscura.sqlite`.
    /// On init, restores Signal identity from DB if one exists.
    /// - Parameter keychainAccessGroup: shared keychain access group for the SQLCipher key. Pass
    ///   `nil` (default) for today's behaviour. Required if a Notification Service Extension must
    ///   open this database — see `KIT_API.md` P2, and note the extension also needs
    ///   `dataDirectory` to be an App Group container path, which is the caller's to supply.
    public init(apiURL: String, dataDirectory: String, userId: String? = nil,
                keychainAccessGroup: String? = nil, logger: ObscuraLogger = PrintLogger()) throws {
        let recordingLogger = RecordingLogger(delegate: logger)
        self.recordingLogger = recordingLogger

        // Ensure directory exists with iOS Data Protection (encrypted at rest)
        try FileManager.default.createDirectory(
            atPath: dataDirectory, withIntermediateDirectories: true,
            attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]
        )
        let dbPath = (dataDirectory as NSString).appendingPathComponent("obscura.sqlite")

        // SQLCipher encryption: per-user key from Keychain
        var config = Configuration()
        if let userId = userId {
            let key = DatabaseSecret.getOrCreate(userId: userId, accessGroup: keychainAccessGroup)
            config.prepareDatabase { db in
                try db.usePassphrase(key)
                try db.execute(sql: "PRAGMA kdf_iter = 1") // key is already 256-bit entropy
                try db.execute(sql: "PRAGMA cipher_page_size = 4096")
            }
        }

        let db = try DatabaseQueue(path: dbPath, configuration: config)
        try db.write { db in try db.execute(sql: "PRAGMA secure_delete = ON") }

        // Set file protection on the DB file itself
        try FileManager.default.setAttributes(
            [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication],
            ofItemAtPath: dbPath
        )
        self.sharedDb = db
        self.attachmentCache = try? AttachmentCache(db: db)

        self.api = APIClient(baseURL: apiURL)
        self.friends = try FriendStore(db: db)
        self.devices = try DeviceStore(db: db)
        self.inbox = try InboxStore(db: db, onDiscard: Self.discardLogger(recordingLogger))
        self.entries = try EntryStore(db: db)
        self.gateway = GatewayConnection(api: api, logger: recordingLogger)

        // Restore Signal store from persisted DB if identity exists
        let store = try PersistentSignalStore(db: db)
        store.logger = recordingLogger
        if store.hasPersistedIdentity {
            self.persistentSignalStore = store
            self.identityKeyPair = try store.identityKeyPair(context: NullContext())
            self.registrationId = try store.localRegistrationId(context: NullContext())
        }
    }

    deinit {
        envelopeTask?.cancel()
        tokenRefreshTask?.cancel()
        removeForegroundObserver()
        gateway.disconnectSync()
    }

    // MARK: - Session State

    /// Quick check if authenticated (has token + userId)
    public var hasSession: Bool { token != nil && userId != nil }

    /// Restore a previously saved session without re-authenticating.
    /// If a PersistentSignalStore exists (file-backed client), rebuilds the Messenger
    /// so decrypt/encrypt work immediately. Call `connect()` after this.
    public func restoreSession(token: String, refreshToken: String?, userId: String,
                               deviceId: String?, username: String?, registrationId: UInt32 = 0) async {
        self.token = token
        self.refreshToken = refreshToken
        self.userId = userId
        self.deviceId = deviceId
        self.username = username
        await api.setToken(token)

        // Rebuild messenger from persisted Signal store if available
        if let store = persistentSignalStore, store.hasPersistedIdentity {
            self.identityKeyPair = try? store.identityKeyPair(context: NullContext())
            self.registrationId = (try? store.localRegistrationId(context: NullContext())) ?? registrationId
            self._messenger = Messenger(api: api, store: store)
        } else {
            self.registrationId = registrationId
        }

        if let deviceId = deviceId {
            await _messenger?.mapDevice(deviceId, userId: userId)
        }
        _authState = .authenticated
    }

    /// Ensure the current token is fresh; refresh if expiring within buffer.
    /// Returns true if a valid token is available after the call.
    @discardableResult
    public func ensureFreshToken() async -> Bool {
        guard let token = token else { return false }
        guard let payload = APIClient.decodeJWT(token),
              let exp = payload["exp"] as? Double else { return false }
        let now = Date().timeIntervalSince1970
        guard (exp - now) <= Self.tokenExpiryBufferSeconds else { return true }
        do {
            return try await refreshTokenNow()
        } catch {
            if Self.isCancellation(error) { return false }
            logger.tokenRefreshFailed(attempt: 1, error: "\(error)")
            return false
        }
    }

    /// Refresh the access token using the (single-use) refresh token, update
    /// both tokens, and fire `onSessionChanged` so the host can re-persist the
    /// rotated refresh token. Returns false if there's no refresh token; throws
    /// on API failure. Shared by `ensureFreshToken()` and the background
    /// refresh loop so persistence-on-rotation happens on every path.
    ///
    /// Concurrent callers (background loop, reconnect, broadcast path) share a
    /// single in-flight refresh — the refresh token is single-use, so two racing
    /// refreshes mean the loser POSTs an already-consumed token and gets a 401.
    /// Mirrors Kotlin's `refreshInProgress`. The refresh runs in its own Task,
    /// so cancelling a caller mid-refresh doesn't tear down the HTTP request.
    @discardableResult
    internal func refreshTokenNow() async throws -> Bool {
        return try await dedupedRefreshTask().value
    }

    /// Synchronous (lock-guarded) join-or-start for the shared refresh task.
    private func dedupedRefreshTask() -> Task<Bool, Error> {
        refreshLock.lock()
        defer { refreshLock.unlock() }
        if let existing = refreshInFlight { return existing }
        let task = Task { [weak self] () throws -> Bool in
            guard let self = self else { return false }
            defer { self.clearRefreshInFlight() }
            guard let rt = self.refreshToken else { return false }
            let result = try await self.api.refreshSession(rt)
            self.token = result.token
            await self.api.setToken(result.token)
            if let newRT = result.refreshToken { self.refreshToken = newRT }
            self.onSessionChanged?()
            return true
        }
        refreshInFlight = task
        return task
    }

    private func clearRefreshInFlight() {
        refreshLock.lock()
        refreshInFlight = nil
        refreshLock.unlock()
    }

    /// True when the error is a task/URLSession cancellation rather than a server
    /// rejection. Reconnect cancels in-flight loops mid-request; that must not
    /// be logged as a refresh failure or count toward the 3-strike logout.
    private static func isCancellation(_ error: Error) -> Bool {
        if error is CancellationError { return true }
        let ns = error as NSError
        return ns.domain == NSURLErrorDomain && ns.code == NSURLErrorCancelled
    }

    /// Lightweight account registration — API call only, no Signal keys or DB.
    /// Returns (token, refreshToken, userId) so the caller can create a user-scoped client.
    public static func registerAccount(_ username: String, _ password: String, apiURL: String = "https://obscura.barrelmaker.dev") async throws -> (token: String, refreshToken: String?, userId: String) {
        let api = APIClient(baseURL: apiURL)
        let result = try await api.registerUser(username, password)
        let userId = APIClient.extractUserId(result.token) ?? ""
        return (token: result.token, refreshToken: result.refreshToken, userId: userId)
    }

    /// Lightweight login — API call only, returns credentials.
    /// Pass deviceId to get a device-scoped token (required for messaging).
    public static func loginAccount(_ username: String, _ password: String, deviceId: String? = nil, apiURL: String = "https://obscura.barrelmaker.dev") async throws -> (token: String, refreshToken: String?, userId: String) {
        let api = APIClient(baseURL: apiURL)
        let result = try await api.loginWithDevice(username, password, deviceId: deviceId)
        let userId = APIClient.extractUserId(result.token) ?? ""
        return (token: result.token, refreshToken: result.refreshToken, userId: userId)
    }

    // MARK: - Register

    public func register(_ username: String, _ password: String) async throws {
        // 1. Register user account
        let result = try await api.registerUser(username, password)
        let token = result.token

        self.token = token
        self.refreshToken = result.refreshToken
        self.userId = APIClient.extractUserId(token)
        self.username = username
        await api.setToken(token)
        await rateLimitDelay()

        // 2. Generate Signal keys
        let (identity, regId) = generateSignalIdentity()
        let (spkPrivate, spkSig) = generateSignedPreKey(identity: identity)
        let (otpKeys, preKeyRecords) = generateOneTimePreKeys()

        // 3. Provision device
        let deviceResult = try await api.provisionDevice(
            name: "ObscuraKit-device",
            identityKey: Data(identity.publicKey.serialize()).base64EncodedString(),
            registrationId: Int(regId),
            signedPreKey: SignedPreKeyUpload(
                keyId: Int(Self.signedPreKeyId),
                publicKey: Data(spkPrivate.publicKey.serialize()).base64EncodedString(),
                signature: Data(spkSig).base64EncodedString()
            ),
            oneTimePreKeys: otpKeys
        )

        let deviceToken = deviceResult.token

        self.token = deviceToken
        // Use the DEVICE provision's refresh token, not the user-scoped one from
        // registerUser above — refreshing a user-scoped token drops device scope
        // and 403s the gateway. (Matches Kotlin `session.refreshToken = provResult.refreshToken`.)
        self.refreshToken = deviceResult.refreshToken
        self.deviceId = APIClient.extractDeviceId(deviceToken)
        await api.setToken(deviceToken)

        // 4. Persistent Signal protocol store (survives app restart)
        let store = try initializeSignalStore(identity: identity, regId: regId, spkPrivate: spkPrivate, spkSig: spkSig, preKeyRecords: preKeyRecords)

        // 5. Messenger
        self._messenger = Messenger(api: api, store: store)

        // Link approval and DeviceAnnounce require a complete own-device registry.
        await recordOwnDevice(deviceName: "ObscuraKit-device")

        self._authState = .authenticated
    }

    /// Record this device in the own-device registry. Insertion is idempotent.
    private func recordOwnDevice(deviceName: String) async {
        guard let did = self.deviceId else { return }
        await devices.addOwnDevice(OwnDevice(deviceId: did, deviceName: deviceName))
    }

    /// Provision the current device with Signal keys. Requires token + userId already set.
    /// Used after registerAccount/loginAccount when the client was created with a user-scoped DB.
    public func provisionCurrentDevice(deviceName: String = "ObscuraKit-device") async throws {
        guard let _ = token, let userId = userId else {
            throw NSError(domain: "ObscuraKit", code: 1, userInfo: [NSLocalizedDescriptionKey: "No auth token or userId set"])
        }
        await rateLimitDelay()

        let (identity, regId) = generateSignalIdentity()
        let (spkPrivate, spkSig) = generateSignedPreKey(identity: identity)
        let (otpKeys, preKeyRecords) = generateOneTimePreKeys()

        let deviceResult = try await api.provisionDevice(
            name: deviceName,
            identityKey: Data(identity.publicKey.serialize()).base64EncodedString(),
            registrationId: Int(regId),
            signedPreKey: SignedPreKeyUpload(
                keyId: Int(Self.signedPreKeyId),
                publicKey: Data(spkPrivate.publicKey.serialize()).base64EncodedString(),
                signature: Data(spkSig).base64EncodedString()
            ),
            oneTimePreKeys: otpKeys
        )

        self.token = deviceResult.token
        // Device-scoped refresh token (was left as the user-scoped one from the
        // preceding registerAccount/loginAccount) — see register() for why.
        self.refreshToken = deviceResult.refreshToken
        self.deviceId = APIClient.extractDeviceId(deviceResult.token)
        await api.setToken(deviceResult.token)

        let store = try initializeSignalStore(identity: identity, regId: regId, spkPrivate: spkPrivate, spkSig: spkSig, preKeyRecords: preKeyRecords)
        self._messenger = Messenger(api: api, store: store)

        // Keep the own-device registry complete for linking and announcements.
        await recordOwnDevice(deviceName: deviceName)

        self._authState = .authenticated
    }

    // MARK: - Login

    public func login(_ username: String, _ password: String, deviceId: String? = nil) async throws {
        let result = try await api.loginWithDevice(username, password, deviceId: deviceId)
        let token = result.token

        self.token = token
        self.refreshToken = result.refreshToken
        self.userId = APIClient.extractUserId(token)
        self.username = username
        self.deviceId = APIClient.extractDeviceId(token) ?? deviceId
        await api.setToken(token)
        self._authState = .authenticated
    }

    /// Smart login — returns a scenario telling the app what to do next.
    /// File-backed clients: checks for existing DB + stored device identity.
    ///
    /// ```swift
    /// let scenario = try await client.loginSmart(username, password)
    /// switch scenario {
    /// case .existingDevice: try await client.connect()
    /// case .newDevice:      // show link code screen
    /// case .invalidCredentials: // show error
    /// }
    /// ```
    public func loginSmart(_ username: String, _ password: String) async throws -> LoginScenario {
        let storedIdentity = await devices.getIdentity()

        // Device-first (Android parity): a local device logs in with one
        // device-scoped session. A competing user-scoped session can make token
        // refresh drift to the wrong scope and produce a gateway 403.
        if let identity = storedIdentity, !identity.deviceId.isEmpty {
            do {
                let deviceResult = try await api.loginWithDevice(username, password, deviceId: identity.deviceId)
                self.token = deviceResult.token
                self.refreshToken = deviceResult.refreshToken
                self.userId = APIClient.extractUserId(deviceResult.token)
                self.deviceId = identity.deviceId
                self.username = username
                await api.setToken(deviceResult.token)

                // Restore messenger from persisted Signal store
                if let store = persistentSignalStore, store.hasPersistedIdentity {
                    self.identityKeyPair = try? store.identityKeyPair(context: NullContext())
                    self.registrationId = try? store.localRegistrationId(context: NullContext())
                    self._messenger = Messenger(api: api, store: store)
                    await _messenger?.mapDevice(identity.deviceId, userId: self.userId!)
                }
                self._authState = .authenticated
                return .existingDevice
            } catch let error as APIClient.APIError {
                // 404 → no such user. 401/403 → wrong password OR the device was
                // rejected local device; fall through to a user-scoped login to distinguish.
                if error.status == 404 { return .userNotFound }
                if error.status != 401 && error.status != 403 { throw error }
                await rateLimitDelay()
            }
        }

        // No local device (or the device login was rejected) — user-scoped login
        // to verify credentials and decide the scenario.
        do {
            let result = try await api.loginWithDevice(username, password, deviceId: nil)
            self.token = result.token
            self.refreshToken = result.refreshToken
            self.userId = APIClient.extractUserId(result.token)
            self.username = username
            await api.setToken(result.token)
        } catch let error as APIClient.APIError {
            if error.status == 401 { return .invalidCredentials }
            if error.status == 404 { return .userNotFound }
            throw error
        }

        // Credentials valid. A local device that got rejected above means it was
        // Rejected local device → mismatch. Otherwise decide by the server's device count.
        if let identity = storedIdentity, !identity.deviceId.isEmpty {
            return .deviceMismatch
        }

        await rateLimitDelay()
        let serverDevices = try await api.listDevices()
        if serverDevices.count <= 1 {
            // Only device (or none) — re-provision directly, no linking needed.
            return .onlyDevice
        } else {
            // Multiple devices exist — need approval from an existing one.
            self._authState = .pendingApproval
            return .newDevice
        }
    }

    // MARK: - Login + Provision (device linking)

    /// Combined login + new device provisioning for device linking.
    /// Logs in with user credentials, generates fresh Signal keys, provisions a new device.
    public func loginAndProvision(_ username: String, _ password: String, deviceName: String = "Device 2") async throws {
        self.username = username
        let loginResult = try await api.loginWithDevice(username, password, deviceId: nil)
        self.token = loginResult.token
        self.userId = APIClient.extractUserId(loginResult.token)
        await api.setToken(loginResult.token)
        await rateLimitDelay()

        let (identity, regId) = generateSignalIdentity()
        let (spkPrivate, spkSig) = generateSignedPreKey(identity: identity)
        let (otpKeys, preKeyRecords) = generateOneTimePreKeys()

        let deviceResult = try await api.provisionDevice(
            name: deviceName,
            identityKey: Data(identity.publicKey.serialize()).base64EncodedString(),
            registrationId: Int(regId),
            signedPreKey: SignedPreKeyUpload(
                keyId: Int(Self.signedPreKeyId),
                publicKey: Data(spkPrivate.publicKey.serialize()).base64EncodedString(),
                signature: Data(spkSig).base64EncodedString()
            ),
            oneTimePreKeys: otpKeys
        )

        self.token = deviceResult.token
        self.refreshToken = deviceResult.refreshToken
        self.deviceId = APIClient.extractDeviceId(deviceResult.token) ?? deviceResult.deviceId
        await api.setToken(deviceResult.token)

        let store = try initializeSignalStore(identity: identity, regId: regId, spkPrivate: spkPrivate, spkSig: spkSig, preKeyRecords: preKeyRecords)
        self._messenger = Messenger(api: api, store: store)

        await devices.storeIdentity(DeviceIdentity(deviceId: self.deviceId ?? ""))

        // Record the pending device locally; approval later reconciles the full account list.
        await recordOwnDevice(deviceName: deviceName)

        _authState = .authenticated
        await rateLimitDelay()
    }

    // MARK: - Connect (WebSocket + envelope loop + token refresh + auto-reconnect)

    public func connect() async throws {
        try await connectionCoordinator.run { [weak self] in
            guard let self else { return }
            guard self._connectionState != .connected else { return }
            try await self.establishConnection()
        }
    }

    /// Lifecycle-safe reconnect entrypoint. It is safe to call on every foreground resume.
    ///
    /// A connect or automatic reconnect already in flight owns the transition out of
    /// `.connecting`/`.reconnecting`; only a fully disconnected authenticated client starts a new
    /// connection attempt here.
    public func ensureConnected() async throws {
        guard _authState == .authenticated, _connectionState == .disconnected else { return }
        try await connect()
    }

    private func establishConnection() async throws {
        do {
            logger.log("[client] connect() begin state=\(_connectionState)")
            // Cancel any existing loops (but not reconnectTask — it called us)
            envelopeTask?.cancel()
            envelopeTask = nil
            tokenRefreshTask?.cancel()
            tokenRefreshTask = nil

            shouldReconnect = true
            _connectionState = .connecting

            // Listen for PreKeyStatus frames from server
            await gateway.setOnPreKeyStatus { [weak self] count, threshold in
                if count < threshold {
                    Task { [weak self] in await self?.replenishPreKeys() }
                }
            }

            // Ensure fresh token before connecting
            await ensureFreshToken()

            try await gateway.connect()
            _connectionState = .connected
            reconnectAttempts = 0
            persistSession() // save refreshed tokens on connect/reconnect
            logger.log("gateway connected (messenger: \(_messenger != nil))")
            startEnvelopeLoop()
            startTokenRefresh()
            startForegroundObserver()
        } catch {
            if _connectionState != .connected {
                _connectionState = .disconnected
            }
            throw error
        }
    }

    #if os(iOS)
    /// The one foreground observer's removal token. Held so teardown can remove it.
    private var foregroundObserver: NSObjectProtocol?
    #endif

    /// Re-check connection when app returns to foreground.
    /// Matches JS client's visibilitychange handler.
    ///
    /// Register exactly once and retain the removal token. The observer is lifecycle-scoped, not
    /// connection-scoped.
    private func startForegroundObserver() {
        #if os(iOS)
        guard foregroundObserver == nil else { return }
        foregroundObserver = NotificationCenter.default.addObserver(
            forName: UIApplication.willEnterForegroundNotification,
            object: nil, queue: .main
        ) { [weak self] _ in
            guard let self = self else { return }
            Task {
                do {
                    try await self.ensureConnected()
                } catch {
                    self.logger.log("app foreground reconnect failed: \(error)")
                }
            }
        }
        #endif
    }

    /// Balance ``startForegroundObserver()``. Idempotent.
    private func removeForegroundObserver() {
        #if os(iOS)
        if let token = foregroundObserver {
            NotificationCenter.default.removeObserver(token)
            foregroundObserver = nil
        }
        #endif
    }

    /// Intentional disconnect — stops reconnection.
    public func disconnect() {
        shouldReconnect = false
        removeForegroundObserver()
        envelopeTask?.cancel()
        envelopeTask = nil
        tokenRefreshTask?.cancel()
        tokenRefreshTask = nil
        reconnectTask?.cancel()
        reconnectTask = nil
        gateway.disconnectSync()
        _connectionState = .disconnected
        messageQueue.removeAll()
    }

    // MARK: - Push Notifications

    /// Register APNS/FCM push token with server. Requires device-scoped JWT.
    /// Safe to call multiple times — server upserts by deviceId.
    public func registerPushToken(_ token: String) async throws {
        try await api.registerPushToken(token)
    }

    /// Drain queued envelopes after a silent push wake. Connects if needed, waits up to `timeout`
    /// seconds (returning early when the receive path stays idle for 500ms), and returns the
    /// number of successfully processed envelopes. Does NOT disconnect afterwards — the OS will freeze
    /// the app when done.
    ///
    /// This observes successful receive-path persistence without consuming `messageQueue`.
    /// The app owns notification classification; the kit treats model keys as opaque.
    ///
    /// Returns zero after both connection attempts fail, which is indistinguishable from a
    /// successful drain that processed no envelopes. Connection failure is logged.
    public func processPendingMessages(timeout: TimeInterval) async -> Int {
        await pushDrainCoordinator.run { [weak self] in
            guard let self else { return 0 }
            return await self.performPendingMessageDrain(timeout: timeout)
        }
    }

    private func performPendingMessageDrain(timeout: TimeInterval) async -> Int {
        let startSnapshot = await processedEnvelopes.snapshot()
        let processedAtStart = startSnapshot.count
        let startedConnected = _connectionState == .connected
        logger.log("[push] drain start (timeout=\(Int(timeout))s, connected=\(startedConnected))")

        if !startedConnected {
            let connected = await connectWithOneRetry()
            guard connected else { return 0 }
        }

        let deadline = Date().addingTimeInterval(timeout)
        await waitForPushDrainActivity(until: deadline)

        var snapshot = await processedEnvelopes.snapshot()
        var processed = snapshot.count >= processedAtStart ? snapshot.count - processedAtStart : 0

        if shouldForceReconnectAfterPush(
            processed: processed,
            startedConnected: startedConnected,
            lastProcessedAt: snapshot.lastProcessedAt,
            now: Date(),
            recentActivityWindow: Self.pushDrainRecentActivityWindow
        ) {
            logger.log("[push] contradiction: connected socket drained nothing; forcing reconnect")
            if await forceReconnect() {
                await waitForPushDrainActivity(until: deadline)
                snapshot = await processedEnvelopes.snapshot()
                processed = snapshot.count >= processedAtStart ? snapshot.count - processedAtStart : 0
            }
        }

        let result = Int(min(processed, UInt64(Int.max)))
        logger.log("[push] drain done: processed=\(result)")
        return result
    }

    private func connectWithOneRetry() async -> Bool {
        do {
            try await connect()
            return true
        } catch {
            logger.log("[push] connect failed (attempt 1/2): \(error)")
            try? await Task.sleep(nanoseconds: Self.pushDrainReconnectRetryNanos)
            do {
                try await connect()
                return true
            } catch {
                logger.log("[push] drain ABORTED — could not connect after 2 attempts: \(error)")
                return false
            }
        }
    }

    private func forceReconnect() async -> Bool {
        envelopeTask?.cancel()
        envelopeTask = nil
        tokenRefreshTask?.cancel()
        tokenRefreshTask = nil
        reconnectTask?.cancel()
        reconnectTask = nil
        await gateway.disconnect()
        _connectionState = .disconnected
        return await connectWithOneRetry()
    }

    private func waitForPushDrainActivity(until deadline: Date) async {
        let idleThreshold: TimeInterval = 0.5
        var lastActivityAt = Date()

        while Date() < deadline {
            let observedAt = await processedEnvelopes.snapshot().lastProcessedAt
            if observedAt > lastActivityAt {
                lastActivityAt = observedAt
            }
            if Date().timeIntervalSince(lastActivityAt) > idleThreshold {
                break
            } else {
                try? await Task.sleep(nanoseconds: 50_000_000)
            }
        }
    }

    /// Schedule auto-reconnect with exponential backoff.
    /// 1s → 2s → 4s → 8s → 16s → 30s cap. Matches JS client.
    private func scheduleReconnect() {
        guard shouldReconnect else { return }

        let delay = min(
            Self.reconnectDelayMs * (1 << UInt64(min(reconnectAttempts, 5))),
            Self.reconnectMaxDelayMs
        )
        reconnectAttempts += 1
        _connectionState = .reconnecting
        logger.log("reconnecting in \(delay)ms (attempt \(reconnectAttempts))")

        reconnectTask = Task { [weak self] in
            try? await Task.sleep(nanoseconds: delay * 1_000_000)
            guard let self = self, self.shouldReconnect, !Task.isCancelled else { return }

            do {
                // Refresh token before reconnecting
                await self.ensureFreshToken()
                try await self.connect()
                self.logger.log("reconnected after \(self.reconnectAttempts) attempts")
            } catch {
                // connect() failed — onClose in envelope loop will schedule next attempt
                self.logger.log("reconnect failed: \(error.localizedDescription)")
                self.scheduleReconnect()
            }
        }
    }

    // MARK: - High-Level Operations

    /// Send a friend request. Stores the target with their username so the UI can display it.
    ///
    /// - Note: A second device does not learn about friends added after it was linked.
    public func befriend(_ targetUserId: String, username targetUsername: String) async throws {
        _ = try requireMessenger()

        let existing = await friends.getFriend(targetUserId)
        switch existing?.status {
        case .accepted:
            return
        case .pendingReceived:
            try await acceptFriend(targetUserId)
            return
        case .pendingSent, .none:
            break
        }

        var msg = Obscura_Client_V1_ClientMessage()
        msg.friendRequest.username = username ?? ""
        msg.timestamp = UInt64(Date().timeIntervalSince1970 * 1000)

        try await sendToAllDevices(targetUserId, msg)
        if existing == nil {
            try await friends.add(targetUserId, targetUsername, status: .pendingSent)
        }
    }

    /// Accept a friend request. Updates status to accepted.
    ///
    /// - Note: friendship changes are not copied to own devices after link time.
    public func acceptFriend(_ targetUserId: String) async throws {
        _ = try requireMessenger()

        var msg = Obscura_Client_V1_ClientMessage()
        msg.friendAccept = Obscura_Client_V1_FriendAccept()
        msg.timestamp = UInt64(Date().timeIntervalSince1970 * 1000)

        try await sendToAllDevices(targetUserId, msg)
        try await friends.updateStatus(targetUserId, .accepted)
    }

    /// Announce the current device list to all friends.
    public func announceDevices() async throws {
        let ownDevices = await devices.getOwnDevices()
        var announce = Obscura_Client_V1_DeviceAnnounce()
        announce.devices = ownDevices.map { dev in
            var info = Obscura_Client_V1_DeviceInfo()
            info.id = dev.deviceId
            info.name = dev.deviceName
            return info
        }

        var msg = Obscura_Client_V1_ClientMessage()
        msg.deviceAnnounce = announce
        msg.timestamp = UInt64(Date().timeIntervalSince1970 * 1000)

        let accepted = await friends.getAccepted()
        for friend in accepted {
            try await sendToAllDevices(friend.userId, msg)
        }
    }

    /// Signal sessions are keyed on peer device UUID, so clearing a user means clearing each of
    /// that user's device sessions.
    /// `deleteAllSessions(for:)` matches on the address-name prefix, so passing a device UUID here
    /// deletes exactly that device's session.
    private func clearSessionsWithUser(_ userId: String) async {
        guard let messenger = _messenger else { return }
        for deviceId in await messenger.getDeviceIdsForUser(userId) {
            try? persistentSignalStore?.deleteAllSessions(for: deviceId)
        }
    }

    /// Approve a device link request — fetch bundles, send DEVICE_LINK_APPROVAL, then announce.
    public func approveLink(newDeviceId: String, challengeResponse: Data) async throws {
        let messenger = try requireMessenger()
        guard let uid = userId else { throw ObscuraError.notAuthenticated }

        // Fetch prekey bundles so we can encrypt to the new device
        let bundles = try await messenger.fetchPreKeyBundles(uid)
        await rateLimitDelay()
        for bundle in bundles {
            do {
                try await messenger.processServerBundle(bundle, userId: uid)
            } catch {
                logger.sessionEstablishFailed(userId: uid, error: "\(error)")
            }
        }

        if await devices.getOwnDevices().contains(where: { $0.deviceId == newDeviceId }) == false {
            let deviceName = (try? await api.getDevice(newDeviceId))?.name ?? "Device"
            await devices.addOwnDevice(OwnDevice(deviceId: newDeviceId, deviceName: deviceName))
        }
        let ownDevices = await devices.getOwnDevices()
        let friendsData = await friends.getAll()
        let friendsExportData = Self.encodeFriendsForLink(friendsData)

        var approval = Obscura_Client_V1_DeviceLinkApproval()
        approval.challengeResponse = challengeResponse
        approval.ownDevices = ownDevices.map { d in
            var info = Obscura_Client_V1_DeviceInfo()
            info.id = d.deviceId
            info.name = d.deviceName
            return info
        }
        approval.friendsExport = friendsExportData

        var msg = Obscura_Client_V1_ClientMessage()
        msg.deviceLinkApproval = approval
        msg.timestamp = UInt64(Date().timeIntervalSince1970 * 1000)

        let msgData = try msg.serializedData()
        try await messenger.queueMessage(targetDeviceId: newDeviceId, clientMessageData: msgData, targetUserId: uid)
        _ = try await messenger.flushMessages()

        try await announceDevices()
    }

    // MARK: - Device Linking (QR Code / Link Code)

    /// Generate a link code for this device. Display as QR code or copyable text.
    /// The new device calls this, the existing device scans/validates it.
    public func generateLinkCode() -> String? {
        guard let deviceId else { return nil }
        return DeviceLink.generateLinkCode(deviceId: deviceId)
    }

    /// Validate a link code and approve the device link.
    /// The existing device calls this after scanning the QR code.
    /// Validates the code, then sends DEVICE_LINK_APPROVAL + DEVICE_ANNOUNCE.
    public func validateAndApproveLink(_ linkCodeString: String) async throws {
        let result = DeviceLink.validateLinkCode(linkCodeString)

        switch result {
        case .valid(let code):
            guard let challenge = DeviceLink.extractChallenge(code) else {
                throw ObscuraError.deviceLinkFailed("invalid challenge in link code")
            }

            // Fetch prekey bundles for the new device so we can encrypt to it
            let messenger = try requireMessenger()
            let bundles = try await messenger.fetchPreKeyBundles(userId!)
            await rateLimitDelay()

            // Find the bundle for the new device
            guard let newDeviceBundle = bundles.first(where: { $0.deviceId == code.deviceId }) else {
                throw ObscuraError.deviceLinkFailed("no prekey bundle for device \(code.deviceId)")
            }
            try await messenger.processServerBundle(newDeviceBundle, userId: userId!)

            // Add new device to own device list
            let deviceName = (try? await api.getDevice(code.deviceId))?.name ?? "Device"
            let newDevice = OwnDevice(deviceId: code.deviceId, deviceName: deviceName)
            await devices.addOwnDevice(newDevice)

            // Approve the new device, then announce the updated device list.
            try await approveLink(newDeviceId: code.deviceId, challengeResponse: challenge)

        case .expired:
            throw ObscuraError.deviceLinkFailed("link code expired")

        case .invalid(let reason):
            throw ObscuraError.deviceLinkFailed(reason)
        }
    }

    /// Re-provision this device with a new identity key (device takeover).
    public func takeoverDevice() async throws {
        let (identity, regId) = generateSignalIdentity()
        let (spkPrivate, spkSig) = generateSignedPreKey(identity: identity)
        let (otpKeys, preKeyRecords) = generateOneTimePreKeys()

        try await api.uploadDeviceKeys(
            identityKey: Data(identity.publicKey.serialize()).base64EncodedString(),
            registrationId: Int(regId),
            signedPreKey: SignedPreKeyUpload(
                keyId: Int(Self.signedPreKeyId),
                publicKey: Data(spkPrivate.publicKey.serialize()).base64EncodedString(),
                signature: Data(spkSig).base64EncodedString()
            ),
            oneTimePreKeys: otpKeys
        )

        // The identity key changed, so clear every device-UUID session and force fresh prekey
        // exchanges on the next send.
        for friend in await friends.getAccepted() {
            await clearSessionsWithUser(friend.userId)
        }

        let store = try initializeSignalStore(identity: identity, regId: regId, spkPrivate: spkPrivate, spkSig: spkSig, preKeyRecords: preKeyRecords)
        self._messenger = Messenger(api: api, store: store)

        if let did = deviceId, let uid = userId {
            await _messenger?.mapDevice(did, userId: uid)
        }
    }

    // MARK: - Encrypted Attachments

    /// Encrypt plaintext and upload the ciphertext, returning the reference triple.
    /// The caller embeds `{id, contentKey, nonce}` in a synced model entry whose sync carries the
    /// reference. The server only ever sees ciphertext. Mirrors the Kotlin `uploadAttachment`;
    /// returns key material so the bridge needn't reach into `AttachmentCrypto` directly. Pair with
    /// `downloadDecryptedAttachment`.
    public func uploadAttachment(_ plaintext: Data) async throws -> (id: String, contentKey: Data, nonce: Data) {
        let encrypted = try AttachmentCrypto.encrypt(plaintext)
        let result = try await api.uploadAttachment(encrypted.ciphertext)
        return (id: result.id, contentKey: encrypted.contentKey, nonce: encrypted.nonce)
    }

    /// Download ciphertext and decrypt with provided key material.
    /// Checks in-DB cache first — returns instantly on hit.
    public func downloadDecryptedAttachment(id: String, contentKey: Data, nonce: Data) async throws -> Data {
        // Cache hit — return immediately, zero network
        if let cached = await attachmentCache?.get(id) {
            return cached
        }
        // Cache miss — fetch, decrypt, cache
        let ciphertext = try await api.fetchAttachment(id)
        let plaintext = try AttachmentCrypto.decrypt(ciphertext, contentKey: contentKey, nonce: nonce)
        await attachmentCache?.put(id, plaintext: plaintext)
        return plaintext
    }

    /// Send an application entry (`KIT_API.md` §5) — the outbox half of the thin kit,
    /// paired with ``inbox`` on the receive side and ``entries`` for local storage.
    ///
    /// **The caller names the recipients** (SPEC §0.4). The kit fans out to every device of every
    /// listed userId, plus this user's own *other* devices, and makes **no delivery decision of its
    /// own** — no audience resolution, no reading of `payload` to discover who it is for.
    ///
    /// Two properties §5 asks to be proven rather than assumed, both pinned by Kotlin's
    /// `EntrySendTests` and mirrored here:
    ///
    /// 1. **The sending device is excluded from its own fan-out.** `getOwnDevices()` includes this
    ///    device, and a message encrypted to yourself is at best waste and at worst a duplicate the
    ///    app must dedupe.
    /// 2. **The sender gets no inbox row.** Nothing loops back locally, so the app writes its own
    ///    outgoing entry to ``entries`` — one write path in the kit, two in the app.
    ///
    /// An empty `recipientUserIds` is legitimate and not an error: it means "my own devices only",
    /// which is what a self-scoped model wants. Failing loud is for an audience the kit was asked to
    /// *guess*, and here it never guesses.
    public func send(
        to recipientUserIds: [String],
        modelKey: String,
        entryId: String,
        sentAt: UInt64 = UInt64(Date().timeIntervalSince1970 * 1000),
        payload: Data
    ) async throws {
        var sync = Obscura_Client_V1_AppEntry()
        sync.model = modelKey
        sync.id = entryId
        sync.timestamp = sentAt
        sync.data = payload

        var msg = Obscura_Client_V1_ClientMessage()
        msg.appEntry = sync

        // Deduplicated because the app may legitimately name the same user twice — e.g. both
        // participants of a canonical `userIdA_userIdB` conversation, one of whom is you.
        var seen = Set<String>()
        let targets = recipientUserIds.filter { $0 != userId && seen.insert($0).inserted }

        // PER-RECIPIENT, not all-or-nothing. `sendToAllDevices` throws for a recipient with no
        // registered devices, so letting the first failure escape would abandon recipients 2..N —
        // and, worse, skip the own-device self-sync below, so the user's own other devices would
        // silently never receive something they wrote. One unreachable friend must not cost the
        // other four, or the sender's own copy.
        var failures: [(String, Error)] = []
        for recipient in targets {
            do {
                try await sendToAllDevices(recipient, msg)
            } catch {
                failures.append((recipient, error))
                logger.log("SEND FAILED to \(recipient.prefix(8)) for \(modelKey)/\(entryId.prefix(20)): \(error)")
            }
        }

        // Own OTHER devices. Runs whether or not a recipient failed — see above. The `!=` is
        // §5 property 1: without it this device encrypts to itself.
        let ownDevices = await devices.getOwnDevices().filter { $0.deviceId != self.deviceId }
        if !ownDevices.isEmpty, let uid = userId {
            let messenger = try requireMessenger()
            let msgData = try msg.serializedData()
            for device in ownDevices {
                do {
                    try await messenger.queueMessage(targetDeviceId: device.deviceId,
                                                     clientMessageData: msgData, targetUserId: uid)
                } catch {
                    logger.log("self-sync failed for device \(device.deviceId): \(error)")
                }
            }
            _ = try? await messenger.flushMessages()
        }

        // Throw only when NOBODY named got it. A partial failure is logged and survivable — the
        // entry is stored, the other recipients have it, and the caller can retry. A total failure
        // is different in kind: the app believes it sent something that reached no one, and it must
        // be able to tell the user so.
        if !targets.isEmpty && failures.count == targets.count {
            throw ObscuraError.sendFailed(
                "\(modelKey)/\(entryId.prefix(20)) reached none of its \(targets.count) recipient(s)")
        }
    }

    // ── Ephemeral typing signals ──────────────────────────────────────────────────────────────

    /// Send an explicit typing state to the caller-named recipients.
    ///
    /// The context id is opaque. As with application entries, the kit never derives or broadens
    /// the audience from it.
    public func sendTyping(
        to recipientUserIds: [String],
        contextId: String,
        state: TypingState
    ) async {
        guard !contextId.isEmpty, let ownDeviceId = deviceId else { return }
        guard await TypingThrottle.shared.shouldSend(
            contextId: contextId, state: state, senderDeviceId: ownDeviceId
        ) else { return }

        var signal = Obscura_Client_V1_TypingSignal()
        signal.contextID = contextId
        signal.state = WireCodec.encodeTypingState(state)

        var msg = Obscura_Client_V1_ClientMessage()
        msg.typingSignal = signal
        msg.timestamp = UInt64(Date().timeIntervalSince1970 * 1000)

        var seen = Set<String>()
        for recipient in recipientUserIds where
            recipient != userId && seen.insert(recipient).inserted
        {
            do {
                try await sendToAllDevices(recipient, msg)
            } catch {
                logger.log("typing signal to \(recipient.prefix(8)) failed: \(error)")
            }
        }
    }

    /// Who is currently typing in a context, by display name.
    ///
    /// Auto-expires; a signal with no refresh disappears on its own, which is what makes signals
    /// droppable (`KIT_API.md` §4) rather than something the inbox has to carry.
    public nonisolated func observeTyping(contextId: String) -> TypingObservation {
        TypingObservation(tracker: TypingStateRegistry.shared.tracker, contextId: contextId)
    }

    // MARK: - Facade (high-level methods for thin bridges)

    /// Typed event for the unified event stream.
    public enum ObscuraEvent {
        case friendsChanged
        case connectionChanged(ConnectionState)
        case authChanged(AuthState)
        case messageReceived(model: String)
        case typingChanged(contextId: String, typers: [String])
        case authFailed(reason: String)
    }

    /// Unified event stream — bridge subscribes once and relays all events.
    public func observeEvents() -> AsyncStream<ObscuraEvent> {
        AsyncStream { continuation in
            // Friends
            let friendTask = Task {
                for await _ in friends.observeAll().values {
                    continuation.yield(.friendsChanged)
                }
            }
            // Connection
            let connTask = Task {
                for await state in observeConnectionState() {
                    continuation.yield(.connectionChanged(state))
                }
            }

            // Auth
            let authTask = Task {
                for await state in observeAuthState() {
                    continuation.yield(.authChanged(state))
                }
            }
            // Incoming entries carry the opaque model key declared on APP_ENTRY.
            let msgTask = Task {
                for await event in events() {
                    if event.type == "APP_ENTRY" {
                        guard let model = event.model, !model.isEmpty else {
                            logger.log("RECV APP_ENTRY missing model; event suppressed")
                            continue
                        }
                        continuation.yield(.messageReceived(model: model))
                    }
                }
            }
            // Auth failure (token refresh exhausted) — distinct from a clean logout.
            let authFailedTask = Task {
                for await reason in observeAuthFailed() {
                    continuation.yield(.authFailed(reason: reason))
                }
            }

            continuation.onTermination = { _ in
                friendTask.cancel()
                connTask.cancel()
                authTask.cancel()
                msgTask.cancel()
                authFailedTask.cancel()
            }
        }
    }

    /// Current friend rows. Aggregate wake events intentionally carry no copies of this payload.
    public func getFriends() async -> [Friend] {
        await friends.getAll()
    }

    /// Snapshot of the bounded debug ring, newest first. Debug lines are never live events.
    public func getDebugLog() -> [String] {
        recordingLogger.snapshot()
    }

    /// Decode a friend code and send a friend request.
    public func addFriendByCode(_ code: String) async throws {
        let cleaned = code.replacingOccurrences(of: "\u{00AD}", with: "")
        let decoded = try FriendCode.decode(cleaned)
        try await befriend(decoded.userId, username: decoded.username)
    }

    /// Generate a shareable friend code for this user.
    public func friendCode() -> String? {
        guard let userId = userId, let username = username else { return nil }
        return FriendCode.encode(userId: userId, username: username)
    }

    /// Full logout — handles ALL teardown. Bridge calls this one method.
    public func fullLogout() async {
        // Cancel all background tasks
        envelopeTask?.cancel()
        envelopeTask = nil
        tokenRefreshTask?.cancel()
        tokenRefreshTask = nil
        reconnectTask?.cancel()
        reconnectTask = nil
        shouldReconnect = false
        removeForegroundObserver()

        // Disconnect
        gateway.disconnectSync()

        // Clear auth state
        try? await api.clearToken()
        token = nil
        refreshToken = nil
        userId = nil
        username = nil
        deviceId = nil
        _messenger = nil
        _connectionState = .disconnected
        _authState = .loggedOut
        messageQueue.removeAll()
        await TypingStateRegistry.shared.tracker.clearAll()

        // Clear persisted session
        sessionStorage?.clear()
        Task { await attachmentCache?.clearAll() }
        logger.log("full logout complete")
    }

    /// Persist current session. Called internally after register, login, connect, reconnect.
    public func persistSession() {
        guard let token = token, let userId = userId else { return }
        var data: [String: Any] = [
            "token": token,
            "refreshToken": refreshToken ?? "",
            "userId": userId,
            "deviceId": deviceId ?? "",
            "username": username ?? "",
            "registrationId": registrationId ?? 0,
        ]
        sessionStorage?.save(data)
    }

    /// Restore kit-owned session state from storage and connect. Application schemas are not part
    /// of persisted kit state (SPEC §0.4).
    public func restorePersistedSession() async throws {
        guard let storage = sessionStorage, let saved = storage.load(),
              let token = saved["token"] as? String, !token.isEmpty,
              let userId = saved["userId"] as? String, !userId.isEmpty else {
            throw ObscuraError.notAuthenticated
        }

        let regId = UInt32(saved["registrationId"] as? Int ?? 0)
        await restoreSession(
            token: token,
            refreshToken: saved["refreshToken"] as? String,
            userId: userId,
            deviceId: saved["deviceId"] as? String,
            username: saved["username"] as? String,
            registrationId: regId
        )

        // Refresh token and connect
        let fresh = await ensureFreshToken()
        guard fresh else {
            storage.clear()
            throw ObscuraError.notAuthenticated
        }

        try await connect()
        persistSession() // save refreshed tokens
        logger.log("session restored from storage")
    }

    /// Wait for next incoming message. Uses buffered queue — messages processed by
    /// the envelope loop are queued here, so timing doesn't matter.
    func waitForMessage(timeout: TimeInterval = 10) async throws -> MessageWakeEvent {
        // Check buffer first
        if !messageQueue.isEmpty {
            return messageQueue.removeFirst()
        }

        // Poll with short sleeps — avoids continuation leak that caused hangs
        let deadline = Date().addingTimeInterval(timeout)
        while Date() < deadline {
            if !messageQueue.isEmpty {
                return messageQueue.removeFirst()
            }
            try await Task.sleep(nanoseconds: 50_000_000) // 50ms poll
        }
        throw ObscuraError.timeout
    }

    // MARK: - Logout

    /// Logout — clears credentials and disconnects. Durable kit data is preserved.
    /// Call `restoreSession()` + `connect()` to resume, or `login()`/`loginAndProvision()` for a fresh session.
    public func logout() async throws {
        disconnect()
        if let rt = refreshToken { try? await api.logout(rt) }
        token = nil
        refreshToken = nil
        userId = nil
        username = nil
        deviceId = nil
        _messenger = nil
        _authState = .loggedOut
        await api.clearToken()
    }

    /// Nuclear wipe — clears all data from this device.
    /// After this, the device must re-register or loginAndProvision.
    public func wipeDevice() async throws {
        try await logout()
        identityKeyPair = nil
        registrationId = nil
        persistentSignalStore?.clearAll()
        persistentSignalStore = nil
        await friends.clearAll()
        await devices.clearAll()
        try? await entries.wipe()
        // The §3.3 rule 2 carve-out, and the reason it is worded as a MUST: the inbox holds
        // DECRYPTED plaintext — full payloads, the resolved sender name, the model key. A wipe that
        // spared it would leave decrypted application content behind.
        try? await inbox.wipe()
    }

    // MARK: - Internal: Send to all devices of a user

    internal func sendSerializedClientMessage(to targetUserId: String, data: Data) async throws {
        let messenger = try requireMessenger()
        let bundles = try await messenger.fetchPreKeyBundles(targetUserId)
        await rateLimitDelay()

        for bundle in bundles {
            do {
                try await messenger.processServerBundle(bundle, userId: targetUserId)
            } catch {
                logger.sessionEstablishFailed(userId: targetUserId, error: "\(error)")
                continue
            }
            try await messenger.queueMessage(
                targetDeviceId: bundle.deviceId,
                clientMessageData: data,
                targetUserId: targetUserId
            )
        }
        _ = try await messenger.flushMessages()
    }

    private func sendToAllDevices(_ targetUserId: String, _ msg: Obscura_Client_V1_ClientMessage, excludingDeviceId: String? = nil) async throws {
        let messenger = try requireMessenger()
        let bundles = try await messenger.fetchPreKeyBundles(targetUserId)
        await rateLimitDelay()

        let msgData = try msg.serializedData()
        var queued = 0
        for bundle in bundles {
            if let excluded = excludingDeviceId, bundle.deviceId == excluded { continue }
            do {
                try await messenger.processServerBundle(bundle, userId: targetUserId)
            } catch {
                logger.sessionEstablishFailed(userId: targetUserId, error: "\(error)")
                continue
            }
            let targetDeviceId = bundle.deviceId
            try await messenger.queueMessage(targetDeviceId: targetDeviceId, clientMessageData: msgData, targetUserId: targetUserId)
            queued += 1
        }
        guard queued > 0 else {
            throw ObscuraError.sendFailed("No usable devices for \(targetUserId)")
        }
        let result = try await messenger.flushMessages()
        guard result.sent > 0 else {
            throw ObscuraError.sendFailed("No submissions sent to \(targetUserId)")
        }
    }

    // MARK: - Internal: Envelope Loop

    private func startEnvelopeLoop() {
        envelopeTask = Task { [weak self] in
            while !Task.isCancelled {
                guard let self = self else { break }
                do {
                    let raw = try await self.gateway.waitForRawEnvelope(timeout: 30)
                    await self.processEnvelope(raw)
                } catch {
                    if Task.isCancelled { break }
                    if error is CancellationError { break }
                    if let gwError = error as? GatewayConnection.GatewayError {
                        if case .timeout = gwError { continue } // Normal idle timeout, keep looping
                        if case .notConnected = gwError {
                            // Connection dropped — trigger reconnect
                            self._connectionState = .disconnected
                            self.logger.log("gateway disconnected, scheduling reconnect")
                            self.scheduleReconnect()
                            break
                        }
                    }
                    // Unknown error — also trigger reconnect
                    self._connectionState = .disconnected
                    self.logger.log("envelope loop error: \(error.localizedDescription)")
                    self.scheduleReconnect()
                    break
                }
            }
        }
    }

    private func processEnvelope(_ raw: (id: Data, senderID: Data, senderDeviceID: Data, timestamp: UInt64, message: Data)) async {
        guard let messenger = _messenger else { return }

        let sourceUserId = bytesToUuid(raw.senderID)

        // Rate limit: skip senders with too many recent decrypt failures
        if let entry = decryptFailures[sourceUserId] {
            if Date().timeIntervalSince(entry.windowStart) > decryptFailureWindow {
                decryptFailures[sourceUserId] = nil // Reset window
            } else if entry.count >= maxDecryptFailures {
                return // Skip — rate limited
            }
        }

        do {
            // The server-stamped sender device UUID selects the pairwise inbound Signal session.
            // Missing identity is an error, never a registration-id guess, and skips the ack.
            guard raw.senderDeviceID.count == 16 else {
                throw ObscuraError.provisionFailed(
                    "Envelope from \(sourceUserId) has no sender_device_id (\(raw.senderDeviceID.count) bytes); cannot select an inbound session")
            }
            let senderDeviceId = bytesToUuid(raw.senderDeviceID)

            let encMsg = try Obscura_Client_V1_EncryptedMessage(serializedData: raw.message)
            let messageType = encMsg.type == .prekeyMessage ? 1 : 2

            // Count only decrypt failures. Persistence faults are local and must not rate-limit a
            // sender after storage becomes available again.
            let plaintext: [UInt8]
            do {
                plaintext = try await messenger.decrypt(
                    senderUserId: sourceUserId, senderDeviceUuid: senderDeviceId,
                    content: encMsg.content, messageType: messageType
                )
            } catch {
                let entry = decryptFailures[sourceUserId] ?? (count: 0, windowStart: Date())
                decryptFailures[sourceUserId] = (count: entry.count + 1, windowStart: entry.windowStart)
                throw error
            }
            let clientMsg = try Obscura_Client_V1_ClientMessage(serializedData: Data(plaintext))

            // Route by message type. SPEC §0.9 rule 3+4: decrypt → persist → (notify) → ack.
            // routeMessage now throws, so if durable persistence fails the error propagates to the
            // catch below and we SKIP the ack — the message stays on the server for retry rather
            // than being deleted un-persisted. authorDeviceId is the decrypting session's device
            // UUID (== senderDeviceId, proven by the MAC), never the userId.
            // `Envelope.id` is the inbox's DEDUPE KEY, so it gets the same length check
            // `sender_device_id` already gets above — and for the same reason: SPEC §0.10 treats
            // everything the relay stamps as untrusted.
            //
            // Without it, `bytesToUuid` falls back to raw hex for non-16-byte input, so an EMPTY id
            // (proto3's default) becomes "" for every envelope. They would all hash to one key: the
            // first inserts and is acked, and every one after is suppressed by INSERT OR IGNORE,
            // reported as a duplicate, and ACKED — the server deleting messages never stored.
            guard raw.id.count == 16 else {
                throw ObscuraError.provisionFailed(
                    "Envelope id is \(raw.id.count) bytes, expected 16; cannot use it as a dedupe key")
            }
            let isNew = try await routeMessage(
                clientMsg, sourceUserId: sourceUserId, senderDeviceId: senderDeviceId,
                envelopeId: bytesToUuid(raw.id)
            )
            await processedEnvelopes.record()

            // Emit to event subscribers
            let received = MessageWakeEvent(
                type: WireCodec.decodeMessageType(clientMsg.payload),
                username: {
                    switch clientMsg.payload {
                    case .friendRequest?: return clientMsg.friendRequest.username
                    default: return ""
                    }
                }(),
                sourceUserId: sourceUserId,
                senderDeviceId: senderDeviceId,
                timestamp: clientMsg.timestamp,
                model: {
                    if case .appEntry? = clientMsg.payload { return clientMsg.appEntry.model }
                    return nil
                }(),
                rawBytes: Data(plaintext)
            )
            if isNew {
                emit(received)
            }

            // Check prekey count (non-blocking, fire-and-forget)
            checkAndReplenishPreKeys()

            // Ack
            do {
                try await gateway.acknowledge([raw.id])
            } catch {
                logger.ackFailed(envelopeId: raw.id.map { String(format: "%02x", $0) }.joined(), error: "\(error)")
            }
        } catch {
            // No ack — the message stays on the server (SPEC §0.9 rule 3). The rate-limit counter is
            // NOT touched here; see the inner catch around `decrypt`.
            logger.decryptFailed(sourceUserId: sourceUserId, error: "\(error)")
        }
    }

    // MARK: - Internal: Message Routing

    /// Persist a decrypted message, by class (`KIT_API.md` §4).
    ///
    /// Called from the envelope loop **before** the ack, and it throws on a failed durable write so
    /// the ack is skipped and the message survives on the server (SPEC §0.9 rule 3).
    ///
    /// `classify` decides what each arm may do; the switch routes only within
    /// that policy.
    private func routeMessage(
        _ msg: Obscura_Client_V1_ClientMessage,
        sourceUserId: String,
        senderDeviceId: String,
        envelopeId: String
    ) async throws -> Bool {
        NSLog("[ObscuraKit] routeMessage payload=%@ from=%@", WireCodec.decodeMessageType(msg.payload), String(sourceUserId.prefix(8)))

        switch payloadDisposition(msg.payload) {
        case .inboxed:
            return try await inboxMessage(
                msg, sourceUserId: sourceUserId, senderDeviceId: senderDeviceId,
                envelopeId: envelopeId)

        case .unimplemented:
            // Diagnose and acknowledge declared unsupported arms so they cannot wedge the queue.
            logger.log("RECV UNIMPLEMENTED arm=\(WireCodec.decodeMessageType(msg.payload)) "
                + "from=\(sourceUserId.prefix(8)) (dropped and acked — see KIT_API.md §4.2)")
            return true

        case .kitInternal, .droppable:
            break // fall through to the handlers below
        }

        switch msg.payload {
        case .friendRequest?:
            // The payload username is a first-contact label only. A known peer cannot use another
            // request to replace its locally trusted name or friendship status.
            if let existing = await friends.getFriend(sourceUserId) {
                if existing.status == .pendingSent {
                    // Crossed requests are mutual consent. Accepting here prevents both peers from
                    // remaining permanently pending when they scan each other's codes.
                    try await acceptFriend(sourceUserId)
                    logger.log("crossed friend request from \(sourceUserId) promoted to accepted")
                    return true
                }
                // Known peer: the name comes from our graph now, never from their payload. Refresh
                // the device list (that IS ours to learn — the sending device was just added to the
                // messenger's map by decrypt) and change nothing else.
                if let messenger = _messenger {
                    let deviceIds = await messenger.getDeviceIdsForUser(sourceUserId)
                    if !deviceIds.isEmpty {
                        try await friends.updateDevices(
                            sourceUserId,
                            devices: deviceIds.map { ["deviceId": $0, "deviceName": ""] })
                    }
                }
                logger.log("friend request from already-known peer \(sourceUserId) "
                    + "(status=\(existing.status.rawValue)); keeping stored name and status")
            } else {
                try await friends.add(sourceUserId, msg.friendRequest.username, status: .pendingReceived)
            }

        case .friendAccept?:
            // An acceptance is valid only for a request we sent. Delivery alone must not let an
            // authenticated stranger insert itself as an accepted friend.
            let existing = await friends.getFriend(sourceUserId)
            if let existing, existing.status == .pendingSent {
                try await friends.updateStatus(sourceUserId, .accepted)
            } else {
                logger.log("ignoring unsolicited FRIEND_ACCEPT from \(sourceUserId) "
                    + "(local status=\(existing?.status.rawValue ?? "none"); expected pendingSent)")
            }

        case .deviceAnnounce?:
            let announce = msg.deviceAnnounce
            let deviceInfos = announce.devices.map { dev -> [String: String] in
                ["deviceId": dev.id, "deviceName": dev.name]
            }
            try await friends.updateDevices(sourceUserId, devices: deviceInfos,
                                            timestamp: clampFutureTimestamp(msg.timestamp))

        case .typingSignal?:
            await handleTypingSignal(msg, sourceUserId: sourceUserId, senderDeviceId: senderDeviceId)

        default:
            // A kit-internal arm with no handler must NOT be acked — the ack would destroy the only
            // copy of a message this kit is supposed to own. Kotlin throws here for the same reason.
            //
            // This guard catches a future arm classified kitInternal without a matching handler.
            // Throwing leaves it on the server rather than acknowledging and destroying it.
            throw ObscuraError.provisionFailed(
                "\(WireCodec.decodeMessageType(msg.payload)) is classified kit-internal but this kit "
                + "has no handler; refusing to ack it away")
        }
        return true
    }

    internal func handleTypingSignal(
        _ msg: Obscura_Client_V1_ClientMessage,
        sourceUserId: String,
        senderDeviceId: String
    ) async {
        let signal = msg.typingSignal
        guard !signal.contextID.isEmpty,
              let state = WireCodec.decodeTypingState(signal.state) else { return }

        let username = await friends.getAccepted()
            .first(where: { $0.userId == sourceUserId })?.username ?? sourceUserId
        if state == .stopped {
            await TypingStateRegistry.shared.tracker.remove(
                contextId: signal.contextID, senderDeviceId: senderDeviceId)
        } else {
            await TypingStateRegistry.shared.tracker.receive(TypingEvent(
                contextId: signal.contextID,
                senderUserId: sourceUserId,
                senderDeviceId: senderDeviceId,
                senderDisplayName: username,
                timestamp: msg.timestamp
            ))
        }
    }

    /// Write an inboxed payload to the durable inbox.
    ///
    /// Order matters: the inbox row commits before the caller acks. If it throws, nothing is acked
    /// and the message stays on the server — which is the whole point of persist-then-ack and the
    /// reason this is not an event stream.
    ///
    /// This is the only durable receive write for an application entry.
    private func inboxMessage(
        _ msg: Obscura_Client_V1_ClientMessage,
        sourceUserId: String,
        senderDeviceId: String,
        envelopeId: String
    ) async throws -> Bool {
        var isAppEntry = false
        if case .appEntry? = msg.payload { isAppEntry = true }
        let sync = msg.appEntry

        let inserted = try await inbox.put(
            InboxInsert(
                envelopeId: envelopeId,
                // Must match Kotlin byte for byte — the app reads one `kind` column from two kits,
                // and §4.1 has pix's drain BRANCH on it. WireCodec returns "" for an unset payload,
                // which is a poor value for a NOT NULL column read across a bridge; both kits now
                // share the UNKNOWN sentinel.
                kind: WireCodec.decodeMessageType(msg.payload).isEmpty
                    ? "UNKNOWN" : WireCodec.decodeMessageType(msg.payload),
                senderUserId: sourceUserId,
                senderDeviceId: senderDeviceId,
                // AppEntry-derived, so nil for an unknown arm — there is nothing to derive from.
                modelKey: isAppEntry ? sync.model : nil,
                entryId: isAppEntry ? sync.id : nil,
                sentAt: isAppEntry ? clampFutureTimestamp(sync.timestamp) : nil,
                // Opaque bytes. For an unknown arm this is the whole serialized message, because the
                // kit cannot know which sub-field would have been the payload.
                payload: isAppEntry ? sync.data : ((try? msg.serializedData()) ?? Data())
            )
        )

        if !inserted {
            // A redelivered envelope. Not an error: persist-then-ack guarantees this happens, and
            // absorbing it here is what keeps depth() and the app's counts honest. Still ack.
            logger.log("RECV DUPLICATE envelope=\(envelopeId.prefix(12)) "
                + "kind=\(WireCodec.decodeMessageType(msg.payload)) (already inboxed)")
        }
        return inserted
    }

    /// SPEC §2.4: a peer-supplied timestamp is clamped before it is stored, not after.
    ///
    /// Two distinct failures, one clamp. Apply it at **every** site that takes a `uint64` off the
    /// wire and puts it in the database:
    ///
    /// 1. **It is a crash fix.** GRDB binds `UInt64` through the NON-FAILABLE `Int64(self)` (see
    ///    `GRDB/Core/Support/StandardLibrary/StandardLibrary.swift`), so any value above
    ///    `Int64.max` TRAPS. A Swift trap is not catchable, so `processEnvelope`'s do/catch cannot
    ///    contain it: the process dies. Any authenticated user can deliver a message — friendship
    ///    is not required — so this is a remote kill with no privileges. The same bug class was
    ///    also applies to the ephemeral typing timestamp.
    /// 2. **It is an ordering fix.** Without it a peer sets a timestamp far in the future and wins
    ///    every LWW/REPLACE conflict forever — a tie-break can only order writes it can compare
    ///    honestly. On `friends.devices_updated_at` this is permanent: the guard
    ///    `WHERE devices_updated_at < ?` never passes again.
    ///
    /// Clamping toward now rather than rejecting keeps a peer whose clock is a few seconds fast
    /// working normally.
    private func clampFutureTimestamp(_ sentAt: UInt64) -> UInt64 {
        min(sentAt, UInt64(Date().timeIntervalSince1970 * 1000) + 60_000)
    }

    /// A discard is data loss the app chose deliberately, and §3.3 rule 5 requires it be logged as a
    /// security-relevant event rather than being the quiet path.
    ///
    /// - Note: the hook is passed at construction rather than set afterwards. A detached
    ///   `Task { await inbox.setOnDiscard … }` from the initializer leaves a window in which the
    ///   store is reachable with no hook, and a `discard` in that window is data loss with no record
    ///   — precisely the quiet path rule 5 forbids.
    private static func discardLogger(_ logger: ObscuraLogger) -> @Sendable ([Int64], String) -> Void {
        { ids, reason in
            logger.log("INBOX DISCARD \(ids.count) row(s) reason=\"\(reason)\" ids=\(ids)")
        }
    }

    private static func encodeFriendsForLink(_ friends: [Friend]) -> Data {
        let encoded = friends.map { friend -> [String: Any] in
            [
                "userId": friend.userId,
                "username": friend.username,
                "status": friend.status.rawValue,
                "devices": friend.devices,
            ]
        }
        return (try? JSONSerialization.data(withJSONObject: encoded)) ?? Data()
    }

    // MARK: - Internal: Prekey Replenishment (matches Kotlin pattern)

    private func checkAndReplenishPreKeys() {
        Task { [weak self] in
            guard let self = self,
                  let store = self.persistentSignalStore,
                  store.getPreKeyCount() < self.prekeyMinCount else { return }
            await self.replenishPreKeys()
        }
    }

    private func replenishPreKeys() async {
        guard let store = persistentSignalStore, let identity = identityKeyPair else { return }
        do {
            let highestId = store.getHighestPreKeyId()
            var newKeys: [PreKeyUpload] = []

            for i: UInt32 in 1...prekeyReplenishCount {
                let keyId = highestId + i
                let pk = PrivateKey.generate()
                newKeys.append(PreKeyUpload(
                    keyId: Int(keyId),
                    publicKey: Data(pk.publicKey.serialize()).base64EncodedString()
                ))
                try store.storePreKey(
                    PreKeyRecord(id: keyId, publicKey: pk.publicKey, privateKey: pk),
                    id: keyId, context: NullContext()
                )
            }

            // Reuse existing signed prekey — don't generate a new one
            let existingSpk = try store.loadSignedPreKey(id: 1, context: NullContext())

            try await api.uploadDeviceKeys(
                identityKey: Data(identity.publicKey.serialize()).base64EncodedString(),
                registrationId: Int(registrationId ?? 0),
                signedPreKey: SignedPreKeyUpload(
                    keyId: 1,
                    publicKey: Data(existingSpk.publicKey.serialize()).base64EncodedString(),
                    signature: Data(existingSpk.signature).base64EncodedString()
                ),
                oneTimePreKeys: newKeys
            )
        } catch {
            logger.sessionEstablishFailed(userId: userId ?? "unknown", error: "prekey replenish: \(error)")
        }
    }

    // MARK: - Internal: Token Refresh

    private func startTokenRefresh() {
        tokenRefreshTask = Task { [weak self] in
            var consecutiveFailures = 0
            while !Task.isCancelled {
                guard let self = self, let token = self.token else {
                    try? await Task.sleep(nanoseconds: 30_000_000_000)
                    continue
                }

                let delayMs = self.getTokenRefreshDelay(token)
                try? await Task.sleep(nanoseconds: UInt64(delayMs) * 1_000_000)

                if self.refreshToken != nil {
                    do {
                        _ = try await self.refreshTokenNow()
                        consecutiveFailures = 0
                    } catch {
                        if Self.isCancellation(error) { continue }
                        consecutiveFailures += 1
                        self.logger.tokenRefreshFailed(attempt: consecutiveFailures, error: "\(error)")
                        if consecutiveFailures >= 3 {
                            self.emitAuthFailed("token refresh failed after \(consecutiveFailures) attempts: \(error)")
                            self._authState = .loggedOut
                            break
                        }
                    }
                }
            }
        }
    }

    private func getTokenRefreshDelay(_ token: String) -> UInt64 {
        guard let payload = APIClient.decodeJWT(token),
              let exp = payload["exp"] as? Double else { return 30000 }
        let now = Date().timeIntervalSince1970
        let ttl = exp - now
        let delay = max(ttl * 0.8, 5) * 1000
        return UInt64(delay)
    }

    // MARK: - Helpers

    private func requireMessenger() throws -> Messenger {
        guard let m = _messenger else { throw ObscuraError.noMessenger }
        return m
    }

    /// Generate a fresh Signal identity keypair + registration ID. Stores on self.
    private func generateSignalIdentity() -> (IdentityKeyPair, UInt32) {
        let identity = IdentityKeyPair.generate()
        let regId = UInt32.random(in: 1...Self.maxRegistrationId)
        self.identityKeyPair = identity
        self.registrationId = regId
        return (identity, regId)
    }

    /// Generate a signed pre-key from the given identity.
    private func generateSignedPreKey(identity: IdentityKeyPair) -> (privateKey: PrivateKey, signature: [UInt8]) {
        let spkPrivate = PrivateKey.generate()
        let spkSig = identity.privateKey.generateSignature(message: spkPrivate.publicKey.serialize())
        return (spkPrivate, spkSig)
    }

    /// Generate one-time pre-keys for upload + local storage.
    private func generateOneTimePreKeys() -> (uploads: [PreKeyUpload], records: [(id: UInt32, privateKey: PrivateKey)]) {
        var uploads: [PreKeyUpload] = []
        var records: [(id: UInt32, privateKey: PrivateKey)] = []
        for i: UInt32 in 1...Self.initialPreKeyCount {
            let pk = PrivateKey.generate()
            uploads.append(PreKeyUpload(keyId: Int(i), publicKey: Data(pk.publicKey.serialize()).base64EncodedString()))
            records.append((id: i, privateKey: pk))
        }
        return (uploads, records)
    }

    /// Create and populate a PersistentSignalStore with identity + keys.
    private func initializeSignalStore(identity: IdentityKeyPair, regId: UInt32,
                                       spkPrivate: PrivateKey, spkSig: [UInt8],
                                       preKeyRecords: [(id: UInt32, privateKey: PrivateKey)]) throws -> PersistentSignalStore {
        let store = try sharedDb.map { try PersistentSignalStore(db: $0) } ?? PersistentSignalStore()
        store.logger = self.logger
        store.initialize(keyPair: identity, registrationId: regId)
        try store.storeSignedPreKey(
            SignedPreKeyRecord(id: Self.signedPreKeyId, timestamp: UInt64(Date().timeIntervalSince1970), privateKey: spkPrivate, signature: spkSig),
            id: Self.signedPreKeyId, context: NullContext()
        )
        for record in preKeyRecords {
            try store.storePreKey(
                PreKeyRecord(id: record.id, publicKey: record.privateKey.publicKey, privateKey: record.privateKey),
                id: record.id, context: NullContext()
            )
        }
        self.persistentSignalStore = store
        return store
    }

    private func bytesToUuid(_ data: Data) -> String {
        guard data.count == 16 else { return data.map { String(format: "%02x", $0) }.joined() }
        let hex = data.map { String(format: "%02x", $0) }.joined()
        let i = hex.startIndex
        return "\(hex[i..<hex.index(i, offsetBy: 8)])-\(hex[hex.index(i, offsetBy: 8)..<hex.index(i, offsetBy: 12)])-\(hex[hex.index(i, offsetBy: 12)..<hex.index(i, offsetBy: 16)])-\(hex[hex.index(i, offsetBy: 16)..<hex.index(i, offsetBy: 20)])-\(hex[hex.index(i, offsetBy: 20)..<hex.index(i, offsetBy: 32)])"
    }

    /// Errors surfaced by the current public kit API.
    public enum ObscuraError: Error, LocalizedError {
        case notAuthenticated
        case provisionFailed(String)
        case noMessenger
        case timeout
        case notFriends(String)
        case deviceLinkFailed(String)
        /// A `send` reached none of its named recipients. Distinct from a PARTIAL failure, which is
        /// logged and survivable — this one means the app believes it sent something that got
        /// nowhere. Matches Kotlin's `ObscuraError.SendFailed` and the bridge's `SEND_FAILED`.
        case sendFailed(String)

        /// Stable, machine-readable code for cross-boundary propagation (mirrors
        /// Kotlin `ObscuraError.code`). The bridge rejects promises with this so JS
        /// can branch on *what* failed rather than parse the message.
        public var code: String {
            switch self {
            case .notAuthenticated: return "NOT_AUTHENTICATED"
            case .provisionFailed: return "NOT_PROVISIONED"
            case .noMessenger: return "NO_MESSENGER"
            case .timeout: return "TIMEOUT"
            case .notFriends: return "NOT_FRIENDS"
            case .deviceLinkFailed: return "DEVICE_LINK_FAILED"
            case .sendFailed: return "SEND_FAILED"
            }
        }

        public var errorDescription: String? {
            switch self {
            case .notAuthenticated: return "Not authenticated"
            case .provisionFailed(let msg): return "Device provisioning failed: \(msg)"
            case .noMessenger: return "Messenger not initialized (call register first)"
            case .timeout: return "Operation timed out"
            case .notFriends(let userId): return "Not friends with \(userId)"
            case .deviceLinkFailed(let reason): return "Device link failed: \(reason)"
            case .sendFailed(let msg): return "Send failed: \(msg)"
            }
        }
    }
}
