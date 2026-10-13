import Foundation
import LibSignalClient
import SwiftProtobuf

/// Messenger — encrypt, decrypt, queue, flush messages.
/// Mirrors src/v2/lib/messenger.js
public actor Messenger {
    private let api: APIClient
    private let identityStore: IdentityKeyStore
    private let preKeyStore: PreKeyStore
    private let signedPreKeyStore: SignedPreKeyStore
    private let sessionStore: SessionStore
    private let kyberPreKeyStore: KyberPreKeyStore

    // Device UUID → owning user. Signal registration IDs stay in bundle/session state.
    private var deviceMap: [String: String] = [:]

    // Message queue for batch sending. `attempts` is what stops a permanently-failing submission
    // from poisoning the queue forever — see `flushMessages`.
    private var queue: [(submissionId: Data, deviceId: Data, message: Data, attempts: Int)] = []

    /// How many times a queued submission may be re-flushed before it is dropped.
    ///
    /// A batch that fails is restored to the HEAD of the queue, so without a cap ONE permanently
    /// failing submission — a zeroed `deviceId` from `uuidToBytes` silently zeroing a malformed
    /// UUID, say — is carried by every subsequent flush from every caller and fails all of them.
    /// `send`, `befriend`, `acceptFriend` and self-sync then break for the rest of the session.
    private static let maxFlushAttempts = 3

    public init(api: APIClient, store: PersistentSignalStore) {
        self.api = api
        self.identityStore = store
        self.preKeyStore = store
        self.signedPreKeyStore = store
        self.sessionStore = store
        self.kyberPreKeyStore = store
    }

    // MARK: - Device Mapping

    public func mapDevice(_ deviceId: String, userId: String) {
        deviceMap[deviceId] = userId
    }

    // MARK: - PreKey Bundle Fetching

    /// Fetch prekey bundles for all devices of a user, auto-populate device map
    public func fetchPreKeyBundles(_ userId: String) async throws -> [PreKeyBundleResponse] {
        let bundles = try await api.fetchPreKeyBundles(userId)

        for bundle in bundles {
            deviceMap[bundle.deviceId] = userId
        }

        return bundles
    }

    // MARK: - Addressing

    /// The deviceId slot of every ProtocolAddress. Constant because the device UUID in the name
    /// slot already uniquely identifies the peer device; libsignal just needs SOME stable int here.
    static let addrDeviceId: UInt32 = 1

    /// THE single ProtocolAddress constructor for a peer device, used by BOTH send
    /// (queueMessage/encrypt/processServerBundle) and receive (decrypt). name = device UUID,
    /// deviceId = constant. A ProtocolAddress is a purely LOCAL store key that is never
    /// transmitted. If send and receive ever built different addresses for the same device the
    /// bidirectional session would split — this function is why they cannot. (Mirrors Kotlin
    /// Messenger.addressFor.)
    static func addressFor(_ deviceUuid: String) throws -> ProtocolAddress {
        try ProtocolAddress(name: deviceUuid, deviceId: addrDeviceId)
    }

    /// Enumerate the device UUIDs known for a user.
    public func getDeviceIdsForUser(_ userId: String) -> [String] {
        deviceMap.filter { $0.value == userId }.map { $0.key }
    }

    // MARK: - Encryption

    /// Encrypt plaintext for a target peer device, addressed by its UUID.
    public func encrypt(deviceUuid: String, _ plaintext: [UInt8]) throws -> (type: CiphertextMessage.MessageType, body: [UInt8]) {
        let address = try Self.addressFor(deviceUuid)

        let ciphertext = try signalEncrypt(
            message: plaintext,
            for: address,
            sessionStore: sessionStore,
            identityStore: identityStore,
            context: NullContext()
        )

        return (type: ciphertext.messageType, body: Array(ciphertext.serialize()))
    }

    /// Establish an outbound session keyed on the peer device UUID at `(deviceUuid, 1)`, the same
    /// address used for inbound decrypt. `registrationId` remains Signal protocol metadata.
    public func processServerBundle(_ bundleData: PreKeyBundleResponse, userId: String) throws {
        let regId = UInt32(bundleData.registrationId)
        let address = try Self.addressFor(bundleData.deviceId)
        // Learn the device -> user mapping so getDeviceIdsForUser can enumerate this user's devices.
        deviceMap[bundleData.deviceId] = userId

        guard let identityKeyData = Data(base64Encoded: bundleData.identityKey) else {
            throw MessengerError.invalidBundle("invalid identityKey base64")
        }

        let spk = bundleData.signedPreKey
        guard let spkPubData = Data(base64Encoded: spk.publicKey),
              let spkSigData = Data(base64Encoded: spk.signature) else {
            throw MessengerError.invalidBundle("invalid signedPreKey base64")
        }

        let identityKey = try IdentityKey(bytes: Array(identityKeyData))
        let signedPreKeyPublic = try PublicKey(Array(spkPubData))

        // One-time pre-key (optional)
        var preKeyPublic: PublicKey? = nil
        var preKeyId: UInt32 = ~0
        if let otpk = bundleData.oneTimePreKey,
           let otpkPubData = Data(base64Encoded: otpk.publicKey) {
            preKeyPublic = try PublicKey(Array(otpkPubData))
            preKeyId = UInt32(otpk.keyId)
        }

        let bundle: PreKeyBundle
        if let preKeyPublic = preKeyPublic {
            bundle = try PreKeyBundle(
                registrationId: regId, deviceId: Self.addrDeviceId,
                prekeyId: preKeyId, prekey: preKeyPublic,
                signedPrekeyId: UInt32(spk.keyId), signedPrekey: signedPreKeyPublic,
                signedPrekeySignature: Array(spkSigData), identity: identityKey
            )
        } else {
            bundle = try PreKeyBundle(
                registrationId: regId, deviceId: Self.addrDeviceId,
                signedPrekeyId: UInt32(spk.keyId), signedPrekey: signedPreKeyPublic,
                signedPrekeySignature: Array(spkSigData), identity: identityKey
            )
        }

        try LibSignalClient.processPreKeyBundle(
            bundle, for: address,
            sessionStore: sessionStore, identityStore: identityStore,
            context: NullContext()
        )
    }

    // MARK: - Decryption

    /// Decrypt an envelope's encrypted message.
    ///
    /// The caller passes the server-stamped sender device UUID. Signal sessions are pairwise and a
    /// SignalMessage carries no sender identity, so the UUID selects the inbound session at the
    /// same `(deviceUuid, 1)` address used by send.
    ///
    /// A valid MAC proves possession of that session's chain key, which only the sender's device
    /// holds, so `senderDeviceUuid` is a cryptographically sound attribution once decrypt succeeds.
    public func decrypt(senderUserId: String, senderDeviceUuid: String, content: Data, messageType: Int) throws -> [UInt8] {
        let address = try Self.addressFor(senderDeviceUuid)

        let plaintext: [UInt8]
        if messageType == 1 {
            // PreKey message
            let preKeyMessage = try PreKeySignalMessage(bytes: Array(content))
            plaintext = try signalDecryptPreKey(
                message: preKeyMessage,
                from: address,
                sessionStore: sessionStore,
                identityStore: identityStore,
                preKeyStore: preKeyStore,
                signedPreKeyStore: signedPreKeyStore,
                kyberPreKeyStore: kyberPreKeyStore,
                context: NullContext()
            )
        } else {
            // Whisper message
            let signalMessage = try SignalMessage(bytes: Array(content))
            plaintext = try signalDecrypt(
                message: signalMessage,
                from: address,
                sessionStore: sessionStore,
                identityStore: identityStore,
                context: NullContext()
            )
        }

        // Learn the sender's device for later fan-out.
        if deviceMap[senderDeviceUuid] == nil {
            deviceMap[senderDeviceUuid] = senderUserId
        }

        return plaintext
    }

    // MARK: - Message Queuing

    /// Encode a ClientMessage, encrypt it, wrap in EncryptedMessage, queue for sending
    /// Queue a message for batch sending. clientMessage is the protobuf payload.
    public func queueMessage(targetDeviceId: String, clientMessageData: Data, targetUserId: String? = nil) throws {
        let submission = try prepareSubmission(
            targetDeviceId: targetDeviceId, clientMessageData: clientMessageData, targetUserId: targetUserId
        )
        queue.append((submissionId: submission.submissionID, deviceId: submission.deviceID,
                      message: submission.message, attempts: 0))
    }

    func prepareSubmission(
        targetDeviceId: String, clientMessageData: Data, targetUserId: String? = nil
    ) throws -> Obscura_V1_SendMessageRequest.Submission {
        guard UUID(uuidString: targetDeviceId) != nil else {
            throw MessengerError.invalidBundle("Invalid device UUID")
        }
        // targetDeviceId IS the peer's device UUID — the address name slot (see addressFor).
        // The session must already exist (processServerBundle built it at the same (deviceUuid, 1)
        // address); encrypt throws if it does not. The owning userId is retained only so
        // getDeviceIdsForUser can enumerate the user's devices for fan-out; it is NOT an address.
        if let uid = targetUserId, deviceMap[targetDeviceId] == nil {
            deviceMap[targetDeviceId] = uid
        }

        // Use pre-serialized ClientMessage bytes
        let plaintext = Array(clientMessageData)

        // Encrypt at the canonical device-UUID address.
        let encrypted = try encrypt(deviceUuid: targetDeviceId, plaintext)

        // Wrap in EncryptedMessage protobuf
        var encMsg = Obscura_Client_V1_EncryptedMessage()
        encMsg.type = encrypted.type == .preKey ? .prekeyMessage : .encryptedMessage
        encMsg.content = Data(encrypted.body)

        let encMsgBytes = try encMsg.serializedData()

        // Generate submission ID (UUID as 16 bytes)
        let submissionId = uuidToBytes(UUID().uuidString)
        let deviceIdBytes = uuidToBytes(targetDeviceId)

        var submission = Obscura_V1_SendMessageRequest.Submission()
        submission.submissionID = submissionId
        submission.deviceID = deviceIdBytes
        submission.message = encMsgBytes
        return submission
    }

    func submit(
        userId: String, submissions: [Obscura_V1_SendMessageRequest.Submission]
    ) async throws -> [SubmissionResult] {
        if submissions.isEmpty { return [] }
        var request = Obscura_V1_SendMessageRequest()
        request.messages = submissions
        do {
            let data = try await api.sendMessage(request.serializedData())
            let response = try Obscura_V1_SendMessageResponse(serializedBytes: data)
            return try submissionResults(userId: userId, submissions: submissions, response: response)
        } catch is CancellationError {
            throw CancellationError()
        } catch {
            return submissions.map {
                SubmissionResult(userId: userId, deviceId: uuidFromBytes($0.deviceID),
                                 status: .unknown, error: "\(error)")
            }
        }
    }

    /// Send all queued messages in a single batch.
    ///
    /// On failure the batch is restored to the head of the queue for at most
    /// ``maxFlushAttempts``. The cap prevents one permanently invalid submission from blocking
    /// every later flush.
    ///
    public func flushMessages() async throws -> (sent: Int, failed: Int) {
        guard !queue.isEmpty else { return (0, 0) }

        let batch = queue
        queue.removeAll()

        var request = Obscura_V1_SendMessageRequest()
        for item in batch {
            var submission = Obscura_V1_SendMessageRequest.Submission()
            submission.submissionID = item.submissionId
            submission.deviceID = item.deviceId
            submission.message = item.message
            request.messages.append(submission)
        }

        let data = try request.serializedData()

        let results: [SubmissionResult]
        do {
            let response = try Obscura_V1_SendMessageResponse(serializedBytes: await api.sendMessage(data))
            results = try submissionResults(userId: "", submissions: request.messages, response: response)
        } catch {
            let retryable = batch
                .map { (submissionId: $0.submissionId, deviceId: $0.deviceId,
                        message: $0.message, attempts: $0.attempts + 1) }
                .filter { $0.attempts < Self.maxFlushAttempts }
            let dropped = batch.count - retryable.count
            if dropped > 0 {
                NSLog("[ObscuraKit] flush dropped %d submission(s) after %d failed attempts: %@",
                      dropped, Self.maxFlushAttempts, "\(error)")
            }
            queue.insert(contentsOf: retryable, at: 0)
            throw error
        }

        let sent = results.filter { $0.status == .accepted }.count
        return (sent: sent, failed: batch.count - sent)
    }

    // MARK: - Helpers

    private func uuidToBytes(_ uuid: String) -> Data {
        let cleaned = uuid.replacingOccurrences(of: "-", with: "")
        guard cleaned.count == 32 else { return Data(repeating: 0, count: 16) }
        var bytes = Data(count: 16)
        for i in 0..<16 {
            let start = cleaned.index(cleaned.startIndex, offsetBy: i * 2)
            let end = cleaned.index(start, offsetBy: 2)
            bytes[i] = UInt8(cleaned[start..<end], radix: 16) ?? 0
        }
        return bytes
    }

    public enum MessengerError: Error {
        case invalidBundle(String)
        case invalidResponse(String)
    }
}
