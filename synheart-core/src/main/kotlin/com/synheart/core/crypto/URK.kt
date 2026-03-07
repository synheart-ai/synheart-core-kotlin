package com.synheart.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.util.Base64 as JBase64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * User Root Key management for E2EE sync (RFC-CORE-0005 §3).
 *
 * URK is a 32-byte key used to derive per-artifact encryption keys for sync.
 */
class URK private constructor(val bytes: ByteArray) {
    init {
        require(bytes.size == KEY_LENGTH) { "URK must be $KEY_LENGTH bytes" }
    }

    companion object {
        const val KEY_LENGTH = 32
        private const val KEYSTORE_ALIAS = "synheart_urk_kek"
        private const val PREFS_NAME = "synheart_urk"
        private const val KEY_WRAPPED = "urk_wrapped"
        private const val KEY_IV = "urk_iv"
        private const val BUNDLE_KDF_INFO = "synheart-urk-bundle:v1"
        private const val SYNC_KDF_INFO = "synheart-sync:v1"

        /** Generate a fresh 32-byte URK. */
        fun generate(): URK {
            val bytes = ByteArray(KEY_LENGTH)
            SecureRandom().nextBytes(bytes)
            return URK(bytes)
        }

        /** Construct from raw bytes. */
        fun fromBytes(bytes: ByteArray): URK = URK(bytes.copyOf())

        /** Derive a Bundle Encryption Key from the auth session secret. */
        fun deriveBEK(sessionSecret: String, subjectId: String): ByteArray {
            return hkdfDerive(
                ikm = sessionSecret.toByteArray(Charsets.UTF_8),
                salt = subjectId.toByteArray(Charsets.UTF_8),
                info = BUNDLE_KDF_INFO.toByteArray(Charsets.UTF_8),
                length = KEY_LENGTH
            )
        }

        /** Derive a per-artifact encryption key for sync. */
        fun deriveArtifactKey(urk: ByteArray, artifactId: String): ByteArray {
            return hkdfDerive(
                ikm = urk,
                salt = artifactId.toByteArray(Charsets.UTF_8),
                info = SYNC_KDF_INFO.toByteArray(Charsets.UTF_8),
                length = KEY_LENGTH
            )
        }

        /** Encrypt URK for cloud storage as a bundle. */
        fun encryptBundle(urk: ByteArray, sessionSecret: String, subjectId: String): Map<String, String> {
            val bek = deriveBEK(sessionSecret, subjectId)
            val iv = ByteArray(12)
            SecureRandom().nextBytes(iv)

            val key = SecretKeySpec(bek, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
            val encrypted = cipher.doFinal(urk)

            return mapOf(
                "urk_bundle_version" to "1",
                "ciphertext_b64" to JBase64.getEncoder().encodeToString(encrypted),
                "nonce_b64" to JBase64.getEncoder().encodeToString(iv),
                "kdf_info" to BUNDLE_KDF_INFO
            )
        }

        /** Decrypt a URK bundle downloaded from the server. */
        fun decryptBundle(bundle: Map<String, String>, sessionSecret: String, subjectId: String): URK {
            val bek = deriveBEK(sessionSecret, subjectId)
            val ciphertext = JBase64.getDecoder().decode(bundle["ciphertext_b64"])
            val iv = JBase64.getDecoder().decode(bundle["nonce_b64"])

            val key = SecretKeySpec(bek, "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            val plaintext = cipher.doFinal(ciphertext)

            return URK(plaintext)
        }

        /** Unwrap URK from local Android Keystore-protected storage. */
        fun unwrap(context: Context): URK? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val wrappedB64 = prefs.getString(KEY_WRAPPED, null) ?: return null
            val ivB64 = prefs.getString(KEY_IV, null) ?: return null

            val wrapperKey = getOrCreateKEK()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            val iv = JBase64.getDecoder().decode(ivB64)
            cipher.init(Cipher.DECRYPT_MODE, wrapperKey, GCMParameterSpec(128, iv))
            val urk = cipher.doFinal(JBase64.getDecoder().decode(wrappedB64))
            return URK(urk)
        }

        /** Delete URK and KEK from storage. */
        fun delete(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply()
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                ks.deleteEntry(KEYSTORE_ALIAS)
            } catch (_: Exception) {}
        }

        // MARK: - HKDF (RFC 5869)

        private fun hkdfDerive(ikm: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
            // Extract
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(salt, "HmacSHA256"))
            val prk = mac.doFinal(ikm)

            // Expand
            val okm = ByteArray(length)
            var t = ByteArray(0)
            var offset = 0
            var counter: Byte = 1
            while (offset < length) {
                mac.init(SecretKeySpec(prk, "HmacSHA256"))
                mac.update(t)
                mac.update(info)
                mac.update(byteArrayOf(counter))
                t = mac.doFinal()
                val toCopy = minOf(t.size, length - offset)
                System.arraycopy(t, 0, okm, offset, toCopy)
                offset += toCopy
                counter++
            }
            return okm
        }

        private fun getOrCreateKEK(): SecretKey {
            val ks = KeyStore.getInstance("AndroidKeyStore")
            ks.load(null)
            if (ks.containsAlias(KEYSTORE_ALIAS)) {
                return (ks.getEntry(KEYSTORE_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
            }
            val spec = KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            val kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
            kg.init(spec)
            return kg.generateKey()
        }
    }

    /** Wrap and store URK locally using Android Keystore. */
    fun wrapAndStore(context: Context) {
        val wrapperKey = getOrCreateKEK()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, wrapperKey)
        val encrypted = cipher.doFinal(bytes)

        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString(KEY_WRAPPED, JBase64.getEncoder().encodeToString(encrypted))
            .putString(KEY_IV, JBase64.getEncoder().encodeToString(cipher.iv))
            .apply()
    }
}
