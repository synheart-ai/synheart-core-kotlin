package com.synheart.core.models

import kotlinx.serialization.Serializable

/**
 * Quality flags that can be raised on the HSV.
 */
enum class HsvQualityFlag {
    /** At least one modality has stale data. */
    STALE_DATA,
    /** Only a single modality contributed (low fusion diversity). */
    SINGLE_MODALITY,
    /** Baselines have not yet converged (insufficient history). */
    LOW_BASELINE_HISTORY,
    /** Sensor data had gaps or interpolation was needed. */
    SENSOR_GAPS
}

/**
 * Aggregated quality assessment for the Human State Vector.
 *
 * Mirrors Rust `StateQuality` from synheart-runtime.
 * Summarizes the reliability and completeness of the fused state.
 * Downstream consumers can use [overallConfidence]
 * and [degraded] to decide whether to export or gate readings.
 */
@Serializable
data class StateQuality(
    /** Overall confidence in the fused HSV, [0.0, 1.0]. */
    val overallConfidence: Float = 0f,
    /** Number of modalities that contributed data (0–3). */
    val modalityCount: Int = 0,
    /** True when quality is below acceptable thresholds. */
    val degraded: Boolean = true,
    /** Set of active quality flags. */
    val qualityFlags: Set<HsvQualityFlag> = emptySet()
) {
    companion object {
        val EMPTY = StateQuality()
    }
}
