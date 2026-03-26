package ai.synheart.core.crypto

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Storage Master Key — a 32-byte symmetric key used for artifact encryption-at-rest.
 *
 * On Android, the SMK is itself encrypted by an Android Keystore-backed key,
 * so it is protected by hardware-backed key storage where available.
 */
class SMK private constructor(val bytes: ByteArray) {
    init {
        require(bytes.size == 32) { "SMK must be 32 bytes" }
    }

    companion object {
        private const val KEYSTORE_ALIAS = "synheart_smk_wrapper"
        private const val PREFS_NAME = "synheart_smk"
        private const val KEY_ENCRYPTED = "smk_encrypted"
        private const val KEY_IV = "smk_iv"

        /** Load the SMK from encrypted storage, or generate and persist a new one. */
        fun loadOrCreate(context: Context): SMK {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val encBase64 = prefs.getString(KEY_ENCRYPTED, null)
            val ivBase64 = prefs.getString(KEY_IV, null)

            if (encBase64 != null && ivBase64 != null) {
                val wrapperKey = getOrCreateWrapperKey()
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                val iv = android.util.Base64.decode(ivBase64, android.util.Base64.NO_WRAP)
                cipher.init(Cipher.DECRYPT_MODE, wrapperKey, GCMParameterSpec(128, iv))
                val smkBytes = cipher.doFinal(
                    android.util.Base64.decode(encBase64, android.util.Base64.NO_WRAP)
                )
                return SMK(smkBytes)
            }

            // Generate new SMK
            val smkBytes = ByteArray(32)
            SecureRandom().nextBytes(smkBytes)
            val smk = SMK(smkBytes)

            // Encrypt and persist
            val wrapperKey = getOrCreateWrapperKey()
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, wrapperKey)
            val encrypted = cipher.doFinal(smkBytes)
            prefs.edit()
                .putString(KEY_ENCRYPTED, android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
                .putString(KEY_IV, android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
                .apply()

            return smk
        }

        /** Create an SMK from raw bytes (for testing). */
        fun fromBytes(bytes: ByteArray): SMK = SMK(bytes)

        /** Delete the persisted SMK. */
        fun delete(context: Context) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().clear().apply()
            try {
                val ks = KeyStore.getInstance("AndroidKeyStore")
                ks.load(null)
                ks.deleteEntry(KEYSTORE_ALIAS)
            } catch (_: Exception) {}
        }

        private fun getOrCreateWrapperKey(): SecretKey {
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
}
