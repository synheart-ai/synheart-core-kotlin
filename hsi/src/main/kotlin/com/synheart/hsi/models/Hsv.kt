package com.synheart.hsi.models

import kotlinx.serialization.Serializable

/**
 * Human State Vector - the main data structure representing human state
 */
@Serializable
data class HumanStateVector(
    val timestamp: Long,
    val meta: MetaState,
    
    // Biosignals
    val heartRate: Float? = null, // BPM
    val hrv: Float? = null, // RMSSD in ms
    val hrvSdnn: Float? = null, // SDNN in ms
    
    // Derived metrics
    val hsiEmbedding: List<Float> = emptyList(), // Latent representation
    
    // State components
    val emotion: EmotionState? = null,
    val focus: FocusState? = null,
    val behavior: BehaviorState? = null,
    val context: ContextState? = null
)

/**
 * Metadata about the state vector
 */
@Serializable
data class MetaState(
    val device: DeviceInfo,
    val sessionId: String,
    val version: String = "1.0.0"
)

/**
 * Device information
 */
@Serializable
data class DeviceInfo(
    val platform: String = "Android",
    val osVersion: String,
    val model: String? = null,
    val manufacturer: String? = null
)

