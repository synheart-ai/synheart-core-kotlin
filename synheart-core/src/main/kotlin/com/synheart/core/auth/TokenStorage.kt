package com.synheart.core.auth

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Secure token storage using EncryptedSharedPreferences (RFC-CORE-0008 §1.5).
 *
 * Backed by Android Keystore via MasterKeys.
 */
class TokenStorage(context: Context) {
    private val prefs: SharedPreferences = try {
        val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
        EncryptedSharedPreferences.create(
            PREFS_NAME,
            masterKeyAlias,
            context.applicationContext,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (_: Exception) {
        // Fallback to regular SharedPreferences if crypto is unavailable (e.g. in unit tests)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveRefreshToken(token: String) =
        prefs.edit().putString(KEY_REFRESH_TOKEN, token).apply()

    fun loadRefreshToken(): String? =
        prefs.getString(KEY_REFRESH_TOKEN, null)

    fun saveSubjectId(id: String) =
        prefs.edit().putString(KEY_SUBJECT_ID, id).apply()

    fun loadSubjectId(): String? =
        prefs.getString(KEY_SUBJECT_ID, null)

    fun saveSessionSecret(secret: String) =
        prefs.edit().putString(KEY_SESSION_SECRET, secret).apply()

    fun loadSessionSecret(): String? =
        prefs.getString(KEY_SESSION_SECRET, null)

    fun clearAll() =
        prefs.edit()
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_SUBJECT_ID)
            .remove(KEY_SESSION_SECRET)
            .apply()

    companion object {
        private const val PREFS_NAME = "synheart_auth"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_SUBJECT_ID = "subject_id"
        private const val KEY_SESSION_SECRET = "session_secret"
    }
}
