package com.synheart.core.models

import kotlinx.serialization.Serializable

/**
 * Focus state metrics
 */
@Serializable
data class FocusState(
    val score: Float, // 0.0 - 1.0, overall focus score
    val cognitiveLoad: Float, // 0.0 - 1.0, higher = more load
    val clarity: Float, // 0.0 - 1.0, mental clarity
    val distraction: Float // 0.0 - 1.0, level of distraction
) {
    init {
        require(score in 0f..1f) { "score must be between 0 and 1" }
        require(cognitiveLoad in 0f..1f) { "cognitiveLoad must be between 0 and 1" }
        require(clarity in 0f..1f) { "clarity must be between 0 and 1" }
        require(distraction in 0f..1f) { "distraction must be between 0 and 1" }
    }
}

