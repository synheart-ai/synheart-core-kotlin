package com.synheart.core.heads

import com.synheart.core.models.EmotionState
import com.synheart.core.models.HumanStateVector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * Emotion Head: Populates emotion state in HSV.
 *
 * Delegates inference to an external [EmotionModel] implementation, which is
 * provided by the synheart-emotion SDK (synheart-emotion-kotlin). The Core SDK
 * does NOT perform emotion inference itself — it only orchestrates the pipeline:
 *
 * ```
 * Base HSV → EmotionHead.process() → [EmotionModel.predict()] → HSV + emotion
 * ```
 *
 * To integrate:
 * 1. Add synheart-emotion-kotlin as a dependency
 * 2. Pass its EmotionModel implementation to EmotionHead constructor
 * 3. EmotionHead extracts features from HSV and delegates to the model
 */
class EmotionHead(
    private val emotionModel: EmotionModel? = null
) {
    /**
     * Process a flow of base HSVs, adding emotion state from the external model.
     * If no model is configured, HSVs pass through unchanged.
     */
    fun process(baseHsvFlow: Flow<HumanStateVector?>): Flow<HumanStateVector> {
        return baseHsvFlow
            .map { hsv ->
                hsv ?: return@map null
                if (emotionModel == null) return@map hsv

                val features = extractEmotionFeatures(hsv)
                val emotion = emotionModel.predict(features)
                hsv.copy(emotion = emotion)
            }
            .filterNotNull()
    }

    /**
     * Process a single base HSV and add emotion state.
     * Returns the HSV unchanged if no model is configured.
     */
    fun processOne(hsv: HumanStateVector): HumanStateVector {
        if (emotionModel == null) return hsv

        val features = extractEmotionFeatures(hsv)
        val emotion = emotionModel.predict(features)
        return hsv.copy(emotion = emotion)
    }

    /**
     * Extract features from HSV for emotion prediction.
     * Includes physiology (from wearable), behavioral, and context signals.
     */
    private fun extractEmotionFeatures(hsv: HumanStateVector): Map<String, Float> {
        return buildMap {
            // HSI embedding
            hsv.hsiEmbedding.forEachIndexed { index, value ->
                put("embedding_$index", value)
            }

            // Heart rate features (raw biosignals)
            hsv.heartRate?.let { put("heart_rate", it) }
            hsv.hrv?.let { put("hrv", it) }
            hsv.hrvSdnn?.let { put("hrv_sdnn", it) }

            // Physiology features (from PhysiologyState — synheart-runtime)
            hsv.physiology.recoveryScore.score?.let { put("recovery_score", it) }
            hsv.physiology.sleepEfficiency.score?.let { put("sleep_efficiency", it) }
            hsv.physiology.hrvDeviation.score?.let { put("hrv_deviation", it) }
            hsv.physiology.strain.score?.let { put("strain", it) }

            // Behavioral features
            hsv.behavior?.let { behavior ->
                behavior.typingSpeed?.let { put("typing_speed", it) }
                behavior.typingBurstiness?.let { put("typing_burstiness", it) }
                behavior.scrollVelocity?.let { put("scroll_velocity", it) }
                behavior.appSwitchRate?.let { put("app_switch_rate", it) }
                behavior.interactionIntensity?.let { put("interaction_intensity", it) }
                behavior.engagementLevel?.let { put("engagement_level", it) }
            }

            // Context features
            hsv.context?.let { context ->
                context.conversation?.isActive?.let { put("conversation_active", if (it) 1f else 0f) }
                context.device?.batteryLevel?.let { put("battery_level", it) }
                context.device?.isCharging?.let { put("is_charging", if (it) 1f else 0f) }
            }
        }
    }
}

/**
 * Interface for emotion prediction models.
 *
 * Implemented by synheart-emotion-kotlin SDK. The Core SDK provides this
 * interface; the emotion SDK provides the implementation.
 *
 * ```kotlin
 * // In your app, wire up the external SDK:
 * val emotionModel: EmotionModel = SynheartEmotionModel(context)
 * val emotionHead = EmotionHead(emotionModel)
 * ```
 */
interface EmotionModel {
    /**
     * Predict emotion state from extracted HSV features.
     * @param features Feature map extracted from HSV by EmotionHead
     * @return Predicted emotion state
     */
    fun predict(features: Map<String, Float>): EmotionState
}
