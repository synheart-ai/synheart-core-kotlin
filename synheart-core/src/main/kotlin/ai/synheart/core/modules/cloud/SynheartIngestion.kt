package ai.synheart.core.modules.cloud

import ai.synheart.core.Synheart
import java.time.Instant

/**
 * Aggregate cloud-sync state for host UI.
 *
 * Collapses upload-queue depth, last-success time, and the cloud consent flag
 * into one of four user-facing buckets so the host renders a single pill
 * instead of combining signals by hand.
 */
enum class CloudSyncStatus {
    /** Everything queued has landed. */
    SYNCED,

    /** Work is in the queue right now. */
    SYNCING,

    /** Cloud is on and consented, but nothing has uploaded yet. */
    PENDING,

    /** Cloud upload is not consented — data stays on the device. */
    LOCAL_ONLY,
}

/** Outcome of one upload-queue flush. */
data class QueueFlushResult(
    val success: Boolean,
    val uploaded: Int,
    val failed: Int,
    val requeued: Int,
    val errorMessage: String? = null,
)

/** Point-in-time view of the outbound upload queue. */
data class QueueStatusSnapshot(
    val queueLength: Int,
    val lastUploadBatchId: String? = null,
    val lastUploadAt: Instant? = null,
    val lastUploadAttemptAt: Instant? = null,
    val lastUploadError: String? = null,
)

/** Result of handing a payload to the bridge-first ingestion path. */
data class IngestionSubmissionResponse(
    val success: Boolean,
    val statusCode: Int,
    val errorMessage: String? = null,
    val details: Map<String, Any?> = emptyMap(),
)

/**
 * Bridge-first ingestion facade for queue + upload orchestration.
 *
 * Reached through `Synheart.ingestion`. Every method routes through the native
 * runtime's upload queue — there is no separate HTTP path in the SDK.
 */
object SynheartIngestion {

    /** Current queue depth plus the last-upload bookkeeping. */
    val queueStatus: QueueStatusSnapshot
        get() = QueueStatusSnapshot(
            queueLength = Synheart.uploadQueueLength,
            lastUploadBatchId = Synheart.lastUploadBatchId,
            lastUploadAt = Synheart.lastUploadAt,
            lastUploadAttemptAt = Synheart.lastUploadAttemptAt,
            lastUploadError = Synheart.lastUploadError,
        )

    /** Enqueue raw HSI window payloads for the next flush. Blank entries are skipped. */
    fun enqueueHsiWindows(hsiJsons: List<String>, timestampMs: Long? = null) {
        val bridge = Synheart.runtimeBridge ?: return
        if (hsiJsons.isEmpty()) return
        val ts = timestampMs ?: System.currentTimeMillis()
        for (hsiJson in hsiJsons) {
            if (hsiJson.isBlank()) continue
            bridge.enqueueHsi(hsiJson, ts)
        }
    }

    /**
     * Explain a closed cloud gate in terms of what the caller can act on.
     *
     * `hasConsent` is not a simple read of the user's choice. Once a cloud
     * consent client is configured, the runtime returns false for EVERY
     * consent type until the consent service has issued a token, whatever the
     * user granted:
     *
     * ```rust
     * if cloud_configured && self.consent_status() != ConsentStatus::Granted {
     *     return false;
     * }
     * ```
     *
     * Reporting that as "consent not granted" sends developers to re-check a
     * consent screen that is already correct. The usual cause is a consent
     * service that never issued a token — commonly a `PROFILE_NOT_FOUND` on
     * the app id — so this separates "the user said no" from "the user said
     * yes and the cloud has not confirmed it".
     */
    private fun describeClosedCloudGate(): String {
        val effective = Synheart.consentEffectiveState()
        val grantedLocally = effective?.optBoolean("cloud_upload", false) == true ||
            effective?.optBoolean("cloudUpload", false) == true

        if (!grantedLocally) {
            return "cloudUpload consent not granted"
        }
        return "cloudUpload is granted locally, but the runtime is holding the " +
            "cloud gate closed: no consent token has been issued. Every consent " +
            "type reads as denied in this state, regardless of what the user chose. " +
            "Check the consent service for this app id — a missing default consent " +
            "profile (PROFILE_NOT_FOUND) is the usual cause."
    }

