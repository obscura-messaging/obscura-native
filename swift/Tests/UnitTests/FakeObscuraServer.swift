import Foundation
import GRDB
import LibSignalClient
@testable import ObscuraKit

/// An in-process stand-in for the device, bundle and message endpoints with the real server's
/// semantics. All devices of a user share the account identity; the first one registered is the
/// primary. `GET /v1/users/{id}` returns one bundle per device and consumes a one-time prekey from each
/// (404 when the user has none), `GET /v1/users/{id}/devices/{device}` consumes one from that device
/// only, and `POST /v1/messages` enforces complete delivery for every named or targeted user: a user's
/// submissions are all rejected with `ERROR_CODE_DEVICE_MISMATCH` unless they cover the user's devices
/// other than the sender, and
/// a submission for an unknown device or the sender fails with `ERROR_CODE_INVALID_DEVICE`. Refresh
/// tokens are rejected unless ``acceptRefresh(token:refreshToken:)`` says otherwise.
///
/// Served through ``FakeServerProtocol`` for requests to ``host``; one server is active at a time.
final class FakeObscuraServer: @unchecked Sendable {
    static let host = "sender-unit.invalid"
    static var baseURL: String { "https://\(host)" }
    nonisolated(unsafe) static var active: FakeObscuraServer?

    private let lock = NSLock()
    private var devices: [String: FakeDevice] = [:]
    private var order: [String] = []
    private var userFetches: [String: Int] = [:]
    private var deviceFetches: [String: Int] = [:]
    private var listFetches: [String: Int] = [:]
    private var listStatus = 200
    private(set) var idempotencyKeys: [String] = []

    private var accounts: [String: String] = [:]
    private var linkSessions: [String: (userId: String, body: Data?)] = [:]
    private var attachments: [String: Data] = [:]
    private var createDeviceStatus = 201
    private var tokenRejections = 0
    private var refreshed: [String: String]?

    func failDeviceList(status: Int) { lock.withLock { listStatus = status } }
    func failDeviceCreation(status: Int) { lock.withLock { createDeviceStatus = status } }
    /// Answers the next `count` token-authenticated requests with 401.
    func rejectTokens(_ count: Int) { lock.withLock { tokenRejections = count } }
    /// Lets refreshes succeed with these tokens instead of being rejected.
    func acceptRefresh(token: String, refreshToken: String) {
        lock.withLock { refreshed = ["token": token, "refreshToken": refreshToken] }
    }

    /// Lets `username` log in (any password) as `userId`.
    func account(_ username: String, userId: String) { lock.withLock { accounts[username] = userId } }
    func linkSession(_ id: String) -> (userId: String, body: Data?)? { lock.withLock { linkSessions[id] } }
    func approve(_ id: String, body: Data) { lock.withLock { linkSessions[id]?.body = body } }
    func attachment(_ id: String) -> Data? { lock.withLock { attachments[id] } }

    init() {
        Self.active = self
        URLProtocol.registerClass(FakeServerProtocol.self)
    }

    func close() {
        FakeServerProtocol.onDeviceList.withLock { $0 = nil }
        FakeServerProtocol.messageResponse.withLock { $0 = nil }
        URLProtocol.unregisterClass(FakeServerProtocol.self)
        Self.active = nil
    }

    /// Whole-user bundle fetches for `userId`, each of which consumed a prekey on every device.
    func bundleFetches(_ userId: String) -> Int { lock.withLock { userFetches[userId] ?? 0 } }
    /// Single-device bundle fetches for `deviceId`.
    func deviceBundleFetches(_ deviceId: String) -> Int { lock.withLock { deviceFetches[deviceId] ?? 0 } }
    func deviceListFetches(_ userId: String) -> Int { lock.withLock { listFetches[userId] ?? 0 } }
    func messageRequests() -> Int { lock.withLock { idempotencyKeys.count } }

    /// A new device of `userId`: the primary with a new identity for a new user, otherwise a linked
    /// device holding the account identity.
    /// `rogueIdentity` gives a device of an existing user an identity other than the account's.
    func register(_ userId: String, corruptBundle: Bool = false, rogueIdentity: Bool = false) async throws -> FakeDevice {
        let primary = lock.withLock { devices.values.first { $0.userId == userId && $0.isPrimary } }
        let device = try await FakeDevice(userId: userId, primary: primary, corruptBundle: corruptBundle,
                                          rogueIdentity: rogueIdentity)
        lock.withLock {
            devices[device.deviceId] = device
            order.append(device.deviceId)
        }
        return device
    }

    /// An account reset: every device of the user is deleted, and a new primary with a new identity
    /// takes their place.
    func reset(_ userId: String) async throws -> FakeDevice {
        lock.withLock { devices = devices.filter { $0.value.userId != userId } }
        return try await register(userId)
    }

