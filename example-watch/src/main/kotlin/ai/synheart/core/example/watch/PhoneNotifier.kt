package ai.synheart.core.example.watch

import ai.synheart.session.SessionEvent
import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Sends session events to the phone.
 *
 * Shared by [SessionCommandService] and the watch UI: a session started on the
 * wrist has to reach the phone the same way a phone-started one does, or the
 * phone's card sits at "ready" while the watch is measuring.
 */
object PhoneNotifier {

    private const val TAG = "PhoneNotifier"

    /** Watch → phone. Must match WatchSessionRelay.EVENT_PATH. */
    const val EVENT_PATH = "/synheart/session/event"

    /** Watch → phone. One heart-rate sample per message. */
    const val HR_SAMPLE_PATH = "/synheart/session/hr_sample"

    /**
     * Send one heart-rate reading to the phone.
     *
     * The watch owns the sensor, so this is the phone's only biosignal source —
     * it has no PPG of its own. Without these the phone's engine has no
     * physiology and every canonical HSI axis stays at zero confidence.
     *
     * Fire-and-forget: a dropped sample is one missing beat, and blocking the
     * sensor callback to retry would cost more than it recovers.
     */
    suspend fun sendHr(context: Context, timestampMs: Long, bpm: Int) {
        val payload = JSONObject()
            .put("ts_ms", timestampMs)
            .put("bpm", bpm)
            .toString()
            .toByteArray(Charsets.UTF_8)
        broadcast(context, HR_SAMPLE_PATH, payload, label = "hr")
    }

    /**
     * Broadcast one event to every connected phone.
     *
     * Broadcast rather than replying to a stored node id: a reconnect gives the
     * phone a new id, and events addressed to the old one vanish silently.
     */
    suspend fun send(context: Context, event: SessionEvent) {
        val payload = JSONObject(event.toMap()).toString().toByteArray(Charsets.UTF_8)
        broadcast(context, EVENT_PATH, payload, label = event::class.simpleName ?: "event")
    }

    private suspend fun broadcast(
        context: Context,
        path: String,
        payload: ByteArray,
        label: String,
    ) {
        val nodes = runCatching { Wearable.getNodeClient(context).connectedNodes.await() }
            .onFailure { Log.w(TAG, "connectedNodes failed: ${it.message}") }
            .getOrDefault(emptyList())

        if (nodes.isEmpty()) {
            Log.i(TAG, "no connected phone; $label not sent")
            return
        }
        for (node in nodes) {
            runCatching {
                Wearable.getMessageClient(context).sendMessage(node.id, path, payload).await()
            }.onFailure { Log.w(TAG, "$label to ${node.displayName} failed: ${it.message}") }
        }
    }
}
