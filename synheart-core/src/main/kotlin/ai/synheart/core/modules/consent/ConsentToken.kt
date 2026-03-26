package ai.synheart.core.modules.consent

import android.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant

/**
 * JWT consent token issued by consent service.
 */
data class ConsentToken(
    /** JWT token string */
    val token: String,

    /** Token expiration time */
    val expiresAt: Instant,

    /** Consent profile ID that this token was issued for */
    val profileId: String,

    /** Token scopes (e.g., ["bio:vitals", "cloud:upload"]) */
    val scopes: List<String>,

    /** Decoded JWT claims (for validation) */
    val claims: Map<String, Any?>
) {
    /** Check if token is expired */
    val isExpired: Boolean
        get() = Instant.now().isAfter(expiresAt)

    /** Check if token is valid (not expired) */
    val isValid: Boolean
        get() = !isExpired

    /** Check if token expires soon (within specified duration) */
    fun expiresSoon(thresholdSeconds: Long = 300): Boolean {
        val remaining = expiresAt.epochSecond - Instant.now().epochSecond
        return remaining <= thresholdSeconds
    }

    /**
     * Convert to JSON map for storage.
     */
    fun toStorageJson(): Map<String, Any?> {
        return mapOf(
            "token" to token,
            "expires_at" to expiresAt.toString(),
            "profile_id" to profileId,
            "scopes" to scopes,
            "claims" to claims
        )
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Create from JSON response.
         *
         * Supports multiple API response formats:
         * 1. RFC format: { "token": "...", "expires_at": "2026-01-10T19:00:00Z", "profile_id": "...", "scopes": [...] }
         * 2. Actual API format: { "access_token": "...", "expires_in": 86400, "consent_profile_id": "...", "token_type": "Bearer" }
         */
        fun fromJson(jsonObj: JsonObject): ConsentToken {
            // Extract token (required field)
            val tokenStr = jsonObj["token"]?.jsonPrimitive?.content
                ?: jsonObj["access_token"]?.jsonPrimitive?.content
                ?: throw IllegalArgumentException(
                    "Missing required field \"token\" or \"access_token\" in consent token response. " +
                        "Available keys: ${jsonObj.keys.joinToString(", ")}"
                )

            // Extract expiration time
            val expiresAt: Instant = when {
                jsonObj.containsKey("expires_at") -> {
                    val expiresAtStr = jsonObj["expires_at"]!!.jsonPrimitive.content
                    Instant.parse(expiresAtStr)
                }
                jsonObj.containsKey("expires_in") -> {
                    val expiresIn = jsonObj["expires_in"]!!.jsonPrimitive.int
                    Instant.now().plusSeconds(expiresIn.toLong())
                }
                else -> throw IllegalArgumentException(
                    "Missing required field \"expires_at\" or \"expires_in\" in consent token response. " +
                        "Available keys: ${jsonObj.keys.joinToString(", ")}"
                )
            }

            // Decode JWT to extract claims (basic decoding, no signature verification)
            val parts = tokenStr.split(".")
            if (parts.size != 3) {
                throw IllegalArgumentException("Invalid JWT format")
            }

            val payload = parts[1]
            val claims: Map<String, Any?> = try {
                val decodedBytes = Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
                val decodedStr = String(decodedBytes, Charsets.UTF_8)
                val claimsObj = json.parseToJsonElement(decodedStr).jsonObject
                claimsObj.entries.associate { (k, v) ->
                    k to try { v.jsonPrimitive.content } catch (_: Exception) { v.toString() }
                }
            } catch (e: Exception) {
                throw IllegalArgumentException("Failed to decode JWT payload: $e")
            }

            // Extract profile ID - API may return either "profile_id" or "consent_profile_id"
            val profileId = jsonObj["profile_id"]?.jsonPrimitive?.content
                ?: jsonObj["consent_profile_id"]?.jsonPrimitive?.content
                ?: claims["profile_id"]?.toString()
                ?: claims["consent_profile_id"]?.toString()
                ?: ""

            // Extract scopes - may be in response or in JWT claims
            val scopes: List<String> = try {
                jsonObj["scopes"]?.jsonArray?.map { it.jsonPrimitive.content }
            } catch (_: Exception) { null }
                ?: try {
                    val claimsObj = json.parseToJsonElement(
                        String(Base64.decode(parts[1], Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP), Charsets.UTF_8)
                    ).jsonObject
                    claimsObj["scopes"]?.jsonArray?.map { it.jsonPrimitive.content }
                } catch (_: Exception) { null }
                ?: emptyList()

            return ConsentToken(
                token = tokenStr,
                expiresAt = expiresAt,
                profileId = profileId,
                scopes = scopes,
                claims = claims
            )
        }

        /**
         * Create from stored JSON map (loaded from encrypted storage).
         */
        fun fromStoredJson(jsonObj: JsonObject): ConsentToken {
            return ConsentToken(
                token = jsonObj["token"]!!.jsonPrimitive.content,
                expiresAt = Instant.parse(jsonObj["expires_at"]!!.jsonPrimitive.content),
                profileId = jsonObj["profile_id"]!!.jsonPrimitive.content,
                scopes = jsonObj["scopes"]!!.jsonArray.map { it.jsonPrimitive.content },
                claims = jsonObj["claims"]?.jsonObject?.entries?.associate { (k, v) ->
                    k to try { v.jsonPrimitive.content } catch (_: Exception) { v.toString() }
                } ?: emptyMap()
            )
        }
    }
}

/**
 * Consent status enumeration.
 */
enum class ConsentStatus {
    /** Consent granted with valid token */
    GRANTED,

    /** Consent pending (user hasn't responded) */
    PENDING,

    /** Consent denied by user */
    DENIED,

    /** Token expired */
    EXPIRED
}
