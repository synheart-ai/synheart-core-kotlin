package ai.synheart.core.modules.lab_ingest

import ai.synheart.core.SynheartLogger
import ai.synheart.core.config.ApiEndpoints
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * Stateless HTTP client for platform session and metadata ingestion.
 *
 * Can be used standalone (without full SDK init) — e.g. from a
 * WorkManager background task — by providing hmacSecret, apiKey,
 * and consent token directly.
 *
 * Uses a simpler HMAC signing scheme than the cloud connector:
 * `HMAC-SHA256(timestamp_bytes + body_bytes, secret)`
 */
class LabIngestClient(
    private val baseUrl: String = ApiEndpoints.DEFAULT_LAB_INGEST_BASE_URL,
    private val timeoutMs: Long = 30_000,
    private val maxRetries: Int = 3,
    httpClient: OkHttpClient? = null
) {
    private val client = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * POST a session payload to `/platform/v1/session/ingest`.
     */
    suspend fun ingestSession(
        payload: Map<String, Any?>,
        hmacSecret: String,
        apiKey: String,
        consentToken: String? = null
    ): LabIngestResponse {
        return post(
            path = ApiEndpoints.LAB_SESSION_INGEST_PATH,
            payload = payload,
            hmacSecret = hmacSecret,
            apiKey = apiKey,
            consentToken = consentToken
        )
    }

    /**
     * POST a metadata payload to `/platform/v1/metadata/ingest`.
     */
    suspend fun ingestMetadata(
        payload: Map<String, Any?>,
        hmacSecret: String,
        apiKey: String,
        consentToken: String? = null
    ): LabIngestResponse {
        return post(
            path = ApiEndpoints.LAB_METADATA_INGEST_PATH,
            payload = payload,
            hmacSecret = hmacSecret,
            apiKey = apiKey,
            consentToken = consentToken
        )
    }

    private suspend fun post(
        path: String,
        payload: Map<String, Any?>,
        hmacSecret: String,
        apiKey: String,
        consentToken: String?
    ): LabIngestResponse {
        val bodyJson = mapToJson(payload)
        var attempts = 0

        while (attempts < maxRetries) {
            attempts++
            try {
                val url = "$baseUrl$path"
                val requestBody = bodyJson.toRequestBody("application/json".toMediaType())

                val nonce = generateNonce()
                val timestamp = (System.currentTimeMillis() / 1000).toString()
                val signature = computeSignature(
                    timestamp = timestamp,
                    bodyJson = bodyJson,
                    hmacSecret = hmacSecret
                )

                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("Content-Type", "application/json")
                    .header("X-API-Key", apiKey)
                    .header("X-Synheart-Signature", signature)
                    .header("X-Synheart-Timestamp", timestamp)
                    .header("X-Synheart-Nonce", nonce)

                if (!consentToken.isNullOrEmpty()) {
                    requestBuilder.header("X-Consent-Token", consentToken)
                }

                val request = requestBuilder.build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val parsedBody = tryParseJson(responseBody)
                    return LabIngestResponse(
                        success = true,
                        statusCode = response.code,
                        body = parsedBody
                    )
                }

                // 4xx — don't retry client errors
                if (response.code in 400..499) {
                    val errorBody = response.body?.string()
                    return LabIngestResponse(
                        success = false,
                        statusCode = response.code,
                        errorMessage = "Client error: HTTP ${response.code}",
                        body = tryParseJson(errorBody)
                    )
                }

                // 5xx — retry with exponential backoff
                if (attempts < maxRetries) {
                    val delayMs = 1000L * (1 shl attempts)
                    delay(delayMs)
                    continue
                }

                val errorBody = response.body?.string()
                return LabIngestResponse(
                    success = false,
                    statusCode = response.code,
                    errorMessage = "Server error: HTTP ${response.code}",
                    body = tryParseJson(errorBody)
                )
            } catch (e: Exception) {
                if (attempts >= maxRetries) {
                    return LabIngestResponse(
                        success = false,
                        statusCode = 0,
                        errorMessage = "Request failed after $maxRetries attempts: ${e.message}"
                    )
                }
                val delayMs = 1000L * (1 shl attempts)
                delay(delayMs)
            }
        }

        return LabIngestResponse(
            success = false,
            statusCode = 0,
            errorMessage = "Request failed: max retries exceeded"
        )
    }

    /**
     * Compute HMAC-SHA256 signature for lab ingestion.
     *
     * Formula: HMAC-SHA256(timestamp_bytes + body_bytes, secret)
     */
    private fun computeSignature(
        timestamp: String,
        bodyJson: String,
        hmacSecret: String
    ): String {
        val timestampBytes = timestamp.toByteArray(Charsets.UTF_8)
        val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)
        val message = timestampBytes + bodyBytes

        val hmac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(hmacSecret.toByteArray(Charsets.UTF_8), "HmacSHA256")
        hmac.init(secretKey)

        val digest = hmac.doFinal(message)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Generate UUID v4 nonce.
     */
    private fun generateNonce(): String {
        val bytes = Random.nextBytes(16)
        // Set version (4) in 7th byte
        bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
        // Set variant (10) in 9th byte
        bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()

        val hex = bytes.joinToString("") { "%02x".format(it) }
        return "${hex.substring(0, 8)}-${hex.substring(8, 12)}-${hex.substring(12, 16)}-${hex.substring(16, 20)}-${hex.substring(20, 32)}"
    }

    @Suppress("UNCHECKED_CAST")
    private fun mapToJson(map: Map<String, Any?>): String {
        return org.json.JSONObject(map).toString()
    }

    private fun tryParseJson(body: String?): Map<String, Any?>? {
        if (body.isNullOrEmpty()) return null
        return try {
            val jsonObj = org.json.JSONObject(body)
            jsonObj.keys().asSequence().associateWith { jsonObj.opt(it) }
        } catch (e: Exception) {
            null
        }
    }

    fun dispose() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
