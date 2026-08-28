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

/** Whether background sync is currently enabled. */
data class SyncStatus(val enabled: Boolean)
