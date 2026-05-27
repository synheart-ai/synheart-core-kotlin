package ai.synheart.core.modules.consent

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import ai.synheart.core.SynheartLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.time.Duration
import java.time.Instant

/**
 * Secure storage for consent tokens and profiles.
 *
 * Uses AndroidX Security EncryptedSharedPreferences for AES-256 encryption
 * backed by the Android Keystore.
 */
class ConsentTokenStorage(context: Context) {

    /**
     * Encrypted shared prefs. Null when the Android Keystore is unavailable
     * (rooted device, Keystore lockout, AOSP variant missing the provider).
     * In that case persistence is disabled — every save/load is a no-op
     * and the host will have to re-grant consent after each process start.
     * We do NOT fall back to plaintext SharedPreferences because consent
     * tokens are sensitive and must not land on disk in cleartext.
     */
    private val sharedPreferences: SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILENAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        SynheartLogger.log(
            "[ConsentTokenStorage] Android Keystore unavailable ($e). " +
            "Consent persistence is disabled; the host will need to " +
            "re-grant consent on each process start."
        )
        null
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Save consent token.
     */
    fun saveToken(token: ConsentToken) {
        val prefs = sharedPreferences ?: return
        try {
            val jsonString = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                buildTokenJsonObject(token)
            )
            prefs.edit()
                .putString(TOKEN_KEY, jsonString)
                .apply()
        } catch (e: Exception) {
            SynheartLogger.log("[ConsentTokenStorage] Error saving token: $e")
            throw e
        }
    }

    /**
     * Load consent token.
     */
    fun loadToken(): ConsentToken? {
        val prefs = sharedPreferences ?: return null
        return try {
            val jsonString = prefs.getString(TOKEN_KEY, null) ?: return null
            val jsonObj = json.parseToJsonElement(jsonString).jsonObject
            ConsentToken.fromStoredJson(jsonObj)
        } catch (e: Exception) {
            SynheartLogger.log("[ConsentTokenStorage] Error loading token: $e")
            null
        }
    }

    /**
     * Delete consent token.
     */
    fun deleteToken() {
        val prefs = sharedPreferences ?: return
        try {
            prefs.edit()
                .remove(TOKEN_KEY)
                .apply()
        } catch (e: Exception) {
            SynheartLogger.log("[ConsentTokenStorage] Error deleting token: $e")
        }
    }

    /**
     * Check if a valid token exists.
     */
    fun hasToken(): Boolean {
        val token = loadToken()
        return token != null && token.isValid
    }

    /**
     * Cache consent profiles.
     */
    fun cacheProfiles(profiles: List<ConsentProfile>) {
        val prefs = sharedPreferences ?: return
        try {
            val profilesJson = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(ConsentProfile.serializer()),
                profiles
            )
            prefs.edit()
                .putString(PROFILES_CACHE_KEY, profilesJson)
                .putString(PROFILES_CACHE_TIMESTAMP_KEY, Instant.now().toString())
                .apply()
        } catch (e: Exception) {
            SynheartLogger.log("[ConsentTokenStorage] Error caching profiles: $e")
        }
    }

    /**
     * Load cached consent profiles (if not expired).
     */
    fun loadCachedProfiles(): List<ConsentProfile>? {
        val prefs = sharedPreferences ?: return null
        return try {
            val timestampStr = prefs.getString(PROFILES_CACHE_TIMESTAMP_KEY, null)
                ?: return null

            val timestamp = Instant.parse(timestampStr)
            val age = Duration.between(timestamp, Instant.now())
            if (age > PROFILES_CACHE_TTL) {
                clearProfilesCache()
                return null
            }

            val profilesJsonString = prefs.getString(PROFILES_CACHE_KEY, null)
                ?: return null

            json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(ConsentProfile.serializer()),
                profilesJsonString
            )
        } catch (e: Exception) {
            SynheartLogger.log("[ConsentTokenStorage] Error loading cached profiles: $e")
            null
        }
    }

    /**
     * Clear profiles cache.
     */
    fun clearProfilesCache() {
        val prefs = sharedPreferences ?: return
        try {
            prefs.edit()
                .remove(PROFILES_CACHE_KEY)
                .remove(PROFILES_CACHE_TIMESTAMP_KEY)
                .apply()
        } catch (e: Exception) {
            SynheartLogger.log("[ConsentTokenStorage] Error clearing profiles cache: $e")
        }
    }

    /**
     * Clear all consent data.
     */
    fun clearAll() {
        deleteToken()
        clearProfilesCache()
    }

    private fun buildTokenJsonObject(token: ConsentToken): JsonObject {
        return kotlinx.serialization.json.buildJsonObject {
            put("token", kotlinx.serialization.json.JsonPrimitive(token.token))
            put("expires_at", kotlinx.serialization.json.JsonPrimitive(token.expiresAt.toString()))
            put("profile_id", kotlinx.serialization.json.JsonPrimitive(token.profileId))
            put("scopes", kotlinx.serialization.json.buildJsonArray {
                token.scopes.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) }
            })
            put("claims", kotlinx.serialization.json.buildJsonObject {
                token.claims.forEach { (k, v) ->
                    put(k, kotlinx.serialization.json.JsonPrimitive(v?.toString() ?: ""))
                }
            })
        }
    }

    companion object {
        private const val PREFS_FILENAME = "synheart_consent_token_prefs"
        private const val TOKEN_KEY = "synheart_consent_token"
        private const val PROFILES_CACHE_KEY = "synheart_consent_profiles_cache"
        private const val PROFILES_CACHE_TIMESTAMP_KEY = "synheart_consent_profiles_cache_ts"
        private val PROFILES_CACHE_TTL = Duration.ofHours(24)
    }
}
