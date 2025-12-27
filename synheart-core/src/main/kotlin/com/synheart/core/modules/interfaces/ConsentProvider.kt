package com.synheart.core.modules.interfaces

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/// Types of consent
enum class ConsentType {
    /// Consent for biosignal collection
    BIOSIGNALS,

    /// Consent for behavioral data collection
    BEHAVIOR,

    /// Consent for motion/context data collection
    MOTION,

    /// Consent for cloud uploads
    CLOUD_UPLOAD,

    /// Consent for Syni personalization
    SYNI
}

/// Snapshot of user consent at a point in time
data class ConsentSnapshot(
    /// Consent for biosignal collection
    val biosignals: Boolean,

    /// Consent for behavioral data collection
    val behavior: Boolean,

    /// Consent for motion/context data collection
    val motion: Boolean,

    /// Consent for cloud uploads
    val cloudUpload: Boolean,

    /// Consent for Syni personalization
    val syni: Boolean,

    /// Timestamp when this consent was given
    val timestamp: Instant = Instant.now(),

    /// Schema version for this consent snapshot
    val version: String = "1.0.0"
) {
    /// Check if a specific consent type is allowed
    fun allows(type: ConsentType): Boolean {
        return when (type) {
            ConsentType.BIOSIGNALS -> biosignals
            ConsentType.BEHAVIOR -> behavior
            ConsentType.MOTION -> motion
            ConsentType.CLOUD_UPLOAD -> cloudUpload
            ConsentType.SYNI -> syni
        }
    }

    /// Create a copy with updated values
    fun copyWith(
        biosignals: Boolean? = null,
        behavior: Boolean? = null,
        motion: Boolean? = null,
        cloudUpload: Boolean? = null,
        syni: Boolean? = null,
        timestamp: Instant? = null,
        version: String? = null
    ): ConsentSnapshot {
        return ConsentSnapshot(
            biosignals = biosignals ?: this.biosignals,
            behavior = behavior ?: this.behavior,
            motion = motion ?: this.motion,
            cloudUpload = cloudUpload ?: this.cloudUpload,
            syni = syni ?: this.syni,
            timestamp = timestamp ?: this.timestamp,
            version = version ?: this.version
        )
    }

    companion object {
        /// Create a consent snapshot with all consents denied
        fun none(): ConsentSnapshot {
            return ConsentSnapshot(
                biosignals = false,
                behavior = false,
                motion = false,
                cloudUpload = false,
                syni = false
            )
        }

        /// Create a consent snapshot with all consents granted
        fun all(): ConsentSnapshot {
            return ConsentSnapshot(
                biosignals = true,
                behavior = true,
                motion = true,
                cloudUpload = true,
                syni = true
            )
        }
    }
}

/// Provider interface for consent management
interface ConsentProvider {
    /// Get the current consent snapshot
    fun current(): ConsentSnapshot

    /// Observe consent changes
    fun observe(): Flow<ConsentSnapshot>

    /// Update consent (internal use)
    suspend fun updateConsent(newConsent: ConsentSnapshot)
}