    /**
     * Flush the upload queue when the cloud gate is open.
     *
     * Pass `requireConsent = false` only for paths that have already verified
     * the gate themselves.
     */
    suspend fun flushIfEligible(requireConsent: Boolean = true): QueueFlushResult {
        val bridge = Synheart.runtimeBridge
        if (bridge == null) {
            Synheart.recordUploadAttempt(error = "core runtime bridge unavailable")
            return QueueFlushResult(
                success = false,
                uploaded = 0,
                failed = 0,
                requeued = 0,
                errorMessage = "core runtime bridge unavailable",
            )
        }
        if (requireConsent && !Synheart.hasConsent("cloudUpload")) {
            val why = describeClosedCloudGate()
            Synheart.recordUploadAttempt(error = why)
            return QueueFlushResult(
                success = false,
                uploaded = 0,
                failed = 0,
                requeued = 0,
                errorMessage = why,
            )
        }

        Synheart.recordUploadAttempt(error = null)
        val raw = bridge.flushUploads()
        val result = raw?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
        if (result == null) {
            Synheart.recordUploadAttempt(error = "flush_uploads returned null")
            return QueueFlushResult(
                success = false,
                uploaded = 0,
                failed = 0,
                requeued = 0,
                errorMessage = "flush_uploads returned null",
            )
        }

        val uploaded = result.optInt("uploaded", 0)
        val failed = result.optInt("failed", 0)
        val requeued = result.optInt("requeued", 0)
        Synheart.recordUploadSuccess(
            batchId = result.opt("batch_id")?.toString()?.takeIf { it.isNotEmpty() },
            uploaded = uploaded,
        )
        return QueueFlushResult(
            success = true,
            uploaded = uploaded,
            failed = failed,
            requeued = requeued,
        )
    }

    /**
     * Queue [hsiWindows] and flush, reporting the combined outcome. [payload]
     * is accepted for call-site symmetry with the cloud API; the bridge-first
     * path carries session artifacts through the queue, not a separate body.
     */
    suspend fun submitSessionArtifacts(
        payload: Map<String, Any?>,
        hsiWindows: List<String> = emptyList(),
    ): IngestionSubmissionResponse {
        Synheart.runtimeBridge ?: return IngestionSubmissionResponse(
            success = false,
            statusCode = 503,
            errorMessage = "core runtime bridge unavailable",
        )
        enqueueHsiWindows(hsiWindows)
        val flush = flushIfEligible()
        if (!flush.success) {
            return IngestionSubmissionResponse(
                success = false,
                statusCode = 403,
                errorMessage = flush.errorMessage,
                details = mapOf("payloadAccepted" to false),
            )
        }
        return IngestionSubmissionResponse(
            success = true,
            statusCode = 200,
            details = mapOf(
                "payloadAccepted" to true,
                "payloadKeys" to payload.keys.size,
                "uploaded" to flush.uploaded,
                "failed" to flush.failed,
                "requeued" to flush.requeued,
                "queuedWindows" to hsiWindows.size,
            ),
        )
    }

    /**
     * Accept a metadata payload. There is no separate metadata upload path in
     * the bridge-first SDK — the runtime attaches metadata on its own cadence
     * — so this reports acceptance rather than performing a round-trip.
     */
    fun submitMetadata(payload: Map<String, Any?>): IngestionSubmissionResponse {
        Synheart.runtimeBridge ?: return IngestionSubmissionResponse(
            success = false,
            statusCode = 503,
            errorMessage = "core runtime bridge unavailable",
        )
        return IngestionSubmissionResponse(
            success = true,
            statusCode = 202,
            details = mapOf(
                "accepted" to true,
                "payloadKeys" to payload.keys.size,
                "message" to "Metadata payload accepted by bridge-first SDK; " +
                    "no separate metadata upload path.",
            ),
        )
    }
}
