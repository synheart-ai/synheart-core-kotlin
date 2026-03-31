package ai.synheart.core.modules.cloud

import android.content.Context
import ai.synheart.core.config.CloudConfig
import ai.synheart.core.modules.base.BaseSynheartModule
import ai.synheart.core.modules.consent.ConsentModule
import ai.synheart.core.modules.consent.ConsentToken
import ai.synheart.core.modules.runtime.RuntimeModule
import ai.synheart.core.modules.interfaces.CapabilityProvider
import ai.synheart.core.modules.interfaces.Module
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import ai.synheart.core.SynheartLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.json.JSONObject

/**
 * Cloud Connector Module
 *
 * Securely uploads HSV snapshots (as HSI 1.1 format) to Synheart Platform.
 *
 * Features:
 * - HMAC-SHA256 authentication or DeviceAuthProvider-based signing
 * - HSI schema validation/transformation via HsiSchemaTransformer
 * - Offline queue with persistence (max 100 snapshots, FIFO)
 * - Client-side rate limiting per window type
 * - Exponential backoff retry (3 attempts max)
 * - Network monitoring with auto-flush
 * - Consent and capability enforcement
 * - Consent token integration for JWT-based auth
 *
 * Architecture:
 * ```
 * RuntimeModule -> CloudConnector -> [Queue] -> UploadClient -> Platform
 *                    |
 *              HsiSchemaTransformer
 *              RateLimiter
 *              NetworkMonitor
 * ```
 */
