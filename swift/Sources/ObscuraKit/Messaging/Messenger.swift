import Foundation
import LibSignalClient
import SwiftProtobuf

/// Signal sessions, encryption and submission for single devices. Which devices to address is
/// `MessageSender`'s job.
actor Messenger {
    private let api: APIClient
    private let store: PersistentSignalStore

    init(api: APIClient, store: PersistentSignalStore) {
        self.api = api
        self.store = store
    }

    /// Send and receive must build the same address for a device, or its session splits in two. The
    /// device UUID identifies the peer; libsignal's device-id slot is a constant.
    static func address(_ deviceUuid: String) throws -> ProtocolAddress {
        try ProtocolAddress(name: deviceUuid, deviceId: 1)
    }

    func hasSession(_ deviceUuid: String) throws -> Bool {
        try store.loadSession(for: Self.address(deviceUuid), context: NullContext())?.hasCurrentState ?? false
    }

    /// Processing a bundle over an established session would restart the ratchet, so an existing
    /// session always wins.
    func establishSession(_ bundle: PreKeyBundleResponse) throws {
        guard try !hasSession(bundle.deviceId) else { return }
        guard let identityKey = Data(base64Encoded: bundle.identityKey),
              let signedPreKey = Data(base64Encoded: bundle.signedPreKey.publicKey),
              let signature = Data(base64Encoded: bundle.signedPreKey.signature) else {
            throw ObscuraError.protocolViolation("invalid base64 in bundle for \(bundle.deviceId)")
        }
        let identity = try IdentityKey(bytes: identityKey)
        let signedPublic = try PublicKey(signedPreKey)
        let registrationId = UInt32(bundle.registrationId)
        let signedPreKeyId = UInt32(bundle.signedPreKey.keyId)

        let preKeyBundle: PreKeyBundle
        if let oneTime = bundle.oneTimePreKey {
            guard let oneTimeKey = Data(base64Encoded: oneTime.publicKey) else {
                throw ObscuraError.protocolViolation("invalid one-time prekey for \(bundle.deviceId)")
            }
            preKeyBundle = try PreKeyBundle(
                registrationId: registrationId, deviceId: 1, prekeyId: UInt32(oneTime.keyId), prekey: PublicKey(oneTimeKey),
                signedPrekeyId: signedPreKeyId, signedPrekey: signedPublic, signedPrekeySignature: signature, identity: identity)
        } else {
            preKeyBundle = try PreKeyBundle(
                registrationId: registrationId, deviceId: 1,
                signedPrekeyId: signedPreKeyId, signedPrekey: signedPublic, signedPrekeySignature: signature, identity: identity)
        }
        try processPreKeyBundle(preKeyBundle, for: Self.address(bundle.deviceId),
                                sessionStore: store, identityStore: store, context: NullContext())
    }

    /// Encrypts for one device with its existing session.
    func prepareSubmission(deviceId: String, clientMessage: Data) throws -> Obscura_V1_SendMessageRequest.Submission {
        guard let deviceBytes = uuidBytes(deviceId) else {
            throw ObscuraError.protocolViolation("device id \(deviceId) is not a UUID")
        }
        let ciphertext = try signalEncrypt(message: clientMessage, for: Self.address(deviceId),
                                           sessionStore: store, identityStore: store, context: NullContext())
        var encrypted = Obscura_Client_V1_EncryptedMessage()
        encrypted.type = ciphertext.messageType == .preKey ? .prekeyMessage : .encryptedMessage
        encrypted.content = Data(ciphertext.serialize())

        var submission = Obscura_V1_SendMessageRequest.Submission()
        submission.submissionID = withUnsafeBytes(of: UUID().uuid) { Data($0) }
        submission.deviceID = deviceBytes
        submission.message = try encrypted.serializedData()
        return submission
    }

    /// Uploads `submissions` as one request. A transport or decoding failure leaves every
    /// submission's acceptance unknown.
    func submit(userId: String, submissions: [Obscura_V1_SendMessageRequest.Submission]) async -> [Submitted] {
        if submissions.isEmpty { return [] }
        var request = Obscura_V1_SendMessageRequest()
        request.messages = submissions
        do {
            let response = try Obscura_V1_SendMessageResponse(serializedBytes: try await api.sendMessage(request.serializedData()))
            return try submissionResults(userId: userId, submissions: submissions, response: response)
        } catch {
            return submissions.map {
                Submitted(SubmissionResult(userId: userId, deviceId: uuidString($0.deviceID), status: .unknown, error: "\(error)"))
            }
        }
    }

    /// A valid MAC proves the sender holds this device's session, so `senderDeviceUuid` is a sound
    /// attribution once decrypt succeeds.
    func decrypt(senderDeviceUuid: String, _ encrypted: Obscura_Client_V1_EncryptedMessage) throws -> Data {
        let address = try Self.address(senderDeviceUuid)
        switch encrypted.type {
        case .prekeyMessage:
            return Data(try signalDecryptPreKey(
                message: PreKeySignalMessage(bytes: encrypted.content), from: address,
                sessionStore: store, identityStore: store, preKeyStore: store, signedPreKeyStore: store,
                kyberPreKeyStore: store, context: NullContext()))
        case .encryptedMessage:
            return Data(try signalDecrypt(message: SignalMessage(bytes: encrypted.content), from: address,
                                          sessionStore: store, identityStore: store, context: NullContext()))
        case .unspecified, .UNRECOGNIZED:
            throw ObscuraError.protocolViolation("encrypted message type \(encrypted.type.rawValue)")
        }
    }
}
