package ai.synheart.core.artifacts

import java.security.MessageDigest

/**
 * Compute a deterministic artifact ID from canonical fields.
 *
 * Format: `{type}|v1|{subject_id}|{session_id_or_~}|{start_ms}|{end_ms}|{schema_name}@{schema_version}`
 * Result: SHA-256 hex digest of the canonical string (UTF-8 encoded).
 *
 * See RFC-CORE-0006 Section 5.
 */
fun computeArtifactId(
    type: String,
    subjectId: String,
    sessionId: String? = null,
    startMs: Long,
    endMs: Long,
    schemaName: String,
    schemaVersion: String
): String {
    val fields = listOf(type, subjectId, schemaName, schemaVersion)
    for (field in fields) {
        require(field.isNotEmpty()) { "Artifact ID field must not be empty" }
        require(!field.contains("|")) { "Artifact ID field must not contain '|'" }
    }
    if (sessionId != null) {
        require(!sessionId.contains("|")) { "sessionId must not contain '|'" }
    }

    val sessionField = sessionId ?: "~"
    val canonical = "$type|v1|$subjectId|$sessionField|$startMs|$endMs|$schemaName@$schemaVersion"

    return sha256Hex(canonical)
}

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    val hash = digest.digest(input.toByteArray(Charsets.UTF_8))
    return hash.joinToString("") { "%02x".format(it) }
}
