package ai.synheart.core.baseline

import ai.synheart.core.models.WearableReferenceView
import org.json.JSONObject

/** Mean / std / confidence triplet for one axis or metric. */
data class AxisStats(
    val mean: Double,
    val std: Double,
    /** 0.0–1.0. Same semantics as the per-dimension wearable confidence. */
    val confidence: Double,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mean", mean)
        put("std", std)
        put("confidence", confidence)
    }

    companion object {
        fun fromJson(json: JSONObject): AxisStats = AxisStats(
            mean = json.optDouble("mean", 0.0),
            std = json.optDouble("std", 0.0),
            confidence = json.optDouble("confidence", 0.0),
        )
    }
}

/**
 * Marker for any typed baseline payload. Bounds the generic payload-extractor
 * surface on the snapshot facade.
 */
interface BaselinePayload {
    /**
     * Per-payload schema version. Independent across kinds — a bump on one
     * kind does not force changes on the others.
     */
    val schemaVersion: Int
}

// ---------------------------------------------------------------------------
// kind = "session.hsi_axes"
// ---------------------------------------------------------------------------

/**
 * HSI axes aggregator output for one session. [axes] is a string-keyed map
 * (not a fixed 4-field struct) so future HSI versions can publish additional
 * axes without a payload schema bump.
 */
data class HsiAxesBaseline(
    override val schemaVersion: Int,
    val axes: Map<String, AxisStats>,
) : BaselinePayload {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema_version", schemaVersion)
        put("axes", JSONObject().also { o -> axes.forEach { (k, v) -> o.put(k, v.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): HsiAxesBaseline {
            val raw = json.optJSONObject("axes")
            val out = linkedMapOf<String, AxisStats>()
            raw?.keys()?.forEach { key ->
                raw.optJSONObject(key)?.let { out[key] = AxisStats.fromJson(it) }
            }
            return HsiAxesBaseline(
                schemaVersion = json.optInt("schema_version", 1),
                axes = out,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// kind = "session.srm_metrics"
// ---------------------------------------------------------------------------

/** Per-metric session SRM baseline. */
data class SrmMetricBaseline(
    val muTilde: Double,
    val sigmaTilde: Double,
    val status: SrmMetricStatus,
    val nEff: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mu_tilde", muTilde)
        put("sigma_tilde", sigmaTilde)
        put("status", status.wire)
        put("n_eff", nEff)
    }

    companion object {
        fun fromJson(json: JSONObject): SrmMetricBaseline = SrmMetricBaseline(
            muTilde = json.optDouble("mu_tilde", 0.0),
            sigmaTilde = json.optDouble("sigma_tilde", 0.0),
            status = SrmMetricStatus.fromWire(json.optString("status"))
                ?: SrmMetricStatus.EMPTY,
            nEff = json.optInt("n_eff", 0),
        )
    }
}

/** The SRM engine's per-session output. */
data class SessionSrmMetricsBaseline(
    override val schemaVersion: Int,
    val metrics: Map<String, SrmMetricBaseline>,
) : BaselinePayload {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schema_version", schemaVersion)
        put("metrics", JSONObject().also { o -> metrics.forEach { (k, v) -> o.put(k, v.toJson()) } })
    }

    companion object {
        fun fromJson(json: JSONObject): SessionSrmMetricsBaseline {
            val raw = json.optJSONObject("metrics")
            val out = linkedMapOf<String, SrmMetricBaseline>()
            raw?.keys()?.forEach { key ->
                raw.optJSONObject(key)?.let { out[key] = SrmMetricBaseline.fromJson(it) }
            }
            return SessionSrmMetricsBaseline(
                schemaVersion = json.optInt("schema_version", 1),
                metrics = out,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// kind = "longitudinal.wear"
// ---------------------------------------------------------------------------

/**
 * Payload wrapper for the `longitudinal.wear` kind.
 *
 * The bare [WearableReferenceView] is already reachable via
 * `Synheart.wearableReference` (the FFI hot path); this wrapper adds the
 * kind-local [schemaVersion] for envelope round-tripping.
 */
data class LongitudinalWearBaseline(
    override val schemaVersion: Int,
    val reference: WearableReferenceView,
) : BaselinePayload {
    fun toJson(): JSONObject {
        // Wire shape: the reference's fields sit top-level alongside
        // `schema_version` (flat, no nested `reference` object).
        val dims = JSONObject()
        reference.dimensions.forEach { (k, v) -> dims.put(k, v) }
        reference.recentSleepScoreMedian?.let { dims.put("recent_sleep_score_median", it) }
        val conf = JSONObject()
        reference.confidence.forEach { (k, v) -> conf.put(k, v) }

        return JSONObject().apply {
            put("schema_version", schemaVersion)
            put("status", reference.status)
            reference.modelVersion?.let { put("model_version", it) }
            put("dimensions", dims)
            put("confidence", conf)
        }
    }

    companion object {
        fun fromJson(json: JSONObject): LongitudinalWearBaseline = LongitudinalWearBaseline(
            schemaVersion = json.optInt("schema_version", 1),
            // `WearableReferenceView.fromJson` expects status / model_version /
            // dimensions / confidence — the same flattened shape `toJson` emits.
            reference = WearableReferenceView.fromJson(json),
        )
    }
}
