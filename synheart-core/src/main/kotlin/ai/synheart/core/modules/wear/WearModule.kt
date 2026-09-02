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
    private val sources: List<WearSourceHandler>? = null,
    /**
     * Whether to fall back to a synthetic generator when [sources] is null.
     *
     * See [ai.synheart.core.config.SynheartConfig.allowSyntheticBiosignals]:
     * this used to be unconditional, which made invented heart rates the only
     * biosignal source on Android and fed them into real SRM baselines.
     */
    private val allowSynthetic: Boolean = false
) : BaseSynheartModule("wear"), RawWearDataProvider {

    // An empty list rather than a mock: a module with no source reports no
    // signal, which is the truth. Fabricating one to avoid an empty screen is
    // what made "biosignals are working" indistinguishable from "biosignals are
    // invented".
    /** The sources this module is driving; empty when none is attached. */
    val attachedSources: List<WearSourceHandler> get() = actualSources

    private val actualSources = sources
        ?: if (allowSynthetic) listOf(MockWearSourceHandler()) else emptyList()
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

    /**
     * Drop every buffered sample. Used by the SDK's per-module erasure path;
     * the durable record, if any, lives in the runtime's storage.
     */
    suspend fun clearCache() {
        cache.clear()
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
                    SynheartLogger.log(
                        "[WearModule] WARNING: synthetic biosignal generator active — " +
                            "heart rate and HRV are INVENTED and will enter the runtime's " +
                            "longitudinal baselines. Never enable " +
                            "allowSyntheticBiosignals against a real subject.",
                    )
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
        val canonical = processor.processRamenEvent(
            provider = provider,
            eventType = eventType,
            payload = payload,
            eventId = eventId,
            seq = seq
        )
        if (canonical != null) _canonicalEvents.tryEmit(canonical)
        return canonical
    }

    private val _canonicalEvents =
        kotlinx.coroutines.flow.MutableSharedFlow<CanonicalWearableEvent>(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

    /**
     * Canonical vendor events, emitted after normalization and storage.
     *
     * Buffered and drop-oldest: a slow collector must not stall the stream
     * callback, which runs on a native thread.
     */
    val canonicalEvents: kotlinx.coroutines.flow.Flow<CanonicalWearableEvent> =
        _canonicalEvents.asSharedFlow()

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
