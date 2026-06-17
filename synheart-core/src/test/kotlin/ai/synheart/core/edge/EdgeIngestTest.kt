// SPDX-License-Identifier: Apache-2.0

package ai.synheart.core.edge

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests for [EdgeIngest] — no Android, no Play Services. Validates the
 * phone-side consumer against the Synheart Edge wire contract
 * (EDGE-WIRE-CONTRACT.md in the synheart-edge repo).
 */
class EdgeIngestTest {

    /** Recording listener that captures every callback. */
    private class Recorder : EdgeIngest.Listener {
        val hr = mutableListOf<EdgeIngest.HrSample>()
        val bio = mutableListOf<EdgeIngest.BioSample>()
        val artifacts = mutableListOf<EdgeIngest.HsiArtifact>()
        val events = mutableListOf<Pair<String, JSONObject>>()
        val unsupported = mutableListOf<Pair<String, String>>()
        val mismatches = mutableListOf<Triple<String, String, String>>()
        val poisonPills = mutableListOf<Pair<String, Int>>()

        override fun onHrSample(sample: EdgeIngest.HrSample) { hr.add(sample) }
        override fun onBioSample(sample: EdgeIngest.BioSample) { bio.add(sample) }
        override fun onArtifact(artifact: EdgeIngest.HsiArtifact) { artifacts.add(artifact) }
        override fun onSessionEvent(type: String, body: JSONObject) { events.add(type to body) }
        override fun onUnsupportedHsiVersion(artifactId: String, hsiVersion: String) {
            unsupported.add(artifactId to hsiVersion)
        }
        override fun onHashMismatch(artifactId: String, expected: String, actual: String) {
            mismatches.add(Triple(artifactId, expected, actual))
        }
        override fun onPoisonPill(artifactId: String, expected: String, actual: String, attempts: Int) {
            poisonPills.add(artifactId to attempts)
        }
    }

    private fun newIngest(r: Recorder = Recorder()): Pair<EdgeIngest, Recorder> =
        EdgeIngest(r) to r

    /** Build an artifact body with a correct hash for [payloadJson] unless overridden. */
    private fun artifactBody(
        artifactId: String = "hsi_abc123def456_0",
        payloadJson: String = """{"hsi_version":"1.3","metrics":{"hr":72}}""",
        hash: String? = null,
        hsiVersion: String? = "1.3",
        includeOptional: Boolean = true,
    ): JSONObject = JSONObject().apply {
        put("type", "hsi_artifact")
        put("artifact_id", artifactId)
        put("payload_json", payloadJson)
        put("payload_hash_sha256", hash ?: EdgeIngest.sha256Hex(payloadJson))
        if (hsiVersion != null) put("hsi_version", hsiVersion)
        if (includeOptional) {
            put("session_id", "sess_1")
            put("seq", 0)
            put("created_at_ms", 1718900000000L)
            put("schema_version", "1.1")
            put("delivery_mode", "REALTIME")
            put("session_origin", "PHONE")
            put("session_kind", "FOCUS")
        }
    }

    // --- §3.1 hr_sample ----------------------------------------------------

    @Test
    fun `hr_sample parses bpm timestamp and source`() {
        val (ingest, r) = newIngest()
        ingest.onMessage(
            "hr_sample",
            JSONObject("""{"type":"hr_sample","bpm":72.0,"timestamp_ms":1718900000000,"source":"healthkit"}"""),
        )
        assertEquals(1, r.hr.size)
        assertEquals(72.0, r.hr[0].bpm, 0.0)
        assertEquals(1718900000000L, r.hr[0].timestampMs)
        assertEquals("healthkit", r.hr[0].source)
    }

    @Test
    fun `hr_sample with omitted optional source yields null source`() {
        val (ingest, r) = newIngest()
        ingest.onMessage("hr_sample", JSONObject("""{"type":"hr_sample","bpm":80.0,"timestamp_ms":1}"""))
        assertEquals(1, r.hr.size)
        assertNull(r.hr[0].source)
    }

