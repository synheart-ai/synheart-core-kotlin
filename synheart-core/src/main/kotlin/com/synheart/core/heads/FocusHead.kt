package com.synheart.core.heads

import com.synheart.core.models.FocusState
import com.synheart.core.models.HumanStateVector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Focus Head — DEPRECATED.
 *
 * Focus values now come from synheart-runtime via `RuntimeBridge.lastHsv()`.
 * The runtime computes focus with confidence and full inference metadata as
 * part of the canonical 6-head HSV output.
 *
 * This class is retained only for API compatibility. It passes HSVs through
 * unchanged and will be removed in a future release.
 */
@Deprecated(
    message = "Use RuntimeBridge.lastHsv() — focus is computed by synheart-runtime",
    level = DeprecationLevel.WARNING
)
class FocusHead(
    private val focusModel: FocusModel? = null
) {
    /** Pass-through: HSVs are forwarded unchanged. */
    fun process(hsvWithEmotionFlow: Flow<HumanStateVector>): Flow<HumanStateVector> {
        return hsvWithEmotionFlow.map { it }
    }

    /** Pass-through: HSV is returned unchanged. */
    fun processOne(hsv: HumanStateVector): HumanStateVector = hsv
}

/**
 * Interface for focus prediction models — DEPRECATED.
 *
 * Focus inference is now performed by synheart-runtime. Use
 * `RuntimeBridge.lastHsv()` to access focus values.
 */
@Deprecated(
    message = "Use RuntimeBridge.lastHsv() — focus is computed by synheart-runtime",
    level = DeprecationLevel.WARNING
)
interface FocusModel {
    fun predict(features: Map<String, Float>): FocusState
}
