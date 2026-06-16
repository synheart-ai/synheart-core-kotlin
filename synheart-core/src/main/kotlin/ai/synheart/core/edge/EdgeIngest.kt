// SPDX-License-Identifier: Apache-2.0

package ai.synheart.core.edge

import ai.synheart.core.SynheartLogger
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * Transport-independent, pure-JVM consumer of the Synheart **edge wire
 * contract** (watch → phone). This is the phone-side counterpart to the watch
 * producer in `synheart-core-kotlin-edge`. It exists so every app stops
 * re-implementing watch→phone ingest; the canonical parse/validate/dedupe/ack
 * logic lives here once.
 *
 * It has **no** Android or Play Services dependency, so it unit-tests under
 * plain JUnit. A thin [EdgeIngestService] adapter decodes the Wear Data Layer
 * `path`/`type` and feeds message bodies into this class; on iOS the analogous
 * adapter would route by the body `type`.
 *
 * ### What it does (per docs/EDGE-WIRE-CONTRACT.md)
 *  - **§3.1 `hr_sample`** → [Listener.onHrSample] (bpm, timestampMs, source?).
 *  - **§3.2 `bio_sample`** → [Listener.onBioSample] (bpm, timestampMs,
 *    rrIntervalsMs[], accel?, source?).
 *  - **§3.3 `hsi_artifact`** → validate `hsi_version` against
 *    [SUPPORTED_HSI_VERSIONS] (§0), verify `payload_hash_sha256` ==
 *    sha256(`payload_json`) (§5), dedupe by `artifact_id` (§5), then surface
 *    [Listener.onArtifact] and record the id as needing ACK.
 *  - **§3.4 / events** → [Listener.onSessionEvent] for `type` values that are
 *    not the three sample/artifact types above (e.g. `session_started`,
 *    `session_frame`, `session_summary`).
 *  - **§4/§5 ACK** → [drainPendingAcks] / [buildAckBody] produce the
 *    `{ "command":"artifact_ack", "artifact_ids":[...] }` body to send back on
 *    the command channel.
 *
 * Malformed or unknown bodies are dropped + logged; this class never throws.
 *
 * This class is thread-safe: the seen/pending sets are guarded by an internal
 * lock so a transport that delivers on multiple threads is safe.
 */
