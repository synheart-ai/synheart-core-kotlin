package com.synheart.core.modules.srm

/// SRM configuration — thresholds, buffer sizes, and gating parameters.
///
/// All values are fixed per deployment and identical across platforms
/// to guarantee deterministic behavior (SRM.pdf §3.4).
data class SRMConfig(
    /// Tracked metric names.
    val trackedMetrics: List<String> = listOf("hr_mean", "rmssd"),

    /// Maximum buffer size per stratum.
    val bufferSize: Int = 30,

    /// Minimum signal quality score to accept a candidate window.
    val qualityThreshold: Double = 0.5,

    /// Per-stratum motion thresholds. Windows with motion > beta are rejected.
    val motionThresholds: Map<SRMStratum, Double> = mapOf(
        SRMStratum.SLEEP to 0.1,
        SRMStratum.REST to 0.2,
        SRMStratum.BREATHING to 0.15,
        SRMStratum.MORNING to 0.3,
        SRMStratum.OTHER to 0.4
    ),

    /// Minimum window duration in seconds.
    val durationThresholdSeconds: Double = 30.0,

    /// Outlier z-score threshold. Reject if |z_k| > kappa for any metric.
    val outlierKappa: Double = 3.0,

    /// Floor epsilon to prevent division by zero in z-score computation.
    val epsilon: Double = 0.001,

    /// Minimum accepted windows for WARMING status.
    val mMin: Int = 3,

    /// Minimum accepted windows for READY status.
    val mReady: Int = 10,

    /// Minimum distinct calendar days for READY status.
    val dMin: Int = 3,

    /// SRM schema version for snapshot compatibility.
    val srmVersion: String = "1.0.0"
) {
    /// Get motion threshold for a stratum, falling back to [OTHER].
    fun motionThresholdFor(stratum: SRMStratum): Double {
        return motionThresholds[stratum] ?: motionThresholds[SRMStratum.OTHER] ?: 0.4
    }

    companion object {
        val DEFAULTS = SRMConfig()
    }
}
