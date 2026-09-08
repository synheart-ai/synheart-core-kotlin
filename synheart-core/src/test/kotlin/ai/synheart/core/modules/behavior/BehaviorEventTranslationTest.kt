package ai.synheart.core.modules.behavior

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host-recorded event → rich engine event translation.
 *
 * Two things are load-bearing. Keystrokes must translate to `null` so they
 * take the legacy path and are never pushed as an all-null
 * `TypingSessionData`. And nothing the host did not record may appear in the
 * payload — a Kotlin scroll has a travelled distance and no velocity, and a
 * `velocity: 0.0` would be a measured stillness the engine scores on.
 */
class BehaviorEventTranslationTest {

    private val ts = 1_700_000_000_000L

    @Test
    fun `a tap becomes a touch with no invented duration`() {
        val rich = BehaviorModule.translateBehaviorEvent(
            BehaviorEvent(BehaviorEventType.TAP, ts, mapOf("x" to 10.0, "y" to 20.0)),
        )!!
        val json = rich.toJson()
        assertEquals("touch", json.getString("kind"))
        assertEquals(ts, json.getLong("ts_ms"))
        assertFalse("no duration was measured", json.has("data"))
    }

    @Test
    fun `a scroll becomes a scroll with no invented velocity or direction`() {
        val rich = BehaviorModule.translateBehaviorEvent(
            BehaviorEvent(BehaviorEventType.SCROLL, ts, mapOf("delta" to 140.0)),
        )!!
        val json = rich.toJson()
        assertEquals("scroll", json.getString("kind"))
        assertFalse(json.has("data"))
    }

    @Test
    fun `keystrokes are not translated`() {
        assertNull(BehaviorModule.translateBehaviorEvent(BehaviorEvent(BehaviorEventType.KEY_DOWN, ts)))
        assertNull(BehaviorModule.translateBehaviorEvent(BehaviorEvent(BehaviorEventType.KEY_UP, ts)))
    }

    @Test
    fun `an opened notification carries the action, a received one does not`() {
        val opened = BehaviorModule.translateBehaviorEvent(
            BehaviorEvent(BehaviorEventType.NOTIFICATION_OPENED, ts),
        )!!.toJson()
        assertEquals("notification", opened.getString("kind"))
        assertEquals("opened", opened.getJSONObject("data").getString("action"))

        // Arrival is not a response. The host recorded that something came in
        // and nothing about what the person did with it.
        val received = BehaviorModule.translateBehaviorEvent(
            BehaviorEvent(BehaviorEventType.NOTIFICATION_RECEIVED, ts),
        )!!.toJson()
        assertEquals("notification", received.getString("kind"))
        assertFalse(received.has("data"))
    }

    @Test
    fun `context - a tap is a real pointer click on Kotlin`() {
        // Unlike the Flutter plugin, where Android emits every keystroke as a
        // tap, this module's taps come from real MotionEvents and keystrokes
        // arrive separately — so LeftClick is honest and does not inflate
        // N_click with typing.
        val ctx = BehaviorModule.translateContextEvent(
            BehaviorEvent(BehaviorEventType.TAP, ts, mapOf("x" to 1.0, "y" to 2.0)),
        )!!.toJson().getJSONObject("Mouse")
        assertEquals("LeftClick", ctx.getString("event_type"))
    }

    @Test
    fun `context - a scroll is forwarded with no invented direction or magnitude`() {
        val ctx = BehaviorModule.translateContextEvent(
            BehaviorEvent(BehaviorEventType.SCROLL, ts, mapOf("delta" to 140.0)),
        )!!.toJson().getJSONObject("Mouse")
        assertEquals("Scroll", ctx.getString("event_type"))
        assertTrue(ctx.isNull("scroll_direction"))
        assertTrue(ctx.isNull("scroll_magnitude"))
    }

    @Test
    fun `context - keystrokes are not translated, the text layer owns them`() {
        // Forwarding KEY_DOWN here as well as the host's textChange would count
        // each keystroke twice on the same channel.
        assertNull(BehaviorModule.translateContextEvent(BehaviorEvent(BehaviorEventType.KEY_DOWN, ts)))
        assertNull(BehaviorModule.translateContextEvent(BehaviorEvent(BehaviorEventType.KEY_UP, ts)))
    }

    @Test
    fun `context - interruptions and switches stay on the behaviour channel`() {
        for (t in listOf(
            BehaviorEventType.APP_SWITCH,
            BehaviorEventType.NOTIFICATION_RECEIVED,
            BehaviorEventType.NOTIFICATION_OPENED,
        )) {
            assertNull(BehaviorModule.translateContextEvent(BehaviorEvent(t, ts)))
        }
    }

    @Test
    fun `an app switch has no identity to forward`() {
        // Without UsageStatsManager there are no app ids; the switch is
        // recorded without them rather than with placeholders.
        val json = BehaviorModule.translateBehaviorEvent(
            BehaviorEvent(BehaviorEventType.APP_SWITCH, ts),
        )!!.toJson()
        assertEquals("app_switch", json.getString("kind"))
        assertFalse(json.has("data"))
    }
}
