package ai.synheart.core.modules.session

import ai.synheart.core.SynheartLogger
import ai.synheart.session.SessionConfig
import ai.synheart.session.SessionErrorEvent
import ai.synheart.session.SessionEvent
import ai.synheart.session.SessionSummary
import ai.synheart.session.WatchSessionRelay
import ai.synheart.session.WatchStatus
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

/**
 * Runs a session on a companion Wear OS watch.
 *
 * A thin adapter over `synheart-session`'s [WatchSessionRelay], mirroring the
 * sibling SDKs' watch session module. The session SDK stays standalone and
 * usable on its own; this only adds the lifecycle bookkeeping the facade needs
 * — a single active session, and a shared event stream that outlives any one
 * subscriber.
 *
 * The watch owns the session: it computes the metrics and decides when the
 * session ends. Nothing here derives anything.
 */
class WatchSessionModule(
    private val context: Context,
    private val scope: CoroutineScope,
    /**
     * Where a heart-rate sample from the watch goes.
     *
     * A callback rather than calling `Synheart.pushWearHr` directly: this module
     * is owned by the facade, and reaching back up into it would make the
     * dependency circular and untestable.
     */
    private val onHrSample: (timestampMs: Long, bpm: Double) -> Unit = { _, _ -> },
) {

    private var relay: WatchSessionRelay? = null
    private var activeSession: String? = null
    private var relayJob: kotlinx.coroutines.Job? = null
    private var hrJob: kotlinx.coroutines.Job? = null

    private val _events = MutableSharedFlow<SessionEvent>(
        // Replay so a screen that subscribes after the session started still
        // sees SessionStarted; a watch session is short and its whole event
        // history is worth keeping.
        replay = 64,
        extraBufferCapacity = 64,
    )

    /**
     * Events from the active watch session.
     *
     * `SessionStarted` → `SessionFrame*` → `SessionSummary`, or a
     * `SessionErrorEvent`. Shared, so several observers can watch one session.
     */
    val events: SharedFlow<SessionEvent> = _events.asSharedFlow()

    /** Whether a watch session is currently running. */
    val isActive: Boolean get() = activeSession != null

    /** The running session's id, or null. */
    val activeSessionId: String? get() = activeSession

    /** Create the relay and start forwarding biosignals. Safe to call twice. */
    fun initialize() {
        if (relay != null) return
        val r = WatchSessionRelay(context)
        relay = r
        SynheartLogger.log("[WatchSessionModule] Initialized")

        // Collected for the module's lifetime, not per session: the watch is the
        // only biosignal source here, and a sample that arrives between sessions
        // still belongs in the engine's longitudinal baselines.
        hrJob = scope.launch {
            runCatching {
                r.hrSamples().collect { sample ->
                    hrSampleCount++
                    onHrSample(sample.timestampMs, sample.bpm)
                }
            }.onFailure {
                SynheartLogger.log("[WatchSessionModule] hr stream ended: ${it.message}")
            }
        }
    }

    /** How many watch heart-rate samples have been forwarded to the engine. */
    var hrSampleCount: Int = 0
        private set

    /**
     * Watch connectivity, or null before [initialize].
     *
     * Null and [WatchStatus.UNAVAILABLE] mean different things: the first is
     * "this module was never set up", the second "this device cannot reach a
     * watch". Collapsing them would hide a wiring mistake behind a hardware
     * explanation.
     */
    suspend fun getWatchStatus(): WatchStatus? = relay?.status()

    /**
     * Start a session on the watch and return its event stream.
     *
     * @throws IllegalStateException if [initialize] has not run, or a session is
     *   already active — starting a second would leave the first running on the
     *   watch with nothing listening for its events.
     */
    fun startSession(config: SessionConfig): SharedFlow<SessionEvent> {
        val r = checkNotNull(relay) {
            "WatchSessionModule not initialized. Call initialize() first."
        }
        check(activeSession == null) {
            "A watch session is already active (id: $activeSession). " +
                "Stop it before starting a new one."
        }

        activeSession = config.sessionId
        SynheartLogger.log(
            "[WatchSessionModule] Starting session ${config.sessionId} " +
                "(mode: ${config.mode.value}, duration: ${config.durationSec}s)",
        )

        relayJob?.cancel()
        relayJob = scope.launch {
            relayEvents(r.startSession(config), config.sessionId)
        }
        return events
    }

    /**
     * Stop the active session. No-op when none is running.
     *
     * Clears the bookkeeping here rather than waiting for the relay's flow to
     * complete. A stop command does not itself end that flow — only the watch's
     * terminal event or the acknowledgement timeout does — so leaving it to the
     * flow meant `isActive` stayed true after an explicit stop, the UI kept
     * offering "Stop", and every later start was refused.
     *
     * The collector is cancelled too: a summary arriving after the user has
     * already stopped belongs to a session nothing is showing any more.
     */
    suspend fun stopSession() {
        val id = activeSession ?: return
        SynheartLogger.log("[WatchSessionModule] Stopping session $id")
        runCatching { relay?.stopSession(id) }
            .onFailure { SynheartLogger.log("[WatchSessionModule] stop failed: ${it.message}") }
        relayJob?.cancel()
        relayJob = null
        activeSession = null
    }

    /** Release the relay. The module can be re-[initialize]d afterwards. */
    fun dispose() {
        hrJob?.cancel()
        hrJob = null
        relayJob?.cancel()
        relayJob = null
        activeSession = null
        relay = null
        SynheartLogger.log("[WatchSessionModule] Disposed")
    }

    /**
     * Pump the relay's cold flow into [_events], clearing the active session on
     * a terminal event.
     *
     * The relay's flow completes on its own when the watch finishes, but the
     * bookkeeping has to clear even if it ends by cancellation or error —
     * otherwise `isActive` stays true forever and every later start throws.
     */
    private suspend fun relayEvents(source: Flow<SessionEvent>, sessionId: String) {
        try {
            source.collect { event ->
                _events.emit(event)
                if (event is SessionSummary || event is SessionErrorEvent) {
                    SynheartLogger.log(
                        "[WatchSessionModule] Session $sessionId ended " +
                            "(${event::class.simpleName})",
                    )
                }
            }
        } catch (e: Exception) {
            SynheartLogger.log("[WatchSessionModule] Session $sessionId stream error: ${e.message}")
        } finally {
            if (activeSession == sessionId) activeSession = null
        }
    }
}