    func remove(_ device: FakeDevice) { lock.withLock { _ = devices.removeValue(forKey: device.deviceId) } }

    fileprivate func handle(_ request: URLRequest, body: Data) -> (Int, Data) {
        let path = request.url!.path
        let token = request.value(forHTTPHeaderField: "Authorization")?.replacingOccurrences(of: "Bearer ", with: "") ?? ""
        let callerDevice = APIClient.deviceId(token) ?? token
        if !token.isEmpty, !path.hasPrefix("/v1/sessions"), lock.withLock({
            defer { tokenRejections = max(0, tokenRejections - 1) }
            return tokenRejections > 0
        }) { return (401, Data()) }
        if request.httpMethod == "GET", path == "/v1/devices" { return deviceList(token) }
        if request.httpMethod == "POST", path == "/v1/sessions" { return login(body) }
        if request.httpMethod == "POST", path == "/v1/devices" { return lock.withLock { (createDeviceStatus, Data()) } }
        if path.hasPrefix("/v1/link-sessions") { return link(request, token: token, body: body) }
        if request.httpMethod == "POST", path == "/v1/attachments" {
            let id = UUID().uuidString.lowercased()
            lock.withLock { attachments[id] = body }
            return (201, Data(#"{"id":"\#(id)"}"#.utf8))
        }
        if request.httpMethod == "GET", path.hasPrefix("/v1/attachments/") {
            return lock.withLock { attachments[String(path.dropFirst("/v1/attachments/".count))].map { (200, $0) } ?? (404, Data()) }
        }
        if request.httpMethod == "GET", path.hasPrefix("/v1/users/") {
            let parts = path.dropFirst("/v1/users/".count).split(separator: "/").map(String.init)
            return parts.count == 3 ? bundle(parts[0], deviceId: parts[2]) : bundles(parts[0])
        }
        if request.httpMethod == "POST", path == "/v1/messages" {
            lock.withLock { idempotencyKeys.append(request.value(forHTTPHeaderField: "Idempotency-Key") ?? "") }
            return accept(from: callerDevice, body)
        }
        if request.httpMethod == "DELETE", path == "/v1/sessions" { return (200, Data()) }
        if path == "/v1/sessions/refresh" {
            return lock.withLock { refreshed.map { (200, try! JSONSerialization.data(withJSONObject: $0)) } ?? (401, Data()) }
        }
        if request.httpMethod == "DELETE", path.hasPrefix("/v1/devices/") {
            return lock.withLock {
                devices.removeValue(forKey: String(path.dropFirst("/v1/devices/".count))) == nil ? (404, Data()) : (200, Data())
            }
        }
        if request.httpMethod == "POST", path == "/v1/account/reset" {
            return lock.withLock {
                let request = try! JSONSerialization.jsonObject(with: body) as! [String: String]
                guard let userId = accounts[request["username"]!] else { return (401, Data()) }
                devices = devices.filter { $0.value.userId != userId }
                return (200, Data())
            }
        }
        return (404, Data())
    }

    private func owned(_ userId: String) -> [FakeDevice] {
        order.compactMap { devices[$0] }.filter { $0.userId == userId }
    }

    /// A user-scoped token names only its user.
    private func caller(_ token: String) -> String? {
        if let device = devices[APIClient.deviceId(token) ?? token] { return device.userId }
        return APIClient.deviceId(token) == nil ? APIClient.userId(token) : nil
    }

    private func login(_ body: Data) -> (Int, Data) {
        lock.withLock {
            let request = try! JSONSerialization.jsonObject(with: body) as! [String: String]
            guard let userId = accounts[request["username"]!] else { return (401, Data()) }
            let deviceId = request["deviceId"].flatMap { devices[$0]?.userId == userId ? $0 : nil }
            var response = ["token": jwt(userId: userId, deviceId: deviceId)]
            response["refreshToken"] = deviceId.map { _ in "rt" }
            return (200, try! JSONSerialization.data(withJSONObject: response))
        }
    }

    private func link(_ request: URLRequest, token: String, body: Data) -> (Int, Data) {
        lock.withLock {
            let id = String(request.url!.path.dropFirst("/v1/link-sessions".count).drop { $0 == "/" })
            switch request.httpMethod {
            case "POST":
                let id = UUID().uuidString
                linkSessions[id] = (APIClient.userId(token)!, nil)
                return (201, try! JSONSerialization.data(withJSONObject: [
                    "id": id, "expiresAt": Int(Date().timeIntervalSince1970) + 600,
                ]))
            case "PUT":
                guard let session = linkSessions[id] else { return (404, Data()) }
                guard let device = devices[APIClient.deviceId(token) ?? token], device.userId == session.userId,
                      device.isPrimary else { return (403, Data()) }
                linkSessions[id]?.body = body
                return (200, Data())
            default:
                guard let session = linkSessions[id] else { return (404, Data()) }
                return session.body.map { (200, $0) } ?? (204, Data())
            }
        }
    }

    private func deviceList(_ token: String) -> (Int, Data) {
        lock.withLock {
            guard let userId = caller(token) else { return (401, Data()) }
            listFetches[userId, default: 0] += 1
            guard listStatus == 200 else { return (listStatus, Data("device list failed".utf8)) }
            return (200, try! JSONSerialization.data(withJSONObject: [
                "devices": owned(userId).map { ["deviceId": $0.deviceId, "name": "test", "isPrimary": $0.isPrimary] },
            ]))
        }
    }

    private func bundles(_ userId: String) -> (Int, Data) {
        lock.withLock {
            userFetches[userId, default: 0] += 1
            let owned = owned(userId)
            if owned.isEmpty { return (404, Data()) }
            return (200, try! JSONSerialization.data(withJSONObject: owned.map { $0.consumeBundle() }))
        }
    }

    private func bundle(_ userId: String, deviceId: String) -> (Int, Data) {
        lock.withLock {
            deviceFetches[deviceId, default: 0] += 1
            guard let device = devices[deviceId], device.userId == userId else { return (404, Data()) }
            return (200, try! JSONSerialization.data(withJSONObject: device.consumeBundle()))
        }
    }

    private func accept(from senderDeviceId: String, _ body: Data) -> (Int, Data) {
        lock.withLock {
            let request = try! Obscura_V1_SendMessageRequest(serializedBytes: body)
            let senderUserId = devices[senderDeviceId]!.userId
            var response = Obscura_V1_SendMessageResponse()
            func fail(_ submission: Obscura_V1_SendMessageRequest.Submission, _ code: Obscura_V1_SendMessageResponse.ErrorCode) {
                var failure = Obscura_V1_SendMessageResponse.FailedSubmission()
                failure.submissionID = submission.submissionID
                failure.errorCode = code
                response.failedSubmissions.append(failure)
            }
            var valid: [(FakeDevice, Obscura_V1_SendMessageRequest.Submission)] = []
            for submission in request.messages {
                guard let id = uuidString(submission.deviceID), id != senderDeviceId, let target = devices[id] else {
                    fail(submission, .invalidDevice)
                    continue
                }
                valid.append((target, submission))
            }
            var mismatched: Set<String> = []
            for userId in Set(valid.map(\.0.userId) + request.recipientUserIds.compactMap(uuidString)) {
                let current = Set(owned(userId).map(\.deviceId)).subtracting([senderDeviceId])
                let targeted = Set(valid.filter { $0.0.userId == userId }.map(\.0.deviceId))
                guard targeted != current else { continue }
                mismatched.insert(userId)
                var mismatch = Obscura_V1_SendMessageResponse.DeviceMismatch()
                mismatch.userID = uuidBytes(userId)!
                mismatch.missingDeviceIds = current.subtracting(targeted).sorted().map { uuidBytes($0)! }
                response.deviceMismatches.append(mismatch)
            }
            for (target, submission) in valid {
                if mismatched.contains(target.userId) {
                    fail(submission, .deviceMismatch)
                } else {
                    target.mailbox.append((senderUserId, senderDeviceId, submission.message))
                }
            }
            return (200, try! response.serializedData())
        }
    }
}

final class FakeServerProtocol: URLProtocol {
    static let onDeviceList = Locked<(@Sendable () -> Void)?>(nil)
    /// Answers `POST /v1/messages` instead of the server when it returns a response; gets the request body.
    static let messageResponse = Locked<(@Sendable (Data) -> (Int, Data)?)?>(nil)
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == FakeObscuraServer.host }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        if request.url?.path == "/v1/devices", request.httpMethod == "GET",
           let held = Self.onDeviceList.withLock({ $0 }) {
            held()
            return
        }
        let body = Self.body(of: request)
        let override = request.url?.path == "/v1/messages" ? Self.messageResponse.withLock { $0 }?(body) : nil
        let (status, data) = override ?? FakeObscuraServer.active!.handle(request, body: body)
        client?.urlProtocol(self, didReceive: HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: nil)!,
                            cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: data)
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}

    private static func body(of request: URLRequest) -> Data {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 4096)
        while stream.hasBytesAvailable {
            let read = stream.read(&buffer, maxLength: buffer.count)
            if read <= 0 { break }
            data.append(buffer, count: read)
        }
        return data
    }
}

