package ai.synheart.core.config

/**
 * Configuration for the Lab Ingest module.
 *
 * Used to send custom session and metadata payloads to the
 * Synheart lab ingestion service.
 *
 * Example:
 * ```kotlin
 * val labIngestConfig = LabIngestConfig(
 *     apiKey = "synheart_sk_live_...",
 *     hmacSecret = "synheart_whsec_..."
 * )
 * ```
 */
data class LabIngestConfig(
    /// Base URL for the lab ingestion service.
    val baseUrl: String = ApiEndpoints.DEFAULT_LAB_INGEST_BASE_URL,

    /// API key for authentication (X-API-Key header).
    /// Optional when device auth is configured (DeviceAuthProvider signs requests).
    val apiKey: String? = null,

    /// HMAC secret for request signing.
    /// Optional when device auth is configured (DeviceAuthProvider signs requests).
    val hmacSecret: String? = null,

    /// HTTP request timeout in milliseconds (default: 30 seconds).
    val timeoutMs: Long = 30_000,

    /// Maximum retry attempts for failed requests.
    val maxRetries: Int = 3,

    /// When true, automatically build and ingest a session payload after
    /// stopSession(). Defaults to false (manual ingestion).
    val autoIngest: Boolean = false
)
