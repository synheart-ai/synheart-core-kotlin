package ai.synheart.core.modules.consent

import ai.synheart.core.SynheartLogger
import ai.synheart.core.modules.interfaces.ConsentTier
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * REST client for consent service API.
 */
/**
 * Callback type for device request signing.
 * Returns a map of signed headers (X-Synheart-Signature, etc.)
 */
typealias DeviceRequestSigner = (method: String, path: String, bodyBytes: ByteArray) -> Map<String, String>

class ConsentAPIClient(
    private val baseUrl: String,
    private val appId: String,
    private val appApiKey: String,
    httpClient: OkHttpClient? = null,
    /** Optional device signer for adding X-Synheart-* headers to consent requests. */
    var deviceSigner: DeviceRequestSigner? = null
) {
    private val client = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Fetch available consent profiles for this app.
     *
     * GET /consent/v1/apps/{app_id}/consent-profiles?active_only=true
     */
    fun getAvailableProfiles(activeOnly: Boolean = true): List<ConsentProfile> {
        try {
            val url = "${baseUrl}${ApiEndpoints.consentProfilesPath(appId)}?active_only=$activeOnly"

            SynheartLogger.log("[ConsentAPI] Fetching profiles from: $url")

            val stopwatch = System.currentTimeMillis()
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Authorization", "Bearer $appApiKey")
                .header("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            val duration = System.currentTimeMillis() - stopwatch

            SynheartLogger.log("[ConsentAPI] Response received: statusCode=${response.code}, duration=${duration}ms")

            response.use { resp ->
                val bodyStr = resp.body?.string() ?: ""

                when (resp.code) {
                    200 -> {
                        SynheartLogger.log("[ConsentAPI] Response body length: ${bodyStr.length} bytes")

                        val body = json.parseToJsonElement(bodyStr).jsonObject
                        SynheartLogger.log("[ConsentAPI] Parsed response body keys: ${body.keys.joinToString(", ")}")

                        val profilesJson = body["profiles"]?.jsonArray
                            ?: throw ConsentAPIException("Invalid response: missing \"profiles\" key")

                        SynheartLogger.log("[ConsentAPI] Found ${profilesJson.size} profiles in response")

                        val profiles = profilesJson.map { element ->
                            json.decodeFromJsonElement(ConsentProfile.serializer(), element)
                        }

                        SynheartLogger.log("[ConsentAPI] Successfully parsed ${profiles.size} profiles")
                        return profiles
                    }
                    401 -> {
                        SynheartLogger.log("[ConsentAPI] ERROR: 401 Unauthorized - Invalid app API key")
                        throw ConsentAPIException("Invalid app API key")
                    }
                    404 -> {
                        SynheartLogger.log("[ConsentAPI] ERROR: 404 Not Found - App not found")
                        throw ConsentAPIException("App not found")
                    }
                    else -> {
                        SynheartLogger.log("[ConsentAPI] ERROR: Unexpected status code ${resp.code}")
                        throw ConsentAPIException("Failed to fetch profiles: ${resp.code}")
                    }
                }
            }
        } catch (e: ConsentAPIException) {
            SynheartLogger.log("[ConsentAPI] ConsentAPIException: ${e.message}")
            throw e
        } catch (e: Exception) {
            SynheartLogger.log("[ConsentAPI] Network/parsing error: $e")
            throw ConsentAPIException("Network error: $e")
        }
    }

    /**
     * Issue SDK token after user consent.
     *
     * POST /consent/v1/sdk/consent-token
     */
    fun issueToken(
        deviceId: String,
        consentProfileId: String,
        platform: String,
        userId: String? = null,
        region: String? = null,
        ipAddress: String? = null,
        userAgent: String? = null,
        grantedChannels: ConsentChannels? = null,
        tier: ConsentTier? = null,
        cloud: Boolean? = null,
        vendorSync: Boolean? = null,
        research: Boolean = false
    ): ConsentToken {
        try {
            val url = "${baseUrl}${ApiEndpoints.CONSENT_TOKEN_PATH}"

            val bodyJson = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("app_id", kotlinx.serialization.json.JsonPrimitive(appId))
                    put("device_id", kotlinx.serialization.json.JsonPrimitive(deviceId))
                    put("platform", kotlinx.serialization.json.JsonPrimitive(platform))
                    put("consent_profile_id", kotlinx.serialization.json.JsonPrimitive(consentProfileId))
                    userId?.let { put("user_id", kotlinx.serialization.json.JsonPrimitive(it)) }
                    region?.let { put("region", kotlinx.serialization.json.JsonPrimitive(it)) }
                    ipAddress?.let { put("ip_address", kotlinx.serialization.json.JsonPrimitive(it)) }
                    userAgent?.let { put("user_agent", kotlinx.serialization.json.JsonPrimitive(it)) }
                    grantedChannels?.let {
                        put("granted_channels", json.encodeToJsonElement(ConsentChannels.serializer(), it))
                    }
                    tier?.let { put("consent_tier", kotlinx.serialization.json.JsonPrimitive(it.name)) }
                    cloud?.let { put("cloud", kotlinx.serialization.json.JsonPrimitive(it)) }
                    vendorSync?.let { put("vendor_sync", kotlinx.serialization.json.JsonPrimitive(it)) }
                    if (research) {
                        put("research", kotlinx.serialization.json.JsonPrimitive(true))
                    }
                }
            )

            val requestBuilder = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $appApiKey")
                .header("Content-Type", "application/json")

            // Sign the request with device identity if available
            deviceSigner?.let { signer ->
                val deviceHeaders = signer(
                    "POST",
                    ApiEndpoints.CONSENT_TOKEN_PATH,
                    bodyJson.toByteArray(Charsets.UTF_8)
                )
                for ((key, value) in deviceHeaders) {
                    requestBuilder.header(key, value)
                }
            }

            val request = requestBuilder.build()

            val response = client.newCall(request).execute()

            response.use { resp ->
                val bodyStr = resp.body?.string() ?: ""

                when {
                    resp.code == 200 || resp.code == 201 -> {
                        SynheartLogger.log("[ConsentAPI] Token response received: statusCode=${resp.code}")

                        val bodyObj = json.parseToJsonElement(bodyStr).jsonObject
                        SynheartLogger.log("[ConsentAPI] Parsed response keys: ${bodyObj.keys.joinToString(", ")}")

                        try {
                            val token = ConsentToken.fromJson(bodyObj)
                            SynheartLogger.log(
                                "[ConsentAPI] Successfully parsed consent token: profileId=${token.profileId}, expiresAt=${token.expiresAt}"
                            )
                            return token
                        } catch (e: Exception) {
                            SynheartLogger.log("[ConsentAPI] ERROR parsing token from JSON: $e")
                            SynheartLogger.log("[ConsentAPI] Full response body for debugging: $bodyStr")
                            throw e
                        }
                    }
                    resp.code == 401 -> {
                        throw ConsentAPIException("Invalid app API key")
                    }
                    resp.code == 400 -> {
                        val errorBody = json.parseToJsonElement(bodyStr).jsonObject
                        val message = errorBody["message"]?.jsonPrimitive?.content ?: "Invalid request"
                        throw ConsentAPIException(message)
                    }
                    else -> {
                        throw ConsentAPIException("Failed to issue token: ${resp.code}")
                    }
                }
            }
        } catch (e: ConsentAPIException) {
            throw e
        } catch (e: Exception) {
            SynheartLogger.log("[ConsentAPI] Error issuing token: $e")
            throw ConsentAPIException("Network error: $e")
        }
    }

    /**
     * Revoke consent (notify cloud service).
     *
     * POST /consent/v1/sdk/consent-revoke
     *
     * This is best-effort -- errors are logged but not thrown.
     */
    fun revokeConsent(deviceId: String, profileId: String) {
        try {
            val url = "${baseUrl}${ApiEndpoints.CONSENT_REVOKE_PATH}"

            val bodyJson = json.encodeToString(
                kotlinx.serialization.json.JsonObject.serializer(),
                kotlinx.serialization.json.buildJsonObject {
                    put("app_id", kotlinx.serialization.json.JsonPrimitive(appId))
                    put("device_id", kotlinx.serialization.json.JsonPrimitive(deviceId))
                    put("profile_id", kotlinx.serialization.json.JsonPrimitive(profileId))
                }
            )

            val request = Request.Builder()
                .url(url)
                .post(bodyJson.toRequestBody("application/json".toMediaType()))
                .header("Authorization", "Bearer $appApiKey")
                .header("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            response.use { resp ->
                if (resp.code != 200 && resp.code != 204) {
                    SynheartLogger.log("[ConsentAPI] Failed to revoke consent: ${resp.code}")
                }
            }
        } catch (e: Exception) {
            SynheartLogger.log("[ConsentAPI] Error revoking consent: $e")
            // Don't throw - revocation is best-effort
        }
    }

    /**
     * Close the HTTP client.
     */
    fun dispose() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}

/**
 * Exception thrown by consent API client.
 */
class ConsentAPIException(message: String) : Exception(message)
