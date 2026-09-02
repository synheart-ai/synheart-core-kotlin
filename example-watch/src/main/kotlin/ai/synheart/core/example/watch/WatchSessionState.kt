package ai.synheart.core.example.watch

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the watch is doing right now. */
data class WatchSession(val sessionId: String)

/**
 * The single source of truth for whether a session is running on this watch.
 *
 * A process-wide object because the two things that drive a session do not
 * share a lifecycle: [SessionCommandService] is started by the system when the
 * phone sends a command — with the UI closed, or never opened — while
 * [WatchActivity] comes and goes with the screen. Without somewhere common to
 * look, a phone-started session left the watch UI showing its start button, and
 * a watch-started session was invisible to the phone.
 *
 * Held in memory only. A session does not outlive the process, so persisting it
 * would just resurrect one that is no longer running.
 */
object WatchSessionState {

    private val _session = MutableStateFlow<WatchSession?>(null)

    /** The running session, or null. Both the UI and the service observe this. */
    val session: StateFlow<WatchSession?> = _session.asStateFlow()

    /**
     * Mark a session started, unless one already is.
     *
     * Returns false when one was already running: a second start would leave the
     * first with nothing tracking it, and both sides would disagree about which
     * id is live.
     */
    fun start(sessionId: String): Boolean {
        if (_session.value != null) return false
        _session.value = WatchSession(sessionId)
        return true
    }

    /**
     * Mark the session stopped.
     *
     * Ignores a stop naming a different session — a late stop for one that
     * already ended must not cancel the one running now.
     */
    fun stop(sessionId: String? = null): WatchSession? {
        val current = _session.value ?: return null
        if (sessionId != null && sessionId != current.sessionId) return null
        _session.value = null
        return current
    }
}
