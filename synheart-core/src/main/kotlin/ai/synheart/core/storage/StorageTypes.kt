package ai.synheart.core.storage

import org.json.JSONObject

/**
 * Lightweight session record returned by `CoreRuntimeBridge.listSessions()`.
 */
data class SessionRecord(
    val sessionId: String,
    val subjectId: String = "",
    val mode: String = "personal",
    val createdAtUtc: Long = 0,
    val startUtc: Long = 0,
    /**
     * Null while the session is still active; set to the runtime's
     * `ended_at_ms` once the session is closed.
     */
    val endedAtUtc: Long? = null,
    /**
     * `"active"` while a session is in flight; `"closed"` once `stopSession`
     * (or an orphan sweep) has finalized it.
     */
    val state: String = "active",
    val appId: String = "",
    val appVersion: String = "0.0.0",
    val deviceId: String = "",
    val platform: String = "android"
) {
    /**
     * True while the session is still marked `active` in storage. Used by
     * `Synheart.sweepOrphanSessions` to find candidates.
     */
    val isActive: Boolean get() = state == "active"

    companion object {
        /**
         * Parse one row of the `list_sessions` array.
         *
         * Timestamps are read under both the runtime's `*_at_ms` spelling and
         * the older `*_utc` one: the two have coexisted across runtime
         * versions, and reading only one silently yields 0, which reads as
         * "started at the epoch" to anything filtering on age.
         */
        fun fromJson(json: JSONObject): SessionRecord {
            fun readLong(vararg keys: String): Long {
                for (k in keys) {
                    val v = json.opt(k)
                    if (v is Number) return v.toLong()
                }
                return 0
            }

            fun readLongOrNull(vararg keys: String): Long? {
                for (k in keys) {
                    val v = json.opt(k)
                    if (v is Number) return v.toLong()
                }
                return null
            }

            return SessionRecord(
                sessionId = json.optString("session_id"),
                subjectId = json.optString("subject_id"),
                mode = json.optString("mode").takeIf { it.isNotEmpty() } ?: "personal",
                createdAtUtc = readLong("created_at_utc", "created_at_ms"),
                startUtc = readLong("started_at_ms", "start_utc"),
                endedAtUtc = readLongOrNull("ended_at_ms", "end_utc"),
                state = json.optString("state").takeIf { it.isNotEmpty() } ?: "active",
                appId = json.optString("app_id"),
                appVersion = json.optString("app_version").takeIf { it.isNotEmpty() } ?: "0.0.0",
                deviceId = json.optString("device_id"),
                platform = json.optString("platform").takeIf { it.isNotEmpty() } ?: "android",
            )
        }
    }
}
