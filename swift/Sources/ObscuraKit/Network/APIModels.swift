import Foundation

struct AuthResponse: Decodable {
    let token: String
    let refreshToken: String?
}

struct SignedPreKeyUpload: Encodable {
    let keyId: Int
    let publicKey: String
    let signature: String
}

struct PreKeyUpload: Encodable {
    let keyId: Int
    let publicKey: String
}

struct DeviceKeysUpload: Encodable {
    let identityKey: String
    let registrationId: Int
    let signedPreKey: SignedPreKeyUpload
    let oneTimePreKeys: [PreKeyUpload]
}

struct PreKeyBundleResponse: Decodable {
    let deviceId: String
    let registrationId: Int
    let identityKey: String
    let signedPreKey: SignedPreKeyData
    let oneTimePreKey: PreKeyData?

    struct SignedPreKeyData: Decodable {
        let keyId: Int
        let publicKey: String
        let signature: String
    }

    struct PreKeyData: Decodable {
        let keyId: Int
        let publicKey: String
    }
}

struct AttachmentResponse: Decodable {
    let id: String
}

struct GatewayTicketResponse: Decodable {
    let ticket: String
}