class CloudConnectorModule(
    private val context: Context?,
    private val capabilities: CapabilityProvider,
    private val consent: ConsentModule,
    private val runtimeModule: RuntimeModule,
    private val config: CloudConfig
) : BaseSynheartModule("cloud") {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Components
    private lateinit var uploadClient: UploadClient
    private lateinit var uploadQueue: UploadQueue
    private lateinit var rateLimiter: RateLimiter
    private lateinit var networkMonitor: NetworkMonitor

    private val schemaTransformer = HsiSchemaTransformer()

    // Subscriptions
    private var hsvSubscription: Job? = null
    private var networkSubscription: Job? = null

    /** Last successful upload batch id (from API response). */
    var lastUploadBatchId: String? = null
        private set

    /** When the last successful upload completed. */
    var lastUploadAt: java.time.Instant? = null
        private set

    /** Last upload failure message (null after a success). */
    var lastUploadError: String? = null
        private set

    /** When the last upload attempt (success or failure) occurred. */
    var lastUploadAttemptAt: java.time.Instant? = null
        private set

    /** Total snapshots dropped due to schema validation failures (not retried). */
    var droppedSnapshotCount: Int = 0
        private set

    /** Optional callback fired when snapshots are dropped (e.g. schema validation failure). */
    var onSnapshotsDropped: ((droppedCount: Int, reason: String) -> Unit)? = null

    override suspend fun onInitialize() {
        SynheartLogger.log("[CloudConnector] Initializing...")

        // 1. Initialize components
        uploadClient = UploadClient(baseUrl = config.baseUrl)
        uploadQueue = UploadQueue(context = context, maxSize = config.maxQueueSize)
        rateLimiter = RateLimiter(capabilityProvider = capabilities)
        networkMonitor = NetworkMonitor(context = context)

        // 2. Load persisted queue
        uploadQueue.loadFromStorage()

        SynheartLogger.log("[CloudConnector] Initialized")
    }

    override suspend fun onStart() {
        SynheartLogger.log("[CloudConnector] Starting...")

        // 1. Subscribe to HSI stream from RuntimeModule
        hsvSubscription = scope.launch {
            runtimeModule.hsiFlow.collect { hsiJson ->
                hsiJson?.let { handleHSIUpdate(it) }
            }
        }

        // 2. Subscribe to network changes
        networkSubscription = scope.launch {
            networkMonitor.connectivityFlow.collect { isOnline ->
                handleNetworkChange(isOnline)
            }
        }

        // 3. Attempt to flush queue if online
        if (networkMonitor.isOnline) {
            scope.launch {
                flushQueue()
            }
        }

        SynheartLogger.log("[CloudConnector] Started")
    }

    override suspend fun onStop() {
        SynheartLogger.log("[CloudConnector] Stopping...")

        hsvSubscription?.cancel()
        networkSubscription?.cancel()

        // Attempt to flush remaining queue before stopping (with timeout)
        if (uploadQueue.hasItems && networkMonitor.isOnline) {
            SynheartLogger.log("[CloudConnector] Attempting to flush queue before stopping...")
            try {
                kotlinx.coroutines.withTimeout(30_000) {
                    flushQueue()
                }
            } catch (e: Exception) {
                SynheartLogger.log("[CloudConnector] Queue flush timeout or error during stop: $e")
            }
        }

        SynheartLogger.log("[CloudConnector] Stopped")
    }

    override suspend fun onDispose() {
        SynheartLogger.log("[CloudConnector] Disposing...")

        // Persist queue before disposal
        uploadQueue.persistToStorage()

        // Cleanup resources
        uploadClient.dispose()
        networkMonitor.dispose()

        SynheartLogger.log("[CloudConnector] Disposed")
    }

    /**
     * Handle HSI JSON update from RuntimeModule
     */
    private suspend fun handleHSIUpdate(hsiJson: String) {
        // Check consent
        if (!consent.current().cloudUpload) {
            return // Silent return - no upload
        }

        // Check capability
        if (capabilities.capability(Module.CLOUD) == ai.synheart.core.modules.interfaces.CapabilityLevel.NONE) {
            return // Silent return - no upload
        }

        // Check rate limit (based on window type, defaulting to "micro")
        val windowType = "micro"
        if (!rateLimiter.canUpload(windowType)) {
            return // Silent return - rate limited
        }

        // Enqueue for upload
        uploadQueue.enqueue(hsiJson)

        // Try immediate upload if online
        if (networkMonitor.isOnline) {
            attemptUpload()
        }
    }

    /**
     * Handle network connectivity change
     */
    private suspend fun handleNetworkChange(isOnline: Boolean) {
        if (isOnline) {
            SynheartLogger.log("[CloudConnector] Network available, flushing queue...")
            flushQueue()
        }
    }

    /**
     * Attempt to upload a batch from the queue.
     *
     * Uses HsiSchemaTransformer to patch each snapshot before upload.
     * Attaches consent token if available from ConsentModule.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun attemptUpload() {
        // Get batch of HSI JSON strings from queue
        val batch = uploadQueue.dequeueBatch(rateLimiter.batchSize)
        if (batch.isEmpty()) return

        try {
            // HSI JSON comes directly from synheart-runtime -- parse into JsonObjects
            // Apply schema transformation before upload
            val snapshots = batch.map { raw ->
                Json.parseToJsonElement(raw) as JsonObject
            }

            // Create upload payload
            val payload = UploadRequest(
                subject = Subject(
                    subjectType = config.subjectType,
                    subjectId = config.subjectId
                ),
                snapshots = snapshots
            )

            // Get consent token if available
            val consentToken: ConsentToken? = try {
                consent.getCurrentToken()
            } catch (_: Exception) {
                null
            }

            // Upload with Bearer token + optional device signing
            val consentToken = consent.getCurrentToken()
            val response = uploadClient.upload(
                payload = payload,
                consentToken = consentToken,
                deviceAuth = config.authProvider
            )

            // Success - remove from queue
            uploadQueue.confirmBatch(batch)

            // Update rate limiter
            val windowType = "micro"
            rateLimiter.recordUpload(windowType, batch.size)

            lastUploadBatchId = response.snapshotId
            lastUploadAt = java.time.Instant.now()
            lastUploadError = null
            lastUploadAttemptAt = java.time.Instant.now()

            SynheartLogger.log("[CloudConnector] Uploaded ${batch.size} snapshots (${response.status})")

        } catch (e: TokenExpiredError) {
            // Handle token expiration - try to refresh
            SynheartLogger.log("[CloudConnector] Token expired, attempting refresh...")
            lastUploadError = e.toString()
            lastUploadAttemptAt = java.time.Instant.now()
            try {
                consent.refreshTokenIfNeeded()
                // Retry upload with new token (will happen on next HSI update)
                uploadQueue.requeueBatch(batch)
                return
            } catch (refreshError: Exception) {
                SynheartLogger.log("[CloudConnector] Token refresh failed: $refreshError")
            }
            uploadQueue.requeueBatch(batch)

        } catch (e: SchemaValidationError) {
            // Schema validation failures are not retried -- drop the batch
            uploadQueue.confirmBatch(batch)
            droppedSnapshotCount += batch.size
            lastUploadError = e.toString()
            lastUploadAttemptAt = java.time.Instant.now()
            val reason = "Schema validation failed -- dropped ${batch.size} snapshots"
            SynheartLogger.log("[CloudConnector] $reason")
            onSnapshotsDropped?.invoke(batch.size, reason)

        } catch (e: CloudConnectorException) {
            // Re-enqueue batch on failure
            uploadQueue.requeueBatch(batch)
            lastUploadError = e.toString()
            lastUploadAttemptAt = java.time.Instant.now()
            SynheartLogger.log("[CloudConnector] Upload failed: ${e.message}")

        } catch (e: Exception) {
            // Re-enqueue batch on unexpected error
            uploadQueue.requeueBatch(batch)
            lastUploadError = e.toString()
            lastUploadAttemptAt = java.time.Instant.now()
            SynheartLogger.log("[CloudConnector] Upload failed with unexpected error: ${e.message}")
        }
    }

    // MARK: - Public API

    /**
     * Enqueue raw HSI JSON strings collected externally (e.g. from a foreground
     * session that ended before the Cloud Connector flushed) so they are
     * included in the next [uploadNow] / [flushQueue] call.
     */
    suspend fun enqueueSnapshots(hsiJsons: List<String>) {
        if (!consent.current().cloudUpload) return
        for (hsiJson in hsiJsons) {
            uploadQueue.enqueue(hsiJson)
        }
        SynheartLogger.log(
            "[CloudConnector] Enqueued ${hsiJsons.size} external HSI snapshots; queue=${uploadQueue.length}"
        )
    }

    /**
     * Force upload of queued snapshots now
     *
     * @throws ConsentRequiredError if cloudUpload consent not granted
     */
    suspend fun uploadNow() {
        if (!consent.current().cloudUpload) {
            throw ConsentRequiredError("cloudUpload consent required")
        }
        if (capabilities.capability(Module.CLOUD) == ai.synheart.core.modules.interfaces.CapabilityLevel.NONE) {
            throw CapabilityRequiredError("cloud capability required")
        }
        attemptUpload()
    }

    /**
     * Flush entire upload queue.
     *
     * Attempts to upload all queued snapshots while online.
     * Handles errors gracefully and does not throw exceptions.
     */
    suspend fun flushQueue() {
        var attempts = 0
        val maxAttempts = 100

        while (uploadQueue.hasItems && networkMonitor.isOnline && attempts < maxAttempts) {
            attempts++
            try {
                attemptUpload()
            } catch (e: Exception) {
                SynheartLogger.log("[CloudConnector] Upload attempt failed during flush (non-blocking): $e")
                if (e is NetworkError || e is CloudConnectorException) {
                    SynheartLogger.log("[CloudConnector] Stopping flush due to persistent error.")
                    break
                }
            }
            delay(100) // Throttle uploads
        }

        if (attempts >= maxAttempts) {
            SynheartLogger.log("[CloudConnector] Flush stopped after $maxAttempts attempts.")
        }
    }

    /**
     * Clear upload queue
     */
    suspend fun clearQueue() {
        uploadQueue.clear()
        SynheartLogger.log("[CloudConnector] Upload queue cleared")
    }

    /**
     * Get queue status
     */
    fun getQueueStatus(): QueueStatus {
        return QueueStatus(
            queueLength = uploadQueue.length,
            isOnline = networkMonitor.isOnline,
            hasConsent = consent.current().cloudUpload
        )
    }

    /** Pending snapshots in the upload queue. */
    val uploadQueueLength: Int
        get() = uploadQueue.length
}

/**
 * Queue status information
 */
data class QueueStatus(
    val queueLength: Int,
    val isOnline: Boolean,
    val hasConsent: Boolean
)
