package com.synheart.core.modules.consent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.synheart.core.modules.interfaces.ConsentSnapshot
import org.json.JSONObject
import java.time.Instant

/// Encrypted storage for consent snapshots using Android Keystore
class ConsentStorage(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val sharedPreferences: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_FILENAME,
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /// Save consent snapshot (encrypted via Android Keystore)
    fun save(consent: ConsentSnapshot) {
        val json = JSONObject().apply {
            put("biosignals", consent.biosignals)
            put("behavior", consent.behavior)
            put("motion", consent.motion)
            put("cloudUpload", consent.cloudUpload)
            put("syni", consent.syni)
            put("timestamp", consent.timestamp.toString())
            put("version", consent.version)
        }

        sharedPreferences.edit()
            .putString(STORAGE_KEY, json.toString())
            .apply()
    }

    /// Load consent snapshot from encrypted storage
    fun load(): ConsentSnapshot? {
        return try {
            val jsonString = sharedPreferences.getString(STORAGE_KEY, null) ?: return null
            val json = JSONObject(jsonString)

            ConsentSnapshot(
                biosignals = json.getBoolean("biosignals"),
                behavior = json.getBoolean("behavior"),
                motion = json.getBoolean("motion"),
                cloudUpload = json.getBoolean("cloudUpload"),
                syni = json.getBoolean("syni"),
                timestamp = Instant.parse(json.getString("timestamp")),
                version = json.optString("version", "1.0.0")
            )
        } catch (e: Exception) {
            // If there's an error reading/parsing, return null
            android.util.Log.e("ConsentStorage", "Error loading consent", e)
            null
        }
    }

    /// Clear consent data
    fun clear() {
        sharedPreferences.edit()
            .remove(STORAGE_KEY)
            .apply()
    }

    /// Check if consent data exists
    fun exists(): Boolean {
        return sharedPreferences.contains(STORAGE_KEY)
    }

    companion object {
        private const val PREFS_FILENAME = "synheart_consent_prefs"
        private const val STORAGE_KEY = "synheart_consent_snapshot"
    }
}
