package ai.synheart.core.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class SyncStatusTest {

    @Test
    fun `reads every field the runtime sends`() {
        val s = SyncStatus.fromJson(
            JSONObject(
                """
                {"enabled": true, "sync_space_id": "sp_1", "pending_count": 3,
                 "last_success_ms": 1793000000000, "srk_ready": true, "account_owned": false}
                """.trimIndent(),
            ),
        )
        assertTrue(s.enabled)
        assertEquals("sp_1", s.syncSpaceId)
        assertEquals(3, s.pendingCount)
        assertEquals(1_793_000_000_000L, s.lastSuccessMs)
        assertEquals(true, s.srkReady)
        assertEquals(false, s.accountOwned)
        assertTrue(s.hasActiveSpace)
    }

    @Test
    fun `fields the runtime did not send stay null, not zero`() {
        // An older payload: device_count is no longer read (it was always 0).
        val s = SyncStatus.fromJson(JSONObject("""{"enabled": true, "device_count": 0}"""))
        assertNull(s.pendingCount)
        assertNull(s.lastSuccessMs)
        assertNull(s.srkReady)
        assertNull(s.accountOwned)
        assertNull(s.syncSpaceId)
        assertFalse(s.hasActiveSpace)
    }

    @Test
    fun `an empty space id is no space, and a missing enabled is false`() {
        val s = SyncStatus.fromJson(JSONObject("""{"sync_space_id": ""}"""))
        assertFalse(s.enabled)
        assertNull(s.syncSpaceId)
    }

    @Test
    fun `pairing states parse, and anything else is unknown`() {
        assertEquals(SyncPairingState.WAITING, SyncPairingState.fromWire("waiting"))
        assertEquals(SyncPairingState.JOINED, SyncPairingState.fromWire("joined"))
        assertEquals(SyncPairingState.EXPIRED, SyncPairingState.fromWire("expired"))
        assertEquals(SyncPairingState.UNKNOWN, SyncPairingState.fromWire("cancelled"))
        assertEquals(SyncPairingState.UNKNOWN, SyncPairingState.fromWire(null))
        assertTrue(SyncPairingState.JOINED.isTerminal)
        assertTrue(SyncPairingState.EXPIRED.isTerminal)
        assertFalse(SyncPairingState.WAITING.isTerminal)
        assertFalse(SyncPairingState.UNKNOWN.isTerminal)
    }

    @Test
    fun `a joined pairing carries the device and its times`() {
        val p = SyncPairingStatus.fromJson(
            JSONObject(
                """
                {"status": "joined", "sync_space_id": "sp_1",
                 "joined_device": {"device_id": "dev_2", "device_name": "Pixel"},
                 "joined_at": "2026-10-30T12:00:05.123Z", "expires_at": "2026-10-30T12:10:00Z"}
                """.trimIndent(),
            ),
        )
        assertEquals(SyncPairingState.JOINED, p.state)
        assertEquals("sp_1", p.syncSpaceId)
        assertEquals(SyncPairedDevice("dev_2", "Pixel"), p.joinedDevice)
        assertEquals(Instant.parse("2026-10-30T12:00:05.123Z"), p.joinedAt)
        assertEquals(Instant.parse("2026-10-30T12:10:00Z"), p.expiresAt)
    }

    @Test
    fun `a waiting pairing has no device, and expiry is bounded by expiresAt`() {
        val p = SyncPairingStatus.fromJson(
            JSONObject(
                """
                {"status": "waiting", "sync_space_id": "sp_1", "joined_device": null,
                 "joined_at": null, "expires_at": "2026-10-30T12:10:00+00:00"}
                """.trimIndent(),
            ),
        )
        assertNull(p.joinedDevice)
        assertNull(p.joinedAt)
        assertFalse(p.isExpiredAt(Instant.parse("2026-10-30T12:09:59Z")))
        assertTrue(p.isExpiredAt(Instant.parse("2026-10-30T12:10:00Z")))
        // A server-reported expiry counts whatever the clock says.
        assertTrue(p.copy(state = SyncPairingState.EXPIRED).isExpiredAt(Instant.EPOCH))
    }

    @Test
    fun `a malformed timestamp reads as absent`() {
        val p = SyncPairingStatus.fromJson(JSONObject("""{"status": "waiting", "expires_at": "soon"}"""))
        assertNull(p.expiresAt)
        assertFalse(p.isExpiredAt(Instant.now()))
    }
}
