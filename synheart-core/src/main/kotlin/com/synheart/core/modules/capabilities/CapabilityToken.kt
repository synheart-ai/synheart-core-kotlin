package com.synheart.core.modules.capabilities

import com.synheart.core.modules.interfaces.CapabilityLevel
import com.synheart.core.modules.interfaces.Module
import java.time.Instant

/// Capability token received from authentication service
data class CapabilityToken(
    /// Organization ID
    val orgId: String,

    /// Project ID
    val projectId: String,

    /// Environment (dev, staging, production)
    val environment: String,

    /// Capability levels per module
    val capabilities: Map<String, String>,

    /// HMAC signature for verification
    val signature: String,

    /// Token expiration timestamp
    val expiresAt: Instant,

    /// Token issue timestamp
    val issuedAt: Instant
) {
    /// Check if token is expired
    val isExpired: Boolean
        get() = Instant.now().isAfter(expiresAt)

    /// Check if token is valid (not expired and issued in past)
    val isValid: Boolean
        get() {
            val now = Instant.now()
            return !isExpired && now.isAfter(issuedAt) && expiresAt.isAfter(issuedAt)
        }
}

/// SDK capabilities parsed from token
data class SDKCapabilities(
    /// Behavior module capability level
    val behavior: CapabilityLevel,

    /// Wear module capability level
    val wear: CapabilityLevel,

    /// Phone module capability level
    val phone: CapabilityLevel,

    /// HSV Runtime module capability level
    val hsvRuntime: CapabilityLevel,

    /// Cloud module capability level
    val cloud: CapabilityLevel
) {
    /// Get capability level for a module
    fun getLevel(module: Module): CapabilityLevel {
        return when (module) {
            Module.BEHAVIOR -> behavior
            Module.WEAR -> wear
            Module.PHONE -> phone
            Module.HSI -> hsvRuntime
            Module.CLOUD -> cloud
        }
    }

    companion object {
        /// Create capabilities from token
        fun fromToken(token: CapabilityToken): SDKCapabilities {
            return SDKCapabilities(
                behavior = parseLevel(token.capabilities["behavior"]),
                wear = parseLevel(token.capabilities["wear"]),
                phone = parseLevel(token.capabilities["phone"]),
                hsvRuntime = parseLevel(token.capabilities["hsi_runtime"] ?: token.capabilities["hsi"]),
                cloud = parseLevel(token.capabilities["cloud"])
            )
        }

        /// Parse capability level from string
        private fun parseLevel(level: String?): CapabilityLevel {
            return when (level?.lowercase()) {
                "core" -> CapabilityLevel.CORE
                "extended" -> CapabilityLevel.EXTENDED
                "research" -> CapabilityLevel.RESEARCH
                else -> CapabilityLevel.NONE
            }
        }

        /// Create default capabilities (core level for all modules)
        fun defaultCapabilities(): SDKCapabilities {
            return SDKCapabilities(
                behavior = CapabilityLevel.CORE,
                wear = CapabilityLevel.CORE,
                phone = CapabilityLevel.CORE,
                hsvRuntime = CapabilityLevel.CORE,
                cloud = CapabilityLevel.CORE
            )
        }
    }
}
