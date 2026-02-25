package com.synheart.core.heads

import com.synheart.core.models.EmotionState
import com.synheart.core.models.HumanStateVector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map

/**
 * Emotion Head — DEPRECATED.
 *
 * Emotion values now come from synheart-runtime via `RuntimeBridge.lastHsv()`.
 * The runtime computes emotion (valence, arousal) with confidence and full
 * inference metadata as part of the canonical 6-head HSV output.
 *
 * This class is retained only for API compatibility. It passes HSVs through
 * unchanged and will be removed in a future release.
 */
@Deprecated(
    message = "Use RuntimeBridge.lastHsv() — emotion is computed by synheart-runtime",
    level = DeprecationLevel.WARNING
)
class EmotionHead(
    private val emotionModel: EmotionModel? = null
) {
    /** Pass-through: HSVs are forwarded unchanged. */
    fun process(baseHsvFlow: Flow<HumanStateVector?>): Flow<HumanStateVector> {
        return baseHsvFlow
            .map { hsv -> hsv }
            .filterNotNull()
    }

    /** Pass-through: HSV is returned unchanged. */
    fun processOne(hsv: HumanStateVector): HumanStateVector = hsv
}

/**
 * Interface for emotion prediction models — DEPRECATED.
 *
 * Emotion inference is now performed by synheart-runtime. Use
 * `RuntimeBridge.lastHsv()` to access emotion values.
 */
@Deprecated(
    message = "Use RuntimeBridge.lastHsv() — emotion is computed by synheart-runtime",
    level = DeprecationLevel.WARNING
)
interface EmotionModel {
    fun predict(features: Map<String, Float>): EmotionState
}
