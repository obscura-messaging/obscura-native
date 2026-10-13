package dev.barrelmaker.obscura.kit.crypto

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** AES-256-GCM with a random key and nonce per attachment. */
internal object AttachmentCrypto {
    private const val KEY_SIZE = 32
    private const val NONCE_SIZE = 12
    private const val GCM_TAG_BITS = 128
    private val random = SecureRandom()

    class Encrypted(val ciphertext: ByteArray, val contentKey: ByteArray, val nonce: ByteArray)

    fun encrypt(plaintext: ByteArray): Encrypted {
        val contentKey = ByteArray(KEY_SIZE).also(random::nextBytes)
        val nonce = ByteArray(NONCE_SIZE).also(random::nextBytes)
        return Encrypted(cipher(Cipher.ENCRYPT_MODE, contentKey, nonce).doFinal(plaintext), contentKey, nonce)
    }

    /** Throws on a wrong key or tampered ciphertext. */
    fun decrypt(ciphertext: ByteArray, contentKey: ByteArray, nonce: ByteArray): ByteArray =
        cipher(Cipher.DECRYPT_MODE, contentKey, nonce).doFinal(ciphertext)

    private fun cipher(mode: Int, key: ByteArray, nonce: ByteArray) = Cipher.getInstance("AES/GCM/NoPadding")
        .apply { init(mode, SecretKeySpec(key, "AES"), GCMParameterSpec(GCM_TAG_BITS, nonce)) }
}
