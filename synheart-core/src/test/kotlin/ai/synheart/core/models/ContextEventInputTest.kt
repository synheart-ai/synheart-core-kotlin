package ai.synheart.core.models

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the context channel's wire shape to the runtime's documented form.
 *
 * Getting it wrong is silent: a payload that does not deserialize into
 * `DesktopEvent` returns non-zero, buffers nothing, and is indistinguishable
 * from a runtime built without the `app-context` feature. So the shape is not a
 * place to guess, and these assert it literally — the externally-tagged
 * variant key, the PascalCase enum spellings, and the explicit nulls.
 */
class ContextEventInputTest {

    private val ts = 1_712_345_678_000L

    @Test
    fun `keyboard is externally tagged with the complete field set`() {
        val json = ContextEventInput.keyboard(ts, KeyboardEventType.TYPING_TAP).toJson()
        assertEquals(1, json.length())
        val kb = json.getJSONObject("Keyboard")
        assertEquals(ts, kb.getLong("timestamp_ms"))
        assertTrue(kb.getBoolean("is_key_down"))
        assertEquals("TypingTap", kb.getString("event_type"))
    }

    @Test
    fun `mouse scroll emits every field, absent ones as JSON null`() {
        val json = ContextEventInput.mouse(
            ts,
            MouseEventType.SCROLL,
            scrollDirection = ScrollDirection.DOWN,
            scrollMagnitude = ScrollMagnitude.MEDIUM,
        ).toJson()
        val m = json.getJSONObject("Mouse")
        assertEquals("Scroll", m.getString("event_type"))
        assertEquals("Down", m.getString("scroll_direction"))
        assertEquals("Medium", m.getString("scroll_magnitude"))
        // Present and null — the Rust side is a struct variant, not a bag of
        // optionals, so an omitted key is a parse risk rather than an absence.
        assertTrue(m.has("delta_magnitude"))
        assertTrue(m.isNull("delta_magnitude"))
    }

    @Test
    fun `mouse move carries a magnitude and null scroll fields`() {
        val m = ContextEventInput.mouse(ts, MouseEventType.MOVE, deltaMagnitude = 140.0)
            .toJson().getJSONObject("Mouse")
        assertEquals("Move", m.getString("event_type"))
        assertEquals(140.0, m.getDouble("delta_magnitude"), 0.0)
        assertTrue(m.isNull("scroll_direction"))
        assertTrue(m.isNull("scroll_magnitude"))
    }

    @Test
    fun `a bare scroll keeps direction and magnitude null rather than defaulting`() {
        // The Kotlin host records a scroll distance and no direction. Inventing
        // a direction would fabricate a reversal; inventing Small would assert
        // a measured magnitude.
        val m = ContextEventInput.mouse(ts, MouseEventType.SCROLL).toJson().getJSONObject("Mouse")
        assertTrue(m.isNull("scroll_direction"))
        assertTrue(m.isNull("scroll_magnitude"))
    }

    @Test
    fun `direction spelling differs between the two channels`() {
        // Behaviour channel: lowercase. Context channel: PascalCase. One
        // public enum, two wire vocabularies.
        assertEquals("down", ScrollDirection.DOWN.wire)
        val m = ContextEventInput.mouse(ts, MouseEventType.SCROLL, scrollDirection = ScrollDirection.LEFT)
            .toJson().getJSONObject("Mouse")
        assertEquals("Left", m.getString("scroll_direction"))
    }

    @Test
    fun `shortcut carries the type`() {
        val sc = ContextEventInput.shortcut(ts, ShortcutType.UNDO).toJson().getJSONObject("Shortcut")
        assertEquals(ts, sc.getLong("timestamp_ms"))
        assertEquals("Undo", sc.getString("shortcut_type"))
    }

    @Test
    fun `textChange maps insertion to TypingTap and deletion to Backspace`() {
        val ins = ContextEventInput.textChange(ts, isDeletion = false).toJson().getJSONObject("Keyboard")
        val del = ContextEventInput.textChange(ts, isDeletion = true).toJson().getJSONObject("Keyboard")
        assertEquals("TypingTap", ins.getString("event_type"))
        assertEquals("Backspace", del.getString("event_type"))
        // Never Delete: a soft keyboard cannot tell them apart and both count
        // identically, so the split is not asserted.
        assertFalse(del.getString("event_type") == "Delete")
    }

    @Test
    fun `there is no app-category shape on this channel`() {
        // The old `{ts_ms, app_id, category}` payload does not exist as a
        // factory, and no variant other than the three is producible.
        for (e in listOf(
            ContextEventInput.keyboard(ts, KeyboardEventType.ENTER),
            ContextEventInput.mouse(ts, MouseEventType.LEFT_CLICK),
            ContextEventInput.shortcut(ts, ShortcutType.PASTE),
        )) {
            val json: JSONObject = e.toJson()
            assertTrue(e.variant in setOf("Keyboard", "Mouse", "Shortcut"))
            assertFalse(json.has("app_id")); assertFalse(json.has("category"))
        }
    }

    @Test
    fun `appForeground rides the behaviour channel with the app key`() {
        val json = BehaviorEventInput.appForeground(ts, "com.google.android.gm").toJson()
        assertEquals("app_foreground", json.getString("kind"))
        assertEquals("com.google.android.gm", json.getJSONObject("data").getString("app"))
    }
}
