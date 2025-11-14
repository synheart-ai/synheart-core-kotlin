package com.synheart.hsi.models

import kotlinx.serialization.Serializable

/**
 * Emotion state metrics
 */
@Serializable
data class EmotionState(
    val stress: Float, // 0.0 - 1.0
    val calm: Float, // 0.0 - 1.0
    val engagement: Float, // 0.0 - 1.0
    val activation: Float, // 0.0 - 1.0
    val valence: Float // -1.0 (negative) to 1.0 (positive)
) {
    init {
        require(stress in 0f..1f) { "stress must be between 0 and 1" }
        require(calm in 0f..1f) { "calm must be between 0 and 1" }
        require(engagement in 0f..1f) { "engagement must be between 0 and 1" }
        require(activation in 0f..1f) { "activation must be between 0 and 1" }
        require(valence in -1f..1f) { "valence must be between -1 and 1" }
    }
}

