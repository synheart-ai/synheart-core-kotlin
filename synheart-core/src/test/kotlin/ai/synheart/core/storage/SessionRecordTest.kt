package ai.synheart.core.storage

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the `list_sessions` row parse.
 *
 * The bug these exist for: the parser read `start_utc` while the runtime emits
 * `started_at_ms`. Every record then carried `startUtc == 0`, so
 * `sweepOrphanSessions` — which filters on `startUtc > 0` — silently matched
 * nothing and stranded sessions accumulated forever.
 */
class SessionRecordTest {

    @Test
    fun `reads the runtime's actual key spelling`() {
        val json = JSONObject()
            .put("session_id", "s1")
            .put("subject_id", "sub")
            .put("started_at_ms", 1_700_000_000_000L)
            .put("ended_at_ms", 1_700_000_060_000L)
            .put("mode", "personal")
            .put("state", "closed")

        val r = SessionRecord.fromJson(json)
        assertEquals("s1", r.sessionId)
        assertEquals(1_700_000_000_000L, r.startUtc)
        assertEquals(1_700_000_060_000L, r.endedAtUtc)
        assertEquals("closed", r.state)
        assertFalse(r.isActive)
    }

    @Test
    fun `still reads the legacy utc spelling`() {
        // Both spellings have coexisted across runtime versions.
        val json = JSONObject()
            .put("session_id", "s2")
            .put("start_utc", 42L)
            .put("created_at_utc", 41L)
            .put("end_utc", 99L)
        val r = SessionRecord.fromJson(json)
        assertEquals(42L, r.startUtc)
        assertEquals(41L, r.createdAtUtc)
        assertEquals(99L, r.endedAtUtc)
    }

    @Test
    fun `an open session has a null end and reads as active`() {
        val r = SessionRecord.fromJson(
            JSONObject().put("session_id", "s3").put("started_at_ms", 5L).put("state", "active"),
        )
        assertNull(r.endedAtUtc)
        assertTrue(r.isActive)
    }

    @Test
    fun `state defaults to active when the runtime omits it`() {
        val r = SessionRecord.fromJson(JSONObject().put("session_id", "s4"))
        assertEquals("active", r.state)
        assertTrue(r.isActive)
    }

    @Test
    fun `defaults fill in for a sparse row`() {
        val r = SessionRecord.fromJson(JSONObject().put("session_id", "s5"))
        assertEquals("personal", r.mode)
        assertEquals("0.0.0", r.appVersion)
        assertEquals("android", r.platform)
        assertEquals(0L, r.startUtc)
    }
}
