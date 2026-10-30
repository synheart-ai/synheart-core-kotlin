// SPDX-License-Identifier: Apache-2.0
//
// Typed Kotlin models for the daily readiness scorer. Mirrors the JSON
// shapes of the Synheart Runtime's `ReadinessScore` computation (Readiness
// `model_version` 3.0.0, runtime 0.45.0+). snake_case wire keys for
// cross-language portability.
//
// Readiness is the decision layer that runs after Sleep and Recovery for the
// day, and answers:
//
//     "How much demand can the user take today?"
//
// It blends Recovery (0.50), Sleep (0.25) and how far overnight HR / HRV sit
// from the person's baseline (0.25). Without overnight physiology the
// alignment weight moves onto Recovery and Sleep and confidence drops.

package ai.synheart.core.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * Discrete readiness band. The Kotlin names are the action each band
 * recommends; [wire] is the engine's name for the band.
 */
enum class ReadinessBand(val wire: String, val label: String, val recommendation: String) {
    /** 0–39, "Low Readiness". */
    REST("low", "Rest", "Rest, reduce difficulty, avoid high strain"),

    /** 40–59, "Reduced Readiness". */
    LIGHT("reduced", "Light", "Light activity, lower cognitive load, more breaks"),

    /** 60–79, "Normal Readiness". */
    NORMAL("normal", "Normal", "Standard task / training schedule"),

    /** 80–100, "High Readiness". */
    PUSH("high", "Push", "You may tolerate higher demand");

    companion object {
        /**
         * The band for [s], or the band [score] falls in when [s] is missing or
         * unknown — the engine derives the band from the score with the same
         * breakpoints, so this never invents one.
         */
        fun fromWire(s: String?, score: Int): ReadinessBand =
            values().firstOrNull { it.wire == s } ?: fromScore(score)

        /** The band a `0..100` score falls in (breakpoints 40 / 60 / 80). */
        fun fromScore(score: Int): ReadinessBand = when {
            score < 40 -> REST
            score < 60 -> LIGHT
            score < 80 -> NORMAL
            else -> PUSH
        }
    }
}

/** Discrete reasons surfaced for "why is your readiness X?" panels. */
enum class ReadinessFactor(val wire: String) {
    STRONG_RECOVERY("strong_recovery"),
    LOW_RECOVERY("low_recovery"),
    POOR_SLEEP("poor_sleep"),
    GOOD_SLEEP("good_sleep"),
    HRV_ABOVE_BASELINE("hrv_above_baseline"),
    HRV_BELOW_BASELINE("hrv_below_baseline"),
    RESTING_HR_ELEVATED("resting_hr_elevated"),

    /** The HR/HRV baseline is immature or absent. */
    BASELINE_IMMATURE("baseline_immature"),
    RECOVERY_COLD_START("recovery_cold_start"),
    SLEEP_COLD_START("sleep_cold_start"),

    /** No overnight HR/HRV: readiness is a Recovery + Sleep blend. */
    SCORES_ONLY("scores_only");

    companion object {
        fun fromWire(s: String?): ReadinessFactor? =
            values().firstOrNull { it.wire == s }
    }
}

/**
 * Overnight physiology against the person's baselines, for the
 * baseline-alignment term. All four values are required; pass no
 * [OvernightAlignment] at all when any is unavailable.
 */
data class OvernightAlignment(
    /** Mean HR during sleep (bpm). */
    val hrSleepBpm: Double,
    /** Personal resting HR baseline (bpm). */
    val hrBaseBpm: Double,
    /** Mean RMSSD during sleep (ms). */
    val rmssdSleepMs: Double,
    /** Personal RMSSD baseline (ms). */
    val rmssdBaseMs: Double,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("hr_sleep_bpm", hrSleepBpm)
        put("hr_base_bpm", hrBaseBpm)
        put("rmssd_sleep_ms", rmssdSleepMs)
        put("rmssd_base_ms", rmssdBaseMs)
    }
}

/**
 * Top-level input bundle. Recovery and Sleep are both required: readiness
 * runs after both are known for the day.
 */
