// SPDX-License-Identifier: Apache-2.0
//
// Typed Kotlin models for the RFC-SLEEP-SCORE-PIPELINE-0001 batch
// scorer. Mirrors the JSON shapes produced by the Synheart Runtime's
// `SleepScore` computation. snake_case wire keys for cross-language
// portability — Flutter (`lib/src/models/sleep_score.dart`) and Swift
// emit the same JSON.

package ai.synheart.core.models

import org.json.JSONArray
import org.json.JSONObject

/** Canonical sleep stage. */
enum class SleepStage(val wire: String) {
    AWAKE("awake"),
    LIGHT("light"),
    DEEP("deep"),
    REM("rem"),
    UNKNOWN("unknown");

    companion object {
        fun fromWire(s: String?): SleepStage =
            values().firstOrNull { it.wire == s } ?: UNKNOWN
    }
}

/** Which pipeline path produced a score. */
enum class SleepPath(val wire: String) {
    STAGE("stage"),
    AGGREGATED("aggregated"),
    VENDOR_SCORE("vendor_score"),
    PROXY("proxy");

    companion object {
        fun fromWire(s: String?): SleepPath =
            values().firstOrNull { it.wire == s } ?: PROXY
    }
}

/** Mode derived from history length (0 → cold, 1–6 → short, ≥ 7 → stable). */
enum class SleepScoreMode(val wire: String) {
    COLD_START("cold_start"),
    SHORT_HISTORY("short_history"),
    STABLE("stable");

    companion object {
        fun fromWire(s: String?): SleepScoreMode =
            values().firstOrNull { it.wire == s } ?: COLD_START
    }
}

/** Reason a score was absent or degraded. */
enum class SleepScoreReason(val wire: String) {
    NO_SLEEP_DATA("no_sleep_data"),
    INSUFFICIENT_DATA("insufficient_data"),
    PROXY_FALLBACK("proxy_fallback"),
    VENDOR_PASSTHROUGH("vendor_passthrough");

    companion object {
        fun fromWire(s: String?): SleepScoreReason? =
            if (s == null) null else values().firstOrNull { it.wire == s }
    }
}

/** A contiguous stage interval. Half-open: `[startMs, endMs)`. */
data class StageSegment(
    val stage: SleepStage,
    val startMs: Long,
    val endMs: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("stage", stage.wire)
        put("start_ms", startMs)
        put("end_ms", endMs)
    }
}

/**
 * Aggregate per-night totals for vendors that don't expose a timeline.
 *
 * Stage durations ([deepSleepMinutes], [remSleepMinutes]) are nullable:
 * pass `null` when the source genuinely cannot distinguish stages. The
 * engine then scores on duration + continuity only instead of treating
 * zero deep/REM as "absurdly poor stage profile" and tanking the result.
 */
data class AggregatedTotals(
    val totalSleepMinutes: Double,
    val deepSleepMinutes: Double? = null,
    val remSleepMinutes: Double? = null,
    val awakeMinutes: Double,
    val awakenings: Int? = null,
    val timeInBedMinutes: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("total_sleep_minutes", totalSleepMinutes)
        if (deepSleepMinutes != null) put("deep_sleep_minutes", deepSleepMinutes)
        if (remSleepMinutes != null) put("rem_sleep_minutes", remSleepMinutes)
        put("awake_minutes", awakeMinutes)
        put("awakenings", awakenings ?: JSONObject.NULL)
        put("time_in_bed_minutes", timeInBedMinutes ?: JSONObject.NULL)
    }
}

/**
 * The three vendor-input shapes. The runtime's `NightInput` is a
 * tagged enum (`kind: segmented | aggregated | vendor_score`).
 */
sealed class NightInput {
    abstract fun toJson(): JSONObject

    data class Segmented(
        val sessionStartMs: Long,
        val sessionEndMs: Long,
        val zoneId: String,
        val segments: List<StageSegment>,
    ) : NightInput() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("kind", "segmented")
            put("session_start_ms", sessionStartMs)
            put("session_end_ms", sessionEndMs)
            put("zone_id", zoneId)
            put("segments", JSONArray(segments.map { it.toJson() }))
        }
    }

    data class Aggregated(
        val sessionStartMs: Long? = null,
        val sessionEndMs: Long? = null,
        val totals: AggregatedTotals,
    ) : NightInput() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("kind", "aggregated")
            put("session_start_ms", sessionStartMs ?: JSONObject.NULL)
            put("session_end_ms", sessionEndMs ?: JSONObject.NULL)
            put("totals", totals.toJson())
        }
    }

    data class VendorScore(val score: Int) : NightInput() {
        override fun toJson(): JSONObject = JSONObject().apply {
            put("kind", "vendor_score")
            put("score", score)
        }
    }
}

/** One night's raw input + optional session-window HR. */
data class NightRaw(
    val wakeCalendarDate: Int,
    val detail: NightInput,
    val avgHrBpm: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("wake_calendar_date", wakeCalendarDate)
        put("detail", detail.toJson())
        put("avg_hr_bpm", avgHrBpm ?: JSONObject.NULL)
    }
}

/** Full input to the scorer. */
data class SleepScoreInput(
    val tonight: NightRaw,
    val priorsNewestFirst: List<NightRaw> = emptyList(),
    val pipelineVersion: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("tonight", tonight.toJson())
        put("priors_newest_first", JSONArray(priorsNewestFirst.map { it.toJson() }))
        put("pipeline_version", pipelineVersion)
    }

    fun toJsonString(): String = toJson().toString()
}

