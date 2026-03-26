package ai.synheart.core.models

import kotlinx.serialization.Serializable

/**
 * A single HSV axis reading: optional score with associated confidence.
 *
 * Mirrors Rust `HsvAxisValue { score: Option<f32>, confidence: f32 }` from
 * synheart-runtime. Score is null when the signal is unavailable;
 * confidence reflects measurement quality independent of the score value.
 */
@Serializable
data class HsvAxisValue(
    /** Normalized score in [0.0, 1.0], or null if signal unavailable. */
    val score: Float? = null,
    /** Confidence in the reading [0.0, 1.0]. */
    val confidence: Float = 0f
) {
    /** Whether a usable score is present. */
    val isPresent: Boolean get() = score != null

    companion object {
        /** An absent axis value with zero confidence. */
        val ABSENT = HsvAxisValue(score = null, confidence = 0f)
    }
}

/**
 * Physiology domain of the Human State Vector.
 *
 * Contains all wearable-derived physiological readings, each paired with
 * a confidence score. Mirrors Rust `PhysiologyState` from synheart-runtime.
 *
 * Populated by wearable adapters (WHOOP, Garmin, etc.) via the biosignal
 * pipeline. Emotion and Focus heads in external SDKs (synheart-emotion,
 * synheart-focus) may consume these values but do NOT populate them.
 */
@Serializable
data class PhysiologyState(
    /** Sleep efficiency (0–1). Higher is better. */
    val sleepEfficiency: HsvAxisValue = HsvAxisValue.ABSENT,
    /** Recovery score (0–1). Higher indicates better recovery. */
    val recoveryScore: HsvAxisValue = HsvAxisValue.ABSENT,
    /** HRV deviation from personal baseline (0–1, 0.5 = at baseline). */
    val hrvDeviation: HsvAxisValue = HsvAxisValue.ABSENT,
    /** Resting heart rate deviation from baseline (0–1, 0.5 = at baseline). */
    val rhrDeviation: HsvAxisValue = HsvAxisValue.ABSENT,
    /** Respiratory rate normalized (0–1). */
    val respiratoryRate: HsvAxisValue = HsvAxisValue.ABSENT,
    /** Blood oxygen saturation normalized (0–1). */
    val spo2: HsvAxisValue = HsvAxisValue.ABSENT,
    /** Physical strain / exertion (0–1). Higher means more strain. */
    val strain: HsvAxisValue = HsvAxisValue.ABSENT,
    /** Sleep duration ratio vs. target (0–1). */
    val sleepDuration: HsvAxisValue = HsvAxisValue.ABSENT,
    /** Deep sleep ratio (0–1). */
    val deepSleepRatio: HsvAxisValue = HsvAxisValue.ABSENT,
    /** REM sleep ratio (0–1). */
    val remSleepRatio: HsvAxisValue = HsvAxisValue.ABSENT,
    /** Sleep fragmentation index (0–1). Higher means more fragmented. */
    val sleepFragmentation: HsvAxisValue = HsvAxisValue.ABSENT
) {
    /** Count of axes that have a present (non-null) score. */
    val presentCount: Int get() = allAxes.count { it.isPresent }

    /** All axis values in declaration order. */
    val allAxes: List<HsvAxisValue> get() = listOf(
        sleepEfficiency, recoveryScore, hrvDeviation, rhrDeviation,
        respiratoryRate, spo2, strain, sleepDuration, deepSleepRatio,
        remSleepRatio, sleepFragmentation
    )

    companion object {
        val EMPTY = PhysiologyState()
    }
}
