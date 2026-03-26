package ai.synheart.core.config

/**
 * Central registry of all Synheart API endpoints.
 *
 * Service paths are within each service (after gateway routing).
 * Base URLs resolve to: gateway (api.synheart.ai/{service}) or direct ({service}-dev.synheart.io)
 */
object ApiEndpoints {
    // ── Base URLs (defaults) ──────────────────────────────────────────
    const val DEFAULT_CLOUD_BASE_URL = "https://api.synheart.ai"

    // ── Cloud / HSI Ingest ──────────────────────────────────────────
    const val INGEST_PATH = "/v1/hsi/ingest"

    // ── Platform Ingest (lab/raw data) ──────────────────────────────
    const val DEFAULT_PLATFORM_INGEST_BASE_URL = "https://api.synheart.ai"
    const val PLATFORM_SESSION_INGEST_PATH = "/v1/platform/session/ingest"
    const val PLATFORM_METADATA_INGEST_PATH = "/v1/platform/metadata/ingest"

    // ── Consent Service ─────────────────────────────────────────────
    const val DEFAULT_CONSENT_BASE_URL = "https://api.synheart.ai"
    fun consentProfilesPath(appId: String) = "/v1/apps/$appId/consent-profiles"
    const val CONSENT_TOKEN_PATH = "/v1/sdk/consent-token"
    const val CONSENT_REVOKE_PATH = "/v1/sdk/consent-revoke"
}
