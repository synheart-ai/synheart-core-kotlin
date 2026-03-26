package ai.synheart.core.modules.session

import ai.synheart.core.SynheartLogger
import ai.synheart.session.BehaviorProvider
import ai.synheart.session.BiosignalProvider
import ai.synheart.session.SessionConfig
import ai.synheart.session.SessionEngine
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Thin adapter around [SessionEngine] for core session management.
 *
 * Provides:
 * - Session start/stop delegated to [SessionEngine]
 * - Callback-to-Flow bridge so that core consumers use idiomatic Kotlin Flow
 * - HSI metrics ingestion pass-through to the engine
 *
 * The underlying [SessionEngine] is composed, not merged -- the session SDK
 * remains standalone and can be used independently.
 *
 * Mirrors the Dart `WatchSessionModule` pattern.
 */
class SessionModule(
    biosignalProvider: BiosignalProvider,
    behaviorProvider: BehaviorProvider? = null
) {

    private val engine = SessionEngine(
        provider = biosignalProvider,
        behaviorProvider = behaviorProvider
    )

    private var activeSessionId: String? = null

    /** Whether a session is currently active. */
    val isActive: Boolean get() = activeSessionId != null

    /** The active session ID, if any. */
    val currentSessionId: String? get() = activeSessionId

    /**
     * Start a session with the given [config].
     *
     * Returns a [Flow] of session event maps. The flow completes when the
     * session ends (summary or error). The same events that the [SessionEngine]
     * emits via its callback are bridged into the returned cold flow.
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

        return callbackFlow {
            engine.start(config) { event ->
                trySend(event)

                val eventType = event["type"] as? String
                if (eventType == "session_summary" || eventType == "session_error") {
                    SynheartLogger.log(
                        "[SessionModule] Session ${event["session_id"]} ended ($eventType)"
                    )
                    cleanup()
                    close()
                }
            }

            awaitClose {
                // If the flow collector is cancelled externally, stop the engine.
                val sid = activeSessionId
                if (sid != null) {
                    try {
                        engine.stop(sid)
                    } catch (_: Exception) {}
                    cleanup()
                }
            }
        }
    }

    /**
     * Stop the active session.
     *
     * No-op if no session is active.
     */
    fun stopSession(sessionId: String) {
        val sid = activeSessionId ?: return
        if (sid != sessionId) return

        SynheartLogger.log("[SessionModule] Stopping session $sid")
        try {
            engine.stop(sid)
        } catch (e: Exception) {
            SynheartLogger.log("[SessionModule] Error stopping session: $e")
        }
    }

    /**
     * Forward pre-computed HSI metrics from synheart-runtime to the session
     * engine. HRV metrics (SDNN, RMSSD, pNN50) come from the Rust runtime
     * which applies artifact filtering -- the session SDK does not compute
     * HRV locally.
     */
    fun ingestHsiMetrics(metrics: Map<String, Any>) {
        engine.ingestHsiMetrics(metrics)
    }

    /** Get the status of the current session from the engine. */
    fun getStatus(): Map<String, Any>? {
        return engine.getStatus()
    }

    private fun cleanup() {
        activeSessionId = null
    }
}
