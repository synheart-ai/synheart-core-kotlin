package com.synheart.core.modules.wear

import com.synheart.core.modules.base.BaseSynheartModule
import com.synheart.core.modules.interfaces.CapabilityProvider
import com.synheart.core.modules.interfaces.ConsentProvider
import com.synheart.core.modules.interfaces.RawWearDataProvider
import com.synheart.core.modules.interfaces.WearFeatureProvider
import com.synheart.core.modules.interfaces.WearWindowFeatures
import com.synheart.core.modules.interfaces.WindowType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import com.synheart.core.SynheartLogger

/// Wear Module
///
/// Collects and buffers raw biosignals from wearables.
/// RFC-CORE-0007 compliant: no feature computation in Core.
class WearModule(
    private val capabilities: CapabilityProvider,
    private val consent: ConsentProvider,
    private val sources: List<WearSourceHandler>? = null
) : BaseSynheartModule("wear"), WearFeatureProvider, RawWearDataProvider {

    private val actualSources = sources ?: listOf(MockWearSourceHandler())
    private val cache = WearCache()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobSet = mutableSetOf<kotlinx.coroutines.Job>()

    private val _sampleFlow = MutableSharedFlow<WearSample>()

    /** Live stream of incoming wear samples for downstream consumers (e.g. RuntimeModule). */
    val sampleFlow: Flow<WearSample> = _sampleFlow.asSharedFlow()

    // MARK: - WearFeatureProvider

    override fun features(window: WindowType): WearWindowFeatures? {
        // Feature computation removed per RFC-CORE-0007.
        // Features will be computed by synheart-runtime when wired.
        return null
    }

    // MARK: - RawWearDataProvider

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

        SynheartLogger.log("[WearModule] Started ${jobSet.size} wear sources")
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