data class ReadinessScoreInput(
    /** Today's Recovery Score, `0..100`. */
    val recoveryScore: Int,
    /** Today's Sleep Score, `0..100`. */
    val sleepScore: Int,
    /** Overnight HR/HRV against baselines, or null when either is missing. */
    val alignment: OvernightAlignment? = null,
    /** Today's Recovery Score is a cold-start estimate (Recovery stage 1). */
    val recoveryColdStart: Boolean = false,
    /** Today's Sleep Score is a cold-start estimate (Sleep mode 1). */
    val sleepColdStart: Boolean = false,
    /**
     * Maturity of the overnight HR/HRV baselines, `0..1`: the lower of the
     * two confidences in `Synheart.dailyBaselineRefs` (see
     * [BaselineRefs.maturity]), or `0` when it returns null. At or below
     * `0.30` the baseline counts as immature.
     */
    val baselineMaturity: Double = 0.0,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("recovery_score", recoveryScore.coerceIn(0, 100))
        put("sleep_score", sleepScore.coerceIn(0, 100))
        put("alignment", alignment?.toJson() ?: JSONObject.NULL)
        put("recovery_cold_start", recoveryColdStart)
        put("sleep_cold_start", sleepColdStart)
        put(
            "baseline_maturity",
            if (baselineMaturity.isFinite()) baselineMaturity.coerceIn(0.0, 1.0) else 0.0,
        )
    }

    fun toJsonString(): String = toJson().toString()
}

/**
 * Per-component breakdown, every value on `0..100`. The alignment values
 * are null when overnight HR/HRV or a baseline was unavailable, so "not
 * measured" never reads as a mid-range score.
 */
data class ReadinessComponents(
    val recovery: Double,
    val sleep: Double,
    val baselineAlignment: Double? = null,
    val hrAlignment: Double? = null,
    val hrvAlignment: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("recovery", recovery)
        put("sleep", sleep)
        put("baseline_alignment", baselineAlignment ?: JSONObject.NULL)
        put("hr_alignment", hrAlignment ?: JSONObject.NULL)
        put("hrv_alignment", hrvAlignment ?: JSONObject.NULL)
    }

    companion object {
        fun fromJson(json: JSONObject): ReadinessComponents = ReadinessComponents(
            recovery = json.optDouble("recovery", 0.0),
            sleep = json.optDouble("sleep", 0.0),
            baselineAlignment = optDouble(json, "baseline_alignment"),
            hrAlignment = optDouble(json, "hr_alignment"),
            hrvAlignment = optDouble(json, "hrv_alignment"),
        )
    }
}

/** Canonical output of the Readiness Score scorer. */
data class ReadinessScoreResult(
    val score: Int,
    val band: ReadinessBand,
    /** The Recovery Score the result was computed from. */
    val recoveryAnchor: Int,
    /** The Sleep Score the result was computed from. */
    val sleepAnchor: Int,
    val components: ReadinessComponents,
    val confidence: Double,
    val explanation: List<ReadinessFactor> = emptyList(),
    val modelId: String,
    val modelVersion: String,
    val pipelineVersion: String,
) {
    /** The engine's JSON shape, for persisting a result and reading it back with [fromJson]. */
    fun toJson(): JSONObject = JSONObject().apply {
        put("score", score)
        put("band", band.wire)
        put("recovery_anchor", recoveryAnchor)
        put("sleep_anchor", sleepAnchor)
        put("components", components.toJson())
        put("confidence", confidence)
        put("explanation", JSONArray(explanation.map { it.wire }))
        put("model_id", modelId)
        put("model_version", modelVersion)
        put("pipeline_version", pipelineVersion)
    }

    companion object {
        fun fromJson(json: JSONObject): ReadinessScoreResult {
            val rawExpl = json.optJSONArray("explanation") ?: JSONArray()
            val factors = mutableListOf<ReadinessFactor>()
            for (i in 0 until rawExpl.length()) {
                val e = rawExpl.optString(i, null) ?: continue
                ReadinessFactor.fromWire(e)?.let { factors.add(it) }
            }
            val score = json.optInt("score", 0)
            return ReadinessScoreResult(
                score = score,
                band = ReadinessBand.fromWire(json.optString("band", null), score),
                recoveryAnchor = json.optInt("recovery_anchor", 0),
                sleepAnchor = json.optInt("sleep_anchor", 0),
                components = ReadinessComponents.fromJson(
                    json.optJSONObject("components") ?: JSONObject()
                ),
                confidence = json.optDouble("confidence", 0.0),
                explanation = factors,
                modelId = json.optString("model_id", ""),
                modelVersion = json.optString("model_version", ""),
                pipelineVersion = json.optString("pipeline_version", ""),
            )
        }

        fun fromJsonString(s: String): ReadinessScoreResult = fromJson(JSONObject(s))
    }
}
