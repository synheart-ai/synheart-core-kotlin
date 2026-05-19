// SPDX-License-Identifier: Apache-2.0
//
// Deterministic artifact ID derivation. Cross-SDK golden vectors live in
// `src/test/kotlin/.../artifacts/ArtifactIdTest.kt`.
//
// Canonical format (RFC-CORE-0006 §5, mirrors the Rust runtime at
// `synheart-core-runtime/crates/core-runtime/src/artifacts/artifact_id.rs`):
//
//   {type}|v1|{subjectId}|{sessionId or "~"}|{startMs}|{endMs}|{schemaName}@{schemaVersion}
//
// Pipes are forbidden in `type` / `subjectId`; `null` sessionId is
// encoded as a single tilde so the digest distinguishes "no session"
// from a legitimate sessionId of any other value (passing `"~"`
// explicitly is treated identically to `null`, matching the runtime).

package ai.synheart.core.artifacts

import java.security.MessageDigest

fun computeArtifactId(
    type: String,
    subjectId: String,
    sessionId: String?,
    startMs: Long,
    endMs: Long,
    schemaName: String,
    schemaVersion: String,
): String {
    require(
        type.isNotEmpty() && subjectId.isNotEmpty() &&
            schemaName.isNotEmpty() && schemaVersion.isNotEmpty()
    ) { "artifact_id fields must not be empty" }
    require('|' !in type && '|' !in subjectId) {
        "artifact_id fields must not contain pipe character"
    }

    val session = sessionId ?: "~"
    val canonical = "$type|v1|$subjectId|$session|$startMs|$endMs|$schemaName@$schemaVersion"
    val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