/// A decrypted delivery. `baseKey` identifies the session a prekey message starts; nil otherwise.
struct Received {
    let message: Obscura_Client_V1_ClientMessage
    let senderDeviceId: String
    let type: Obscura_Client_V1_EncryptedMessage.TypeEnum
    let baseKey: PublicKey?
}

/// A real Signal device: its own store, keys, directory and `MessageSender`, provisioned as its
/// user's primary or as a linked device.
final class FakeDevice: @unchecked Sendable {
    let userId: String
    let deviceId = UUID().uuidString.lowercased()
    let primaryDeviceId: String
    let db: DatabaseQueue
    let store: PersistentSignalStore
    let directory: DeviceStore
    let messenger: Messenger
    let sender: MessageSender
    let logger = RecordingLogger(SilentLogger())
    fileprivate var mailbox: [(senderUserId: String, senderDeviceId: String, message: Data)] = []
    private var unusedPreKeys: [PreKeyRecord] = []
    private let corruptBundle: Bool

    var isPrimary: Bool { deviceId == primaryDeviceId }

    init(userId: String, primary: FakeDevice?, corruptBundle: Bool, rogueIdentity: Bool) async throws {
        self.userId = userId
        self.corruptBundle = corruptBundle
        primaryDeviceId = primary?.deviceId ?? deviceId
        db = try ObscuraDatabase.inMemory()
        store = PersistentSignalStore(db: db, logger: logger)
        directory = DeviceStore(db: db)
        let api = try APIClient(baseURL: FakeObscuraServer.baseURL)
        messenger = Messenger(api: api, store: store)
        sender = MessageSender(messenger: messenger, devices: directory, api: api, logger: logger, selfDeviceId: deviceId)
        let identity = try primary.flatMap { rogueIdentity ? nil : try $0.store.identityKeyPair(context: NullContext()) }
            ?? IdentityKeyPair.generate()
        let keys = try SignalKeys.generate(identity: identity)
        unusedPreKeys = keys.preKeys
        try await db.write { [deviceId, primaryDeviceId] db in
            try PersistentSignalStore.install(keys, in: db)
            try DeviceStore.setLocalDevice(deviceId, primaryDeviceId: primaryDeviceId, in: db)
            try DeviceStore.add(userId: userId, deviceId: deviceId, in: db)
        }
        api.setToken(deviceId)
    }

