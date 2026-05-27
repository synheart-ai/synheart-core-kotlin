package ai.synheart.core.modules.wear

import ai.synheart.core.models.CanonicalWearableEvent
import ai.synheart.core.modules.base.BaseSynheartModule
import ai.synheart.core.modules.interfaces.CapabilityProvider
import ai.synheart.core.modules.interfaces.ConsentProvider
import ai.synheart.core.modules.interfaces.RawWearDataProvider
import ai.synheart.core.modules.interfaces.WindowType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ai.synheart.core.SynheartLogger

/** Collects and buffers raw biosignals from wearables. */
class WearModule(
    private val capabilities: CapabilityProvider,
    private val consent: ConsentProvider,
    private val sources: List<WearSourceHandler>? = null
) : BaseSynheartModule("wear"), RawWearDataProvider {

    private val actualSources = sources ?: listOf(MockWearSourceHandler())
    private val cache = WearCache()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobSet = mutableSetOf<kotlinx.coroutines.Job>()

    internal var eventProcessor: WearableEventProcessor? = null
        private set

    private val _vendorSyncState = MutableStateFlow(false)

    /** Emits `true`/`false` when vendor sync consent changes.
     *  The wear SDK (synheart_wear) subscribes to this to start/stop RAMEN. */
    val vendorSyncState: StateFlow<Boolean> = _vendorSyncState.asStateFlow()

    private val _sampleFlow = MutableSharedFlow<WearSample>()

    /** Live stream of incoming wear samples for downstream consumers. */
    val sampleFlow: Flow<WearSample> = _sampleFlow.asSharedFlow()

    override fun rawSamples(window: WindowType): List<WearSample> {
        if (!consent.current().biosignals) return emptyList()
        return cache.getSamples(window)
    }

    override suspend fun onInitialize() {
        SynheartLogger.log("[WearModule] Initializing wear sources...")

        actualSources.forEach { source ->
            if (source.isAvailable) {
                try {
                    source.initialize()
                    SynheartLogger.log("[WearModule] Initialized ${source.sourceType} source")
                } catch (e: Exception) {
                    SynheartLogger.log("[WearModule] Failed to initialize ${source.sourceType}: $e")
                }
            }
        }

        // Initialize vendor sync state from current consent
        val initialVendorSync = consent.current().vendorSync
        _vendorSyncState.value = initialVendorSync
        if (initialVendorSync) {
            SynheartLogger.log("[WearModule] Vendor sync enabled at init")
        }
    }

    override suspend fun onStart() {
        SynheartLogger.log("[WearModule] Starting wear data collection...")

        actualSources.forEach { source ->
            if (source.isAvailable) {
                val job = source.sampleFlow
                    .onEach { sample ->
                        cache.addSample(sample)
                        _sampleFlow.emit(sample)
                    }
                    .launchIn(scope)

                jobSet.add(job)

                if (source is MockWearSourceHandler) {
                    source.startGenerating()
                }
            }
        }

        // Track vendor sync consent changes
        val consentJob = consent.observe()
            .onEach { snapshot ->
                val vendorSyncNow = snapshot.vendorSync
                if (vendorSyncNow != _vendorSyncState.value) {
                    _vendorSyncState.value = vendorSyncNow
                    SynheartLogger.log(
                        "[WearModule] Vendor sync ${if (vendorSyncNow) "enabled" else "disabled"}"
                    )
                }
            }
            .launchIn(scope)
        jobSet.add(consentJob)

        SynheartLogger.log("[WearModule] Started ${jobSet.size} wear sources")
    }

    /**
     * Attach an event processor for RAMEN vendor events.
     *
     * Called by [Synheart] after runtime is initialized,
     * so the processor has access to CoreRuntimeBridge.
     */
    fun setEventProcessor(processor: WearableEventProcessor) {
        this.eventProcessor = processor
        SynheartLogger.log("[WearModule] Event processor attached")
    }

    /**
     * Process a raw RAMEN vendor event through the attached processor.
     *
     * @return the canonical event if processed, null if skipped or no processor.
     */
    fun processVendorEvent(
        provider: String,
        eventType: String,
        payload: Map<String, Any?>,
        eventId: String,
        seq: Int
    ): CanonicalWearableEvent? {
        if (!_vendorSyncState.value) {
            SynheartLogger.log("[WearModule] Vendor sync consent not granted — dropping $provider/$eventType")
            return null
        }
        val processor = eventProcessor
        if (processor == null) {
            SynheartLogger.log("[WearModule] No event processor attached -- ignoring vendor event")
            return null
        }
        return processor.processRamenEvent(
            provider = provider,
            eventType = eventType,
            payload = payload,
            eventId = eventId,
            seq = seq
        )
    }

    override suspend fun onStop() {
        SynheartLogger.log("[WearModule] Stopping wear data collection...")

        jobSet.forEach { it.cancel() }
        jobSet.clear()
    }

    override suspend fun onDispose() {
        SynheartLogger.log("[WearModule] Disposing wear module...")

        actualSources.forEach { source ->
            try {
                source.dispose()
            } catch (e: Exception) {
                SynheartLogger.log("[WearModule] Error disposing ${source.sourceType}: $e")
            }
        }

        scope.cancel()
    }
}
