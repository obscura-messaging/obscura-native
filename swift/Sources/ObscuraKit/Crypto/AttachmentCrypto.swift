import Foundation
import CryptoKit

public struct UploadedAttachment: Sendable, Equatable {
    public let id: String
    public let contentKey: Data
    public let nonce: Data
}

/// AES-256-GCM. The ciphertext is `ciphertext || tag`, the nonce travels separately.
enum AttachmentCrypto {
    private static let tagSize = 16

    static func encrypt(_ plaintext: Data) throws -> (ciphertext: Data, contentKey: Data, nonce: Data) {
        let key = SymmetricKey(size: .bits256)
        let nonce = AES.GCM.Nonce()
        let sealed = try AES.GCM.seal(plaintext, using: key, nonce: nonce)
        return (sealed.ciphertext + sealed.tag, key.withUnsafeBytes { Data($0) }, Data(nonce))
    }

    static func decrypt(_ ciphertext: Data, contentKey: Data, nonce: Data) throws -> Data {
        guard ciphertext.count >= tagSize else {
            throw ObscuraError.protocolViolation("attachment ciphertext shorter than its tag")
        }
        let sealed = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce),
                                           ciphertext: ciphertext.dropLast(tagSize), tag: ciphertext.suffix(tagSize))
        return try AES.GCM.open(sealed, using: SymmetricKey(data: contentKey))
    }
}
