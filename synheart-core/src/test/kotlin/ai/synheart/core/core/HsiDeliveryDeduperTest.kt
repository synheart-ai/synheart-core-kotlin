package ai.synheart.core.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HsiDeliveryDeduperTest {

    private fun window(id: String?): String = if (id == null) {
        """{"axes":{}}"""
    } else {
        """{"meta":{"ids":{"hsi_id":"$id"}},"axes":{}}"""
    }

    @Test
    fun `a window is delivered once and suppressed on repeat`() {
        val d = HsiDeliveryDeduper()
        assertTrue(d.shouldDeliver(window("w1")))
        assertFalse(d.shouldDeliver(window("w1")))
    }

    @Test
    fun `distinct windows all pass`() {
        val d = HsiDeliveryDeduper()
        assertTrue(d.shouldDeliver(window("w1")))
        assertTrue(d.shouldDeliver(window("w2")))
        assertEquals(2, d.length)
    }

    @Test
    fun `an unidentifiable payload is always delivered`() {
        // Dropping a window is worse than repeating one, so a payload with no
        // hsi_id passes every time rather than being collapsed into one entry.
        val d = HsiDeliveryDeduper()
        assertTrue(d.shouldDeliver(window(null)))
        assertTrue(d.shouldDeliver(window(null)))
        assertEquals(0, d.length)
    }

    @Test
    fun `malformed json is delivered rather than dropped`() {
        val d = HsiDeliveryDeduper()
        assertTrue(d.shouldDeliver("not json at all"))
        assertTrue(d.shouldDeliver(""))
    }

    @Test
    fun `eviction is FIFO once capacity is exceeded`() {
        val d = HsiDeliveryDeduper(capacity = 2)
        assertTrue(d.shouldDeliver(window("a")))
        assertTrue(d.shouldDeliver(window("b")))
        assertTrue(d.shouldDeliver(window("c"))) // evicts "a"
        assertEquals(2, d.length)
        // "a" was evicted, so it passes again; "c" is still remembered.
        assertTrue(d.shouldDeliver(window("a")))
        assertFalse(d.shouldDeliver(window("c")))
    }

    @Test
    fun `reset forgets every id`() {
        // Without this a window id from a previous session would suppress the
        // next session's first window.
        val d = HsiDeliveryDeduper()
        assertTrue(d.shouldDeliver(window("w1")))
        d.reset()
        assertEquals(0, d.length)
        assertTrue(d.shouldDeliver(window("w1")))
    }

    @Test
    fun `extractHsiId reads the RFC-IDENTITY path and nothing else`() {
        assertEquals("abc", HsiDeliveryDeduper.extractHsiId(window("abc")))
        assertNull(HsiDeliveryDeduper.extractHsiId("""{"meta":{}}"""))
        assertNull(HsiDeliveryDeduper.extractHsiId("""{"meta":{"ids":{}}}"""))
        assertNull(HsiDeliveryDeduper.extractHsiId("""{"meta":{"ids":{"hsi_id":""}}}"""))
        assertNull(HsiDeliveryDeduper.extractHsiId("[]"))
    }
}
