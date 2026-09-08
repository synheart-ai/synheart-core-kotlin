package ai.synheart.core.modules.behavior

import ai.synheart.core.SynheartDefaults
import ai.synheart.core.SynheartLogger
import ai.synheart.core.modules.base.BaseSynheartModule
import ai.synheart.core.modules.interfaces.CapabilityProvider
import ai.synheart.core.modules.interfaces.ConsentProvider
import ai.synheart.core.modules.interfaces.RawBehaviorDataProvider
import ai.synheart.core.modules.interfaces.WindowType
import ai.synheart.core.models.BehaviorEventInput
import ai.synheart.core.models.ContextEventInput
import ai.synheart.core.models.MouseEventType
import android.content.Context
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
     * The rich behavior-event sink, preferred over [pushBehaviorToRuntime].
     *
     * Must return the runtime's acceptance, or **`null` when the loaded runtime
     * does not export `synheart_core_push_behavior_event`** — that `null` is
     * what makes the module fall back to the legacy int-coded path, so a sink
     * that swallowed it into `false` would silently drop every event on an
     * older runtime instead of degrading.
     */
    var pushBehaviorEventToRuntime: ((BehaviorEventInput) -> Boolean?)? = null

    /**
     * The context-evidence sink — a *second*, independent channel, not an
     * alternative to [pushBehaviorEventToRuntime].
     *
     * The two land in different runtime buffers with different consumers: the
     * behaviour channel feeds the interaction adapter and session-runtime's
     * behavioural features, while this one feeds the person-relative context
     * window, which is the only source of `context.deviation.*` — and therefore
     * the only source of Cognitive Load's friction index. One event on each
     * channel per user action is correct and is **not** a double count.
     *
     * Returns the runtime's acceptance, or `null` when the symbol is absent. A
     * `false` most often means the runtime was built without the `app-context`
     * cargo feature, which compiles the call as an inert stub.
     */
    var pushContextEventToRuntime: ((ContextEventInput) -> Boolean?)? = null

    /** Context events accepted / rejected by the runtime this session. */
    var contextEventsAccepted: Long = 0
        private set
    var contextEventsRejected: Long = 0
        private set
    private var contextRejectionLogged = false

    /**
     * Push a context event the host derived itself.
     *
     * This is how **keyboard** evidence reaches the engine: the module's own
     * translator forwards nothing for keystrokes, because only the text layer
     * can tell an insertion from a deletion. Send
     * [ContextEventInput.textChange] for every insertion *and* every deletion —
     * `err_rate` is `N_corr / N_key`, so corrections without keystrokes leave
     * the denominator at zero.
     */
    fun pushHostContextEvent(event: ContextEventInput) = pushContextEvent(event)

    private fun pushContextEvent(event: ContextEventInput?) {
        if (event == null) return
        val accepted = runCatching { pushContextEventToRuntime?.invoke(event) }.getOrNull() ?: return
        if (accepted) {
            contextEventsAccepted++
            return
        }
        contextEventsRejected++
        if (!contextRejectionLogged) {
            contextRejectionLogged = true
            SynheartLogger.log(
                "[BehaviorModule] push_context_event rejected the payload. Far more likely to " +
                    "mean the runtime was built without the `app-context` cargo feature (an " +
                    "inert stub that always returns 1) than a malformed payload — the shape is " +
                    "pinned by ContextEventInputTest. Without a context layer, CFI and the " +
                    "context deviation terms stay at zero.",
            )
        }
    }

    /**
     * The accelerometer behind [pushAccelToRuntime].
     *
     * Attached by `Synheart` when `BehaviorConfig.emitRawMotionSamples` is on.
     * Without it the forwarder is a callback with no sensor feeding it — which
     * is what every Kotlin host had, since this module is fed by the host's
     * touch dispatch rather than by the `synheart-behavior` collectors, and so
     * the kinematic modality never reached the runtime on Android.
     */
    private var accelForwarder: AccelForwarder? = null

    /** Raw accelerometer samples forwarded this session. For diagnostics. */
    val accelSamplesForwarded: Long get() = accelForwarder?.forwarded ?: 0

    /** Whether the accelerometer is currently registered and forwarding. */
    val isForwardingAccel: Boolean get() = accelForwarder?.isRunning == true

    /**
     * Wire the platform accelerometer. Idempotent; a no-op when [enabled] is
     * false. Starts with the module (consent-gated) rather than immediately.
     */
    fun attachAccelerometer(context: Context, enabled: Boolean) {
        if (!enabled || accelForwarder != null) return
        accelForwarder = AccelForwarder(context) { ts, ax, ay, az ->
            // Consent-gated on the same branch as behavior events: motion the
            // host may not retain must not reach the engine either.
            if (consent.current().behavior) {
                runCatching { pushAccelToRuntime?.invoke(ts, ax, ay, az) }
            }
        }
    }

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
                    //
                    // Two paths, and the rich one is tried first. It carries
                    // whatever payload the event has — scroll distance, tap
                    // position — where the legacy int-coded call flattens
                    // everything to a single double. The legacy path is the
                    // fallback for a vendored runtime that predates
                    // `synheart_core_push_behavior_event`, and the two are
                    // mutually exclusive per event: pushing both would count
                    // every interaction twice.
                    val rich = translateBehaviorEvent(event)
                    val richStatus = if (rich == null) {
                        null
                    } else {
                        runCatching { pushBehaviorEventToRuntime?.invoke(rich) }.getOrNull()
                    }
                    if (richStatus == null) {
                        runtimeCodeFor(event.type)?.let { code ->
                            runCatching {
                                pushBehaviorToRuntime?.invoke(event.timestamp, code, 1.0)
                            }
                        }
                    }

                    // The context channel, in addition to whichever behaviour
                    // path ran above. Independent buffer, independent consumer
                    // — see [pushContextEventToRuntime]. Without this the
                    // context window sees no events, so pause / error / scroll
                    // deviation are structurally zero and CFI has no inputs.
                    pushContextEvent(translateContextEvent(event))
                }
            }
            .launchIn(scope)

        cleanupJob = scope.launch {
            while (isActive) {
                delay(SynheartDefaults.RUNTIME_WINDOW_MS)
                aggregator.cleanOldWindows()
            }
        }

        accelForwarder?.let { fwd ->
            if (fwd.start()) {
                SynheartLogger.log("[BehaviorModule] accelerometer forwarding at 50 Hz")
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
        accelForwarder?.stop()
    }

    override suspend fun onDispose() {
        SynheartLogger.log("[BehaviorModule] Disposing behavior module...")
        eventStream.dispose()
        accelForwarder?.stop()
        accelForwarder = null
        scope.cancel()
    }

    internal companion object {
        /**
         * Translate a host-recorded [BehaviorEvent] into the engine's rich form.
         *
         * The Kotlin module's events come from `Synheart.recordTouchEvent`, so
         * they are thinner than the Flutter plugin's — a tap carries its
         * position and a scroll its travelled distance, and neither carries a
         * velocity or a direction. Only what is actually present is forwarded:
         * a missing velocity stays missing rather than becoming `0.0`, because
         * the engine withholds a null and renormalises it out whereas a zero is
         * a measured stillness that moves the score.
         *
         * Returns `null` for an event the engine has no variant for, so the
         * caller skips the push entirely rather than inventing one. Keystrokes
         * are deliberately in that set: a single `KEY_DOWN` is not a
         * `TypingSessionData` window, and mapping it onto one would land a
         * session whose every field is null — an observation with no evidence.
         * The host aggregates keystrokes into 10 s micro-windows and pushes
         * `BehaviorEventInput.typing` itself; the raw keystroke keeps taking
         * the legacy touch path so the two are never double-counted.
         */
        fun translateBehaviorEvent(event: BehaviorEvent): BehaviorEventInput? = when (event.type) {
            BehaviorEventType.TAP -> BehaviorEventInput.touch(event.timestamp)
            BehaviorEventType.SCROLL -> BehaviorEventInput.scroll(event.timestamp)
            BehaviorEventType.APP_SWITCH -> BehaviorEventInput.appSwitch(event.timestamp)
            BehaviorEventType.NOTIFICATION_RECEIVED ->
                BehaviorEventInput.notification(event.timestamp)
            BehaviorEventType.NOTIFICATION_OPENED ->
                BehaviorEventInput.notification(
                    event.timestamp,
                    action = ai.synheart.core.models.InterruptionAction.OPENED,
                )
            BehaviorEventType.KEY_DOWN, BehaviorEventType.KEY_UP -> null
        }

        /**
         * Translate a host-recorded [BehaviorEvent] into a **context** event.
         *
         * This differs from the Flutter SDK's translator in one honest way:
         * here a `TAP` really is a pointer tap. The Kotlin module is fed by
         * `Synheart.recordTouchEvent` (real `MotionEvent`s), and keystrokes
         * arrive separately as `KEY_DOWN`, so a tap is never a keystroke in
         * disguise and forwarding it as `LeftClick` does not inflate `N_click`
         * with typing.
         *
         * Keystrokes are still deliberately dropped. `KEY_DOWN` carries no
         * insertion/deletion classification, and the review's rule stands:
         * keyboard evidence enters **exclusively** from the host's text layer
         * via [ContextEventInput.textChange], where the two are
         * distinguishable. Forwarding `KEY_DOWN` here as well would count each
         * keystroke twice on the same channel.
         *
         * A scroll carries only a travelled distance — no direction, no
         * velocity — so it is forwarded as a `Scroll` with both null. It still
         * counts toward the scroll rate; it cannot contribute a reversal, and
         * inventing a direction would fabricate one.
         */
        fun translateContextEvent(event: BehaviorEvent): ContextEventInput? = when (event.type) {
            BehaviorEventType.TAP ->
                ContextEventInput.mouse(event.timestamp, MouseEventType.LEFT_CLICK)
            BehaviorEventType.SCROLL ->
                ContextEventInput.mouse(event.timestamp, MouseEventType.SCROLL)
            // Interruptions and attention boundaries are not input evidence;
            // they reach the engine on the behaviour channel.
            BehaviorEventType.APP_SWITCH,
            BehaviorEventType.NOTIFICATION_RECEIVED,
            BehaviorEventType.NOTIFICATION_OPENED,
            BehaviorEventType.KEY_DOWN,
            BehaviorEventType.KEY_UP -> null
        }
    }
}