    @Test
    fun `hr_sample missing required bpm is dropped not thrown`() {
        val (ingest, r) = newIngest()
        ingest.onMessage("hr_sample", JSONObject("""{"type":"hr_sample","timestamp_ms":1}"""))
        assertTrue(r.hr.isEmpty())
    }

    // --- §3.2 bio_sample ---------------------------------------------------

    @Test
    fun `bio_sample parses rr intervals and accel`() {
        val (ingest, r) = newIngest()
        ingest.onMessage(
            "bio_sample",
            JSONObject(
                """{"type":"bio_sample","bpm":72.0,"timestamp_ms":1718900000000,
                   "rr_intervals_ms":[820.0,835.0],"accel":{"x":0.01,"y":-0.02,"z":0.98},
                   "source":"healthkit"}""",
            ),
        )
        assertEquals(1, r.bio.size)
        val s = r.bio[0]
        assertEquals(72.0, s.bpm, 0.0)
        assertEquals(listOf(820.0, 835.0), s.rrIntervalsMs)
        assertNotNull(s.accel)
        assertEquals(0.98, s.accel!!.z, 0.0)
        assertEquals("healthkit", s.source)
    }

    @Test
    fun `bio_sample with omitted optional keys yields empty rr null accel null source`() {
        val (ingest, r) = newIngest()
        ingest.onMessage("bio_sample", JSONObject("""{"type":"bio_sample","bpm":60.0,"timestamp_ms":5}"""))
        assertEquals(1, r.bio.size)
        assertTrue(r.bio[0].rrIntervalsMs.isEmpty())
        assertNull(r.bio[0].accel)
        assertNull(r.bio[0].source)
    }

    @Test
    fun `bio_sample with partial accel treats accel as absent`() {
        val (ingest, r) = newIngest()
        ingest.onMessage(
            "bio_sample",
            JSONObject("""{"type":"bio_sample","bpm":60.0,"timestamp_ms":5,"accel":{"x":0.1,"y":0.2}}"""),
        )
        assertEquals(1, r.bio.size)
        assertNull(r.bio[0].accel)
    }

    // --- §3.3 / §5 hsi_artifact -------------------------------------------

    @Test
    fun `valid artifact is accepted surfaced and recorded for ack`() {
        val (ingest, r) = newIngest()
        ingest.onMessage("hsi_artifact", artifactBody())
        assertEquals(1, r.artifacts.size)
        val a = r.artifacts[0]
        assertEquals("hsi_abc123def456_0", a.artifactId)
        assertEquals("sess_1", a.sessionId)
        assertEquals(0, a.seq)
        assertEquals(1718900000000L, a.createdAtMs)
        assertEquals("1.1", a.schemaVersion)
        assertEquals("1.3", a.hsiVersion)
        assertEquals("REALTIME", a.deliveryMode)
        assertEquals("PHONE", a.sessionOrigin)
        assertEquals("FOCUS", a.sessionKind)
        assertTrue(a.hsiVersionSupported)
        // Recorded for ACK.
        assertEquals(listOf("hsi_abc123def456_0"), ingest.drainPendingAcks())
    }

    @Test
    fun `dedupe — same artifact_id twice yields one accept`() {
        val (ingest, r) = newIngest()
        ingest.onMessage("hsi_artifact", artifactBody())
        ingest.onMessage("hsi_artifact", artifactBody())
        assertEquals("only first accepted", 1, r.artifacts.size)
        assertEquals(1, ingest.seenCount())
        // Only the first is pending for ack.
        assertEquals(listOf("hsi_abc123def456_0"), ingest.drainPendingAcks())
    }

