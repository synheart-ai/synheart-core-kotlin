package ai.synheart.core.crypto

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Handles encryption and decryption of artifact payloads.
 *
 * Uses AES-256-GCM (standard Android/JCE cipher).
 * v1 flow: JSON bytes → SHA-256 → AEAD encrypt.
 *
 * Wire format: nonce(12) || ciphertext || tag (appended by GCM).
 *
 * See RFC-CORE-0004 Section 8.
 */
data class EncryptedPayload(
    val ciphertext: ByteArray,
    val sha256: String,
    val encAlg: String
)

object ArtifactCrypto {
    const val ENC_ALG = "aes256gcm"
    private const val GCM_IV_LENGTH = 12
    private const val GCM_TAG_BITS = 128

    /** Encrypt a JSON byte array with the given SMK. */
    fun encrypt(smk: SMK, plaintext: ByteArray): EncryptedPayload {
        val sha256Hex = sha256(plaintext)

        val iv = ByteArray(GCM_IV_LENGTH)
        SecureRandom().nextBytes(iv)

        val key = SecretKeySpec(smk.bytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        val encrypted = cipher.doFinal(plaintext)

        // Combined: iv || ciphertext+tag
        val combined = iv + encrypted

        return EncryptedPayload(
            ciphertext = combined,
            sha256 = sha256Hex,
            encAlg = ENC_ALG
        )
    }

    /** Decrypt a combined payload back to plaintext bytes. */
    fun decrypt(smk: SMK, combined: ByteArray): ByteArray {
        require(combined.size > GCM_IV_LENGTH) { "Payload too short" }

        val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
        val ciphertext = combined.copyOfRange(GCM_IV_LENGTH, combined.size)

        val key = SecretKeySpec(smk.bytes, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        return cipher.doFinal(ciphertext)
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
