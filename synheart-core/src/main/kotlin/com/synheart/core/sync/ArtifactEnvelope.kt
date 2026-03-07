package com.synheart.core.sync

import org.json.JSONObject

/** Wire format for E2EE artifact sync (RFC-CORE-0005 §4). */
data class ArtifactEnvelope(
    val envelopeVersion: String = "1",
    val artifactId: String,
    val subjectId: String,
    val sessionId: String? = null,
    val type: String,
    val startMs: Long,
    val endMs: Long,
    val seq: Int? = null,
    val schemaName: String,
    val schemaVersion: String,
    val cryptoAlg: String = "AES256GCM",
    val nonceB64: String,
    val compression: String = "none",
    val payloadSha256: String,
    val ciphertextB64: String
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("envelope_version", envelopeVersion)
        put("artifact_id", artifactId)
        put("subject_id", subjectId)
        sessionId?.let { put("session_id", it) }
        put("type", type)
        put("time_range", JSONObject().apply {
            put("start_ms", startMs)
            put("end_ms", endMs)
        })
        seq?.let { put("seq", it) }
        put("schema", JSONObject().apply {
            put("name", schemaName)
            put("version", schemaVersion)
        })
        put("crypto", JSONObject().apply {
            put("alg", cryptoAlg)
            put("nonce_b64", nonceB64)
        })
        put("compression", compression)
        put("payload_sha256", payloadSha256)
        put("ciphertext_b64", ciphertextB64)
    }

    companion object {
        fun fromJson(json: JSONObject): ArtifactEnvelope {
            val timeRange = json.getJSONObject("time_range")
            val schema = json.getJSONObject("schema")
            val crypto = json.getJSONObject("crypto")

            return ArtifactEnvelope(
                envelopeVersion = json.optString("envelope_version", "1"),
                artifactId = json.getString("artifact_id"),
                subjectId = json.getString("subject_id"),
                sessionId = json.optString("session_id", null),
                type = json.getString("type"),
                startMs = timeRange.getLong("start_ms"),
                endMs = timeRange.getLong("end_ms"),
                seq = if (json.has("seq")) json.getInt("seq") else null,
                schemaName = schema.getString("name"),
                schemaVersion = schema.getString("version"),
                cryptoAlg = crypto.optString("alg", "AES256GCM"),
                nonceB64 = crypto.getString("nonce_b64"),
                compression = json.optString("compression", "none"),
                payloadSha256 = json.getString("payload_sha256"),
                ciphertextB64 = json.getString("ciphertext_b64")
            )
        }
    }
}

/** Result of a sync operation. */
data class SyncResult(
    val pushed: Int = 0,
    val pulled: Int = 0,
    val conflictsResolved: Int = 0,
    val errors: List<String> = emptyList()
)

/** Current sync status. */
data class SyncStatus(
    val enabled: Boolean,
    val lastSuccessMs: Long? = null,
    val pendingUploadCount: Int = 0,
    val pendingDownloadCount: Int = 0,
    val cursor: String? = null
)
