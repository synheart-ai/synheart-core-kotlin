package ai.synheart.core.modules.consent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Consent profile from consent service.
 */
@Serializable
data class ConsentProfile(
    /** Profile ID */
    val id: String,

    /** Profile name */
    val name: String,

    /** Profile description */
    val description: String,

    /** Consent channels configuration */
    val channels: ConsentChannels,

    /** Whether cloud upload is enabled */
    @SerialName("cloud")
    val cloudEnabled: Boolean = false,

    /** Whether vendor sync is enabled */
    val vendorSyncEnabled: Boolean = false,

    /** Whether this is the default profile */
    @SerialName("is_default")
    val isDefault: Boolean = false
)

/**
 * Consent channels configuration.
 */
@Serializable
data class ConsentChannels(
    /** Biosignals consent configuration */
    val biosignals: BiosignalsConsent = BiosignalsConsent(),

    /** Phone context consent configuration */
    @SerialName("phone_context")
    val phoneContext: PhoneContextConsent = PhoneContextConsent(),

    /** Behavior consent configuration */
    val behavior: BehaviorConsent = BehaviorConsent(),

    /** Interpretation consent configuration */
    val interpretation: InterpretationConsent = InterpretationConsent()
)

/**
 * Biosignals consent configuration.
 */
@Serializable
data class BiosignalsConsent(
    /** Consent for vitals (HR, HRV) */
    val vitals: Boolean = false,

    /** Consent for sleep data */
    val sleep: Boolean = false,

    /** Consent for advanced cardiac metrics (ECG morphology, arrhythmia flags) */
    @SerialName("cardio_advanced")
    val cardioAdvanced: Boolean = false,

    /** Consent for neuromuscular data (EMG, grip force) */
    val neuromuscular: Boolean = false,

    /** Consent for wearable motion data (accelerometer, gyroscope) */
    @SerialName("wearable_motion")
    val wearableMotion: Boolean = false
)

/**
 * Phone context consent configuration.
 */
@Serializable
data class PhoneContextConsent(
    /** Consent for device motion (accelerometer, gyroscope) */
    @SerialName("device_motion")
    val deviceMotion: Boolean = false,

    /** Consent for device context (locale, timezone, display metrics) */
    @SerialName("device_context")
    val deviceContext: Boolean = false,

    /** Consent for system state (screen on/off, charging, connectivity) */
    @SerialName("system_state")
    val systemState: Boolean = false
)

/**
 * Behavior consent configuration.
 */
@Serializable
data class BehaviorConsent(
    /** Consent for digital activity tracking (typing cadence, app usage) */
    @SerialName("digital_activity")
    val digitalActivity: Boolean = false,

    /** Consent for notification pattern analysis */
    @SerialName("notification_patterns")
    val notificationPatterns: Boolean = false,

    /** Consent for app context collection (foreground app category) */
    @SerialName("app_context")
    val appContext: Boolean = false
)

/**
 * Interpretation consent configuration.
 */
@Serializable
data class InterpretationConsent(
    /** Consent for focus estimation */
    @SerialName("focus_estimation")
    val focusEstimation: Boolean = false,

    /** Consent for emotion estimation */
    @SerialName("emotion_estimation")
    val emotionEstimation: Boolean = false
)
