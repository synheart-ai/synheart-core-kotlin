package ai.synheart.core.modules.cloud

import android.content.Context
import ai.synheart.core.config.CloudConfig
import ai.synheart.core.modules.base.BaseSynheartModule
import ai.synheart.core.modules.consent.ConsentModule
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

/**
 * Cloud Connector Module
 *
 * Securely uploads HSV snapshots (as HSI 1.1 format) to Synheart Platform.
 *
 * Features:
 * - HMAC-SHA256 authentication
 * - Offline queue with persistence (max 100 snapshots, FIFO)
 * - Client-side rate limiting per window type
 * - Exponential backoff retry (3 attempts max)
 * - Network monitoring with auto-flush
 * - Consent and capability enforcement
 *
 * Architecture:
 * ```
 * RuntimeModule → CloudConnector → [Queue] → UploadClient → Platform
 *                    ↓
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
    private var hmacSigner: HMACSigner? = null
    private lateinit var uploadClient: UploadClient
    private lateinit var uploadQueue: UploadQueue
    private lateinit var rateLimiter: RateLimiter
    private lateinit var networkMonitor: NetworkMonitor

    // Subscriptions
    private var hsvSubscription: Job? = null
    private var networkSubscription: Job? = null

    override suspend fun onInitialize() {
        SynheartLogger.log("[CloudConnector] Initializing...")

        // 1. Initialize components
        if (config.hmacSecret != null) {
            hmacSigner = HMACSigner(hmacSecret = config.hmacSecret)
        }
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
     * Attempt to upload a batch from the queue
     */
    private suspend fun attemptUpload() {
        // Get batch of HSI JSON strings from queue
        val batch = uploadQueue.dequeueBatch(rateLimiter.batchSize)
        if (batch.isEmpty()) return

        try {
            // HSI JSON comes directly from synheart-runtime -- parse into JsonObjects
            val snapshots = batch.map { raw ->
                kotlinx.serialization.json.Json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject
            }

            // Create upload payload
            val payload = UploadRequest(
                subject = Subject(
                    subjectType = config.subjectType,
                    subjectId = config.subjectId
                ),
                snapshots = snapshots
            )

            // Sign and upload
            val response = uploadClient.upload(
                payload = payload,
                signer = hmacSigner,
                tenantId = config.tenantId,
                authProvider = config.authProvider
            )

            // Success - remove from queue
            uploadQueue.confirmBatch(batch)

            // Update rate limiter
            val windowType = "micro"
            rateLimiter.recordUpload(windowType, batch.size)

            SynheartLogger.log("[CloudConnector] Uploaded ${batch.size} snapshots (${response.status})")

        } catch (e: CloudConnectorException) {
            // Re-enqueue batch on failure
            uploadQueue.requeueBatch(batch)
            SynheartLogger.log("[CloudConnector] Upload failed: ${e.message}")
        } catch (e: Exception) {
            // Re-enqueue batch on unexpected error
            uploadQueue.requeueBatch(batch)
            SynheartLogger.log("[CloudConnector] Upload failed with unexpected error: ${e.message}")
        }
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
     * Flush entire upload queue
     *
     * Attempts to upload all queued snapshots while online.
     */
    suspend fun flushQueue() {
        while (uploadQueue.hasItems && networkMonitor.isOnline) {
            attemptUpload()
            delay(100) // Throttle uploads
        }
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
}

/**
 * Queue status information
 */
data class QueueStatus(
    val queueLength: Int,
    val isOnline: Boolean,
    val hasConsent: Boolean
)
