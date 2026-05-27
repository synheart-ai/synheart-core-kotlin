package ai.synheart.core.modules.interfaces

import ai.synheart.core.modules.consent.ConsentChannels
import kotlinx.coroutines.flow.Flow
import java.time.Instant

enum class ConsentTier {
    LOCAL,
    CLOUD,
    RESEARCH
}

enum class ConsentType {
    BIOSIGNALS,
    PHONE_CONTEXT,
    BEHAVIOR,
    CLOUD_UPLOAD,
    FOCUS_ESTIMATION,
    EMOTION_ESTIMATION,
    SYNI,
    VENDOR_SYNC
}

/** Snapshot of user consent at a point in time. */
data class ConsentSnapshot(
    val biosignals: Boolean,
    val phoneContext: Boolean,
    val behavior: Boolean,
    val cloudUpload: Boolean,
    val focusEstimation: Boolean,
    val emotionEstimation: Boolean,
    val syni: Boolean,
    val vendorSync: Boolean = false,
    val tier: ConsentTier = ConsentTier.LOCAL,
    val channels: ConsentChannels? = null,
    val timestamp: Instant = Instant.now(),
    val version: String = "1.0.0"
) {
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

    /**
     * Check if a specific granular channel is allowed.
     *
     * If [channels] is non-null, looks up the channel in the granular map.
     * Otherwise falls back to the module-level boolean based on channel prefix.
     * Channel format: "biosignals.vitals", "behavior.digital_activity", etc.
     */
    fun allowsChannel(channel: String): Boolean {
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

/** Provider interface for consent management. */
interface ConsentProvider {
    fun current(): ConsentSnapshot
    fun observe(): Flow<ConsentSnapshot>
    suspend fun updateConsent(newConsent: ConsentSnapshot)
}
