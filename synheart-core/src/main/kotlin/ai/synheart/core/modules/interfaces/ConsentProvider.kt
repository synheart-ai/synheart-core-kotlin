package ai.synheart.core.modules.interfaces

import ai.synheart.core.modules.consent.ConsentChannels
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/// Consent tier level
enum class ConsentTier {
    /// Local-only processing, no cloud upload
    LOCAL,

    /// Cloud upload enabled
    CLOUD,

    /// Research-grade data sharing
    RESEARCH
}

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
    SYNI,

    /// Consent for vendor-side wearable sync (RAMEN connection)
    VENDOR_SYNC
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

    /// Consent for vendor-side wearable sync (RAMEN connection)
    val vendorSync: Boolean = false,

    /// Consent tier level
    val tier: ConsentTier = ConsentTier.LOCAL,

    /// Granular channel-level consent (optional; falls back to module booleans when null)
    val channels: ConsentChannels? = null,

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
            ConsentType.VENDOR_SYNC -> vendorSync
        }
    }

    /// Check if a specific granular channel is allowed.
    /// If [channels] is non-null, looks up the channel in the granular map.
    /// Otherwise falls back to the module-level boolean based on channel prefix.
    /// Channel format: "biosignals.vitals", "behavior.digital_activity", etc.
    fun allowsChannel(channel: String): Boolean {
        // If granular channels are present, check them directly
        if (channels != null) {
            val prefix = channel.substringBefore(".")
            val sub = channel.substringAfter(".", "")
            return when (prefix) {
                "biosignals" -> when (sub) {
                    "vitals" -> channels.biosignals.vitals
                    "sleep" -> channels.biosignals.sleep
                    "cardio_advanced" -> channels.biosignals.cardioAdvanced
                    "neuromuscular" -> channels.biosignals.neuromuscular
                    "wearable_motion" -> channels.biosignals.wearableMotion
                    else -> false
                }
                "phone_context" -> when (sub) {
                    "device_motion" -> channels.phoneContext.deviceMotion
                    "device_context" -> channels.phoneContext.deviceContext
                    "system_state" -> channels.phoneContext.systemState
                    else -> false
                }
                "behavior" -> when (sub) {
                    "digital_activity" -> channels.behavior.digitalActivity
                    "notification_patterns" -> channels.behavior.notificationPatterns
                    "app_context" -> channels.behavior.appContext
                    else -> false
                }
                "interpretation" -> when (sub) {
                    "focus_estimation" -> channels.interpretation.focusEstimation
                    "emotion_estimation" -> channels.interpretation.emotionEstimation
                    else -> false
                }
                else -> false
            }
        }

        // Fallback to module-level booleans based on channel prefix
        val prefix = channel.substringBefore(".")
        return when (prefix) {
            "biosignals" -> biosignals
            "phone_context" -> phoneContext
            "behavior" -> behavior
            "interpretation" -> {
                val sub = channel.substringAfter(".", "")
                when (sub) {
                    "focus_estimation" -> focusEstimation
                    "emotion_estimation" -> emotionEstimation
                    else -> false
                }
            }
            else -> false
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
        vendorSync: Boolean? = null,
        tier: ConsentTier? = null,
        channels: ConsentChannels? = this.channels,
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
            vendorSync = vendorSync ?: this.vendorSync,
            tier = tier ?: this.tier,
            channels = channels,
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
                syni = false,
                vendorSync = false
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
                syni = true,
                vendorSync = true
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
