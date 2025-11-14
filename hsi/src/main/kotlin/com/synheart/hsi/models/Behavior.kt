package com.synheart.hsi.models

import kotlinx.serialization.Serializable

/**
 * Behavioral metrics derived from user interactions
 */
@Serializable
data class BehaviorState(
    val typingSpeed: Float? = null, // Characters per second
    val typingBurstiness: Float? = null, // Burstiness index
    val scrollingVelocity: Float? = null, // Pixels per second
    val appSwitchFrequency: Float? = null, // Switches per minute
    val interactionIntensity: Float? = null, // Normalized 0-1
    val engagementLevel: Float? = null // Normalized 0-1
)

