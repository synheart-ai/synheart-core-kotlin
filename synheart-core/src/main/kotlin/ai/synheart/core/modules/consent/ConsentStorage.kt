package ai.synheart.core.modules.consent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.synheart.core.modules.interfaces.ConsentSnapshot
import ai.synheart.core.modules.interfaces.ConsentTier
import org.json.JSONObject
import java.time.Instant

/** Encrypted storage for consent snapshots using Android Keystore. */
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

    /** Save consent snapshot (encrypted via Android Keystore). */
    fun save(consent: ConsentSnapshot) {
        val json = JSONObject().apply {
            put("biosignals", consent.biosignals)
            put("behavior", consent.behavior)
            put("phoneContext", consent.phoneContext)
            put("motion", consent.phoneContext) // back-compat alias
            put("cloudUpload", consent.cloudUpload)
            put("focusEstimation", consent.focusEstimation)
            put("emotionEstimation", consent.emotionEstimation)
            put("syni", consent.syni)
            put("vendorSync", consent.vendorSync)
            put("tier", consent.tier.name)
            if (consent.channels != null) {
                put("channels", kotlinx.serialization.json.Json.encodeToString(
                    ConsentChannels.serializer(), consent.channels
                ))
            }
            put("timestamp", consent.timestamp.toString())
            put("version", consent.version)
        }

        sharedPreferences.edit()
            .putString(STORAGE_KEY, json.toString())
            .apply()
    }

    /** Load consent snapshot from encrypted storage. */
    fun load(): ConsentSnapshot? {
        return try {
            val jsonString = sharedPreferences.getString(STORAGE_KEY, null) ?: return null
            val json = JSONObject(jsonString)

            val tierStr = json.optString("tier", "LOCAL")
            val tier = try { ConsentTier.valueOf(tierStr) } catch (_: Exception) { ConsentTier.LOCAL }

            val channels: ConsentChannels? = if (json.has("channels")) {
                try {
                    val channelsJson = json.getString("channels")
                    kotlinx.serialization.json.Json { ignoreUnknownKeys = true }
                        .decodeFromString(ConsentChannels.serializer(), channelsJson)
                } catch (_: Exception) { null }
            } else null

            ConsentSnapshot(
                biosignals = json.getBoolean("biosignals"),
                phoneContext = when {
                    json.has("phoneContext") -> json.getBoolean("phoneContext")
                    json.has("motion") -> json.getBoolean("motion") // Back-compat
                    else -> false
                },
                behavior = json.getBoolean("behavior"),
                cloudUpload = json.getBoolean("cloudUpload"),
                focusEstimation = json.optBoolean("focusEstimation", false),
                emotionEstimation = json.optBoolean("emotionEstimation", false),
                syni = json.getBoolean("syni"),
                vendorSync = json.optBoolean("vendorSync", false),
                tier = tier,
                channels = channels,
                timestamp = Instant.parse(json.getString("timestamp")),
                version = json.optString("version", "1.0.0")
            )
        } catch (e: Exception) {
            // If there's an error reading/parsing, return null
            android.util.Log.e("ConsentStorage", "Error loading consent", e)
            null
        }
    }

    /** Clear consent data. */
    fun clear() {
        sharedPreferences.edit()
            .remove(STORAGE_KEY)
            .apply()
    }

    /** Check if consent data exists. */
    fun exists(): Boolean {
        return sharedPreferences.contains(STORAGE_KEY)
    }

    companion object {
        private const val PREFS_FILENAME = "synheart_consent_prefs"
        private const val STORAGE_KEY = "synheart_consent_snapshot"
    }
}
