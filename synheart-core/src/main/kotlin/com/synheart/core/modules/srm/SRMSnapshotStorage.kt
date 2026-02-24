package com.synheart.core.modules.srm

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/// Encrypted storage for SRM snapshots using Android Keystore.
///
/// Mirrors the ConsentStorage pattern — EncryptedSharedPreferences with AES256_GCM.
class SRMSnapshotStorage(context: Context) {
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

    /// Save SRM snapshot (encrypted via Android Keystore)
    fun save(snapshot: SRMSnapshot) {
        val json = snapshot.toJson()
        sharedPreferences.edit()
            .putString(STORAGE_KEY, json.toString())
            .apply()
    }

    /// Load SRM snapshot from encrypted storage
    fun load(): SRMSnapshot? {
        return try {
            val jsonString = sharedPreferences.getString(STORAGE_KEY, null) ?: return null
            val json = JSONObject(jsonString)
            SRMSnapshot.fromJson(json)
        } catch (e: Exception) {
            android.util.Log.e("SRMSnapshotStorage", "Error loading SRM snapshot", e)
            null
        }
    }

    /// Clear SRM snapshot data
    fun clear() {
        sharedPreferences.edit()
            .remove(STORAGE_KEY)
            .apply()
    }

    companion object {
        private const val PREFS_FILENAME = "synheart_srm_snapshot_prefs"
        private const val STORAGE_KEY = "synheart_srm_snapshot"
    }
}
