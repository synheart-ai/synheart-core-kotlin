package ai.synheart.core.example.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/**
 * The composite exists because screen-off alone is wrong in both directions,
 * so most of these are about *declining*. The one-shot tests matter most: a
 * second declaration inside one window is the failure mode that pins Focus at
 * 0.0 for the remainder of a session with nothing on the wire to explain it.
 */
class RestWindowDetectorTest {

    private val midday = LocalDateTime.of(2026, 9, 8, 14, 0)
    private val t0 = 1_700_000_000_000L

    private fun resting(): RestWindowDetector = RestWindowDetector().apply {
        noteScreenOff(t0); noteInteraction(t0); noteMotionRms(0.05)
    }

    @Test
    fun `screen on declines`() {
        val d = RestWindowDetector().apply { noteInteraction(t0); noteMotionRms(0.05) }
        assertNull(d.evaluate(t0 + 600_000, midday))
        assertTrue(d.lastDeclineReason!!.contains("screen is on"))
    }

    @Test
    fun `screen off but not long enough declines`() {
        val d = resting()
        assertNull(d.evaluate(t0 + 60_000, midday))
        assertTrue(d.lastDeclineReason!!.contains("needs 120s"))
    }

    @Test
    fun `recent interaction declines`() {
        val d = resting().apply { noteInteraction(t0 + 500_000) }
        assertNull(d.evaluate(t0 + 540_000, midday))
        assertTrue(d.lastDeclineReason!!.contains("interaction"))
    }

    @Test
    fun `high motion declines`() {
        val d = resting().apply { noteMotionRms(1.4) }
        assertNull(d.evaluate(t0 + 600_000, midday))
        assertTrue(d.lastDeclineReason!!.contains("too high"))
    }

    @Test
    fun `no motion reading is not treated as low motion`() {
        val d = RestWindowDetector().apply { noteScreenOff(t0); noteInteraction(t0) }
        assertNull(d.evaluate(t0 + 600_000, midday))
        assertTrue(d.lastDeclineReason!!.contains("cannot verify"))
    }

    @Test
    fun `all three clauses satisfied declares`() {
        val d = resting()
        assertEquals(t0 + 600_000, d.evaluate(t0 + 600_000, midday))
        assertNull(d.lastDeclineReason)
        assertEquals(1, d.declaredCount)
    }

    @Test
    fun `one-shot per window`() {
        val d = resting()
        assertNotNull(d.evaluate(t0 + 600_000, midday))
        assertNull(d.evaluate(t0 + 610_000, midday))
        assertTrue(d.lastDeclineReason!!.contains("already declared"))
        assertNotNull(d.evaluate(t0 + 660_000, midday))
        assertEquals(2, d.declaredCount)
    }

    @Test
    fun `a long rest declares once per window, not once per tick`() {
        val d = resting()
        var declared = 0
        val windows = HashSet<Long>()
        for (s in 300 until 900) {
            val now = t0 + s * 1000L
            windows += now / 60_000
            if (d.evaluate(now, midday) != null) declared++
        }
        assertEquals(windows.size, declared)
        assertTrue(declared < 20)
    }

    @Test
    fun `sleep window overrides the composite`() {
        val d = RestWindowDetector()
        assertNotNull(d.evaluate(t0, LocalDateTime.of(2026, 9, 8, 2, 30)))
        assertNotNull(d.evaluate(t0 + 60_000, LocalDateTime.of(2026, 9, 8, 23, 30)))
        assertNull(d.evaluate(t0 + 120_000, LocalDateTime.of(2026, 9, 8, 9, 0)))
    }

    @Test
    fun `waking the screen restarts the quiet clock`() {
        val d = resting()
        d.noteScreenOn(t0 + 300_000)
        d.noteScreenOff(t0 + 310_000)
        d.noteInteraction(t0 + 380_000)
        assertNull(d.evaluate(t0 + 480_000, midday))
        assertTrue(d.lastDeclineReason!!.contains("interaction"))
        assertNotNull(d.evaluate(t0 + 520_000, midday))
    }

    @Test
    fun `reset forgets everything`() {
        val d = resting()
        assertNotNull(d.evaluate(t0 + 600_000, midday))
        d.reset()
        assertNull(d.evaluate(t0 + 700_000, midday))
        assertTrue(d.lastDeclineReason!!.contains("screen is on"))
    }
}
