package ai.synheart.core.config

/**
 * Central registry of all Synheart API endpoints and default base URLs.
 *
 * The runtime owns service-postfix routing; SDK base URLs are origin-only.
 *
 * ## No built-in host
 *
 * [defaultBaseUrl] is deliberately empty by default. A host baked in here
 * ships inside every copy of this SDK and silently becomes the destination for
 * any build that forgot to name one — including forks and self-hosted
 * deployments, which is exactly the case that must not default to someone
 * else's server.
 *
 * When empty, the SDK passes no origin to the native runtime and the runtime
 * applies its own built-in default, keeping one source of truth for the
 * endpoint rather than two that can drift.
 *
 * ## Setting the origin
 *
 * Resolution order, first non-empty wins:
 *  1. [baseUrlOverride] (and the per-service overrides) set programmatically
 *     before `Synheart.initialize`;
 *  2. the JVM system property `synheart.baseUrl`;
 *  3. the `SYNHEART_BASE_URL` environment variable.
 *
 * From Gradle, wire it through `BuildConfig` and assign at startup:
 *
 * ```kotlin
 * ApiEndpoints.baseUrlOverride = BuildConfig.SYNHEART_BASE_URL
 * ```
 */
object ApiEndpoints {

    // ── Base URL ──────────────────────────────────────────────────────

    /**
     * Platform origin supplied by the host. Empty means "the host named no
     * environment"; the runtime then applies its own default.
     */
    @Volatile
    var baseUrlOverride: String = ""

    /** Optional per-service overrides, for legacy or split-environment setups. */
    @Volatile
    var authBaseUrlOverride: String = ""

    @Volatile
    var consentBaseUrlOverride: String = ""

    @Volatile
    var ingestBaseUrlOverride: String = ""

    private fun fromEnvironment(key: String, property: String): String =
        System.getProperty(property)?.takeIf { it.isNotBlank() }
            ?: System.getenv(key)?.takeIf { it.isNotBlank() }
            ?: ""

    /** The resolved platform origin, or empty when none was configured. */
    val defaultBaseUrl: String
        get() = baseUrlOverride.takeIf { it.isNotBlank() }
            ?: fromEnvironment("SYNHEART_BASE_URL", "synheart.baseUrl")

    private fun resolve(override: String, key: String, property: String): String {
        val explicit = override.takeIf { it.isNotBlank() }
            ?: fromEnvironment(key, property)
        return explicit.takeIf { it.isNotBlank() } ?: defaultBaseUrl
    }

    /** Auth base URL (origin only; the runtime appends service postfixes). */
    val resolvedAuthBaseUrl: String
        get() = resolve(authBaseUrlOverride, "SYNHEART_AUTH_BASE_URL", "synheart.authBaseUrl")

    /** Consent base URL (origin only; the runtime appends service postfixes). */
    val resolvedConsentBaseUrl: String
        get() = resolve(
            consentBaseUrlOverride,
            "SYNHEART_CONSENT_BASE_URL",
            "synheart.consentBaseUrl",
        )

    /** Ingest base URL (origin only; the runtime appends service postfixes). */
    val resolvedIngestBaseUrl: String
        get() = resolve(ingestBaseUrlOverride, "SYNHEART_INGEST_BASE_URL", "synheart.ingestBaseUrl")

    /** Cloud and lab ingest both go to the ingest service. */
    val resolvedCloudBaseUrl: String get() = resolvedIngestBaseUrl

    val resolvedLabIngestBaseUrl: String get() = resolvedIngestBaseUrl

    // ── Ingest service paths ──────────────────────────────────────────
    const val INGEST_PATH = "/v1/hsi/ingest"
    const val LAB_SESSION_INGEST_PATH = "/v1/lab/session/ingest"
    const val LAB_METADATA_INGEST_PATH = "/v1/lab/metadata/ingest"

    // ── Auth service paths ────────────────────────────────────────────
    const val DEVICE_CAPABILITIES_PATH = "/v1/device/capabilities"
    const val ACCOUNT_DELETE_PATH = "/v1/delete"
    const val ACCOUNT_DELETE_CANCEL_PATH = "/v1/delete/cancel"

    // ── Consent service paths ─────────────────────────────────────────
    fun consentProfilesPath(appId: String) = "/v1/apps/$appId/consent-profiles"
    const val CONSENT_TOKEN_PATH = "/v1/sdk/consent-token"
    const val CONSENT_REVOKE_PATH = "/v1/sdk/consent-revoke"
    const val STUDY_CONSENT_PATH = "/v1/sdk/study-consent"

    /**
     * Throw when [url] is still empty, for call sites that genuinely cannot
     * proceed without an explicit origin.
     */
    fun assertConfigured(url: String, name: String) {
        require(url.isNotEmpty()) {
            "$name is not configured. Set ApiEndpoints.baseUrlOverride, the " +
                "synheart.baseUrl system property, or the SYNHEART_BASE_URL " +
                "environment variable."
        }
    }
}
