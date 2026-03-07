package com.synheart.core.config

/**
 * Configuration for the Platform Ingest module.
 *
 * Used to send custom session and metadata payloads to the
 * Synheart platform ingestion service.
 *
 * Example:
 * ```kotlin
 * val platformIngestConfig = PlatformIngestConfig(
 *     apiKey = "synheart_sk_live_...",
 *     hmacSecret = "synheart_whsec_..."
 * )
 * ```
 */
data class PlatformIngestConfig(
    /// Base URL for the platform ingestion service.
    val baseUrl: String = ApiEndpoints.DEFAULT_PLATFORM_INGEST_BASE_URL,

    /// API key for authentication (X-API-Key header).
    val apiKey: String,

    /// HMAC secret for request signing.
    val hmacSecret: String,

    /// HTTP request timeout in milliseconds (default: 30 seconds).
    val timeoutMs: Long = 30_000,

    /// Maximum retry attempts for failed requests.
    val maxRetries: Int = 3,

    /// When true, automatically build and ingest a session payload after
    /// stopSession(). Defaults to false (manual ingestion).
    val autoIngest: Boolean = false
)
