package ai.synheart.core.modules.capabilities

import ai.synheart.core.modules.interfaces.CapabilityLevel
import ai.synheart.core.modules.interfaces.Module
import java.time.Instant

/** Capability token received from authentication service. */
data class CapabilityToken(
    val orgId: String,
    val projectId: String,
    val environment: String,
    val capabilities: Map<String, String>,
    val signature: String,
    val expiresAt: Instant,
    val issuedAt: Instant
) {
    val isExpired: Boolean
        get() = Instant.now().isAfter(expiresAt)

    val isValid: Boolean
        get() {
            val now = Instant.now()
            return !isExpired && now.isAfter(issuedAt) && expiresAt.isAfter(issuedAt)
        }
}

/** SDK capabilities parsed from token. */
data class SDKCapabilities(
    val behavior: CapabilityLevel,
    val wear: CapabilityLevel,
    val phone: CapabilityLevel,
    val hsvRuntime: CapabilityLevel,
    val cloud: CapabilityLevel
) {
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
        fun fromToken(token: CapabilityToken): SDKCapabilities {
            return SDKCapabilities(
                behavior = parseLevel(token.capabilities["behavior"]),
                wear = parseLevel(token.capabilities["wear"]),
                phone = parseLevel(token.capabilities["phone"]),
                hsvRuntime = parseLevel(token.capabilities["hsi_runtime"] ?: token.capabilities["hsi"]),
                cloud = parseLevel(token.capabilities["cloud"])
            )
        }

        private fun parseLevel(level: String?): CapabilityLevel {
            return when (level?.lowercase()) {
                "core" -> CapabilityLevel.CORE
                "extended" -> CapabilityLevel.EXTENDED
                "research" -> CapabilityLevel.RESEARCH
                else -> CapabilityLevel.NONE
            }
        }

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
