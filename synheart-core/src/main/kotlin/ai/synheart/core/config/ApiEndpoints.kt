package ai.synheart.core.config

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
    const val INGEST_PATH = "/ingest/v1/hsi"

    // ── Platform Ingest ─────────────────────────────────────────────
    const val DEFAULT_PLATFORM_INGEST_BASE_URL = "https://api.synheart.ai"
    const val PLATFORM_SESSION_INGEST_PATH = "/platform/v1/session/ingest"
    const val PLATFORM_METADATA_INGEST_PATH = "/platform/v1/metadata/ingest"

    // ── Consent Service ─────────────────────────────────────────────
    const val DEFAULT_CONSENT_BASE_URL = "https://api.synheart.ai"
    fun consentProfilesPath(appId: String) = "/consent/v1/apps/$appId/consent-profiles"
    const val CONSENT_TOKEN_PATH = "/consent/v1/sdk/consent-token"
    const val CONSENT_REVOKE_PATH = "/consent/v1/sdk/consent-revoke"
}
