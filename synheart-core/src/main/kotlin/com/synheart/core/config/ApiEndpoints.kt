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
}
