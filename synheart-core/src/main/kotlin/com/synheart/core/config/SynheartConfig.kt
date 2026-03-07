package com.synheart.core.config

import com.synheart.core.modules.interfaces.AuthProvider
import java.util.UUID

/** Storage sub-configuration (RFC-CORE-0004). */
data class StorageConfig(
    val enabled: Boolean = true,
    val retentionDays: Int? = null
)

/** Sync sub-configuration (RFC-CORE-0005, Phase 3). */
data class SyncConfig(
    val enabled: Boolean = false
)

/** Privacy sub-configuration (RFC-CORE-0003). */
data class PrivacyConfig(
    val allowResearch: Boolean = false
)

/**
 * Main configuration for Synheart SDK
 */
data class SynheartConfig(
    // RFC-CORE-0007 fields
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
    /** Additional app-level metadata for platform ingestion. */
    val additionalAppMetadata: Map<String, Any> = emptyMap(),
    val deviceId: String = "",
    val platform: String = "android",
    val storage: StorageConfig = StorageConfig(),
    val sync: SyncConfig = SyncConfig(),
    val privacy: PrivacyConfig = PrivacyConfig(),

    // Legacy fields (backward-compatible)
    val enableWear: Boolean = true,
    val enablePhone: Boolean = true,
    val enableBehavior: Boolean = true,
    val cloudConfig: CloudConfig? = null,
    val platformIngestConfig: PlatformIngestConfig? = null,
    /** Server-signed capability token for feature gating */
    val capabilityToken: com.synheart.core.modules.capabilities.CapabilityToken? = null,
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
 *     tenantId = "your_tenant_id",
 *     hmacSecret = "your_hmac_secret",
 *     subjectId = "pseudonymous_user_123",
 *     instanceId = UUID.randomUUID().toString()
 * )
 * ```
 */
data class CloudConfig(
    /// Base URL for Synheart Platform (default: production)
    val baseUrl: String = ApiEndpoints.DEFAULT_CLOUD_BASE_URL,

    /// Tenant ID (from app registration)
    val tenantId: String,

    /// HMAC secret for signing requests (null when authProvider is used)
    val hmacSecret: String? = null,

    /// Custom auth provider for request signing (e.g., ECDSA device-identity).
    /// When set, takes precedence over the HMAC path.
    val authProvider: AuthProvider? = null,

    /// Subject ID (pseudonymous user identifier)
    val subjectId: String,

    /// Subject type (default: "pseudonymous_user")
    val subjectType: String = "pseudonymous_user",

    /// Instance ID (UUID for this SDK instance)
    val instanceId: String = UUID.randomUUID().toString(),

    /// Max upload queue size (default: 100)
    val maxQueueSize: Int = 100,

    /// Batch size for uploads (default: 10)
    val batchSize: Int = 10,

    /// Upload interval (milliseconds, default: 5 minutes)
    val uploadIntervalMs: Long = 300_000,

    /// Max retry attempts (default: 3)
    val maxRetries: Int = 3,

    /// Enable backlog persistence (default: true)
    val enableBacklog: Boolean = true
) {
    init {
        require(hmacSecret != null || authProvider != null) {
            "CloudConfig requires either hmacSecret or authProvider"
        }
    }
}
