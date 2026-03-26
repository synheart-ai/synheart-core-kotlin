package ai.synheart.core.modules.consent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

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
    val sleep: Boolean = false
)

/**
 * Phone context consent configuration.
 */
@Serializable
data class PhoneContextConsent(
    /** Consent for motion data */
    val motion: Boolean = false,

    /** Consent for screen state */
    val screenState: Boolean = false
)

/**
 * Behavior consent configuration.
 */
@Serializable
data class BehaviorConsent(
    /** Consent for behavior tracking */
    val enabled: Boolean = false
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
