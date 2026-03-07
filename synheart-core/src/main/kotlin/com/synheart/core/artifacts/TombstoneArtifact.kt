package com.synheart.core.artifacts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TombstoneData(
    @SerialName("target_artifact_id") val targetArtifactId: String,
    val reason: String,
    @SerialName("deleted_at_ms") val deletedAtMs: Long
)

@Serializable
data class TombstoneArtifact(
    val header: ArtifactHeader,
    val tombstone: TombstoneData
) {
    companion object {
        fun create(
            subjectId: String,
            targetArtifactId: String,
            reason: String,
            deletedAtMs: Long
        ): TombstoneArtifact {
            val header = ArtifactHeader.create(
                type = "tombstone",
                subjectId = subjectId,
                sessionId = null,
                startMs = deletedAtMs,
                endMs = deletedAtMs,
                schemaName = "tombstone",
                schemaVersion = "1"
            )
            return TombstoneArtifact(
                header = header,
                tombstone = TombstoneData(
                    targetArtifactId = targetArtifactId,
                    reason = reason,
                    deletedAtMs = deletedAtMs
                )
            )
        }
    }
}
