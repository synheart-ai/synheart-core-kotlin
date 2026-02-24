package com.synheart.core.modules.srm

import org.json.JSONObject
import java.time.Instant

/// A candidate window submitted by synheart-runtime for SRM consideration.
///
/// Contains the metrics and metadata needed for quality gating,
/// outlier rejection, and buffer update (SRM.pdf §4).
data class CandidateWindow(
    val sessionId: String,
    val windowId: String,
    val stratum: SRMStratum,
    val metrics: Map<String, Double>,
    val qualityScore: Double,
    val motionScore: Double,
    val durationSeconds: Double,
    val observedAtUtc: Instant
) {
    /// Check if any metric value is NaN or Inf.
    val hasInvalidValues: Boolean
        get() {
            if (qualityScore.isNaN() || qualityScore.isInfinite()) return true
            if (motionScore.isNaN() || motionScore.isInfinite()) return true
            if (durationSeconds.isNaN() || durationSeconds.isInfinite()) return true
            return metrics.values.any { it.isNaN() || it.isInfinite() }
        }
}

/// Per-metric robust reference (median and MAD).
data class MetricReference(
    val median: Double,
    val mad: Double
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("median", median)
        put("mad", mad)
    }

    companion object {
        fun fromJson(json: JSONObject): MetricReference = MetricReference(
            median = json.getDouble("median"),
            mad = json.getDouble("mad")
        )
    }
}

/// SRM reference for a stratum — per-metric median/MAD plus status.
data class SRMReference(
    val stratum: SRMStratum,
    val status: SRMBaselineStatus,
    val metrics: Map<String, MetricReference>,
    val bufferCount: Int,
    val distinctDays: Int
)

/// Result returned after submitting a candidate window.
data class SRMResult(
    val accepted: Boolean,
    val rejectionReason: String? = null,
    val baselineStatus: SRMBaselineStatus,
    val reference: Map<String, MetricReference>? = null,
    val srmSnapshotId: String,
    val srmVersion: String
)
