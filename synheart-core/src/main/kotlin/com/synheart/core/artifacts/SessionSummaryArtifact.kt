package com.synheart.core.artifacts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SessionInfo(
    @SerialName("session_id") val sessionId: String,
    val mode: String,
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long
)

@Serializable
data class CoverageInfo(
    @SerialName("total_windows") val totalWindows: Int
)

@Serializable
data class AggregateAxis(
    val mean: Double,
    val min: Double,
    val max: Double
)

@Serializable
data class SessionAggregates(
    val focus: AggregateAxis,
    val arousal: AggregateAxis,
    val capacity: AggregateAxis,
    val sleep: AggregateAxis
)

@Serializable
data class AppMetric(
    val name: String,
    val value: Double,
    val unit: String? = null
)

@Serializable
data class InsightMetrics(
    @SerialName("app_metrics") val appMetrics: List<AppMetric> = emptyList()
)

@Serializable
data class SessionSummaryArtifact(
    val header: ArtifactHeader,
    val session: SessionInfo,
    val coverage: CoverageInfo,
    val aggregates: SessionAggregates,
    @SerialName("insight_metrics") val insightMetrics: InsightMetrics? = null
) {
    companion object {
        fun create(
            subjectId: String,
            sessionId: String,
            startMs: Long,
            endMs: Long,
            mode: String,
            totalWindows: Int,
            aggregates: SessionAggregates,
            insightMetrics: InsightMetrics? = null
        ): SessionSummaryArtifact {
            val header = ArtifactHeader.create(
                type = "session_summary",
                subjectId = subjectId,
                sessionId = sessionId,
                startMs = startMs,
                endMs = endMs,
                schemaName = "session_summary",
                schemaVersion = "1"
            )
            return SessionSummaryArtifact(
                header = header,
                session = SessionInfo(sessionId = sessionId, mode = mode, startMs = startMs, endMs = endMs),
                coverage = CoverageInfo(totalWindows = totalWindows),
                aggregates = aggregates,
                insightMetrics = insightMetrics
            )
        }
    }
}
