package com.synheart.core.modules.cloud

import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP client for uploading HSI 1.0 snapshots to Synheart Platform
 *
 * Features:
 * - HMAC-SHA256 authentication
 * - Exponential backoff retry (1s, 2s, 4s)
 * - Max 3 retry attempts
 * - Specific error handling per status code
 */
class UploadClient(
    private val baseUrl: String,
    httpClient: OkHttpClient? = null
) {
    private val client = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /**
     * Upload HSI 1.0 snapshots to the platform
     *
     * @param payload Upload request containing subject and snapshots
     * @param signer HMAC signer instance
     * @param tenantId Tenant identifier
     * @return UploadResponse on success
     * @throws CloudConnectorException on failure
     */
    suspend fun upload(
        payload: UploadRequest,
        signer: HMACSigner,
        tenantId: String
    ): UploadResponse {
        val method = "POST"
        val path = "/v1/ingest/hsi"

        // Serialize payload
        val bodyJson = json.encodeToString(payload)

        // Upload with retry logic
        return uploadWithRetry(
            method = method,
            path = path,
            bodyJson = bodyJson,
            signer = signer,
            tenantId = tenantId,
            maxAttempts = 3
        )
    }

    /**
     * Upload with exponential backoff retry
     */
    private suspend fun uploadWithRetry(
        method: String,
        path: String,
        bodyJson: String,
        signer: HMACSigner,
        tenantId: String,
        maxAttempts: Int
    ): UploadResponse {
        var attempts = 0
        val baseDelay = 1000L // 1 second

        while (attempts < maxAttempts) {
            attempts++

            try {
                // Generate fresh nonce and timestamp for each attempt
                val nonce = signer.generateNonce()
                val timestamp = System.currentTimeMillis() / 1000
                val signature = signer.computeSignature(
                    method = method,
                    path = path,
                    tenantId = tenantId,
                    timestamp = timestamp,
                    nonce = nonce,
                    bodyJson = bodyJson
                )

                // Build request
                val url = "$baseUrl$path"
                val requestBody = bodyJson.toRequestBody("application/json".toMediaType())
                val request = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("Content-Type", "application/json")
                    .header("X-Synheart-Tenant", tenantId)
                    .header("X-Synheart-Signature", signature)
                    .header("X-Synheart-Nonce", nonce)
                    .header("X-Synheart-Timestamp", timestamp.toString())
                    .header("X-Synheart-SDK-Version", "1.0.0")
                    .build()

                // Execute request
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                        ?: throw NetworkError("Empty response body")
                    return json.decodeFromString<UploadResponse>(responseBody)
                }

                // Parse error response
                val errorBody = response.body?.string()
                    ?: throw NetworkError("Empty error response body")
                val error = json.decodeFromString<UploadErrorResponse>(errorBody)

                // Handle specific errors (non-retryable)
                when {
                    response.code == 401 && error.code == "invalid_signature" -> {
                        throw InvalidSignatureError()
                    }
                    response.code == 403 && error.code == "invalid_tenant" -> {
                        throw InvalidTenantError()
                    }
                    response.code == 400 && error.code == "schema_validation_failed" -> {
                        throw SchemaValidationError()
                    }
                    response.code == 429 -> {
                        throw RateLimitExceededError(error.retryAfter ?: 60)
                    }
                }

                // Generic error - retry if attempts remaining
                if (attempts >= maxAttempts) {
                    throw CloudConnectorException("Upload failed: ${error.message}")
                }

            } catch (e: CloudConnectorException) {
                // Don't retry on known exceptions
                throw e
            } catch (e: Exception) {
                // Network or parsing error - retry if attempts remaining
                if (attempts >= maxAttempts) {
                    throw NetworkError("Upload failed after $maxAttempts attempts: ${e.message}", e)
                }
            }

            // Exponential backoff: 1s, 2s, 4s
            if (attempts < maxAttempts) {
                val delayMs = baseDelay * (1 shl (attempts - 1))
                delay(delayMs)
            }
        }

        throw NetworkError("Upload failed after $maxAttempts attempts")
    }

    /**
     * Close the HTTP client
     */
    fun dispose() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
