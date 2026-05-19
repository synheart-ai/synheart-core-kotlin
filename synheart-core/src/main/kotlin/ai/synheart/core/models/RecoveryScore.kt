// SPDX-License-Identifier: Apache-2.0
//
// Typed Kotlin models for the RFC-RECOVERY-SCORE-0001 daily scorer.
// Mirrors the JSON shapes produced by the Synheart Runtime's
// `RecoveryScore` computation. snake_case wire keys for cross-language
// portability.
//
// Three-stage scoring per RFC §"Adaptation Logic":
//   - Stage 1 (FirstDay):     1 night of sleep + (HR or HRV)
//   - Stage 2 (ShortHistory): ≥ 3 nights with HR/HRV trends
//   - Stage 3 (Personalized): ≥ 7 nights + stable wearable baselines

package ai.synheart.core.models

import org.json.JSONArray
import org.json.JSONObject

/** Which staged-history mode the engine used to compute the score. */
enum class RecoveryStage(val wire: String) {
    FIRST_DAY("first_day"),
    SHORT_HISTORY("short_history"),
    PERSONALIZED("personalized");

    companion object {
        fun fromWire(s: String?): RecoveryStage =
            values().firstOrNull { it.wire == s } ?: FIRST_DAY
    }
}

/** User-facing label for the score's confidence regime. */
enum class RecoveryScoreMode(val wire: String) {
    ESTIMATE("estimate"),
    TRENDED("trended"),
    PERSONALIZED("personalized");

    companion object {
        fun fromWire(s: String?): RecoveryScoreMode =
            values().firstOrNull { it.wire == s } ?: ESTIMATE
    }
}

/** Discrete reasons surfaced for "why is your recovery score X?" panels. */
enum class RecoveryFactor(val wire: String) {
    HRV_ABOVE_BASELINE("hrv_above_baseline"),
    HRV_BELOW_BASELINE("hrv_below_baseline"),
    RESTING_HR_ELEVATED("resting_hr_elevated"),
    STRONG_SLEEP_QUALITY("strong_sleep_quality"),
    FRAGMENTED_SLEEP("fragmented_sleep"),
    INCONSISTENT_SCHEDULE("inconsistent_schedule"),
    HIGH_STRAIN_YESTERDAY("high_strain_yesterday"),
    EARLY_ESTIMATE("early_estimate"),
    SHORT_HISTORY_TREND("short_history_trend"),
    PERSONALIZED_BASELINE("personalized_baseline");

    companion object {
        fun fromWire(s: String?): RecoveryFactor? =
            values().firstOrNull { it.wire == s }
    }
}

/**
 * Aggregate sleep totals for one night. Stage durations are nullable
 * (self-report / basic Health Connect can't break stages out).
 */
data class NightSummary(
    val wakeCalendarDate: Int,
    val totalSleepMinutes: Double,
    val deepSleepMinutes: Double? = null,
    val remSleepMinutes: Double? = null,
    val wasoMinutes: Double? = null,
    val awakeningCount: Int? = null,
    val sleepEfficiency: Double? = null,
    val bedtimeMidpointHours: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("wake_calendar_date", wakeCalendarDate)
        put("total_sleep_minutes", totalSleepMinutes)
        put("deep_sleep_minutes", deepSleepMinutes ?: JSONObject.NULL)
        put("rem_sleep_minutes", remSleepMinutes ?: JSONObject.NULL)
        put("waso_minutes", wasoMinutes ?: JSONObject.NULL)
        put("awakening_count", awakeningCount ?: JSONObject.NULL)
        put("sleep_efficiency", sleepEfficiency ?: JSONObject.NULL)
        put("bedtime_midpoint_hours", bedtimeMidpointHours ?: JSONObject.NULL)
    }
}

/**
 * Per-night overnight physiology (HR + HRV). At least one of the two
 * must be present for the engine to emit a score.
 */
data class OvernightPhysiology(
    val hrvRmssdMs: Double? = null,
    val hrvSdnnMs: Double? = null,
    val hrStdBpm: Double? = null,
    val stepsCount: Double? = null,
    val overnightHrBpm: Double? = null,
    val respiratoryRateBrpm: Double? = null,
    val spo2Pct: Double? = null,
) {
    /**
     * `true` if at least one of HR or HRV is reported. SDNN, hr-std,
     * and steps are *not* consulted: the Recovery formula keys off
     * RMSSD (or its iOS SDNN-as-RMSSD legacy mapping); the others
     * only warm baseline dimensions and shouldn't spuriously trigger
     * Recovery on a night that lacks the signals it actually needs.
     */
    val hasSignal: Boolean
        get() = hrvRmssdMs != null || overnightHrBpm != null

    fun toJson(): JSONObject = JSONObject().apply {
        put("hrv_rmssd_ms", hrvRmssdMs ?: JSONObject.NULL)
        put("hrv_sdnn_ms", hrvSdnnMs ?: JSONObject.NULL)
        put("hr_std_bpm", hrStdBpm ?: JSONObject.NULL)
        put("steps_count", stepsCount ?: JSONObject.NULL)
        put("overnight_hr_bpm", overnightHrBpm ?: JSONObject.NULL)
        put("respiratory_rate_brpm", respiratoryRateBrpm ?: JSONObject.NULL)
        put("spo2_pct", spo2Pct ?: JSONObject.NULL)
    }
}

