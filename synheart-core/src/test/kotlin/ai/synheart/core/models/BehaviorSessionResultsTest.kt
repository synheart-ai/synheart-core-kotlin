package ai.synheart.core.models

import ai.synheart.core.modules.behavior.BehaviorEvent
import ai.synheart.core.modules.behavior.BehaviorEventType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BehaviorSessionResultsTest {

    private fun ev(type: BehaviorEventType, ts: Long) = BehaviorEvent(type = type, timestamp = ts)

    @Test
    fun `an empty session yields zeroes rather than NaN`() {
        val r = BehaviorSessionResults.fromEvents("s", 10_000, emptyList())
        assertEquals(0, r.totalEvents)
        assertEquals(0.0, r.tapRate, 1e-9)
        assertEquals(0.0, r.keystrokeRate, 1e-9)
        assertEquals(0.0, r.burstiness, 1e-9)
    }

    @Test
    fun `a zero duration cannot divide by zero`() {
        val r = BehaviorSessionResults.fromEvents(
            "s",
            0,
            listOf(ev(BehaviorEventType.TAP, 0)),
        )
        assertEquals(0.0, r.tapRate, 1e-9)
    }

    @Test
    fun `rates are per second`() {
        val events = (0 until 10).map { ev(BehaviorEventType.TAP, it * 100L) } +
            (0 until 4).map { ev(BehaviorEventType.KEY_DOWN, it * 250L) }
        val r = BehaviorSessionResults.fromEvents("s", 2_000, events)
        assertEquals(14, r.totalEvents)
        assertEquals(5.0, r.tapRate, 1e-9)
        assertEquals(2.0, r.keystrokeRate, 1e-9)
    }

    @Test
    fun `evenly spaced events are not bursty`() {
        val events = (0 until 20).map { ev(BehaviorEventType.TAP, it * 100L) }
        val r = BehaviorSessionResults.fromEvents("s", 2_000, events)
        assertEquals(0.0, r.burstiness, 1e-6)
    }

    @Test
    fun `clustered events are burstier than even ones`() {
        val even = (0 until 20).map { ev(BehaviorEventType.TAP, it * 100L) }
        // Two tight clusters separated by a long gap.
        val clustered = (0 until 10).map { ev(BehaviorEventType.TAP, it * 10L) } +
            (0 until 10).map { ev(BehaviorEventType.TAP, 5_000L + it * 10L) }
        val a = BehaviorSessionResults.fromEvents("s", 6_000, even)
        val b = BehaviorSessionResults.fromEvents("s", 6_000, clustered)
        assertTrue("clustered=${b.burstiness} even=${a.burstiness}", b.burstiness > a.burstiness)
        assertTrue(b.burstiness <= 1.0)
    }

    @Test
    fun `app switching lowers the focus hint`() {
        val focused = (0 until 10).map { ev(BehaviorEventType.TAP, it * 100L) }
        val fragmented = (0 until 5).map { ev(BehaviorEventType.TAP, it * 100L) } +
            (0 until 5).map { ev(BehaviorEventType.APP_SWITCH, 500L + it * 100L) }
        assertEquals(1.0, BehaviorSessionResults.fromEvents("s", 1_000, focused).focusHint, 1e-9)
        assertEquals(
            0.5,
            BehaviorSessionResults.fromEvents("s", 1_000, fragmented).focusHint,
            1e-9,
        )
    }

    @Test
    fun `interaction intensity is clamped to one`() {
        val busy = (0 until 100).map { ev(BehaviorEventType.TAP, it * 10L) }
        val r = BehaviorSessionResults.fromEvents("s", 1_000, busy)
        assertEquals(1.0, r.interactionIntensity, 1e-9)
    }
}
