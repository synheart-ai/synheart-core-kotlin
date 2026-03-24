package com.synheart.core.artifacts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

@Serializable
data class AxisStats(
    val mean: Double,
    val std: Double,
    val confidence: Double
)

@Serializable
data class BaselineCoverage(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    @SerialName("total_windows") val totalWindows: Int
)

@Serializable
data class BaselineAxes(
    val sleep: AxisStats,
    val capacity: AxisStats,
    val arousal: AxisStats,
    val focus: AxisStats
)

@Serializable
data class BaselineModelRef(
    @SerialName("model_id") val modelId: String,
    @SerialName("model_version") val modelVersion: String
)

@Serializable
data class BaselineData(
    val coverage: BaselineCoverage,
    val axes: BaselineAxes,
    val model: BaselineModelRef
)

@Serializable
data class BaselineSnapshotArtifact(
    val header: ArtifactHeader,
    val baseline: BaselineData,
    @SerialName("wearable_reference")
    val wearableReference: JsonObject? = null
) {
    companion object {
        fun create(
            subjectId: String,
            baseline: BaselineData,
            wearableReference: JsonObject? = null
        ): BaselineSnapshotArtifact {
            val header = ArtifactHeader.create(
                type = "baseline_snapshot",
                subjectId = subjectId,
                sessionId = null,
                startMs = baseline.coverage.startMs,
                endMs = baseline.coverage.endMs,
                schemaName = "baseline_snapshot",
                schemaVersion = "1"
            )
            return BaselineSnapshotArtifact(
                header = header,
                baseline = baseline,
                wearableReference = wearableReference
            )
        }
    }
}
