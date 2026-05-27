package ai.synheart.core.config

import ai.synheart.core.modules.interfaces.AuthProvider
import java.util.UUID

/** Storage sub-configuration. */
data class StorageConfig(
    val enabled: Boolean = true,
    val retentionDays: Int? = null
)

/** Sync sub-configuration. */
data class SyncConfig(
    val enabled: Boolean = false
)

/** Privacy sub-configuration. */
data class PrivacyConfig(
    val allowResearch: Boolean = false
)

/**
 * Main configuration for Synheart SDK
 */
data class SynheartConfig(
    val appId: String = "",
    val subjectId: String = "",
    val mode: SynheartMode = SynheartMode.PERSONAL,
    val appVersion: String = "0.0.0",
    /** Human-readable app name (used in platform metadata). */
    val appName: String = "",
    /** App category (e.g. "Game", "Health", "Productivity"). */
    val category: String = "",
    /** Developer name or organization. */
    val developer: String = "",
    /** Additional app-level metadata for lab ingestion. */
    val additionalAppMetadata: Map<String, Any> = emptyMap(),
    val deviceId: String = "",
    val platform: String = "android",
    /** Device role — controls which modules are enabled. Defaults to PHONE. */
    val deviceRole: DeviceRole = DeviceRole.PHONE,
    val storage: StorageConfig = StorageConfig(),
    val sync: SyncConfig = SyncConfig(),
    val privacy: PrivacyConfig = PrivacyConfig(),

    val cloudConfig: CloudConfig? = null,
    val consentConfig: ConsentConfig? = null,
    val labIngestConfig: LabIngestConfig? = null,
    /** Server-signed capability token for feature gating */
    val capabilityToken: ai.synheart.core.modules.capabilities.CapabilityToken? = null,
    /** HMAC secret for verifying the capability token signature */
    val capabilitySecret: String? = null,
    /** When true, allows SDK to run with default capabilities and no signed token (debug only) */
    val allowUnsignedCapabilities: Boolean = false
) {
    /** Validate config and throw on violations. */
    fun validate() {
        if (mode == SynheartMode.RESEARCH && !privacy.allowResearch) {
            throw SynheartCoreError.ResearchNotAllowed()
        }
        if (appId.isEmpty()) {
            throw SynheartCoreError.NotConfigured("appId must not be empty")
        }
        if (subjectId.isEmpty()) {
            throw SynheartCoreError.NotConfigured("subjectId must not be empty")
        }
        if (subjectId.contains("|")) {
            throw SynheartCoreError.InvalidMode("subjectId must not contain pipe character")
        }
    }
}

/**
 * Cloud Connector configuration
 *
 * Required for cloud upload functionality.
 *
 * Example:
 * ```kotlin
 * val cloudConfig = CloudConfig(
 *     subjectId = "pseudonymous_user_123",
 *     instanceId = UUID.randomUUID().toString()
 * )
 * ```
 */
data class CloudConfig(
    val baseUrl: String = ApiEndpoints.DEFAULT_CLOUD_BASE_URL,
    /**
     * Auth provider for request signing. The runtime signs every ingest
     * request with the device's hardware-backed ECDSA P-256 key; this is
     * an override hook for hosts that need to stub it (e.g. in tests).
     */
    val authProvider: AuthProvider? = null,
    val subjectId: String,
    val subjectType: String = "pseudonymous_user",
    val instanceId: String = UUID.randomUUID().toString(),
    val maxQueueSize: Int = 100,
    val batchSize: Int = 10,
    val uploadIntervalMs: Long = 300_000,
    val maxRetries: Int = 3,
    val enableBacklog: Boolean = true
)

/**
 * Consent service configuration.
 *
 * Required for cloud consent service integration (JWT-based consent tokens).
 *
 * Example:
 * ```kotlin
 * val consentConfig = ConsentConfig(
 *     consentServiceUrl = "https://consent.synheart.ai",
 *     appId = "your_app_id",
 *     appApiKey = "your_app_api_key"
 * )
 * ```
 */
data class ConsentConfig(
    /** Base URL for consent service */
    val consentServiceUrl: String = ApiEndpoints.DEFAULT_CONSENT_BASE_URL,

    /** App ID for consent service */
    val appId: String? = null,

    /** App API key for consent service authentication */
    val appApiKey: String? = null,

    /** Device ID (UUID for this device, auto-generated if not provided) */
    val deviceId: String? = null,

    /** Platform identifier ('android', 'ios', etc.) */
    val platform: String = "android",

    /** User ID (optional, for pseudonymous identification) */
    val userId: String? = null,

    /** Region code (e.g., "US", "EU") */
    val region: String? = null
) {
    /** Check if consent service is configured */
    val isConfigured: Boolean
        get() = appId != null && appApiKey != null
}
