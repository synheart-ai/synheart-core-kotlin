package ai.synheart.core.modules.cloud

import ai.synheart.core.config.ApiEndpoints
import ai.synheart.core.modules.consent.ConsentToken
import ai.synheart.core.modules.interfaces.AuthProvider
import kotlinx.coroutines.delay
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * HTTP client for uploading HSI snapshots to Synheart Platform.
 *
 * Auth model:
 *   Authorization: Bearer {consentToken}   — standard JWT from ConsentModule
 *   + device signature headers              — defense-in-depth (optional)
 *
 * HMAC signing removed — device attestation at token issuance replaces it.
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

    suspend fun upload(
        payload: UploadRequest,
        consentToken: ConsentToken?,
        deviceAuth: AuthProvider? = null
    ): UploadResponse {
        val method = "POST"
        val path = ApiEndpoints.INGEST_PATH
        val bodyJson = json.encodeToString(payload)

        return uploadWithRetry(
            method = method,
            path = path,
            bodyJson = bodyJson,
            maxAttempts = 3,
            consentToken = consentToken,
            deviceAuth = deviceAuth
        )
    }

    private suspend fun uploadWithRetry(
        method: String,
        path: String,
        bodyJson: String,
        maxAttempts: Int,
        consentToken: ConsentToken?,
        deviceAuth: AuthProvider?
    ): UploadResponse {
        var attempts = 0
        val baseDelay = 1000L

        while (attempts < maxAttempts) {
            attempts++

            try {
                val url = "$baseUrl$path"
                val requestBody = bodyJson.toRequestBody("application/json".toMediaType())
                val bodyBytes = bodyJson.toByteArray(Charsets.UTF_8)

                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(requestBody)
                    .header("Content-Type", "application/json")

                // Bearer token from consent module
                if (consentToken != null && consentToken.isValid) {
                    requestBuilder.header("Authorization", "Bearer ${consentToken.token}")
                }

                // Device signature headers (defense-in-depth)
                if (deviceAuth != null) {
                    val deviceHeaders = deviceAuth.signRequest(method, path, bodyBytes)
                    for ((key, value) in deviceHeaders) {
                        requestBuilder.header(key, value)
                    }
                }

                val request = requestBuilder.build()
                val response = client.newCall(request).execute()

                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                        ?: throw NetworkError("Empty response body")
                    return json.decodeFromString<UploadResponse>(responseBody)
                }

                val errorBodyStr = response.body?.string()
                    ?: throw NetworkError("Empty error response body")
                val error: UploadErrorResponse
                try {
                    error = json.decodeFromString<UploadErrorResponse>(errorBodyStr)
                } catch (_: Exception) {
                    throw CloudConnectorException(
                        "Upload failed: ${response.code} $errorBodyStr"
                    )
                }

                // Handle 401 with device auth retry
                if (response.code == 401 && deviceAuth != null) {
                    val responseHeaders = response.headers.toMultimap().mapValues { it.value.first() }
                    val handled = deviceAuth.onAuthError(401, responseHeaders)
                    if (handled && attempts < maxAttempts) {
                        continue
                    }
                }

                when {
                    response.code == 401 -> throw InvalidSignatureError()
                    response.code == 403 -> throw InvalidTenantError()
                    response.code == 400 && (error.code == "schema_validation_failed" || error.code == "hsi_schema_validation_failed") ->
                        throw SchemaValidationError()
                    response.code == 429 -> throw RateLimitExceededError(error.retryAfter ?: 60)
                }

                if (attempts >= maxAttempts) {
                    throw CloudConnectorException("Upload failed: ${error.message}")
                }

            } catch (e: CloudConnectorException) {
                throw e
            } catch (e: Exception) {
                if (attempts >= maxAttempts) {
                    throw NetworkError("Upload failed after $maxAttempts attempts: ${e.message}", e)
                }
            }

            if (attempts < maxAttempts) {
                val delayMs = baseDelay * (1 shl (attempts - 1))
                delay(delayMs)
            }
        }

        throw NetworkError("Upload failed after $maxAttempts attempts")
    }

    fun dispose() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
