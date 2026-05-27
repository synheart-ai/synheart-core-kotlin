// SPDX-License-Identifier: Apache-2.0
//
// Typed Kotlin models for the RFC-READINESS-SCORE-0001 daily scorer.
// Mirrors the JSON shapes produced by the Synheart Runtime's
// `ReadinessScore` computation. snake_case wire keys for cross-language
// portability.
//
// Readiness layers acute / fatigue / history context on top of today's
// Recovery Score to answer:
//
//     "How much strain should the user take today?"

package ai.synheart.core.models

import org.json.JSONArray
import org.json.JSONObject

/** Discrete action label derived from the score. */
enum class ReadinessBand(val wire: String, val label: String) {
    REST("rest", "Rest"),
    LIGHT("light", "Light"),
    NORMAL("normal", "Normal"),
    PUSH("push", "Push");

    companion object {
        fun fromWire(s: String?): ReadinessBand =
            values().firstOrNull { it.wire == s } ?: REST
    }
}

/** Discrete reasons surfaced for "why is your readiness X?" panels. */
enum class ReadinessFactor(val wire: String) {
    STRONG_RECOVERY("strong_recovery"),
    LOW_RECOVERY("low_recovery"),
    ACUTE_LOAD_HIGH("acute_load_high"),
    ACUTE_LOAD_OPTIMAL("acute_load_optimal"),
    SLEEP_DEBT("sleep_debt"),
    RECOVERY_DECLINING("recovery_declining"),
    CONSECUTIVE_OVERLOAD("consecutive_overload"),
    NO_RECENT_REST("no_recent_rest"),
    ANCHOR_ONLY("anchor_only");

    companion object {
        fun fromWire(s: String?): ReadinessFactor? =
            values().firstOrNull { it.wire == s }
    }
}

/**
 * Acute / chronic workload context. Hosts can either pre-compute
 * `acuteChronicRatio` or pass the raw loads.
 */
data class AcuteWorkloadContext(
    val acuteLoad: Double? = null,
    val chronicLoad: Double? = null,
    val acuteChronicRatio: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("acute_load", acuteLoad ?: JSONObject.NULL)
        put("chronic_load", chronicLoad ?: JSONObject.NULL)
        put("acute_chronic_ratio", acuteChronicRatio ?: JSONObject.NULL)
    }
}

/** Short-window fatigue indicators. */
data class FatigueContext(
    val sleepDebtHours: Double? = null,
    val recoverySlopePerDay: Double? = null,
    val recovery7dMean: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sleep_debt_hours", sleepDebtHours ?: JSONObject.NULL)
        put("recovery_slope_per_day", recoverySlopePerDay ?: JSONObject.NULL)
        put("recovery_7d_mean", recovery7dMean ?: JSONObject.NULL)
    }
}

/** Training-history context. */
data class HistoryContext(
    val consecutiveHighStrainDays: Int? = null,
    val daysSinceRest: Int? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("consecutive_high_strain_days", consecutiveHighStrainDays ?: JSONObject.NULL)
        put("days_since_rest", daysSinceRest ?: JSONObject.NULL)
    }
}

/**
 * Top-level input bundle. Recovery score is required; everything else
 * is optional and improves confidence when present.
 */
data class ReadinessScoreInput(
    val recoveryScore: Int,
    val recoveryConfidence: Double? = null,
    val acuteWorkload: AcuteWorkloadContext? = null,
    val fatigue: FatigueContext? = null,
    val history: HistoryContext? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("recovery_score", recoveryScore)
        put("recovery_confidence", recoveryConfidence ?: JSONObject.NULL)
        put("acute_workload", acuteWorkload?.toJson() ?: JSONObject.NULL)
        put("fatigue", fatigue?.toJson() ?: JSONObject.NULL)
        put("history", history?.toJson() ?: JSONObject.NULL)
    }

    fun toJsonString(): String = toJson().toString()

    companion object {
        /**
         * Build an input from only the recovery score; downstream
         * context stays empty. Useful while a host is still wiring
         * fatigue / load.
         */
        fun fromRecovery(recoveryScore: Int): ReadinessScoreInput =
            ReadinessScoreInput(recoveryScore = recoveryScore)
    }
}

/** Per-component breakdown. */
data class ReadinessComponents(
    val recovery: Double,
    val acuteLoad: Double? = null,
    val fatigue: Double? = null,
    val history: Double? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): ReadinessComponents = ReadinessComponents(
            recovery = json.optDouble("recovery", 0.0),
            acuteLoad = optDouble(json, "acute_load"),
            fatigue = optDouble(json, "fatigue"),
            history = optDouble(json, "history"),
        )
    }
}

/** Canonical output of the Readiness Score scorer. */
data class ReadinessScoreResult(
    val score: Int,
    val band: ReadinessBand,
    val recoveryAnchor: Int,
    val components: ReadinessComponents,
    val confidence: Double,
    val explanation: List<ReadinessFactor> = emptyList(),
    val modelId: String,
    val modelVersion: String,
    val pipelineVersion: String,
) {
    companion object {
        fun fromJson(json: JSONObject): ReadinessScoreResult {
            val rawExpl = json.optJSONArray("explanation") ?: JSONArray()
            val factors = mutableListOf<ReadinessFactor>()
            for (i in 0 until rawExpl.length()) {
                val e = rawExpl.optString(i, null) ?: continue
                ReadinessFactor.fromWire(e)?.let { factors.add(it) }
            }
            return ReadinessScoreResult(
                score = json.optInt("score", 0),
                band = ReadinessBand.fromWire(json.optString("band", "rest")),
                recoveryAnchor = json.optInt("recovery_anchor", 0),
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