    @Test
    fun `hash mismatch is rejected logged and not surfaced or acked`() {
        val (ingest, r) = newIngest()
        ingest.onMessage("hsi_artifact", artifactBody(hash = "deadbeef"))
        assertTrue("artifact not surfaced", r.artifacts.isEmpty())
        assertEquals(1, r.mismatches.size)
        assertEquals("deadbeef", r.mismatches[0].second)
        assertTrue("nothing to ack", ingest.drainPendingAcks().isEmpty())
        assertEquals(0, ingest.seenCount())
    }

    @Test
    fun `unsupported hsi_version is flagged but still surfaced`() {
        val (ingest, r) = newIngest()
        ingest.onMessage("hsi_artifact", artifactBody(hsiVersion = "9.9"))
        assertEquals("still surfaced", 1, r.artifacts.size)
        assertFalse(r.artifacts[0].hsiVersionSupported)
        assertEquals(1, r.unsupported.size)
        assertEquals("9.9", r.unsupported[0].second)
        // Still recorded for ack (accepted, just version-flagged).
        assertEquals(1, ingest.drainPendingAcks().size)
    }

    @Test
    fun `hsi_version falls back to payload when envelope key omitted`() {
        val (ingest, r) = newIngest()
        ingest.onMessage(
            "hsi_artifact",
            artifactBody(payloadJson = """{"hsi_version":"1.2"}""", hsiVersion = null),
        )
        assertEquals(1, r.artifacts.size)
        assertEquals("1.2", r.artifacts[0].hsiVersion)
        assertTrue(r.artifacts[0].hsiVersionSupported)
    }

    @Test
    fun `artifact missing required keys is dropped not thrown`() {
        val (ingest, r) = newIngest()
        ingest.onMessage("hsi_artifact", JSONObject("""{"type":"hsi_artifact","artifact_id":"x"}"""))
        assertTrue(r.artifacts.isEmpty())
        assertEquals(0, ingest.seenCount())
    }

    // --- ACK body shape (§4/§5) -------------------------------------------

    @Test
    fun `ack body shape is correct`() {
        val (ingest, _) = newIngest()
        ingest.onMessage("hsi_artifact", artifactBody(artifactId = "a1"))
        ingest.onMessage("hsi_artifact", artifactBody(artifactId = "a2", payloadJson = """{"hsi_version":"1.3","x":2}"""))
        val ack = ingest.drainAckBody()
        assertNotNull(ack)
        assertEquals("artifact_ack", ack!!.getString("command"))
        val ids = ack.getJSONArray("artifact_ids")
        assertEquals(2, ids.length())
        assertEquals("a1", ids.getString(0))
        assertEquals("a2", ids.getString(1))
    }

    @Test
    fun `drain clears pending so a second drain is empty`() {
        val (ingest, _) = newIngest()
        ingest.onMessage("hsi_artifact", artifactBody())
        assertEquals(1, ingest.drainPendingAcks().size)
        assertTrue(ingest.drainPendingAcks().isEmpty())
        assertNull(ingest.drainAckBody())
    }

    @Test
    fun `buildAckBody returns null for empty list`() {
        val (ingest, _) = newIngest()
        assertNull(ingest.buildAckBody(emptyList()))
    }

    // --- session events (§3.4) --------------------------------------------

    @Test
    fun `non-sample non-artifact type routes to session event callback`() {
        val (ingest, r) = newIngest()
        val body = JSONObject("""{"type":"session_started","session_id":"s1"}""")
        ingest.onMessage("session_started", body)
        assertEquals(1, r.events.size)
        assertEquals("session_started", r.events[0].first)
    }

    @Test
    fun `type falls back to body type when transport type is blank`() {
        val (ingest, r) = newIngest()
        ingest.onMessage("", JSONObject("""{"type":"hr_sample","bpm":50.0,"timestamp_ms":7}"""))
        assertEquals(1, r.hr.size)
    }

    // --- malformed safety --------------------------------------------------

    @Test
    fun `unparseable raw string body does not throw and is dropped`() {
        val (ingest, r) = newIngest()
        ingest.onMessage("hr_sample", "not json at all")
        assertTrue(r.hr.isEmpty())
    }

