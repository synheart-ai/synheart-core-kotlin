package ai.synheart.core.modules.behavior

import ai.synheart.core.SynheartDefaults
import ai.synheart.core.SynheartLogger
import ai.synheart.core.modules.base.BaseSynheartModule
import ai.synheart.core.modules.interfaces.CapabilityProvider
import ai.synheart.core.modules.interfaces.ConsentProvider
import ai.synheart.core.modules.interfaces.RawBehaviorDataProvider
import ai.synheart.core.modules.interfaces.WindowType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Captures user-device interaction patterns. */
class BehaviorModule(
    private val capabilities: CapabilityProvider,
    private val consent: ConsentProvider
) : BaseSynheartModule("behavior"), RawBehaviorDataProvider {

    private val eventStream = BehaviorEventStream()
    private val aggregator = WindowAggregator()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var eventJob: kotlinx.coroutines.Job? = null
    private var cleanupJob: kotlinx.coroutines.Job? = null

    val eventStreamInstance: BehaviorEventStream
        get() = eventStream

    override fun rawEvents(window: WindowType): List<BehaviorEvent> {
        if (!consent.current().behavior) return emptyList()
        return aggregator.getEvents(window)
    }

    override suspend fun onInitialize() {
        SynheartLogger.log("[BehaviorModule] Initializing behavior tracking...")
    }

    override suspend fun onStart() {
        SynheartLogger.log("[BehaviorModule] Starting behavior tracking...")

        eventJob = eventStream.events
            .onEach { event ->
                if (consent.current().behavior) {
                    aggregator.addEvent(event)
                }
            }
            .launchIn(scope)

        cleanupJob = scope.launch {
            while (isActive) {
                delay(SynheartDefaults.RUNTIME_WINDOW_MS)
                aggregator.cleanOldWindows()
            }
        }

        SynheartLogger.log("[BehaviorModule] Behavior tracking started")
    }

    override suspend fun onStop() {
        SynheartLogger.log("[BehaviorModule] Stopping behavior tracking...")
        eventJob?.cancel()
        eventJob = null
        cleanupJob?.cancel()
        cleanupJob = null
    }

    override suspend fun onDispose() {
        SynheartLogger.log("[BehaviorModule] Disposing behavior module...")
        eventStream.dispose()
        scope.cancel()
    }
}
