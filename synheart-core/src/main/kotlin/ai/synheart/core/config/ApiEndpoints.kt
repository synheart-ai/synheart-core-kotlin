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

    // ── Lab Ingest (lab/raw data) ──────────────────────────────
    const val DEFAULT_LAB_INGEST_BASE_URL = "https://api.synheart.ai"
    const val LAB_SESSION_INGEST_PATH = "/v1/lab/session/ingest"
    const val LAB_METADATA_INGEST_PATH = "/v1/lab/metadata/ingest"

    // ── Consent Service ─────────────────────────────────────────────
    const val DEFAULT_CONSENT_BASE_URL = "https://api.synheart.ai"
    fun consentProfilesPath(appId: String) = "/v1/apps/$appId/consent-profiles"
    const val CONSENT_TOKEN_PATH = "/v1/sdk/consent-token"
    const val CONSENT_REVOKE_PATH = "/v1/sdk/consent-revoke"
}
