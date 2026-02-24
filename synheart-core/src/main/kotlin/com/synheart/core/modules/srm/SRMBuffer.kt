package com.synheart.core.modules.srm

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset
import kotlin.math.abs
import kotlin.math.max

/// An accepted entry stored in the per-stratum buffer.
data class BufferEntry(
    val windowId: String,
    val metrics: Map<String, Double>,
    val observedAtUtc: Instant
) {
    /// Calendar day key (UTC) for distinct-day counting.
    val dayKey: String
        get() {
            val date = observedAtUtc.atOffset(ZoneOffset.UTC).toLocalDate()
            return "${date.year}-${date.monthValue.toString().padStart(2, '0')}-${date.dayOfMonth.toString().padStart(2, '0')}"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("window_id", windowId)
        put("metrics", JSONObject().apply {
            for ((k, v) in metrics) {
                put(k, v)
            }
        })
        put("observed_at_utc", observedAtUtc.toString())
    }

    companion object {
        fun fromJson(json: JSONObject): BufferEntry {
            val metricsObj = json.getJSONObject("metrics")
            val metrics = mutableMapOf<String, Double>()
            for (key in metricsObj.keys()) {
                metrics[key] = metricsObj.getDouble(key)
            }
            return BufferEntry(
                windowId = json.getString("window_id"),
                metrics = metrics,
                observedAtUtc = Instant.parse(json.getString("observed_at_utc"))
            )
        }
    }
}

/// Bounded per-stratum buffer with quality gating, outlier rejection,
/// oldest-first eviction, and deterministic median/MAD computation.
///
/// Implements SRM.pdf §4 (accept/reject) and §5 (buffer management).
class SRMBuffer(
    val stratum: SRMStratum,
    private val config: SRMConfig
) {
    /// Ordered list of accepted entries (oldest first).
    private val _entries = mutableListOf<BufferEntry>()

    /// Cached per-metric references. Recomputed after every buffer mutation.
    private var cachedReference: Map<String, MetricReference> = emptyMap()

    // ---------------------------------------------------------------------------
    // Public API
    // ---------------------------------------------------------------------------

    val entries: List<BufferEntry> get() = _entries.toList()
    val count: Int get() = _entries.size

    val distinctDays: Int
        get() = _entries.map { it.dayKey }.toSet().size

    val baselineStatus: SRMBaselineStatus
        get() {
            if (_entries.size < config.mMin) return SRMBaselineStatus.EMPTY
            if (_entries.size < config.mReady) return SRMBaselineStatus.WARMING
            if (distinctDays < config.dMin) return SRMBaselineStatus.WARMING
            return SRMBaselineStatus.READY
        }

    val reference: Map<String, MetricReference> get() = cachedReference.toMap()

    /// Submit a candidate window. Returns an [SRMResult].
    fun submit(candidate: CandidateWindow): SRMResult {
        // 1. Reject NaN/Inf
        if (candidate.hasInvalidValues) {
            return reject("nan_or_inf")
        }

        // 2. Quality gate: duration
        if (candidate.durationSeconds < config.durationThresholdSeconds) {
            return reject("duration_below_threshold")
        }

        // 3. Quality gate: quality score
        if (candidate.qualityScore < config.qualityThreshold) {
            return reject("quality_below_threshold")
        }

        // 4. Quality gate: motion
        val motionThreshold = config.motionThresholdFor(stratum)
        if (candidate.motionScore > motionThreshold) {
            return reject("motion_above_threshold")
        }

        // 5. Outlier guard (only if buffer has >= M_min entries)
        if (_entries.size >= config.mMin) {
            for (metric in config.trackedMetrics) {
                val value = candidate.metrics[metric] ?: continue
                val ref = cachedReference[metric] ?: continue

                val denominator = max(ref.mad, config.epsilon)
                val z = (value - ref.median) / denominator
                if (abs(z) > config.outlierKappa) {
                    return reject("outlier_$metric")
                }
            }
        }

        // 6. Accept — evict oldest if at capacity
        if (_entries.size >= config.bufferSize) {
            _entries.removeAt(0)
        }

        _entries.add(BufferEntry(
            windowId = candidate.windowId,
            metrics = candidate.metrics.toMap(),
            observedAtUtc = candidate.observedAtUtc
        ))

        // 7. Recompute reference
        recomputeReference()

        return accept()
    }

    /// Restore buffer from serialized entries (snapshot restore).
    fun restore(entries: List<BufferEntry>) {
        _entries.clear()
        _entries.addAll(entries)
        recomputeReference()
    }

    /// Clear all entries and cached reference.
    fun reset() {
        _entries.clear()
        cachedReference = emptyMap()
    }

    // ---------------------------------------------------------------------------
    // Deterministic Statistics (SRM.pdf §5.2)
    // ---------------------------------------------------------------------------

    companion object {
        /// Deterministic median: lower-middle for even counts.
        fun deterministicMedian(values: List<Double>): Double {
            val sorted = values.sorted()
            val n = sorted.size
            if (n == 0) return Double.NaN
            return if (n % 2 == 1) sorted[n / 2]
            else sorted[n / 2 - 1] // lower-middle
        }

        /// MAD (Median Absolute Deviation).
        fun deterministicMAD(values: List<Double>, median: Double): Double {
            val deviations = values.map { abs(it - median) }
            return deterministicMedian(deviations)
        }
    }

    // ---------------------------------------------------------------------------
    // Private
    // ---------------------------------------------------------------------------

    private fun recomputeReference() {
        val ref = mutableMapOf<String, MetricReference>()
        for (metric in config.trackedMetrics) {
            val values = _entries.mapNotNull { it.metrics[metric] }
            if (values.isEmpty()) continue
            val med = deterministicMedian(values)
            val mad = deterministicMAD(values, med)
            ref[metric] = MetricReference(median = med, mad = mad)
        }
        cachedReference = ref
    }

    private fun reject(reason: String): SRMResult {
        return SRMResult(
            accepted = false,
            rejectionReason = reason,
            baselineStatus = baselineStatus,
            reference = cachedReference.ifEmpty { null },
            srmSnapshotId = snapshotId(),
            srmVersion = config.srmVersion
        )
    }

    private fun accept(): SRMResult {
        return SRMResult(
            accepted = true,
            baselineStatus = baselineStatus,
            reference = cachedReference.ifEmpty { null },
            srmSnapshotId = snapshotId(),
            srmVersion = config.srmVersion
        )
    }

    private fun snapshotId(): String =
        "srm_${stratum.name.lowercase()}_${_entries.size}_${System.currentTimeMillis()}"
}
