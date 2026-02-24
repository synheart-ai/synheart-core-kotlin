package com.synheart.core.heads

import com.synheart.core.models.FocusState
import com.synheart.core.models.HumanStateVector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Focus Head: Populates focus state in HSV.
 *
 * Delegates inference to an external [FocusModel] implementation, which is
 * provided by the synheart-focus SDK (synheart-focus-kotlin). The Core SDK
 * does NOT perform focus inference itself — it only orchestrates the pipeline:
 *
 * ```
 * HSV + emotion → FocusHead.process() → [FocusModel.predict()] → HSV + focus
 * ```
 *
 * To integrate:
 * 1. Add synheart-focus-kotlin as a dependency
 * 2. Pass its FocusModel implementation to FocusHead constructor
 * 3. FocusHead extracts features from HSV (including emotion) and delegates to the model
 */
class FocusHead(
    private val focusModel: FocusModel? = null
) {
    /**
     * Process a flow of HSVs (with emotion), adding focus state from the external model.
     * If no model is configured, HSVs pass through unchanged.
     */
    fun process(hsvWithEmotionFlow: Flow<HumanStateVector>): Flow<HumanStateVector> {
        return hsvWithEmotionFlow.map { hsv ->
            if (focusModel == null) return@map hsv

            val features = extractFocusFeatures(hsv)
            val focus = focusModel.predict(features)
            hsv.copy(focus = focus)
        }
    }

    /**
     * Process a single HSV (with emotion) and add focus state.
     * Returns the HSV unchanged if no model is configured.
     */
    fun processOne(hsv: HumanStateVector): HumanStateVector {
        if (focusModel == null) return hsv

        val features = extractFocusFeatures(hsv)
        val focus = focusModel.predict(features)
        return hsv.copy(focus = focus)
    }

    /**
     * Extract features from HSV for focus prediction.
     * Includes emotion state, physiology, behavioral, and context signals.
     */
    private fun extractFocusFeatures(hsv: HumanStateVector): Map<String, Float> {
        return buildMap {
            // HSI embedding
            hsv.hsiEmbedding.forEachIndexed { index, value ->
                put("embedding_$index", value)
            }

            // Emotion features (from Emotion Head)
            hsv.emotion?.let { emotion ->
                put("stress", emotion.stress)
                put("calm", emotion.calm)
                put("engagement", emotion.engagement)
                put("activation", emotion.activation)
                put("valence", emotion.valence)
            }

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
                context.device?.screenOn?.let { put("screen_on", if (it) 1f else 0f) }
            }
        }
    }
}

/**
 * Interface for focus prediction models.
 *
 * Implemented by synheart-focus-kotlin SDK. The Core SDK provides this
 * interface; the focus SDK provides the implementation.
 *
 * ```kotlin
 * // In your app, wire up the external SDK:
 * val focusModel: FocusModel = SynheartFocusModel(context)
 * val focusHead = FocusHead(focusModel)
 * ```
 */
interface FocusModel {
    /**
     * Predict focus state from extracted HSV features.
     * @param features Feature map extracted from HSV by FocusHead
     * @return Predicted focus state
     */
    fun predict(features: Map<String, Float>): FocusState
}
