package ai.synheart.core.storage

/**
 * Lightweight session record returned by CoreRuntimeBridge.listSessions().
 *
 * core runtime (synheart-core-rust) via FFI.
 */
data class SessionRecord(
    val sessionId: String,
    val subjectId: String = "",
    val mode: String = "personal",
    val createdAtUtc: Long = 0,
    val startUtc: Long = 0,
    val appId: String = "",
    val appVersion: String = "0.0.0",
    val deviceId: String = "",
    val platform: String = "android"
)