class EdgeIngest(
    private val listener: Listener,
    /**
     * Inner HSI payload versions this consumer understands (EDGE-WIRE-CONTRACT.md §0).
     * A payload outside this set is logged + flagged via
     * [Listener.onUnsupportedHsiVersion] but, per the contract, still surfaced
     * (not silently dropped) so producer drift is observable.
     */
    private val supportedHsiVersions: Set<String> = SUPPORTED_HSI_VERSIONS,
) {

    /** Typed callbacks for parsed, validated edge messages. */
    interface Listener {
        /** §3.1 — scalar HR sample. */
        fun onHrSample(sample: HrSample) {}

        /** §3.2 — full raw biosignal sample (HR + RR + optional accel). */
        fun onBioSample(sample: BioSample) {}

        /**
         * §3.3 — an accepted (hash-verified, non-duplicate) HSI artifact. Its
         * id has already been recorded as needing ACK ([drainPendingAcks]).
         */
        fun onArtifact(artifact: HsiArtifact) {}

        /**
         * §3.4 — any session event whose `type` is not one of the three
         * sample/artifact types (e.g. `session_started`, `session_frame`,
         * `session_summary`). Delivered as the raw body so the host can route
         * by `type` without this core taking a dependency on every event shape.
         */
        fun onSessionEvent(type: String, body: JSONObject) {}

        /**
         * §0 — the artifact's `hsi_version` is outside [supportedHsiVersions].
         * Observability hook; the artifact is still surfaced via [onArtifact]
         * if it otherwise validates.
         */
        fun onUnsupportedHsiVersion(artifactId: String, hsiVersion: String) {}

        /**
         * §5 — the artifact's `payload_hash_sha256` did not match
         * sha256(`payload_json`); the artifact is rejected (NOT surfaced, NOT
         * acked) on the first/normal mismatch.
         */
        fun onHashMismatch(artifactId: String, expected: String, actual: String) {}

        /**
         * §5 — a deterministically-corrupt artifact: the same `artifact_id`
         * mismatched its hash [POISON_PILL_THRESHOLD] times. It is dead-lettered
         * — surfaced here as a hard error AND ack-to-discarded so it stops
         * blocking the delete-on-ACK outbox. After this the id is treated as
         * seen (its ACK is queued); it will not be surfaced via [onArtifact].
         */
        fun onPoisonPill(artifactId: String, expected: String, actual: String, attempts: Int) {}
    }

    /** §3.1 */
    data class HrSample(
        val bpm: Double,
        val timestampMs: Long,
        val source: String?,
    )

    /** §2 — accel triple, in g. */
    data class Accel(val x: Double, val y: Double, val z: Double)

    /** §3.2 */
    data class BioSample(
        val bpm: Double,
        val timestampMs: Long,
        /** Empty when the producer omitted/emptied `rr_intervals_ms` (§2). */
        val rrIntervalsMs: List<Double>,
        val accel: Accel?,
        val source: String?,
    )

    /** §3.3 — the accepted artifact, decoded from the envelope. */
    data class HsiArtifact(
        val artifactId: String,
        val sessionId: String?,
        val seq: Int?,
        val createdAtMs: Long?,
        val schemaVersion: String?,
        val hsiVersion: String?,
        val payloadHashSha256: String,
        val payloadJson: String,
        val deliveryMode: String?,
        val sessionOrigin: String?,
        val sessionKind: String?,
        /**
         * True when [hsiVersion] is present AND inside the supported set (§0);
         * false when it is absent or outside the set (the artifact is still
         * surfaced — drift is flagged, not dropped). Mirrors the Swift
         * `Artifact.hsiVersionSupported` / Dart `HsiArtifact.hsiVersionSupported`.
         */
        val hsiVersionSupported: Boolean,
    )

    /**
     * Sealed family of typed edge events emitted on [events]. Mirrors the Dart
     * role model's `EdgeEvent` (`HrEvent | BioEvent | ArtifactEvent |
     * SessionEventWrap`) and the Swift `EdgeEvent` enum, so all three SDKs
     * expose the same reactive surface.
     */
    sealed class EdgeEvent {
        /** §3.1 — an HR sample was parsed. */
        data class HrEvent(val sample: HrSample) : EdgeEvent()

        /** §3.2 — a biosignal sample was parsed. */
        data class BioEvent(val sample: BioSample) : EdgeEvent()

        /** §3.3 — an artifact was accepted (verified + non-duplicate). */
        data class ArtifactEvent(val artifact: HsiArtifact) : EdgeEvent()

        /** §3.4 — a non-sample/-artifact session event (raw body passed through). */
        data class SessionEventWrap(val type: String, val body: JSONObject) : EdgeEvent()
    }

    private val lock = Any()

    /**
     * §5 dedupe set, bounded to [SEEN_LRU_CAPACITY] entries:
     * a `LinkedHashSet` whose insertion order is treated as LRU recency by
     * [recordSeenLocked] (re-insert to refresh, evict eldest over cap). Re-acking
     * a long-evicted stray is harmless per contract §5 (the watch's delete-on-ACK
     * outbox just clears it again), so a bounded LRU is safe and keeps memory
     * flat over a long-lived process.
     */
    private val seenArtifactIds = LinkedHashSet<String>()

    /** Per-id hash-mismatch counter for poison-pill detection. */
    private val mismatchCounts = LinkedHashMap<String, Int>()

    private val pendingAckIds = LinkedHashSet<String>()

    /**
     * Backing hot stream for [events]. `replay = 0` (no late-subscriber replay,
     * matching the Dart broadcast `Stream` and Swift `PassthroughSubject`);
     * `extraBufferCapacity` is generous so a synchronous emit from [onMessage]
     * never suspends even if a collector is briefly slow.
     *
     * AUTHORITATIVE DELIVERY: the synchronous [Listener] is the source of truth
     * for "received" — `acked + seen` state is updated independently of whether
     * any reactive collector observed the event. This [events] flow is an
     * additive, best-effort convenience: a [MutableSharedFlow.tryEmit] that
     * overflows the buffer is logged for observability but never
     * blocks ingest or affects ACK/dedupe.
     */
    private val _events = MutableSharedFlow<EdgeEvent>(
        replay = 0,
        extraBufferCapacity = 64,
    )

    /**
     * Reactive hot stream of typed [EdgeEvent]s (Flow ergonomics, parity with
     * the Dart role model's broadcast `Stream<EdgeEvent>` and the Swift
     * `events` publisher). NOT authoritative — the [Listener] is (see [_events]
     * and [emitEvent]). Emits in lock-step with the [Listener] callbacks;
     * supports multiple collectors. Additive — the [Listener] surface and all
     * existing behaviour are unchanged.
     */
    val events: SharedFlow<EdgeEvent> = _events.asSharedFlow()

    /**
     * Emit on the best-effort [events] flow, logging (not failing) on overflow.
     * The authoritative [Listener] callback has already fired by this point;
     * a dropped reactive emit never affects acked/seen state.
     */
    private fun emitEvent(event: EdgeEvent) {
        if (!_events.tryEmit(event)) {
            SynheartLogger.log(
                "[EdgeIngest] events flow overflow — dropped a reactive event " +
                    "(listener delivery is authoritative and already fired)"
            )
        }
    }

    /**
     * Feed a raw decoded message body into the consumer. The [type] is the
     * `type` field already read by the transport (EDGE-WIRE-CONTRACT.md §1
     * requires every body to carry `type`, even on Android where the path is
     * authoritative). When [type] is null/blank, falls back to the body's own `type`.
     *
     * Never throws: malformed bodies are dropped + logged.
     */
    fun onMessage(type: String?, body: JSONObject) {
        val resolvedType = (type?.takeIf { it.isNotBlank() }) ?: body.optString("type", "")
        try {
            when (resolvedType) {
                TYPE_HR_SAMPLE -> handleHrSample(body)
                TYPE_BIO_SAMPLE -> handleBioSample(body)
                TYPE_HSI_ARTIFACT -> handleArtifact(body)
                "" -> SynheartLogger.log("[EdgeIngest] dropping body with no resolvable type")
                else -> {
                    listener.onSessionEvent(resolvedType, body)
                    emitEvent(EdgeEvent.SessionEventWrap(resolvedType, body))
                }
            }
        } catch (e: Throwable) {
            // Contract: never throw on malformed input. Drop + log.
            SynheartLogger.log("[EdgeIngest] dropping malformed '$resolvedType' body: ${e.message}")
        }
    }

    /** Convenience: parse a raw JSON string body and dispatch. Never throws. */
    fun onMessage(type: String?, rawBody: String) {
        val body = try {
            JSONObject(rawBody)
        } catch (e: Throwable) {
            SynheartLogger.log("[EdgeIngest] dropping unparseable body: ${e.message}")
            return
        }
        onMessage(type, body)
    }

    private fun handleHrSample(body: JSONObject) {
        // bpm + timestamp_ms are required by §3.1; bail (drop) if absent.
        if (!body.has(KEY_BPM) || !body.has(KEY_TIMESTAMP_MS)) {
            SynheartLogger.log("[EdgeIngest] dropping hr_sample missing required keys (bpm, timestamp_ms)")
            return
        }
        val sample = HrSample(
            bpm = body.getDouble(KEY_BPM),
            timestampMs = body.getLong(KEY_TIMESTAMP_MS),
            source = body.optString(KEY_SOURCE, "").takeIf { it.isNotEmpty() },
        )
        listener.onHrSample(sample)
        emitEvent(EdgeEvent.HrEvent(sample))
    }

    private fun handleBioSample(body: JSONObject) {
        if (!body.has(KEY_BPM) || !body.has(KEY_TIMESTAMP_MS)) {
            SynheartLogger.log("[EdgeIngest] dropping bio_sample missing required keys (bpm, timestamp_ms)")
            return
        }
        val sample = BioSample(
            bpm = body.getDouble(KEY_BPM),
            timestampMs = body.getLong(KEY_TIMESTAMP_MS),
            rrIntervalsMs = parseDoubleArray(body.optJSONArray(KEY_RR_INTERVALS_MS)),
            accel = parseAccel(body.optJSONObject(KEY_ACCEL)),
            source = body.optString(KEY_SOURCE, "").takeIf { it.isNotEmpty() },
        )
        listener.onBioSample(sample)
        emitEvent(EdgeEvent.BioEvent(sample))
    }

    private fun handleArtifact(body: JSONObject) {
        // artifact_id + payload_json + payload_hash_sha256 are load-bearing.
        val artifactId = body.optString(KEY_ARTIFACT_ID, "").takeIf { it.isNotEmpty() }
        val payloadJson = body.optString(KEY_PAYLOAD_JSON, "").takeIf { it.isNotEmpty() }
        val expectedHash = body.optString(KEY_PAYLOAD_HASH, "").takeIf { it.isNotEmpty() }
        if (artifactId == null || payloadJson == null || expectedHash == null) {
            SynheartLogger.log("[EdgeIngest] dropping hsi_artifact missing required keys (artifact_id, payload_json, payload_hash_sha256)")
            return
        }

        // §5 — verify payload_hash_sha256 == sha256(payload_json); reject on mismatch.
        val actualHash = sha256Hex(payloadJson)
        if (!actualHash.equals(expectedHash, ignoreCase = true)) {
            // A deterministically-corrupt artifact would otherwise be
            // rejected-without-ack forever and starve the delete-on-ACK outbox.
            // Count per-id mismatches; after POISON_PILL_THRESHOLD, dead-letter
            // it (hard error + ack-to-discard). The first/normal mismatch keeps
            // the old behaviour: reject, don't surface, don't ack.
            val attempts = synchronized(lock) {
                val n = (mismatchCounts[artifactId] ?: 0) + 1
                mismatchCounts[artifactId] = n
                n
            }
            if (attempts >= POISON_PILL_THRESHOLD) {
                SynheartLogger.log(
                    "[EdgeIngest] hsi_artifact '$artifactId' hash mismatch x$attempts " +
                        "(expected=$expectedHash actual=$actualHash) — dead-lettering: " +
                        "ack-to-discard so it stops blocking the outbox"
                )
                synchronized(lock) {
                    mismatchCounts.remove(artifactId)
                    recordSeenLocked(artifactId)
                    pendingAckIds.add(artifactId)
                }
                listener.onPoisonPill(artifactId, expectedHash, actualHash, attempts)
                return
            }
            SynheartLogger.log(
                "[EdgeIngest] hsi_artifact '$artifactId' hash mismatch (attempt $attempts): " +
                    "expected=$expectedHash actual=$actualHash — rejecting"
            )
            listener.onHashMismatch(artifactId, expectedHash, actualHash)
            return
        }

        // §5 — dedupe by artifact_id. On a duplicate, DO NOT re-surface, but
        // STILL queue the id for ACK (idempotent set) and return: the
        // watch outbox is delete-on-ACK, so a lost ACK makes it resend forever;
        // re-acking duplicates is the whole point.
        synchronized(lock) {
            if (seenArtifactIds.contains(artifactId)) {
                SynheartLogger.log(
                    "[EdgeIngest] hsi_artifact '$artifactId' is a duplicate — re-acking, not re-surfacing"
                )
                pendingAckIds.add(artifactId)
                recordSeenLocked(artifactId) // refresh LRU recency
                return
            }
        }

        // §0 — hsi_version: prefer envelope key, fall back to payload's own.
        val hsiVersion = body.optString(KEY_HSI_VERSION, "").takeIf { it.isNotEmpty() }
            ?: extractHsiVersionFromPayload(payloadJson)
        // Flag both an out-of-set version AND a missing one (parity with the
        // Swift EdgeIngest, which treats absent hsi_version as unsupported).
        val supported = hsiVersion != null && hsiVersion in supportedHsiVersions
        if (!supported) {
            val shown = hsiVersion ?: "(absent)"
            SynheartLogger.log(
                "[EdgeIngest] hsi_artifact '$artifactId' hsi_version='$shown' is " +
                    "outside supported set $supportedHsiVersions — flagging (still surfaced)"
            )
            listener.onUnsupportedHsiVersion(artifactId, shown)
        }

        // Accept: record as seen (bounded LRU) + needing ACK, then surface.
        synchronized(lock) {
            recordSeenLocked(artifactId)
            pendingAckIds.add(artifactId)
        }

        val artifact = HsiArtifact(
            artifactId = artifactId,
            sessionId = body.optString(KEY_SESSION_ID, "").takeIf { it.isNotEmpty() },
            seq = if (body.has(KEY_SEQ)) body.optInt(KEY_SEQ) else null,
            createdAtMs = if (body.has(KEY_CREATED_AT_MS)) body.optLong(KEY_CREATED_AT_MS) else null,
            schemaVersion = body.optString(KEY_SCHEMA_VERSION, "").takeIf { it.isNotEmpty() },
            hsiVersion = hsiVersion,
            payloadHashSha256 = expectedHash,
            payloadJson = payloadJson,
            deliveryMode = body.optString(KEY_DELIVERY_MODE, "").takeIf { it.isNotEmpty() },
            sessionOrigin = body.optString(KEY_SESSION_ORIGIN, "").takeIf { it.isNotEmpty() },
            sessionKind = body.optString(KEY_SESSION_KIND, "").takeIf { it.isNotEmpty() },
            hsiVersionSupported = supported,
        )
        listener.onArtifact(artifact)
        emitEvent(EdgeEvent.ArtifactEvent(artifact))
    }

    /**
     * Insert [artifactId] into the bounded [seenArtifactIds] LRU under [lock],
     * refreshing recency (re-inserting an existing id moves it to the most-
     * recent position) and evicting the eldest while over [SEEN_LRU_CAPACITY].
     * Caller MUST hold [lock].
     */
    private fun recordSeenLocked(artifactId: String) {
        // Re-insert to move to most-recently-used position.
        seenArtifactIds.remove(artifactId)
        seenArtifactIds.add(artifactId)
        val it = seenArtifactIds.iterator()
        while (seenArtifactIds.size > SEEN_LRU_CAPACITY && it.hasNext()) {
            it.next()
            it.remove()
        }
    }

    /**
     * Snapshot the ids accepted since the last drain and clear the pending set,
     * returning them in arrival order. Use with [buildAckBody] (or call
     * [drainAckBody]) to send the ACK on the command channel; the ids are
     * considered handed off once drained.
     */
    fun drainPendingAcks(): List<String> = synchronized(lock) {
        if (pendingAckIds.isEmpty()) return emptyList()
        val ids = pendingAckIds.toList()
        pendingAckIds.clear()
        ids
    }

    /**
     * Build the §4/§5 ACK command body for [artifactIds]:
     * `{ "command":"artifact_ack", "artifact_ids":[...] }`. Returns null when
     * [artifactIds] is empty (nothing to ack).
     */
    fun buildAckBody(artifactIds: List<String>): JSONObject? {
        if (artifactIds.isEmpty()) return null
        return JSONObject().apply {
            put(KEY_COMMAND, COMMAND_ARTIFACT_ACK)
            put(KEY_ARTIFACT_IDS, JSONArray(artifactIds))
        }
    }

    /** Drain pending acks and build the ACK body in one step. Null when empty. */
    fun drainAckBody(): JSONObject? = buildAckBody(drainPendingAcks())

    /** Test/diagnostic: number of distinct artifact ids seen so far. */
    fun seenCount(): Int = synchronized(lock) { seenArtifactIds.size }

    // --- helpers -----------------------------------------------------------

    private fun parseDoubleArray(arr: JSONArray?): List<Double> {
        if (arr == null) return emptyList()
        val out = ArrayList<Double>(arr.length())
        for (i in 0 until arr.length()) out.add(arr.getDouble(i))
        return out
    }

    private fun parseAccel(obj: JSONObject?): Accel? {
        if (obj == null) return null
        // Accel requires all three axes (§2); a partial object is treated as absent.
        if (!obj.has("x") || !obj.has("y") || !obj.has("z")) return null
        return Accel(obj.getDouble("x"), obj.getDouble("y"), obj.getDouble("z"))
    }

    private fun extractHsiVersionFromPayload(payloadJson: String): String? = try {
        JSONObject(payloadJson).optString(KEY_HSI_VERSION, "").takeIf { it.isNotEmpty() }
    } catch (_: Throwable) {
        null
    }

    companion object {
        /** EDGE-WIRE-CONTRACT.md §0 — supported inner HSI payload versions. */
        val SUPPORTED_HSI_VERSIONS: Set<String> = setOf("1.1", "1.2", "1.3")

        /**
         * After this many hash mismatches for the SAME `artifact_id`,
         * the artifact is dead-lettered (hard error + ack-to-discard) so a
         * deterministically-corrupt artifact stops resending forever.
         */
        const val POISON_PILL_THRESHOLD = 3

        /**
         * Cap of the seen-artifact LRU. Re-acking a long-evicted
         * stray is harmless per contract §5, so eviction is safe.
         */
        const val SEEN_LRU_CAPACITY = 4096

        // Body `type` discriminators (EDGE-WIRE-CONTRACT.md §1 table + §3).
        const val TYPE_HR_SAMPLE = "hr_sample"
        const val TYPE_BIO_SAMPLE = "bio_sample"
        const val TYPE_HSI_ARTIFACT = "hsi_artifact"

        // §4 command body.
        const val COMMAND_ARTIFACT_ACK = "artifact_ack"

        // Wire keys (EDGE-WIRE-CONTRACT.md §2, §3.1–§3.3, §4).
        private const val KEY_BPM = "bpm"
        private const val KEY_TIMESTAMP_MS = "timestamp_ms"
        private const val KEY_SOURCE = "source"
        private const val KEY_RR_INTERVALS_MS = "rr_intervals_ms"
        private const val KEY_ACCEL = "accel"
        private const val KEY_ARTIFACT_ID = "artifact_id"
        private const val KEY_SESSION_ID = "session_id"
        private const val KEY_SEQ = "seq"
        private const val KEY_CREATED_AT_MS = "created_at_ms"
        private const val KEY_SCHEMA_VERSION = "schema_version"
        private const val KEY_HSI_VERSION = "hsi_version"
        private const val KEY_PAYLOAD_HASH = "payload_hash_sha256"
        private const val KEY_PAYLOAD_JSON = "payload_json"
        private const val KEY_DELIVERY_MODE = "delivery_mode"
        private const val KEY_SESSION_ORIGIN = "session_origin"
        private const val KEY_SESSION_KIND = "session_kind"
        private const val KEY_COMMAND = "command"
        private const val KEY_ARTIFACT_IDS = "artifact_ids"

        /**
         * Lower-case hex SHA-256 of [s] (UTF-8), matching the producer's
         * `HsiArtifactEnvelope.wrap` exactly: `sha256(payload_json_bytes)`.
         */
        fun sha256Hex(s: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(s.toByteArray(Charsets.UTF_8))
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}
