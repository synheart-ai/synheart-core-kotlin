package com.synheart.core.sync

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ArtifactEnvelopeTest {

    @Test
    fun `serializes to and from JSON`() {
        val envelope = ArtifactEnvelope(
            artifactId = "art_123",
            subjectId = "usr_456",
            sessionId = "sess_789",
            type = "hsi_window",
            startMs = 1000L,
            endMs = 2000L,
            seq = 1,
            schemaName = "hsi_window",
            schemaVersion = "1.0",
            nonceB64 = "bm9uY2U=",
            payloadSha256 = "abc123",
            ciphertextB64 = "Y2lwaGVydGV4dA=="
        )

        val json = envelope.toJson()
        assertEquals("art_123", json.getString("artifact_id"))
        assertEquals("usr_456", json.getString("subject_id"))
        assertEquals("sess_789", json.getString("session_id"))
        assertEquals("hsi_window", json.getString("type"))
        assertEquals(1000L, json.getJSONObject("time_range").getLong("start_ms"))
        assertEquals(2000L, json.getJSONObject("time_range").getLong("end_ms"))
        assertEquals(1, json.getInt("seq"))
        assertEquals("AES256GCM", json.getJSONObject("crypto").getString("alg"))

        val deserialized = ArtifactEnvelope.fromJson(json)
        assertEquals("art_123", deserialized.artifactId)
        assertEquals("usr_456", deserialized.subjectId)
        assertEquals("sess_789", deserialized.sessionId)
        assertEquals(1000L, deserialized.startMs)
        assertEquals(2000L, deserialized.endMs)
        assertEquals(1, deserialized.seq)
        assertEquals("bm9uY2U=", deserialized.nonceB64)
        assertEquals("Y2lwaGVydGV4dA==", deserialized.ciphertextB64)
    }

    @Test
    fun `handles null session_id`() {
        val envelope = ArtifactEnvelope(
            artifactId = "art_123",
            subjectId = "usr_456",
            type = "baseline_snapshot",
            startMs = 1000L,
            endMs = 2000L,
            schemaName = "baseline",
            schemaVersion = "1.0",
            nonceB64 = "bm9uY2U=",
            payloadSha256 = "abc123",
            ciphertextB64 = "Y2lwaGVydGV4dA=="
        )

        val json = envelope.toJson()
        assertFalse(json.has("session_id"))

        val deserialized = ArtifactEnvelope.fromJson(json)
        assertNull(deserialized.sessionId)
    }

    @Test
    fun `SyncResult defaults to zero`() {
        val result = SyncResult()
        assertEquals(0, result.pushed)
        assertEquals(0, result.pulled)
        assertEquals(0, result.conflictsResolved)
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `SyncStatus holds all fields`() {
        val status = SyncStatus(
            enabled = true,
            lastSuccessMs = 1234567890L,
            pendingUploadCount = 5,
            cursor = "abc"
        )
        assertTrue(status.enabled)
        assertEquals(1234567890L, status.lastSuccessMs)
        assertEquals(5, status.pendingUploadCount)
        assertEquals("abc", status.cursor)
    }
}
