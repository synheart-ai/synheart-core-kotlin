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

    /**
     * Forwards each behavior event into the native runtime.
     *
     * Wired by `Synheart` once the bridge exists. Without it the module
     * aggregates events for the host-facing stream and the engine receives
     * nothing, so interaction produces no HSI — the failure is silent, because
     * an idle engine and a starved one look identical from outside.
     *
     * The runtime takes the numeric [RuntimeBehaviorEvent] code, not a name.
     */
    var pushBehaviorToRuntime: ((tsMs: Long, eventType: Int, value: Double) -> Unit)? = null

    /**
     * Forwards raw accelerometer samples into the native runtime so it can
     * derive kinematic features and run the motion classifier.
     */
    var pushAccelToRuntime: ((tsMs: Long, ax: Double, ay: Double, az: Double) -> Unit)? = null

    /**
     * Map an SDK-level event to the runtime's behavior code.
     *
     * Null for events the runtime has no code for — those still reach the
     * host-facing stream and the aggregator, they just do not cross the FFI
     * boundary.
     */
    private fun runtimeCodeFor(type: BehaviorEventType): Int? = when (type) {
        BehaviorEventType.TAP -> RuntimeBehaviorEvent.INPUT.code
        // Keystrokes are interaction too; the runtime models them under the
        // same input code. Only the down edge is forwarded, so a single press
        // is not counted twice.
        BehaviorEventType.KEY_DOWN -> RuntimeBehaviorEvent.INPUT.code
        BehaviorEventType.KEY_UP -> null
        BehaviorEventType.SCROLL -> RuntimeBehaviorEvent.SCROLL.code
        BehaviorEventType.APP_SWITCH -> RuntimeBehaviorEvent.APP_SWITCH.code
        BehaviorEventType.NOTIFICATION_RECEIVED -> RuntimeBehaviorEvent.NOTIFICATION.code
        BehaviorEventType.NOTIFICATION_OPENED -> RuntimeBehaviorEvent.NOTIFICATION.code
    }

    override fun rawEvents(window: WindowType): List<BehaviorEvent> {
        if (!consent.current().behavior) return emptyList()
        return aggregator.getEvents(window)
    }

    /**
     * Drop every buffered event. Used by the SDK's per-module erasure path;
     * the durable record, if any, lives in the runtime's storage.
     */
    suspend fun clearCache() {
        aggregator.clear()
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
                    // Consent-gated on the same branch as the aggregator: an
                    // event the host may not retain must not reach the engine
                    // either.
                    runtimeCodeFor(event.type)?.let { code ->
                        runCatching { pushBehaviorToRuntime?.invoke(event.timestamp, code, 1.0) }
                    }
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
