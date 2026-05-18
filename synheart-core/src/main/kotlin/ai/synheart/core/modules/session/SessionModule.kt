package ai.synheart.core.modules.session

import ai.synheart.core.SynheartLogger
import ai.synheart.session.BehaviorProvider
import ai.synheart.session.BiosignalProvider
import ai.synheart.session.SessionConfig
import ai.synheart.session.SessionEvent
import ai.synheart.session.SessionErrorEvent
import ai.synheart.session.SessionStatus
import ai.synheart.session.SessionSummary
import ai.synheart.session.SynheartSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * Thin adapter around [SynheartSession] for core session management.
 *
 * Provides:
 * - Session start/stop delegated to [SynheartSession]
 * - SharedFlow<SessionEvent> → cold Flow<Map<String, Any>> bridge so core
 *   consumers see the on-the-wire shape Flutter / Swift emit
 * - HSI metrics ingestion pass-through (now session-scoped)
 *
 * Mirrors the Dart `WatchSessionModule` pattern.
 */
class SessionModule(
    biosignalProvider: BiosignalProvider,
    behaviorProvider: BehaviorProvider? = null,
) {

    private val session = SynheartSession(
        provider = biosignalProvider,
        behaviorProvider = behaviorProvider,
    )

    private var activeSessionId: String? = null

    /** Whether a session is currently active. */
    val isActive: Boolean get() = activeSessionId != null

    /** The active session ID, if any. */
    val currentSessionId: String? get() = activeSessionId

    /**
     * Start a session with the given [config].
     *
     * Returns a cold [Flow] of session event maps (the on-the-wire shape
     * shared with Flutter / Swift). The flow completes when the underlying
     * session emits a [SessionSummary] or [SessionErrorEvent].
     *
     * @throws IllegalStateException if a session is already active.
     */
    fun startSession(config: SessionConfig): Flow<Map<String, Any>> {
        if (activeSessionId != null) {
            throw IllegalStateException(
                "A session is already active (id: $activeSessionId). " +
                    "Stop it before starting a new one."
            )
        }

        activeSessionId = config.sessionId
        SynheartLogger.log(
            "[SessionModule] Starting session ${config.sessionId} " +
                "(mode: ${config.mode.value}, duration: ${config.durationSec}s)"
        )

        val events = session.startSession(config)

        return callbackFlow {
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val job: Job = scope.launch {
                events.collect { event ->
                    trySend(event.toMap())
                    if (event is SessionSummary || event is SessionErrorEvent) {
                        SynheartLogger.log(
                            "[SessionModule] Session ${config.sessionId} ended " +
                                "(${event::class.simpleName})"
                        )
                        cleanup()
                        close()
                    }
                }
            }

            awaitClose {
                job.cancel()
                // If the flow collector is cancelled externally, stop the session.
                val sid = activeSessionId
                if (sid != null) {
                    try { session.stopSession(sid) } catch (_: Exception) {}
                    cleanup()
                }
            }
        }
    }

    /** Stop the active session. No-op if not active or sessionId mismatches. */
    fun stopSession(sessionId: String) {
        val sid = activeSessionId ?: return
        if (sid != sessionId) return

        SynheartLogger.log("[SessionModule] Stopping session $sid")
        try {
            session.stopSession(sid)
        } catch (e: Exception) {
            SynheartLogger.log("[SessionModule] Error stopping session: $e")
        }
    }

    /**
     * Forward pre-computed HSI metrics from the native runtime to the
     * session. HRV metrics (SDNN, RMSSD, pNN50) come from the runtime
     * which applies artifact filtering — the session SDK does not
     * compute HRV locally. No-op when no session is active.
     */
    fun ingestHsiMetrics(metrics: Map<String, Any>) {
        val sid = activeSessionId ?: return
        session.ingestHsiMetrics(sid, metrics)
    }

    /**
     * Status snapshot of the current session, as the on-the-wire map shape.
     * Returns null when no session is active.
     */
    fun getStatus(): Map<String, Any>? {
        val s: SessionStatus = session.getStatus() ?: return null
        return mapOf(
            "session_id" to s.sessionId,
            "active" to s.active,
            "last_seq" to s.lastSeq,
        )
    }

    private fun cleanup() {
        activeSessionId = null
    }
}