/**
 * Stage-3 personal baselines pulled from the longitudinal SRM. Pass
 * `null` (on `RecoveryScoreInput.baselines`) if the user doesn't have
 * stable references yet — the engine will dispatch to Stage 1 or 2.
 */
data class BaselineRefs(
    val hrvRmssdMs: Double,
    val hrvConfidence: Double,
    val restingHrBpm: Double,
    val rhrConfidence: Double,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("hrv_rmssd_ms", hrvRmssdMs)
        put("hrv_confidence", hrvConfidence)
        put("resting_hr_bpm", restingHrBpm)
        put("rhr_confidence", rhrConfidence)
    }
}

/**
 * Strain context for Stage 3's strain-adjustment component. Any signal
 * may be `null`; the engine uses whichever is present in priority
 * order (`previous_day_strain` → activity composite → `sleep_debt_hours`).
 */
data class StrainContext(
    val previousDayStrain: Double? = null,
    val workoutMinutes: Int? = null,
    val stepCount: Int? = null,
    val activeMinutes: Int? = null,
    val sleepDebtHours: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("previous_day_strain", previousDayStrain ?: JSONObject.NULL)
        put("workout_minutes", workoutMinutes ?: JSONObject.NULL)
        put("step_count", stepCount ?: JSONObject.NULL)
        put("active_minutes", activeMinutes ?: JSONObject.NULL)
        put("sleep_debt_hours", sleepDebtHours ?: JSONObject.NULL)
    }
}

/** Top-level input bundle for the Recovery Score scorer. */
data class RecoveryScoreInput(
    val tonight: NightSummary,
    val priors: List<NightSummary> = emptyList(),
    val overnight: OvernightPhysiology,
    val priorsOvernight: List<OvernightPhysiology> = emptyList(),
    val baselines: BaselineRefs? = null,
    val strain: StrainContext? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("tonight", tonight.toJson())
        put("priors", JSONArray(priors.map { it.toJson() }))
        put("overnight", overnight.toJson())
        put("priors_overnight", JSONArray(priorsOvernight.map { it.toJson() }))
        put("baselines", baselines?.toJson() ?: JSONObject.NULL)
        put("strain", strain?.toJson() ?: JSONObject.NULL)
    }

    fun toJsonString(): String = toJson().toString()
}

/** Per-component breakdown — populated per-stage. Components missing for the active stage are `null`. */
data class RecoveryComponents(
    val sleepQuality: Double? = null,
    val continuity: Double? = null,
    val consistency: Double? = null,
    val overnightHrLevel: Double? = null,
    val hrvLevel: Double? = null,
    val hrvTrend: Double? = null,
    val hrTrend: Double? = null,
    val hrvDeviation: Double? = null,
    val rhrDeviation: Double? = null,
    val strainAdjustment: Double? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): RecoveryComponents = RecoveryComponents(
            sleepQuality = optDouble(json, "sleep_quality"),
            continuity = optDouble(json, "continuity"),
            consistency = optDouble(json, "consistency"),
            overnightHrLevel = optDouble(json, "overnight_hr_level"),
            hrvLevel = optDouble(json, "hrv_level"),
            hrvTrend = optDouble(json, "hrv_trend"),
            hrTrend = optDouble(json, "hr_trend"),
            hrvDeviation = optDouble(json, "hrv_deviation"),
            rhrDeviation = optDouble(json, "rhr_deviation"),
            strainAdjustment = optDouble(json, "strain_adjustment"),
        )
    }
}

/** Canonical output of the Recovery Score scorer. */
data class RecoveryScoreResult(
    val score: Int,
    val stage: RecoveryStage,
    val mode: RecoveryScoreMode,
    val components: RecoveryComponents,
    val confidence: Double,
    val explanation: List<RecoveryFactor> = emptyList(),
    val modelId: String,
    val modelVersion: String,
    val pipelineVersion: String,
) {
    companion object {
        fun fromJson(json: JSONObject): RecoveryScoreResult {
            val rawExpl = json.optJSONArray("explanation") ?: JSONArray()
            val factors = mutableListOf<RecoveryFactor>()
            for (i in 0 until rawExpl.length()) {
                val e = rawExpl.optString(i, null) ?: continue
                RecoveryFactor.fromWire(e)?.let { factors.add(it) }
            }
            return RecoveryScoreResult(
                score = json.optInt("score", 0),
                stage = RecoveryStage.fromWire(json.optString("stage", "first_day")),
                mode = RecoveryScoreMode.fromWire(json.optString("mode", "estimate")),
                components = RecoveryComponents.fromJson(
                    json.optJSONObject("components") ?: JSONObject()
                ),
                confidence = json.optDouble("confidence", 0.0),
                explanation = factors,
                modelId = json.optString("model_id", ""),
                modelVersion = json.optString("model_version", ""),
                pipelineVersion = json.optString("pipeline_version", ""),
            )
        }

        fun fromJsonString(s: String): RecoveryScoreResult = fromJson(JSONObject(s))
    }
}
