package ai.synheart.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the null contract and the wire keys of the rich behavior payload.
 *
 * The keys are checked literally because two of them differ from the engine's
 * own Rust field names — `from_app`/`to_app`, `source_app` — and an
 * unrecognised key is dropped silently, so a mis-spelling here costs the whole
 * field with no error anywhere.
 *
 * The null contract is checked because it is the part a well-meaning change
 * breaks: "just default it to 0" turns an unobserved field into a measured
 * zero, and the engine moves the score on it.
 */
class BehaviorEventInputTest {

    private val ts = 1_700_000_000_000L

    @Test
    fun `an event with no payload omits the data key entirely`() {
        val json = BehaviorEventInput.screenOff(ts).toJson()
        assertEquals(ts, json.getLong("ts_ms"))
        assertEquals("screen_off", json.getString("kind"))
        assertEquals(0.0, json.getDouble("value"), 0.0)
        assertFalse(json.has("data"))
    }

    @Test
    fun `touch forwards only the fields it was given`() {
        val partial = BehaviorEventInput.touch(ts, durationMs = 120).toJson().getJSONObject("data")
        assertEquals(120, partial.getInt("duration_ms"))
        assertFalse("unmeasured must not become false", partial.has("long_press"))

        val full = BehaviorEventInput.touch(ts, durationMs = 900, longPress = true)
            .toJson().getJSONObject("data")
        assertTrue(full.getBoolean("long_press"))
    }

    @Test
    fun `scroll carries direction as its wire string`() {
        val data = BehaviorEventInput.scroll(
            ts,
            velocity = 1200.5,
            direction = ScrollDirection.DOWN,
            directionReversal = true,
        ).toJson().getJSONObject("data")
        assertEquals(1200.5, data.getDouble("velocity"), 0.0)
        assertEquals("down", data.getString("direction"))
        assertTrue(data.getBoolean("direction_reversal"))
    }

    @Test
    fun `a bare scroll has an empty payload and no data key`() {
        // The state mobile hosts historically shipped in: scrolling happened,
        // nothing else known. Still a valid event — just an uninformative one.
        val json = BehaviorEventInput.scroll(ts).toJson()
        assertEquals("scroll", json.getString("kind"))
        assertFalse(json.has("data"))
    }

    @Test
    fun `app switch uses the engine's from_app and to_app keys`() {
        val data = BehaviorEventInput.appSwitch(ts, fromApp = "com.a", toApp = "com.b")
            .toJson().getJSONObject("data")
        assertEquals("com.a", data.getString("from_app"))
        assertEquals("com.b", data.getString("to_app"))
        assertFalse("Rust field name is not the wire key", data.has("from_app_id"))
    }

    @Test
    fun `notification uses source_app, not source_app_id`() {
        val data = BehaviorEventInput.notification(
            ts,
            action = InterruptionAction.OPENED,
            sourceApp = "com.google.android.gm",
        ).toJson().getJSONObject("data")
        assertEquals("opened", data.getString("action"))
        assertEquals("com.google.android.gm", data.getString("source_app"))
        assertFalse(data.has("source_app_id"))
    }

    @Test
    fun `typing is stamped at the window start and carries only measured fields`() {
        val windowStart = 1_700_000_010_000L
        val session = TypingSessionData(
            typingTapCount = 14,
            numberOfBackspace = 2,
            durationSec = 7.4,
            typingSpeedCpm = 96.0,
            pauseCount = 1,
        )
        val json = BehaviorEventInput.typing(windowStart, session).toJson()
        assertEquals(windowStart, json.getLong("ts_ms"))
        assertEquals("typing", json.getString("kind"))

        val data = json.getJSONObject("data")
        assertEquals(14, data.getInt("typing_tap_count"))
        assertEquals(2, data.getInt("number_of_backspace"))
        assertEquals(7.4, data.getDouble("duration_sec"), 0.0)
        assertEquals(96.0, data.getDouble("typing_speed_cpm"), 0.0)
        assertEquals(1, data.getInt("pause_count"))

        // Everything a soft keyboard cannot observe must be absent, not zero.
        for (key in listOf(
            "hold_time_mean", "latency_variability", "number_of_delete",
            "number_of_cut", "number_of_paste", "number_of_copy",
            "shortcut_count", "shortcut_rate", "typing_efficiency",
            "keyboard_scroll_rate", "typing_gap_count", "typing_gap_ratio",
            "typing_burstiness", "deep_typing", "mean_inter_tap_interval_ms",
        )) {
            assertFalse("$key must not be fabricated", data.has(key))
        }
    }

    @Test
    fun `an empty typing session still has no data key`() {
        val json = BehaviorEventInput.typing(ts, TypingSessionData()).toJson()
        assertFalse(json.has("data"))
    }

    @Test
    fun `system failure carries the duration in both value and data`() {
        val json = BehaviorEventInput.systemFailure(ts, durationSecs = 3.5).toJson()
        assertEquals(3.5, json.getDouble("value"), 0.0)
        assertEquals(3.5, json.getJSONObject("data").getDouble("duration_secs"), 0.0)
    }

    @Test
    fun `wire parsers tolerate case and reject unknowns`() {
        assertEquals(ScrollDirection.UP, ScrollDirection.fromWire("UP"))
        assertEquals(ScrollDirection.LEFT, ScrollDirection.fromWire("left"))
        assertNull(ScrollDirection.fromWire("diagonal"))
        assertNull(ScrollDirection.fromWire(null))
        assertEquals(InterruptionAction.DISMISSED, InterruptionAction.fromWire("dismissed"))
        // A collector's "received" is an arrival, not a response — there is no
        // action for it and it must not be coerced into one.
        assertNull(InterruptionAction.fromWire("received"))
    }
}