/** Per-component breakdown; fields populated per-path. */
data class SleepScoreBreakdown(
    val duration: Int? = null,
    val quality: Int? = null,
    val continuity: Int? = null,
    val consistency: Int? = null,
    val personalization: Int? = null,
    val vendorScore: Int? = null,
    val proxyHr: Int? = null,
) {
    companion object {
        fun fromJson(json: JSONObject): SleepScoreBreakdown = SleepScoreBreakdown(
            duration = optInt(json, "duration"),
            quality = optInt(json, "quality"),
            continuity = optInt(json, "continuity"),
            consistency = optInt(json, "consistency"),
            personalization = optInt(json, "personalization"),
            vendorScore = optInt(json, "vendor_score"),
            proxyHr = optInt(json, "proxy_hr"),
        )
    }
}

data class SleepScoreAdjust(
    val debtPenalty: Int,
    val hrAdjustment: Int,
) {
    companion object {
        fun fromJson(json: JSONObject): SleepScoreAdjust = SleepScoreAdjust(
            debtPenalty = json.optInt("debt_penalty", 0),
            hrAdjustment = json.optInt("hr_adjustment", 0),
        )
    }
}

data class ComponentWeights(
    val duration: Double = 0.0,
    val quality: Double = 0.0,
    val continuity: Double = 0.0,
    val consistency: Double = 0.0,
    val personalization: Double = 0.0,
) {
    companion object {
        fun fromJson(json: JSONObject): ComponentWeights = ComponentWeights(
            duration = json.optDouble("duration", 0.0),
            quality = json.optDouble("quality", 0.0),
            continuity = json.optDouble("continuity", 0.0),
            consistency = json.optDouble("consistency", 0.0),
            personalization = json.optDouble("personalization", 0.0),
        )
    }
}

/** The canonical output of the batch scorer. */
data class SleepScoreResult(
    val score: Int? = null,
    val scoreNormalized: Double? = null,
    val confidence: Double,
    val path: SleepPath,
    val mode: SleepScoreMode,
    val components: SleepScoreBreakdown,
    val adjustments: SleepScoreAdjust,
    val effectiveWeights: ComponentWeights,
    val reason: SleepScoreReason? = null,
    val priorNightCount: Int,
    val pipelineVersion: String,
    val modelId: String,
    val constantsHash: String,
) {
    companion object {
        fun fromJson(json: JSONObject): SleepScoreResult = SleepScoreResult(
            score = optInt(json, "score"),
            scoreNormalized = optDouble(json, "score_normalized"),
            confidence = json.optDouble("confidence", 0.0),
            path = SleepPath.fromWire(json.optString("path", "proxy")),
            mode = SleepScoreMode.fromWire(json.optString("mode", "cold_start")),
            components = SleepScoreBreakdown.fromJson(
                json.optJSONObject("components") ?: JSONObject()
            ),
            adjustments = SleepScoreAdjust.fromJson(
                json.optJSONObject("adjustments") ?: JSONObject()
            ),
            effectiveWeights = ComponentWeights.fromJson(
                json.optJSONObject("effective_weights") ?: JSONObject()
            ),
            reason = SleepScoreReason.fromWire(json.optString("reason", null)),
            priorNightCount = json.optInt("prior_night_count", 0),
            pipelineVersion = json.optString("pipeline_version", ""),
            modelId = json.optString("model_id", ""),
            constantsHash = json.optString("constants_hash", ""),
        )

        fun fromJsonString(s: String): SleepScoreResult = fromJson(JSONObject(s))
    }
}

/**
 * Wearable reference view over the Longitudinal SRM engine output.
 *
 * Surfaces the high-signal fields typed (status, Path-B median) and
 * keeps the raw maps accessible for UI that wants to walk every
 * dimension — useful for a baselines page that renders the full SRM.
 */
data class WearableReferenceView(
    val status: String,
    val modelVersion: String? = null,
    val recentSleepScoreMedian: Int? = null,
    val dimensions: Map<String, Number> = emptyMap(),
    val confidence: Map<String, Double> = emptyMap(),
) {
    companion object {
        fun fromJson(json: JSONObject): WearableReferenceView {
            val dimsRaw = json.optJSONObject("dimensions") ?: JSONObject()
            val confRaw = json.optJSONObject("confidence") ?: JSONObject()

            val dims = mutableMapOf<String, Number>()
            for (key in dimsRaw.keys()) {
                val v = dimsRaw.opt(key)
                if (v is Number) dims[key] = v
            }
            val conf = mutableMapOf<String, Double>()
            for (key in confRaw.keys()) {
                val v = confRaw.opt(key)
                if (v is Number) conf[key] = v.toDouble()
            }

            return WearableReferenceView(
                status = json.optString("status", "Empty"),
                modelVersion = json.optString("model_version", null),
                recentSleepScoreMedian = optInt(dimsRaw, "recent_sleep_score_median"),
                dimensions = dims,
                confidence = conf,
            )
        }

        fun fromJsonString(s: String): WearableReferenceView = fromJson(JSONObject(s))
    }
}

// Helpers — `optInt` / `optDouble` that return null when the field is
// absent (vs org.json's defaulting-to-0 behaviour).
internal fun optInt(json: JSONObject, key: String): Int? =
    if (json.has(key) && !json.isNull(key)) (json.opt(key) as? Number)?.toInt() else null

internal fun optDouble(json: JSONObject, key: String): Double? =
    if (json.has(key) && !json.isNull(key)) (json.opt(key) as? Number)?.toDouble() else null
