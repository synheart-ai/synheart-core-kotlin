package ai.synheart.core.example.watch

import ai.synheart.session.SessionStarted
import ai.synheart.session.SessionSummary
import android.util.Log
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Lets the phone start and stop the watch's session.
 *
 * Receives `start_session` / `stop_session` on `/synheart/session/command`,
 * flips [WatchSessionState] — which the screen observes — and acknowledges on
 * `/synheart/session/event` so the phone knows it landed.
 *
 * A [WearableListenerService] rather than something the UI owns: the system
 * starts it on delivery, so the phone can start a session with the watch app
 * closed and never once opened.
 *
 * Deliberately does NOT run a session engine. The watch's job here is to stream
 * heart rate and agree with the phone about whether it is doing so; computing
 * windowed metrics on the wrist as well would be a second, divergent source of
 * truth for the same session.
 */
class SessionCommandService : WearableListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != COMMAND_PATH) return

        val json = runCatching { JSONObject(String(event.data, Charsets.UTF_8)) }
            .onFailure { Log.w(TAG, "unparseable command: ${it.message}") }
            .getOrNull() ?: return

        when (val command = json.optString("command")) {
            "start_session" -> start(json.optString("session_id"))
            "stop_session" -> stop(json.optString("session_id"))
            else -> Log.w(TAG, "unknown command: $command")
        }
    }

    private fun start(sessionId: String) {
        if (sessionId.isEmpty()) {
            Log.w(TAG, "start_session with no session_id")
            return
        }
        if (!WatchSessionState.start(sessionId)) {
            Log.w(TAG, "a session is already running; ignoring $sessionId")
            return
        }
        Log.i(TAG, "started $sessionId from phone")

        // Acknowledge immediately. The phone's relay gives up after a timeout,
        // so silence here reads as "no companion installed" even though the
        // session is running.
        scope.launch {
            PhoneNotifier.send(
                this@SessionCommandService,
                SessionStarted(sessionId = sessionId, startedAtMs = System.currentTimeMillis()),
            )
        }
    }

    private fun stop(sessionId: String) {
        val stopped = WatchSessionState.stop(sessionId.takeIf { it.isNotEmpty() }) ?: run {
            Log.w(TAG, "stop for $sessionId but nothing matching is running")
            return
        }
        Log.i(TAG, "stopped ${stopped.sessionId} from phone")

        scope.launch {
            PhoneNotifier.send(
                this@SessionCommandService,
                SessionSummary(
                    sessionId = stopped.sessionId,
                    durationActualSec = 0,
                    metrics = emptyMap(),
                ),
            )
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private companion object {
        const val TAG = "SessionCommandService"

        /** Must match WatchSessionRelay.COMMAND_PATH on the phone. */
        const val COMMAND_PATH = "/synheart/session/command"
    }
}
