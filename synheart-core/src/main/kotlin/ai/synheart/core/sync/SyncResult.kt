package ai.synheart.core.sync

import org.json.JSONObject

/** Outcome of one sync cycle. */
data class SyncResult(
    val pushed: Int = 0,
    val pulled: Int = 0,
) {
    companion object {
        /**
         * Read the runtime's sync response.
         *
         * @throws IllegalStateException when the runtime returned nothing.
         * @throws IllegalArgumentException when the response is missing the
         *   counts — a shape mismatch is worth surfacing rather than reporting
         *   a successful sync of zero records.
         */
        fun fromRuntimeResponse(response: JSONObject?): SyncResult {
            checkNotNull(response) { "The native sync operation returned no result." }
            val pushed = response.opt("pushed") as? Number
            val pulled = response.opt("pulled") as? Number
            require(pushed != null && pulled != null) {
                "The native sync operation returned an invalid result."
            }
            return SyncResult(pushed = pushed.toInt(), pulled = pulled.toInt())
        }
    }
}

/**
 * Local Device Sync state, read from the runtime with no network call.
 *
 * Built from the runtime's `sync_status`. A field the runtime did not send
 * stays `null` (absent, not zero). The device roster is not part of this: it
 * lives on the server, so read it with `Synheart.syncListDevices()`.
 */
data class SyncStatus(
    /** The sync engine is built. */
    val enabled: Boolean,
    /** The sync space this device is in, or `null` when it is in none. */
    val syncSpaceId: String? = null,
    /** Local artifacts waiting for the next push. */
    val pendingCount: Int? = null,
    /**
     * Unix ms of the last successful sync cycle since the engine was built.
     * `null` before the first success; it does not survive a restart.
     */
    val lastSuccessMs: Long? = null,
    /** The space key is unlocked, so pairing and pull can run. */
    val srkReady: Boolean? = null,
    /** The space is bound to a cloud account. */
    val accountOwned: Boolean? = null,
) {
    /** This device is in a sync space. */
    val hasActiveSpace: Boolean get() = syncSpaceId != null

    companion object {
        /**
         * Parse the runtime's `sync_status` payload. Missing or mistyped fields
         * become `null`; a missing `enabled` reads as `false`.
         */
        fun fromJson(json: JSONObject): SyncStatus = SyncStatus(
            enabled = json.opt("enabled") == true,
            syncSpaceId = (json.opt("sync_space_id") as? String)?.takeIf { it.isNotEmpty() },
            pendingCount = (json.opt("pending_count") as? Number)?.toInt(),
            lastSuccessMs = (json.opt("last_success_ms") as? Number)?.toLong(),
            srkReady = json.opt("srk_ready") as? Boolean,
            accountOwned = json.opt("account_owned") as? Boolean,
        )
    }
}
