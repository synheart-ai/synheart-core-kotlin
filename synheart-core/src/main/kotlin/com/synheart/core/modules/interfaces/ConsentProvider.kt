package com.synheart.core.modules.interfaces

import kotlinx.coroutines.flow.Flow
import java.time.Instant

/// Types of consent
enum class ConsentType {
    /// Consent for biosignal collection
    BIOSIGNALS,

    /// Consent for phone context collection (screen state, motion, app context)
    PHONE_CONTEXT,

    /// Consent for behavioral data collection
    BEHAVIOR,

    /// Consent for cloud uploads
    CLOUD_UPLOAD,

    /// Consent for focus estimation (interpretation module)
    FOCUS_ESTIMATION,

    /// Consent for emotion estimation (interpretation module)
    EMOTION_ESTIMATION,

    /// Consent for Syni personalization
    SYNI
}

/// Snapshot of user consent at a point in time
data class ConsentSnapshot(
    /// Consent for biosignal collection
    val biosignals: Boolean,

    /// Consent for phone context collection
    val phoneContext: Boolean,

    /// Consent for behavioral data collection
    val behavior: Boolean,

    /// Consent for cloud uploads
    val cloudUpload: Boolean,

    /// Consent for focus estimation
    val focusEstimation: Boolean,

    /// Consent for emotion estimation
    val emotionEstimation: Boolean,

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
            ConsentType.PHONE_CONTEXT -> phoneContext
            ConsentType.BEHAVIOR -> behavior
            ConsentType.CLOUD_UPLOAD -> cloudUpload
            ConsentType.FOCUS_ESTIMATION -> focusEstimation
            ConsentType.EMOTION_ESTIMATION -> emotionEstimation
            ConsentType.SYNI -> syni
        }
    }

    /// Create a copy with updated values
    fun copyWith(
        biosignals: Boolean? = null,
        phoneContext: Boolean? = null,
        behavior: Boolean? = null,
        cloudUpload: Boolean? = null,
        focusEstimation: Boolean? = null,
        emotionEstimation: Boolean? = null,
        syni: Boolean? = null,
        timestamp: Instant? = null,
        version: String? = null
    ): ConsentSnapshot {
        return ConsentSnapshot(
            biosignals = biosignals ?: this.biosignals,
            phoneContext = phoneContext ?: this.phoneContext,
            behavior = behavior ?: this.behavior,
            cloudUpload = cloudUpload ?: this.cloudUpload,
            focusEstimation = focusEstimation ?: this.focusEstimation,
            emotionEstimation = emotionEstimation ?: this.emotionEstimation,
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
                phoneContext = false,
                behavior = false,
                cloudUpload = false,
                focusEstimation = false,
                emotionEstimation = false,
                syni = false
            )
        }

        /// Create a consent snapshot with all consents granted
        fun all(): ConsentSnapshot {
            return ConsentSnapshot(
                biosignals = true,
                phoneContext = true,
                behavior = true,
                cloudUpload = true,
                focusEstimation = true,
                emotionEstimation = true,
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
