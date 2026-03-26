package ai.synheart.core.models

import kotlinx.serialization.Serializable

/**
 * Behavioral metrics derived from user interactions
 */
@Serializable
data class BehaviorState(
    /** Typing speed (normalized 0.0-1.0) */
    val typingSpeed: Float? = null,
    /** Typing burstiness index (0.0-1.0) */
    val typingBurstiness: Float? = null,
    /** Scroll velocity (normalized 0.0-1.0) */
    val scrollVelocity: Float? = null,
    /** Idle gaps between interactions (seconds) */
    val idleGaps: Float? = null,
    /** App switch rate (normalized) */
    val appSwitchRate: Float? = null,
    /** Interaction intensity (normalized 0.0-1.0) */
    val interactionIntensity: Float? = null,
    /** Engagement level (normalized 0.0-1.0) */
    val engagementLevel: Float? = null
)