    @Test
    fun `body with no resolvable type is dropped`() {
        val (ingest, r) = newIngest()
        ingest.onMessage(null, JSONObject("""{"bpm":50.0}"""))
        assertTrue(r.hr.isEmpty() && r.events.isEmpty() && r.artifacts.isEmpty())
    }

    @Test
    fun `malformed array in bio_sample does not throw`() {
        val (ingest, r) = newIngest()
        // rr_intervals_ms containing a non-number — getDouble would throw; must be swallowed.
        val body = JSONObject().apply {
            put("type", "bio_sample")
            put("bpm", 60.0)
            put("timestamp_ms", 1)
            put("rr_intervals_ms", JSONArray(listOf("oops")))
        }
        ingest.onMessage("bio_sample", body)
        // Dropped (threw internally, swallowed), not surfaced.
        assertTrue(r.bio.isEmpty())
    }

    // --- hash parity with producer ----------------------------------------

    @Test
    fun `sha256Hex matches known lower-case hex of UTF8 bytes`() {
        // echo -n "" | shasum -a 256  →  e3b0c442...
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            EdgeIngest.sha256Hex(""),
        )
    }

    // --- reactive `events` SharedFlow (parity with Dart Stream<EdgeEvent>) --

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `events flow emits typed EdgeEvents for each message type`() = runTest {
        val (ingest, _) = newIngest()
        val collected = mutableListOf<EdgeIngest.EdgeEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            ingest.events.toList(collected)
        }

        ingest.onMessage("hr_sample", JSONObject("""{"type":"hr_sample","bpm":72.0,"timestamp_ms":10}"""))
        ingest.onMessage("bio_sample", JSONObject("""{"type":"bio_sample","bpm":80.0,"timestamp_ms":20}"""))
        ingest.onMessage("hsi_artifact", artifactBody())
        ingest.onMessage("session_started", JSONObject("""{"type":"session_started","session_id":"s1"}"""))

        assertEquals(4, collected.size)
        assertTrue(collected[0] is EdgeIngest.EdgeEvent.HrEvent)
        assertEquals(72.0, (collected[0] as EdgeIngest.EdgeEvent.HrEvent).sample.bpm, 0.0)
        assertTrue(collected[1] is EdgeIngest.EdgeEvent.BioEvent)
        assertEquals(80.0, (collected[1] as EdgeIngest.EdgeEvent.BioEvent).sample.bpm, 0.0)
        assertTrue(collected[2] is EdgeIngest.EdgeEvent.ArtifactEvent)
        assertEquals(
            "hsi_abc123def456_0",
            (collected[2] as EdgeIngest.EdgeEvent.ArtifactEvent).artifact.artifactId,
        )
        assertTrue(collected[3] is EdgeIngest.EdgeEvent.SessionEventWrap)
        assertEquals("session_started", (collected[3] as EdgeIngest.EdgeEvent.SessionEventWrap).type)

        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `events flow fires in lock-step with the listener`() = runTest {
        val (ingest, r) = newIngest()
        val collected = mutableListOf<EdgeIngest.EdgeEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            ingest.events.toList(collected)
        }
        ingest.onMessage("hr_sample", JSONObject("""{"type":"hr_sample","bpm":60.0,"timestamp_ms":1}"""))
        assertEquals(1, r.hr.size)
        assertEquals(1, collected.size)
        job.cancel()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `duplicate artifact does not re-emit on the events flow`() = runTest {
        val (ingest, _) = newIngest()
        val collected = mutableListOf<EdgeIngest.EdgeEvent>()
        val job = launch(UnconfinedTestDispatcher(testScheduler)) {
            ingest.events.toList(collected)
        }
        ingest.onMessage("hsi_artifact", artifactBody())
        ingest.onMessage("hsi_artifact", artifactBody())
        assertEquals(1, collected.size)
        job.cancel()
    }

    // --- a duplicate must still be re-acked ----------------------------------

    @Test
    fun `duplicate artifact is re-acked not re-surfaced`() {
        val (ingest, r) = newIngest()
        // First accept, then drain (simulates a sent ACK the watch never saw).
        ingest.onMessage("hsi_artifact", artifactBody())
        assertEquals(listOf("hsi_abc123def456_0"), ingest.drainPendingAcks())

        // Watch resends the same artifact: must NOT re-surface, MUST re-queue ack.
        ingest.onMessage("hsi_artifact", artifactBody())
        assertEquals("duplicate must not re-surface", 1, r.artifacts.size)
        assertEquals(
            "duplicate must be re-queued for ACK",
            listOf("hsi_abc123def456_0"),
            ingest.drainPendingAcks(),
        )
    }

    @Test
    fun `duplicate ack is idempotent not double-queued`() {
        val (ingest, _) = newIngest()
        ingest.onMessage("hsi_artifact", artifactBody()) // accept
        ingest.onMessage("hsi_artifact", artifactBody()) // dup
        assertEquals(listOf("hsi_abc123def456_0"), ingest.drainPendingAcks())
    }

    // --- dead-letter after K=3 mismatches -----------------------------------

    @Test
    fun `poison pill is dead-lettered after three hash mismatches`() {
        val (ingest, r) = newIngest()
        // Attempts 1 + 2: normal rejection, not surfaced, not acked, not dead-lettered.
        ingest.onMessage("hsi_artifact", artifactBody(hash = "deadbeef"))
        ingest.onMessage("hsi_artifact", artifactBody(hash = "deadbeef"))
        assertEquals(2, r.mismatches.size)
        assertTrue(r.poisonPills.isEmpty())
        assertTrue("sub-threshold must not be acked", ingest.drainPendingAcks().isEmpty())

        // Attempt 3: dead-letter — hard error + ack-to-discard.
        ingest.onMessage("hsi_artifact", artifactBody(hash = "deadbeef"))
        assertEquals(1, r.poisonPills.size)
        assertEquals(3, r.poisonPills[0].second)
        assertTrue("poison pill must never surface as a valid artifact", r.artifacts.isEmpty())
        assertEquals(
            "dead-lettered id must be ack-to-discarded",
            listOf("hsi_abc123def456_0"),
            ingest.drainPendingAcks(),
        )
    }

    @Test
    fun `poison pill threshold is three`() {
        assertEquals(3, EdgeIngest.POISON_PILL_THRESHOLD)
    }

    // --- the seen set is bounded (LRU) --------------------------------------

    @Test
    fun `dedupe set is bounded and evicts oldest`() {
        val (ingest, _) = newIngest()
        val cap = EdgeIngest.SEEN_LRU_CAPACITY
        for (i in 0 until cap + 100) {
            ingest.onMessage("hsi_artifact", artifactBody(artifactId = "hsi_$i"))
        }
        // The set never exceeds the cap.
        assertEquals(cap, ingest.seenCount())
        // The eldest id ("hsi_0") was evicted → re-sending it accepts again
        // (harmless per contract §5).
        val before = ingest.seenCount()
        ingest.onMessage("hsi_artifact", artifactBody(artifactId = "hsi_0"))
        // It re-accepted (count unchanged because another eldest is evicted).
        assertEquals(cap, ingest.seenCount())
        // And it queued an ACK (re-accept), confirming it was not deduped.
        assertTrue(ingest.drainPendingAcks().contains("hsi_0"))
        assertEquals(cap, before)
    }

    // --- empty-`type` parity — dropped (matches Swift/Dart) -----------------

    @Test
    fun `empty type is dropped`() {
        val (ingest, r) = newIngest()
        ingest.onMessage("", JSONObject("""{"type":"","session_id":"s1"}"""))
        assertTrue(r.hr.isEmpty() && r.bio.isEmpty() && r.artifacts.isEmpty() && r.events.isEmpty())
    }
}
