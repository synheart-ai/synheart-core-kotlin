package com.synheart.core.modules.cloud

import android.content.Context
import com.synheart.core.config.CloudConfig
import com.synheart.core.models.HumanStateVector
import com.synheart.core.models.HSIExportAccessContext
import com.synheart.core.models.toHSI10
import com.synheart.core.modules.base.BaseSynheartModule
import com.synheart.core.modules.consent.ConsentModule
import com.synheart.core.modules.hsv_runtime.HSVRuntimeModule
import com.synheart.core.modules.interfaces.CapabilityProvider
import com.synheart.core.modules.interfaces.Module
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Cloud Connector Module
 *
 * Securely uploads HSV snapshots (as HSI 1.0 format) to Synheart Platform.
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
 * HSIRuntime → CloudConnector → [Queue] → UploadClient → Platform
 *                    ↓
 *              RateLimiter
 *              NetworkMonitor
 * ```
 */
class CloudConnectorModule(
    private val context: Context?,
    private val capabilities: CapabilityProvider,
    private val consent: ConsentModule,
    private val hsvRuntime: HSVRuntimeModule,
    private val config: CloudConfig
) : BaseSynheartModule("cloud") {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Components
    private lateinit var hmacSigner: HMACSigner
    private lateinit var uploadClient: UploadClient
    private lateinit var uploadQueue: UploadQueue
    private lateinit var rateLimiter: RateLimiter
    private lateinit var networkMonitor: NetworkMonitor

    // Subscriptions
    private var hsvSubscription: Job? = null
    private var networkSubscription: Job? = null

    override suspend fun onInitialize() {
        println("[CloudConnector] Initializing...")

        // 1. Initialize components
        hmacSigner = HMACSigner(hmacSecret = config.hmacSecret)
        uploadClient = UploadClient(baseUrl = config.baseUrl)
        uploadQueue = UploadQueue(context = context, maxSize = config.maxQueueSize)
        rateLimiter = RateLimiter(capabilityProvider = capabilities)
        networkMonitor = NetworkMonitor(context = context)

        // 2. Load persisted queue
        uploadQueue.loadFromStorage()

        println("[CloudConnector] Initialized")
    }

    override suspend fun onStart() {
        println("[CloudConnector] Starting...")

        // 1. Subscribe to HSV stream
        hsvSubscription = scope.launch {
            hsvRuntime.hsvFlow.collect { hsv ->
                hsv?.let { handleHSVUpdate(it) }
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

        println("[CloudConnector] Started")
    }

    override suspend fun onStop() {
        println("[CloudConnector] Stopping...")

        hsvSubscription?.cancel()
        networkSubscription?.cancel()

        println("[CloudConnector] Stopped")
    }

    override suspend fun onDispose() {
        println("[CloudConnector] Disposing...")

        // Persist queue before disposal
        uploadQueue.persistToStorage()

        // Cleanup resources
        uploadClient.dispose()
        networkMonitor.dispose()

        println("[CloudConnector] Disposed")
    }

    /**
     * Handle HSV update from runtime
     */
    private suspend fun handleHSVUpdate(hsv: HumanStateVector) {
        // Check consent
        if (!consent.current().cloudUpload) {
            return // Silent return - no upload
        }

        // Check capability
        if (capabilities.capability(Module.CLOUD) == com.synheart.core.modules.interfaces.CapabilityLevel.NONE) {
            return // Silent return - no upload
        }

        // Check rate limit (based on window type, defaulting to "micro")
        val windowType = "micro" // TODO: Extract from HSV when available
        if (!rateLimiter.canUpload(windowType)) {
            return // Silent return - rate limited
        }

        // Enqueue for upload
        uploadQueue.enqueue(hsv)

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
            println("[CloudConnector] Network available, flushing queue...")
            flushQueue()
        }
    }

    /**
     * Attempt to upload a batch from the queue
     */
    private suspend fun attemptUpload() {
        // Get batch from queue
        val batch = uploadQueue.dequeueBatch(rateLimiter.batchSize)
        if (batch.isEmpty()) return

        try {
            // Convert HSV → HSI 1.0
            val hsi10Snapshots = batch.map { hsv ->
                val c = consent.current()
                hsv.toHSI10(
                    producerName = "Synheart Core SDK",
                    producerVersion = "1.0.0",
                    instanceId = config.instanceId,
                    access = HSIExportAccessContext(
                        capabilityHsi = capabilities.capability(Module.HSV_RUNTIME).name,
                        capabilityCloud = capabilities.capability(Module.CLOUD).name,
                        consentBiosignals = c.biosignals,
                        consentPhoneContext = c.phoneContext,
                        consentBehavior = c.behavior,
                        consentCloudUpload = c.cloudUpload,
                        consentEmotionEstimation = c.emotionEstimation,
                        consentFocusEstimation = c.focusEstimation
                    )
                )
            }

            // Create upload payload
            val payload = UploadRequest(
                subject = Subject(
                    subjectType = config.subjectType,
                    subjectId = config.subjectId
                ),
                snapshots = hsi10Snapshots
            )

            // Sign and upload
            val response = uploadClient.upload(
                payload = payload,
                signer = hmacSigner,
                tenantId = config.tenantId
            )

            // Success - remove from queue
            uploadQueue.confirmBatch(batch)

            // Update rate limiter
            val windowType = "micro" // TODO: Extract from batch
            rateLimiter.recordUpload(windowType, batch.size)

            println("[CloudConnector] Uploaded ${batch.size} snapshots (${response.status})")

        } catch (e: CloudConnectorException) {
            // Re-enqueue batch on failure
            uploadQueue.requeueBatch(batch)

            // Log error (but don't throw - this is background operation)
            println("[CloudConnector] Upload failed: ${e.message}")
            e.printStackTrace()
        } catch (e: Exception) {
            // Re-enqueue batch on unexpected error
            uploadQueue.requeueBatch(batch)

            println("[CloudConnector] Upload failed with unexpected error: ${e.message}")
            e.printStackTrace()
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
        if (capabilities.capability(Module.CLOUD) == com.synheart.core.modules.interfaces.CapabilityLevel.NONE) {
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
