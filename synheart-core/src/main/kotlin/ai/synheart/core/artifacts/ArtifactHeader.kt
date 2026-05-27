package ai.synheart.core.artifacts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TimeRange(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long
)

@Serializable
data class SchemaRef(
    val name: String,
    val version: String
)

@Serializable
data class ArtifactHeader(
    @SerialName("artifact_id") val artifactId: String,
    @SerialName("subject_id") val subjectId: String,
    @SerialName("session_id") val sessionId: String? = null,
    val schema: SchemaRef,
    @SerialName("time_range") val timeRange: TimeRange,
    @SerialName("created_at_ms") val createdAtMs: Long
) {
    companion object {
        fun create(
            type: String,
            subjectId: String,
            sessionId: String? = null,
            startMs: Long,
            endMs: Long,
            schemaName: String,
            schemaVersion: String
        ): ArtifactHeader {
            val artifactId = computeArtifactId(
                type = type,
                subjectId = subjectId,
                sessionId = sessionId,
                startMs = startMs,
                endMs = endMs,
                schemaName = schemaName,
                schemaVersion = schemaVersion
            )
            return ArtifactHeader(
                artifactId = artifactId,
                subjectId = subjectId,
                sessionId = sessionId,
                schema = SchemaRef(name = schemaName, version = schemaVersion),
                timeRange = TimeRange(startMs = startMs, endMs = endMs),
                createdAtMs = System.currentTimeMillis()
            )
        }
    }
}
