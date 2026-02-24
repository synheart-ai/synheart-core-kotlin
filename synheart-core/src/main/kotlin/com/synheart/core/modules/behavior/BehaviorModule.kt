package com.synheart.core.modules.behavior

import com.synheart.core.modules.base.BaseSynheartModule
import com.synheart.core.modules.interfaces.BehaviorFeatureProvider
import com.synheart.core.modules.interfaces.BehaviorWindowFeatures
import com.synheart.core.modules.interfaces.CapabilityProvider
import com.synheart.core.modules.interfaces.ConsentProvider
import com.synheart.core.modules.interfaces.RawBehaviorDataProvider
import com.synheart.core.modules.interfaces.WindowType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/// Behavior Module
///
/// Captures user-device interaction patterns.
/// RFC-CORE-0007 compliant: no feature computation in Core.
class BehaviorModule(
    private val capabilities: CapabilityProvider,
    private val consent: ConsentProvider
) : BaseSynheartModule("behavior"), BehaviorFeatureProvider, RawBehaviorDataProvider {

    private val eventStream = BehaviorEventStream()
    private val aggregator = WindowAggregator()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var eventJob: kotlinx.coroutines.Job? = null
    private var cleanupJob: kotlinx.coroutines.Job? = null

    /// Get the event stream for recording events
    val eventStreamInstance: BehaviorEventStream
        get() = eventStream

    // MARK: - BehaviorFeatureProvider

    override fun features(window: WindowType): BehaviorWindowFeatures? {
        // Feature computation removed per RFC-CORE-0007.
        // Features will be computed by synheart-runtime when wired.
        return null
    }

    // MARK: - RawBehaviorDataProvider

    override fun rawEvents(window: WindowType): List<BehaviorEvent> {
        if (!consent.current().behavior) return emptyList()
        return aggregator.getEvents(window)
    }

    override suspend fun onInitialize() {
        println("[BehaviorModule] Initializing behavior tracking...")
    }

    override suspend fun onStart() {
        println("[BehaviorModule] Starting behavior tracking...")

        eventJob = eventStream.events
            .onEach { event ->
                if (consent.current().behavior) {
                    aggregator.addEvent(event)
                }
            }
            .launchIn(scope)

        cleanupJob = scope.launch {
            while (isActive) {
                delay(60000)
                aggregator.cleanOldWindows()
            }
        }

        println("[BehaviorModule] Behavior tracking started")
    }

    override suspend fun onStop() {
        println("[BehaviorModule] Stopping behavior tracking...")

        eventJob?.cancel()
        eventJob = null

        cleanupJob?.cancel()
        cleanupJob = null
    }

    override suspend fun onDispose() {
        println("[BehaviorModule] Disposing behavior module...")
        eventStream.dispose()
        scope.cancel()
    }
}