    var remainingPreKeys: Int { unusedPreKeys.count }

    func forgetSessions() throws {
        try db.write { db in try db.execute(sql: "DELETE FROM signal_sessions") }
    }

    fileprivate func consumeBundle() -> [String: Any] {
        let signed = try! store.loadSignedPreKey(id: 1, context: NullContext())
        let oneTime: Any = unusedPreKeys.isEmpty ? NSNull() : {
            let key = unusedPreKeys.removeFirst()
            return ["keyId": key.id, "publicKey": Data(key.publicKey.serialize()).base64EncodedString()]
        }()
        return [
            "deviceId": deviceId,
            "registrationId": try! store.localRegistrationId(context: NullContext()),
            "identityKey": Data(try! store.identityKeyPair(context: NullContext()).publicKey.serialize()).base64EncodedString(),
            "signedPreKey": [
                "keyId": 1,
                "publicKey": Data(signed.publicKey.serialize()).base64EncodedString(),
                "signature": Data(corruptBundle ? [UInt8](repeating: 0, count: 64) : signed.signature).base64EncodedString(),
            ],
            "oneTimePreKey": oneTime,
        ]
    }

    /// Decrypt and clear the mailbox, recording each sender in the directory as the client does.
    func receive() async throws -> [Received] {
        let delivered = FakeObscuraServer.active!.drain(self)
        var received: [Received] = []
        for (senderUserId, senderDeviceId, data) in delivered {
            let encrypted = try Obscura_Client_V1_EncryptedMessage(serializedBytes: data)
            let plaintext = try await messenger.decryptAndPersist(senderUserId: senderUserId, senderDeviceUuid: senderDeviceId,
                                                                  encrypted) { plaintext, _ in plaintext }
            received.append(Received(
                message: try Obscura_Client_V1_ClientMessage(serializedBytes: plaintext),
                senderDeviceId: senderDeviceId,
                type: encrypted.type,
                baseKey: encrypted.type == .prekeyMessage ? try PreKeySignalMessage(bytes: Array(encrypted.content)).baseKey : nil
            ))
        }
        return received
    }

    /// The pending deliveries as gateway envelopes, for an `ObscuraClient` on this device's database.
    func envelopes() -> [Envelope] {
        FakeObscuraServer.active!.drain(self).map {
            Envelope(id: uuidBytes(UUID().uuidString)!, senderId: uuidBytes($0.senderUserId)!,
                     senderDeviceId: uuidBytes($0.senderDeviceId)!, message: $0.message)
        }
    }
}

extension FakeObscuraServer {
    func drain(_ device: FakeDevice) -> [(senderUserId: String, senderDeviceId: String, message: Data)] {
        lock.withLock {
            defer { device.mailbox.removeAll() }
            return device.mailbox
        }
    }
}
