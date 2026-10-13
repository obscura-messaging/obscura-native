import Foundation
import LibSignalClient
import SwiftProtobuf

/// Signal sessions, encryption and transport submission for single devices. Which devices to
/// address, and when to fetch bundles, is ``MessageSender``'s job.
///
/// Every session is keyed on the peer's device UUID at `(deviceUuid, 1)`; see ``addressFor(_:)``.
public actor Messenger {
    private let api: APIClient
    private let identityStore: IdentityKeyStore
    private let preKeyStore: PreKeyStore
    private let signedPreKeyStore: SignedPreKeyStore
    private let sessionStore: SessionStore
    private let kyberPreKeyStore: KyberPreKeyStore

    public init(api: APIClient, store: PersistentSignalStore) {
        self.api = api
        self.identityStore = store
        self.preKeyStore = store
        self.signedPreKeyStore = store
        self.sessionStore = store
        self.kyberPreKeyStore = store
    }

    // MARK: - Addressing

    /// The deviceId slot of every ProtocolAddress. Constant because the device UUID in the name
    /// slot already uniquely identifies the peer device; libsignal just needs SOME stable int here.
    static let addrDeviceId: UInt32 = 1

    /// THE single ProtocolAddress constructor for a peer device, used by BOTH send
    /// (establishSession/prepareSubmission) and receive (decrypt). name = device UUID,
    /// deviceId = constant. A ProtocolAddress is a purely LOCAL store key that is never
    /// transmitted. If send and receive ever built different addresses for the same device the
    /// bidirectional session would split — this function is why they cannot. (Mirrors Kotlin
    /// Messenger.addressFor.)
    static func addressFor(_ deviceUuid: String) throws -> ProtocolAddress {
        try ProtocolAddress(name: deviceUuid, deviceId: addrDeviceId)
    }

    // MARK: - Sessions

    public func hasSession(_ deviceUuid: String) throws -> Bool {
        try sessionStore.loadSession(for: Self.addressFor(deviceUuid), context: NullContext())?.hasCurrentState ?? false
    }

    /// Build an outbound session from `bundle` unless one already exists. Processing a bundle over
    /// an established session would discard it and restart the ratchet, so an existing session
    /// always wins, including one built by the receive path.
    func establishSession(_ bundle: PreKeyBundleResponse) throws {
        guard try !hasSession(bundle.deviceId) else { return }
        guard let identityKeyData = Data(base64Encoded: bundle.identityKey),
              let spkPublicData = Data(base64Encoded: bundle.signedPreKey.publicKey),
              let spkSignatureData = Data(base64Encoded: bundle.signedPreKey.signature) else {
            throw MessengerError.invalidBundle("invalid base64 in bundle for \(bundle.deviceId)")
        }
        let identity = try IdentityKey(bytes: Array(identityKeyData))
        let signedPreKey = try PublicKey(Array(spkPublicData))
        let registrationId = UInt32(bundle.registrationId)
        let signedPreKeyId = UInt32(bundle.signedPreKey.keyId)

        let preKeyBundle: PreKeyBundle
        if let oneTime = bundle.oneTimePreKey {
            guard let oneTimeData = Data(base64Encoded: oneTime.publicKey) else {
                throw MessengerError.invalidBundle("invalid one-time prekey for \(bundle.deviceId)")
            }
            preKeyBundle = try PreKeyBundle(
                registrationId: registrationId, deviceId: Self.addrDeviceId,
                prekeyId: UInt32(oneTime.keyId), prekey: try PublicKey(Array(oneTimeData)),
                signedPrekeyId: signedPreKeyId, signedPrekey: signedPreKey,
                signedPrekeySignature: Array(spkSignatureData), identity: identity
            )
        } else {
            preKeyBundle = try PreKeyBundle(
                registrationId: registrationId, deviceId: Self.addrDeviceId,
                signedPrekeyId: signedPreKeyId, signedPrekey: signedPreKey,
                signedPrekeySignature: Array(spkSignatureData), identity: identity
            )
        }
        try processPreKeyBundle(
            preKeyBundle, for: Self.addressFor(bundle.deviceId),
            sessionStore: sessionStore, identityStore: identityStore, context: NullContext()
        )
    }

    // MARK: - Encryption and submission

    /// Encrypt serialized `ClientMessage` bytes for one device with an existing session. Never
    /// fetches a bundle.
    func prepareSubmission(deviceId: String, clientMessageData: Data) throws -> Obscura_V1_SendMessageRequest.Submission {
        guard let deviceIdBytes = uuidBytes(deviceId) else {
            throw MessengerError.invalidBundle("Invalid device UUID \(deviceId)")
        }
        guard try hasSession(deviceId) else {
            throw MessengerError.noSession(deviceId)
        }
        let ciphertext = try signalEncrypt(
            message: Array(clientMessageData), for: Self.addressFor(deviceId),
            sessionStore: sessionStore, identityStore: identityStore, context: NullContext()
        )
        var encrypted = Obscura_Client_V1_EncryptedMessage()
        encrypted.type = ciphertext.messageType == .preKey ? .prekeyMessage : .encryptedMessage
        encrypted.content = Data(ciphertext.serialize())

        var submission = Obscura_V1_SendMessageRequest.Submission()
        submission.submissionID = uuidBytes(UUID().uuidString)!
        submission.deviceID = deviceIdBytes
        submission.message = try encrypted.serializedData()
        return submission
    }

    /// Upload `submissions` as one request and report each one. A transport or decoding failure
    /// leaves every submission's acceptance unknown.
    func submit(userId: String, submissions: [Obscura_V1_SendMessageRequest.Submission]) async -> [Submitted] {
        if submissions.isEmpty { return [] }
        var request = Obscura_V1_SendMessageRequest()
        request.messages = submissions
        do {
            let data = try await api.sendMessage(request.serializedData())
            let response = try Obscura_V1_SendMessageResponse(serializedBytes: data)
            return try submissionResults(userId: userId, submissions: submissions, response: response)
        } catch {
            return submissions.map {
                Submitted(SubmissionResult(userId: userId, deviceId: uuidFromBytes($0.deviceID),
                                           status: .unknown, error: "\(error)"))
            }
        }
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
    public func decrypt(senderDeviceUuid: String, content: Data, messageType: Int) throws -> [UInt8] {
        let address = try Self.addressFor(senderDeviceUuid)
        if messageType == 1 {
            return try signalDecryptPreKey(
                message: try PreKeySignalMessage(bytes: Array(content)),
                from: address,
                sessionStore: sessionStore,
                identityStore: identityStore,
                preKeyStore: preKeyStore,
                signedPreKeyStore: signedPreKeyStore,
                kyberPreKeyStore: kyberPreKeyStore,
                context: NullContext()
            )
        }
        return try signalDecrypt(
            message: try SignalMessage(bytes: Array(content)),
            from: address,
            sessionStore: sessionStore,
            identityStore: identityStore,
            context: NullContext()
        )
    }

    // MARK: - Helpers

    /// The 16 bytes of a UUID string, or nil when it is not one. Never a zeroed placeholder.
    private func uuidBytes(_ uuid: String) -> Data? {
        guard let parsed = UUID(uuidString: uuid) else { return nil }
        return withUnsafeBytes(of: parsed.uuid) { Data($0) }
    }

    public enum MessengerError: Error {
        case invalidBundle(String)
        case invalidResponse(String)
        case noSession(String)
    }
}
