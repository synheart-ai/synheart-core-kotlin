package com.synheart.core.artifacts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Provenance(
    val source: String,
    @SerialName("device_id") val deviceId: String,
    @SerialName("app_id") val appId: String,
    @SerialName("runtime_version") val runtimeVersion: String
)

@Serializable
data class WindowData(
    @SerialName("start_ms") val startMs: Long,
    @SerialName("end_ms") val endMs: Long,
    val hsi: Map<String, JsonElement>
)

@Serializable
data class HSIWindowArtifact(
    val header: ArtifactHeader,
    val provenance: Provenance,
    val window: WindowData
) {
    companion object {
        fun create(
            subjectId: String,
            sessionId: String,
            startMs: Long,
            endMs: Long,
            hsi: Map<String, JsonElement>,
            source: String,
            deviceId: String,
            appId: String,
            runtimeVersion: String
        ): HSIWindowArtifact {
            val header = ArtifactHeader.create(
                type = "hsi_window",
                subjectId = subjectId,
                sessionId = sessionId,
                startMs = startMs,
                endMs = endMs,
                schemaName = "hsi_window",
                schemaVersion = "1"
            )
            return HSIWindowArtifact(
                header = header,
                provenance = Provenance(
                    source = source,
                    deviceId = deviceId,
                    appId = appId,
                    runtimeVersion = runtimeVersion
                ),
                window = WindowData(startMs = startMs, endMs = endMs, hsi = hsi)
            )
        }
    }
}
