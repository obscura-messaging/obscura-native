import Foundation
import GRDB
import LibSignalClient
@testable import ObscuraKit

/// An in-process stand-in for the bundle and message endpoints, with the real server's semantics:
/// `GET /v1/users/{id}` returns one bundle per device and consumes one one-time prekey from each
/// (404 when the user has none), and `POST /v1/messages` rejects a submission for a nonexistent
/// device with `ERROR_CODE_INVALID_DEVICE`. Accepted submissions land in the target's mailbox.
///
/// Served through ``FakeServerProtocol`` for requests to ``host``; one server is active at a time.
final class FakeObscuraServer: @unchecked Sendable {
    static let host = "sender-unit.invalid"
    static var baseURL: String { "https://\(host)" }
    nonisolated(unsafe) static var active: FakeObscuraServer?

    private let lock = NSLock()
    private var devices: [String: FakeDevice] = [:]
    private var fetches: [String: Int] = [:]
    private var metadataFetches: [String: Int] = [:]
    private var metadataStatus = 200

    func failOwnDirectory(status: Int) { lock.withLock { metadataStatus = status } }

    init() {
        Self.active = self
        URLProtocol.registerClass(FakeServerProtocol.self)
    }

    func close() {
        FakeServerProtocol.onOwnMetadata.withLock { $0 = nil }
        URLProtocol.unregisterClass(FakeServerProtocol.self)
        Self.active = nil
    }

    /// Bundle fetches made for `userId`, each of which consumed a prekey on every device.
    func bundleFetches(_ userId: String) -> Int { lock.withLock { fetches[userId] ?? 0 } }
    func ownDirectoryFetches(_ userId: String) -> Int { lock.withLock { metadataFetches[userId] ?? 0 } }

    func register(_ userId: String, corruptBundle: Bool = false) async throws -> FakeDevice {
        let device = try await FakeDevice(userId: userId, corruptBundle: corruptBundle)
        lock.withLock { devices[device.deviceId] = device }
        return device
    }

    func remove(_ device: FakeDevice) { lock.withLock { _ = devices.removeValue(forKey: device.deviceId) } }

    fileprivate func handle(_ request: URLRequest, body: Data) -> (Int, Data) {
        let path = request.url!.path
        if request.httpMethod == "GET", path == "/v1/devices" {
            let token = request.value(forHTTPHeaderField: "Authorization")?.replacingOccurrences(of: "Bearer ", with: "") ?? ""
            return lock.withLock {
                let userId = APIClient.userId(token) ?? devices[token]?.userId ?? ""
                metadataFetches[userId, default: 0] += 1
                guard metadataStatus == 200 else { return (metadataStatus, Data("metadata failed".utf8)) }
                let owned = devices.values.filter { $0.userId == userId }
                return (200, try! JSONSerialization.data(withJSONObject: [
                    "devices": owned.map { ["deviceId": $0.deviceId, "name": "test", "createdAt": "2026-01-01"] },
                ]))
            }
        }
        if request.httpMethod == "GET", path.hasPrefix("/v1/users/") {
            return bundles(String(path.dropFirst("/v1/users/".count)))
        }
        if request.httpMethod == "POST", path == "/v1/messages" {
            let token = request.value(forHTTPHeaderField: "Authorization")!.replacingOccurrences(of: "Bearer ", with: "")
            return accept(from: APIClient.deviceId(token) ?? token, body)
        }
        if request.httpMethod == "DELETE", path == "/v1/sessions" { return (200, Data()) }
        return (404, Data())
    }

    private func bundles(_ userId: String) -> (Int, Data) {
        lock.withLock {
            fetches[userId, default: 0] += 1
            let owned = devices.values.filter { $0.userId == userId }.sorted { $0.deviceId < $1.deviceId }
            if owned.isEmpty { return (404, Data()) }
            return (200, try! JSONSerialization.data(withJSONObject: owned.map { $0.consumeBundle() }))
        }
    }

    private func accept(from senderDeviceId: String, _ body: Data) -> (Int, Data) {
        lock.withLock {
            let request = try! Obscura_V1_SendMessageRequest(serializedBytes: body)
            var response = Obscura_V1_SendMessageResponse()
            for submission in request.messages {
                if let target = devices[uuidString(submission.deviceID)!] {
                    target.mailbox.append((senderDeviceId, submission.message))
                } else {
                    var failure = Obscura_V1_SendMessageResponse.FailedSubmission()
                    failure.submissionID = submission.submissionID
                    failure.errorCode = .invalidDevice
                    response.failedSubmissions.append(failure)
                }
            }
            return (200, try! response.serializedData())
        }
    }
}

final class FakeServerProtocol: URLProtocol {
    static let onOwnMetadata = Locked<(@Sendable () -> Void)?>(nil)
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == FakeObscuraServer.host }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        if request.url?.path == "/v1/devices", request.httpMethod == "GET",
           let held = Self.onOwnMetadata.withLock({ $0 }) {
            held()
            return
        }
        let (status, data) = FakeObscuraServer.active!.handle(request, body: Self.body(of: request))
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
    let type: Obscura_Client_V1_EncryptedMessage.TypeEnum
    let baseKey: PublicKey?
}

/// A real Signal device: its own store, keys, directory and `MessageSender`.
final class FakeDevice: @unchecked Sendable {
    let userId: String
    let deviceId = UUID().uuidString.lowercased()
    let db: DatabaseQueue
    let store: PersistentSignalStore
    let directory: DeviceStore
    let messenger: Messenger
    let sender: MessageSender
    let logger = RecordingLogger(SilentLogger())
    fileprivate var mailbox: [(senderDeviceId: String, message: Data)] = []
    private var unusedPreKeys: [PreKeyRecord] = []
    private let corruptBundle: Bool

    init(userId: String, corruptBundle: Bool) async throws {
        self.userId = userId
        self.corruptBundle = corruptBundle
        db = try ObscuraDatabase.inMemory()
        store = PersistentSignalStore(db: db, logger: logger)
        directory = DeviceStore(db: db)
        let api = try APIClient(baseURL: FakeObscuraServer.baseURL)
        messenger = Messenger(api: api, store: store)
        sender = MessageSender(messenger: messenger, devices: directory, api: api, logger: logger, selfDeviceId: deviceId)
        try rekey()
        api.setToken(deviceId)
    }

    var remainingPreKeys: Int { unusedPreKeys.count }

    /// Replace this device's identity and prekeys, as a reinstall does. Drops every session.
    func rekey() throws {
        let keys = try SignalKeys.generate()
        try store.install(keys)
        unusedPreKeys = keys.preKeys
    }

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

    /// Decrypt and clear the mailbox.
    func receive() async throws -> [Received] {
        let delivered = FakeObscuraServer.active!.drain(self)
        var received: [Received] = []
        for (senderDeviceId, data) in delivered {
            let encrypted = try Obscura_Client_V1_EncryptedMessage(serializedBytes: data)
            let isPreKey = encrypted.type == .prekeyMessage
            let plaintext = try await messenger.decrypt(senderDeviceUuid: senderDeviceId, encrypted)
            received.append(Received(
                message: try Obscura_Client_V1_ClientMessage(serializedBytes: Data(plaintext)),
                type: encrypted.type,
                baseKey: isPreKey ? try PreKeySignalMessage(bytes: Array(encrypted.content)).baseKey : nil
            ))
        }
        return received
    }
}

extension FakeObscuraServer {
    func drain(_ device: FakeDevice) -> [(senderDeviceId: String, message: Data)] {
        lock.withLock {
            defer { device.mailbox.removeAll() }
            return device.mailbox
        }
    }
}
