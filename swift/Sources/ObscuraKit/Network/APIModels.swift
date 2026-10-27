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

/// `POST /v1/devices/keys`. The identity is the account's and cannot change there.
struct PreKeysUpload: Encodable {
    let signedPreKey: SignedPreKeyUpload
    let oneTimePreKeys: [PreKeyUpload]
}

/// `POST /v1/devices`. `linkSessionId` is required once the account has a device.
struct CreateDeviceRequest: Encodable {
    let name: String
    let identityKey: String
    let registrationId: Int
    let signedPreKey: SignedPreKeyUpload
    let oneTimePreKeys: [PreKeyUpload]
    let linkSessionId: String?
}

struct DeviceResponse: Decodable {
    let deviceId: String
    let name: String?
    let isPrimary: Bool
}

struct LinkSessionResponse: Decodable {
    let id: String
    let expiresAt: Int64
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
