package com.synheart.core.config

/**
 * Central registry of all Synheart API endpoints and default base URLs.
 *
 * API paths are constants — they follow the server's versioned routes.
 * Base URLs have sensible production defaults but can be overridden
 * via [CloudConfig].
 */
object ApiEndpoints {
    // ── Base URLs (defaults) ──────────────────────────────────────────
    const val DEFAULT_CLOUD_BASE_URL = "https://api.synheart.ai"

    // ── Cloud Ingest ──────────────────────────────────────────────────
    const val INGEST_PATH = "/v1/ingest/hsi"

    // ── Platform Ingest ─────────────────────────────────────────────
    const val DEFAULT_PLATFORM_INGEST_BASE_URL = "https://api.synheart.ai"
    const val PLATFORM_SESSION_INGEST_PATH = "/v1/platform/session/ingest"
    const val PLATFORM_METADATA_INGEST_PATH = "/v1/platform/metadata/ingest"
}
