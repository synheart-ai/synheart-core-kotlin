package ai.synheart.core.example.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The assertions that matter are about what is *absent*. A field this host
 * cannot observe must come out null, because the engine withholds a null and
 * renormalises it out whereas `0.0` is a measured zero that moves the score —
 * so a test that only checked the populated fields would pass while the
 * summary quietly fabricated evidence.
 */
class TypingMicroWindowsTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `nothing is emitted before the window elapses`() {
        val agg = TypingMicroWindowAggregator()
        assertNull(agg.onTextChanged("a", t0))
        assertNull(agg.onTextChanged("ab", t0 + 200))
        assertNull(agg.onTextChanged("abc", t0 + 9_000))
        assertTrue(agg.hasPendingKeystrokes)
    }

    @Test
    fun `the window closes on the first keystroke past its end`() {
        val agg = TypingMicroWindowAggregator()
        agg.onTextChanged("a", t0)
        val completed = agg.onTextChanged("ab", t0 + 11_000)
        assertNotNull(completed)
        assertEquals(1, completed!!.session.typingTapCount)
        // The closing keystroke belongs to the NEXT window.
        assertEquals(1, agg.pendingTapCount)
    }

    @Test
    fun `a stalled typist is drained by the tick loop`() {
        val agg = TypingMicroWindowAggregator()
        agg.onTextChanged("a", t0)
        agg.onTextChanged("ab", t0 + 500)
        assertNull(agg.flushIfElapsed(t0 + 5_000))
        val completed = agg.flushIfElapsed(t0 + 30_000)
        assertEquals(2, completed!!.session.typingTapCount)
        assertFalse(agg.hasPendingKeystrokes)
    }

    @Test
    fun `an elapsed but empty window is dropped, not emitted as zeros`() {
        val agg = TypingMicroWindowAggregator()
        agg.onTextChanged("a", t0)
        agg.flushIfElapsed(t0 + 15_000)
        assertNull(agg.flushIfElapsed(t0 + 40_000))
    }

    @Test
    fun `the stamp is the window start, not the emission time`() {
        val agg = TypingMicroWindowAggregator(windowMs = 10_000)
        agg.onTextChanged("a", t0 + 1_500)
        val completed = agg.onTextChanged("ab", t0 + 25_000)!!
        assertEquals((t0 + 1_500) - ((t0 + 1_500) % 10_000), completed.windowStartMs)
        assertTrue(completed.windowStartMs < t0 + 25_000)
    }

    @Test
    fun `duration is the measured span, not the window length`() {
        val agg = TypingMicroWindowAggregator()
        agg.onTextChanged("a", t0); agg.onTextChanged("ab", t0 + 600); agg.onTextChanged("abc", t0 + 1_200)
        assertEquals(1.2, agg.flushNow()!!.session.durationSec!!, 0.001)
    }

    @Test
    fun `pauses count gaps over 500 ms only`() {
        val agg = TypingMicroWindowAggregator()
        agg.onTextChanged("a", t0)
        agg.onTextChanged("ab", t0 + 200)      // not a pause
        agg.onTextChanged("abc", t0 + 1_000)   // pause
        agg.onTextChanged("abcd", t0 + 1_100)  // not a pause
        agg.onTextChanged("abcde", t0 + 2_000) // pause
        assertEquals(2, agg.flushNow()!!.session.pauseCount)
    }

    @Test
    fun `backspaces are counted per removed character`() {
        val agg = TypingMicroWindowAggregator()
        agg.onTextChanged("hello", t0); agg.onTextChanged("hell", t0 + 200)
        agg.onTextChanged("hel", t0 + 400); agg.onTextChanged("help", t0 + 600)
        val s = agg.flushNow()!!.session
        assertEquals(2, s.numberOfBackspace)
        assertEquals(4, s.typingTapCount)
    }

    @Test
    fun `a held backspace deleting a run counts every character`() {
        val agg = TypingMicroWindowAggregator()
        agg.onTextChanged("abcdefgh", t0); agg.onTextChanged("abc", t0 + 300)
        assertEquals(5, agg.flushNow()!!.session.numberOfBackspace)
    }

    @Test
    fun `speed counts inserted characters over the measured span`() {
        val agg = TypingMicroWindowAggregator()
        agg.onTextChanged("ab", t0); agg.onTextChanged("abcd", t0 + 1_500); agg.onTextChanged("abcdef", t0 + 3_000)
        assertEquals(120.0, agg.flushNow()!!.session.typingSpeedCpm!!, 1.0)
    }

    @Test
    fun `a single-tap window reports no span-derived field`() {
        val agg = TypingMicroWindowAggregator()
        agg.onTextChanged("a", t0)
        val s = agg.flushNow()!!.session
        assertEquals(1, s.typingTapCount)
        assertNull(s.durationSec); assertNull(s.typingSpeedCpm)
        assertNull(s.meanInterTapIntervalMs); assertNull(s.pauseCount); assertNull(s.typingCadenceVariability)
    }

    @Test
    fun `fields a text field cannot see are absent from the wire`() {
        val agg = TypingMicroWindowAggregator()
        agg.onTextChanged("a", t0); agg.onTextChanged("ab", t0 + 300)
        val json = agg.flushNow()!!.session.toJson()
        for (key in listOf(
            "hold_time_mean", "latency_variability", "number_of_cut", "number_of_paste", "number_of_copy",
            "shortcut_count", "shortcut_rate", "typing_efficiency", "keyboard_scroll_rate",
            "typing_gap_count", "typing_gap_ratio", "typing_burstiness", "deep_typing", "number_of_delete",
        )) {
            assertFalse(key, json.has(key))
        }
        assertEquals(2, json.getInt("typing_tap_count"))
        assertEquals(0, json.getInt("number_of_backspace"))
    }

    @Test
    fun `a zero-length change is not a tap`() {
        val agg = TypingMicroWindowAggregator()
        agg.onTextChanged("cat", t0)
        assertNull(agg.onTextChanged("dog", t0 + 200))
        assertEquals(1, agg.pendingTapCount)
    }

    @Test
    fun `syncLength stops a prefilled field reading as a burst of taps`() {
        val agg = TypingMicroWindowAggregator()
        agg.syncLength(40)
        agg.onTextChanged("x".repeat(40) + "y", t0)
        assertEquals(1, agg.flushNow()!!.session.typingTapCount)
    }
}
