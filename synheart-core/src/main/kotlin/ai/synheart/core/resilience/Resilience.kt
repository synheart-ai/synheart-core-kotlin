// SPDX-License-Identifier: Apache-2.0
//
// Public types for the HRV-CV resilience score.
//
// Mirror of `synheart-core-flutter/lib/src/resilience/synheart_resilience.dart`
// and `synheart-core-swift/.../SynheartResilience.swift`. All field
// names are pinned to the runtime's `synheart-resilience` JSON shape.

package ai.synheart.core.resilience

/** One HRV sample at a point in time. */
data class HrvSample(val tsMs: Long, val rmssdMs: Double) {
    fun toJsonObject(): Map<String, Any> =
        mapOf("ts_ms" to tsMs, "rmssd_ms" to rmssdMs)
}

/** Half-open `[startMs, endMs)` interval. */
data class SleepWindow(val startMs: Long, val endMs: Long) {
    fun toJsonObject(): Map<String, Any> =
        mapOf("start_ms" to startMs, "end_ms" to endMs)
}

/** Tunable parameters. Defaults match the runtime's defaults. */
data class ResilienceConfig(
    val lookbackDays: Int = 7,
    val minDaysRequired: Int = 5,
    val minRrSamples: Int = 20,
    val cvCeilingPct: Double = 7.0,
    val cvFloorPct: Double = 40.0,
) {
    fun toJsonObject(): Map<String, Any> = mapOf(
        "lookback_days" to lookbackDays,
        "min_days_required" to minDaysRequired,
        "min_rr_samples" to minRrSamples,
        "cv_ceiling_pct" to cvCeilingPct,
        "cv_floor_pct" to cvFloorPct,
    )
}

/** Why a resilience score might not be available. */
enum class ResilienceReason {
    INSUFFICIENT_DAYS,
    NO_SLEEP_WINDOWS,
    INSUFFICIENT_SAMPLES,
    NO_VALID_SAMPLES,
    ZERO_MEAN_HRV;

    companion object {
        fun fromWire(raw: String?): ResilienceReason? = when (raw) {
            "InsufficientDays"     -> INSUFFICIENT_DAYS
            "NoSleepWindows"       -> NO_SLEEP_WINDOWS
            "InsufficientSamples"  -> INSUFFICIENT_SAMPLES
            "NoValidSamples"       -> NO_VALID_SAMPLES
            "ZeroMeanHrv"          -> ZERO_MEAN_HRV
            else                   -> null
        }
    }
}

/** Result returned by `compute()`. Mirrors the runtime's `ResilienceScoreResult`. */
data class ResilienceResult(
    val score: Int?,
    val rmssdOwMs: Double?,
    val sdnnOwMs: Double?,
    val hrvCvPct: Double?,
    val daysUsed: Int,
    val samplesUsed: Int,
    val confidence: Double,
    val reason: ResilienceReason?,
    val modelId: String,
    val pipelineVersion: String,
    val constantsHash: String,
)

/** Errors thrown by [SynheartResilience]. */
sealed class ResilienceError(message: String) : Exception(message) {
    object RuntimeUnavailable :
        ResilienceError("runtime resilience FFI not present (need native runtime 5.4.0+)")

    class ComputeFailed(detail: String) :
        ResilienceError("resilience compute failed: $detail")
}
