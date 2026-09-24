package ai.synheart.core.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCompatTest {

    @Test
    fun `compare orders dotted numeric versions`() {
        assertTrue(RuntimeCompat.compare("0.31.1", "0.31.0") > 0)
        assertTrue(RuntimeCompat.compare("0.30.1", "0.31.0") < 0)
        assertEquals(0, RuntimeCompat.compare("0.31.1", "0.31.1"))
        assertTrue(RuntimeCompat.compare("1.0.0", "0.99.99") > 0)
    }

    @Test
    fun `compare treats a missing component as zero and ignores suffixes`() {
        assertEquals(0, RuntimeCompat.compare("0.31", "0.31.0"))
        assertEquals(0, RuntimeCompat.compare("0.31.1-rc1", "0.31.1"))
        assertTrue(RuntimeCompat.compare("0.31.10", "0.31.9") > 0)
    }

    @Test
    fun `check is OK at or above the written-against version`() {
        val r = RuntimeCompat.check(JSONObject().put("core_runtime", RuntimeCompat.WRITTEN_AGAINST))
        assertEquals(RuntimeCompatStatus.OK, r.status)
        assertTrue(r.isAcceptable)
    }

    @Test
    fun `check is OLDER between minimum and written-against, still acceptable`() {
        val r = RuntimeCompat.check(JSONObject().put("core_runtime", "0.30.0"))
        assertEquals(RuntimeCompatStatus.OLDER, r.status)
        assertTrue(r.isAcceptable)
        assertTrue(r.message.contains("0.30.0"))
    }

    @Test
    fun `check refuses TOO_OLD below the minimum`() {
        val r = RuntimeCompat.check(JSONObject().put("core_runtime", "0.19.2"))
        assertEquals(RuntimeCompatStatus.TOO_OLD, r.status)
        assertFalse(r.isAcceptable)
    }

    @Test
    fun `check is UNKNOWN without a version, still acceptable`() {
        assertEquals(RuntimeCompatStatus.UNKNOWN, RuntimeCompat.check(null).status)
        assertTrue(RuntimeCompat.check(JSONObject()).isAcceptable)
    }
}
