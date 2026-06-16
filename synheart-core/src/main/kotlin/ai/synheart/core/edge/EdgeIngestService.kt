// SPDX-License-Identifier: Apache-2.0

package ai.synheart.core.edge

import ai.synheart.core.SynheartLogger
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.json.JSONObject

/**
 * Thin, **additive, opt-in** Android Wear Data Layer adapter for the phone-side
 * edge-ingest receiver. It is a [WearableListenerService] that decodes the
 * incoming Data Layer `path` (EDGE-WIRE-CONTRACT.md §1 table) into a body `type`, feeds
 * the body into a pure [EdgeIngest] core, and sends the resulting
 * `artifact_ack` back to the watch on the command channel via [Wearable]'s
 * `MessageClient`.
 *
 * **Nothing in the SDK wires this in by default.** To use it, a host app:
 *  1. Declares it in its own `AndroidManifest.xml` with the Wear message intent
 *     filters (it is exported so the Wear OS framework can deliver to it), and
 *  2. Installs an [EdgeIngestService.Bindings] from `Application.onCreate` so
 *     the service knows which [EdgeIngest.Listener] to deliver to.
 *
 * If [bindings] is null, messages are logged and dropped — a safe no-op default
 * that mirrors the watch producer's `PhoneListenerService`.
 *
 * All real logic lives in [EdgeIngest]; this class only does transport
 * decode/encode so it stays trivially correct and the logic stays unit-testable
 * without Android.
 */
open class EdgeIngestService : WearableListenerService() {

    /**
     * Host-supplied wiring. Provide the [EdgeIngest.Listener] that should
     * receive parsed messages. Optionally override [scope] (used for the
     * asynchronous ACK send) and [supportedHsiVersions].
     */
    interface Bindings {
        val listener: EdgeIngest.Listener
        val supportedHsiVersions: Set<String> get() = EdgeIngest.SUPPORTED_HSI_VERSIONS
        val scope: CoroutineScope get() = defaultScope
    }

    private val ingest: EdgeIngest? by lazy {
        bindings?.let { EdgeIngest(it.listener, it.supportedHsiVersions) }
    }

    override fun onMessageReceived(messageEvent: MessageEvent) {
        val core = ingest
        if (core == null) {
            SynheartLogger.log("[EdgeIngestService] no Bindings installed — dropping ${messageEvent.path}")
            return
        }

        val type = pathToType(messageEvent.path)
        if (type == null && messageEvent.path != COMMAND_PATH && messageEvent.path != PRESETS_PATH) {
            // Unknown path: still try to dispatch by the body's own `type`
            // (EDGE-WIRE-CONTRACT.md §1 requires every body to carry `type`).
            // EdgeIngest drops unknowns.
            SynheartLogger.log("[EdgeIngestService] unmapped path '${messageEvent.path}' — dispatching by body type")
        }

        val raw = try {
            String(messageEvent.data, Charsets.UTF_8)
        } catch (e: Throwable) {
            SynheartLogger.log("[EdgeIngestService] undecodable payload on ${messageEvent.path}: ${e.message}")
            return
        }

        // Feed the core (never throws). For artifacts, drain + send the ACK.
        // The core queues the id for ACK on accept, on duplicate (a lost
        // ACK must be re-sent or the watch resends forever), and on a poison-pill
        // dead-letter (ack-to-discard). So draining here covers all
        // ack-worthy outcomes; a normal (sub-threshold) hash mismatch queues
        // nothing and drainAckBody() returns null.
        core.onMessage(type, raw)

        if (type == EdgeIngest.TYPE_HSI_ARTIFACT) {
            val ackBody = core.drainAckBody() ?: return
            sendAck(messageEvent.sourceNodeId, ackBody)
        }
    }

    /** Send the `artifact_ack` body back to the source node on the command channel. */
    private fun sendAck(nodeId: String, ackBody: JSONObject) {
        val scope = bindings?.scope ?: defaultScope
        scope.launch {
            try {
                Wearable.getMessageClient(applicationContext)
                    .sendMessage(nodeId, COMMAND_PATH, ackBody.toString().toByteArray(Charsets.UTF_8))
                    .await()
            } catch (e: Throwable) {
                SynheartLogger.log("[EdgeIngestService] artifact_ack send failed: ${e.message}")
            }
        }
    }

    companion object {
        // EDGE-WIRE-CONTRACT.md §1 transport table (Android paths).
        const val HR_SAMPLE_PATH = "/synheart/session/hr_sample"
        const val BIO_SAMPLE_PATH = "/synheart/session/bio_sample"
        const val EVENT_PATH = "/synheart/session/event"
        const val ARTIFACT_PATH = "/synheart/session/artifact"
        const val COMMAND_PATH = "/synheart/session/command"
        const val PRESETS_PATH = "/synheart/presets"

        /** Long-lived scope for ACK sends when the host doesn't supply one. */
        private val defaultScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        /**
         * Host-supplied bindings. Null (default) ⇒ messages are dropped. Set
         * from `Application.onCreate`. Mirrors the watch producer's
         * `PhoneListenerService.bindings`.
         */
        @JvmStatic
        var bindings: Bindings? = null

        /**
         * Map a Wear Data Layer path (EDGE-WIRE-CONTRACT.md §1) to the body `type` used
         * by [EdgeIngest]. Returns null for paths that carry no sample/artifact
         * type (command/presets), or unknown paths; the core then falls back to
         * the body's own `type` field.
         */
        @JvmStatic
        fun pathToType(path: String): String? = when (path) {
            HR_SAMPLE_PATH -> EdgeIngest.TYPE_HR_SAMPLE
            BIO_SAMPLE_PATH -> EdgeIngest.TYPE_BIO_SAMPLE
            ARTIFACT_PATH -> EdgeIngest.TYPE_HSI_ARTIFACT
            EVENT_PATH -> null // event-specific type lives in the body (§3.4)
            else -> null
        }
    }
}
