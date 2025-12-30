package com.synheart.core.config

import java.util.UUID

/**
 * Main configuration for Synheart SDK
 */
data class SynheartConfig(
    val enableWear: Boolean = true,
    val enablePhone: Boolean = true,
    val enableBehavior: Boolean = true,
    val cloudConfig: CloudConfig? = null
)

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
    val baseUrl: String = "https://api.synheart.com",

    /// Tenant ID (from app registration)
    val tenantId: String,

    /// HMAC secret for signing requests
    val hmacSecret: String,

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
)
