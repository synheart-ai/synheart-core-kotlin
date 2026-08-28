package ai.synheart.core.models

import org.json.JSONObject
import java.time.Instant

/**
 * Wire models for the customer-facing GDPR Article 17 endpoints.
 *
 * The cloud side returns these as JSON (`/v1/customer/data-deletions`); the
 * bridge round-trips the JSON through FFI as a UTF-8 string. These helpers
 * parse the resulting object into typed values.
 */

/** Lifecycle of a deletion request as reported by the cloud. */
enum class DataDeletionStatus {
    PENDING,
    IN_PROGRESS,
    COMPLETED,
    FAILED,
    UNKNOWN,
    ;

    /** True once the request has reached a terminal state (completed/failed). */
    val isTerminal: Boolean get() = this == COMPLETED || this == FAILED

    companion object {
        fun fromWire(value: String?): DataDeletionStatus = when (value) {
            "pending" -> PENDING
            "in_progress" -> IN_PROGRESS
            "completed" -> COMPLETED
            "failed" -> FAILED
            else -> UNKNOWN
        }
    }
}

/** A single GDPR Article 17 deletion request returned by the platform. */
data class DataDeletionRequest(
    /** Opaque public identifier (e.g. `ddr_01HX...`). Use this for status polls. */
    val requestId: String,
    val orgId: String,
    val tenantId: String,
    val userId: String,
    /**
     * Parsed status; never null. Falls back to [DataDeletionStatus.UNKNOWN]
     * when the cloud returns a value this SDK version doesn't recognize
     * (forward-compatible).
     */
    val status: DataDeletionStatus,
    /** Raw status string from the wire — useful for logging unknown values. */
    val statusRaw: String,
    val dryRun: Boolean,
    val createdAt: Instant,
    val reason: String? = null,
    val contact: String? = null,
    /**
     * Per-layer purge stats once `status == COMPLETED`. The shape is
     * intentionally flexible (the cloud adds fields independently of this SDK)
     * — typically `{s3: {…}, warehouse: {…}}`.
     */
    val result: JSONObject? = null,
    val errorMessage: String? = null,
    val startedAt: Instant? = null,
    val completedAt: Instant? = null,
    val failedAt: Instant? = null,
) {
    override fun toString(): String =
        "DataDeletionRequest($requestId, status=$statusRaw, userId=$userId)"

    companion object {
        fun fromJson(json: JSONObject): DataDeletionRequest {
            val rawStatus = json.opt("status")?.toString() ?: "unknown"
            return DataDeletionRequest(
                requestId = json.opt("request_id")?.toString().orEmpty(),
                orgId = json.opt("org_id")?.toString().orEmpty(),
                tenantId = json.opt("tenant_id")?.toString().orEmpty(),
                userId = json.opt("user_id")?.toString().orEmpty(),
                status = DataDeletionStatus.fromWire(rawStatus),
                statusRaw = rawStatus,
                dryRun = json.optBoolean("dry_run", false),
                reason = json.optNonBlank("reason"),
                contact = json.optNonBlank("contact"),
                result = json.optJSONObject("result"),
                errorMessage = json.optNonBlank("error_message"),
                // `created_at` is the one timestamp the platform always sends;
                // treat a missing one as epoch rather than throwing, so a
                // malformed row still surfaces its status to the host.
                createdAt = json.optInstant("created_at") ?: Instant.EPOCH,
                startedAt = json.optInstant("started_at"),
                completedAt = json.optInstant("completed_at"),
                failedAt = json.optInstant("failed_at"),
            )
        }
    }
}

/** Page of deletion requests returned by `GET /v1/customer/data-deletions`. */
data class DataDeletionList(
    val requests: List<DataDeletionRequest>,
    val total: Int,
) {
    companion object {
        fun fromJson(json: JSONObject): DataDeletionList {
            val arr = json.optJSONArray("requests")
            val requests = if (arr == null) {
                emptyList()
            } else {
                (0 until arr.length()).mapNotNull { i ->
                    arr.optJSONObject(i)?.let { DataDeletionRequest.fromJson(it) }
                }
            }
            return DataDeletionList(
                requests = requests,
                total = json.optInt("total", requests.size),
            )
        }
    }
}

/**
 * Real-time status update for a deletion request, pushed from the cloud over
 * the event stream. Narrower than [DataDeletionRequest] — only the fields the
 * cloud emits on each lifecycle transition plus the envelope fields the stream
 * routes by.
 *
 * Subscribe via `Synheart.onDataDeletionUpdate` to avoid polling
 * `dataDeletionStatus` after `Synheart.requestDataDeletion`.
 */
data class DataDeletionEvent(
    val requestId: String,
    val userId: String,
    val appId: String,
    val orgId: String,
    val tenantId: String,
    /**
     * Parsed status; never null. [DataDeletionStatus.UNKNOWN] for any wire
     * value this SDK version doesn't recognize.
     */
    val status: DataDeletionStatus,
    /** Raw wire string — log this when [status] is [DataDeletionStatus.UNKNOWN]. */
    val statusRaw: String,
    /** Timestamp the cloud emitted the event (best-effort, UTC). */
    val emittedAt: Instant,
    /**
     * Per-layer purge stats — present when [status] is
     * [DataDeletionStatus.COMPLETED]. Same shape as [DataDeletionRequest.result].
     */
    val result: JSONObject? = null,
    /** Set when [status] is [DataDeletionStatus.FAILED]. */
    val errorMessage: String? = null,
) {
    override fun toString(): String =
        "DataDeletionEvent($requestId, status=$statusRaw, userId=$userId)"

    companion object {
        /**
         * Parse from the JSON the runtime delivers on a stream callback.
         * [envelope] is the top-level event object (`event_type`,
         * `payload_json`, `created_at`, …); [payload] is the parsed inner
         * payload.
         *
         * [appId] and [userId] come from the connection-level identifiers
         * captured when the stream was started — the runtime event envelope
         * only carries event-level fields.
         */
        fun fromRuntimeJson(
            envelope: JSONObject,
            payload: JSONObject,
            appId: String,
            userId: String,
        ): DataDeletionEvent {
            val rawStatus = payload.opt("status")?.toString() ?: "unknown"
            return DataDeletionEvent(
                requestId = payload.opt("request_id")?.toString().orEmpty(),
                userId = userId,
                appId = appId,
                orgId = payload.opt("org_id")?.toString().orEmpty(),
                tenantId = payload.opt("tenant_id")?.toString().orEmpty(),
                status = DataDeletionStatus.fromWire(rawStatus),
                statusRaw = rawStatus,
                result = payload.optJSONObject("result"),
                errorMessage = payload.optNonBlank("error_message"),
                emittedAt = envelope.optInstant("created_at") ?: Instant.now(),
            )
        }
    }
}

/** Read a string field, collapsing absent/blank to null. */
internal fun JSONObject.optNonBlank(key: String): String? =
    optString(key).takeIf { it.isNotBlank() }

/** Parse an ISO-8601 timestamp field, tolerating absent and malformed values. */
internal fun JSONObject.optInstant(key: String): Instant? {
    val raw = optString(key).takeIf { it.isNotEmpty() } ?: return null
    return runCatching { Instant.parse(raw) }.getOrNull()
}
