package ai.synheart.core.models

import kotlinx.serialization.Serializable

/**
 * Data provenance tracking aligned with the canonical HSV.
 *
 * Mirrors the runtime's `ProvenanceInfo` — records the origin and lineage of data
 * that contributed to the Human State Vector.
 */
@Serializable
data class ProvenanceInfo(
    /** Unique identifiers of data sources that contributed. */
    val sourceIds: List<String> = emptyList(),
    /** Vendor names that contributed data (e.g., "whoop", "garmin"). */
    val vendors: List<String> = emptyList(),
    /** Device identifier. */
    val deviceId: String = "",
    /** IANA timezone of the observation. */
    val timezone: String = "UTC",
    /** Number of distinct calendar days in the SRM baseline. */
    val baselineDays: Int = 0,
    /** Number of accepted SRM windows (baseline sessions). */
    val baselineSessions: Int = 0,
    /** SRM baseline status: EMPTY, WARMING, or READY. */
    val baselineStatus: String? = null,
    /** SRM snapshot identifier for traceability. */
    val srmSnapshotId: String? = null,
    /** Inference mode: deterministic, probabilistic, or composite. */
    val inferenceMode: String? = null
) {
    companion object {
        val EMPTY = ProvenanceInfo()
    }
}
