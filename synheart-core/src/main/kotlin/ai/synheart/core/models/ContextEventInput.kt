package ai.synheart.core.models

import org.json.JSONObject

/**
 * Typed payloads for `synheart_core_push_context_event`.
 *
 * This is the **context evidence** channel, and it is a different thing from
 * [BehaviorEventInput] even though a single user action often produces one of
 * each. The two land in different places inside the runtime and are consumed
 * by different code:
 *
 * | Channel | Runtime entry | Feeds |
 * |---|---|---|
 * | [BehaviorEventInput] via `push_behavior_event` | `record_interaction` | the interaction adapter (digital axes) **and** session-runtime's behavioural feature group |
 * | [ContextEventInput] via `push_context_event` | `record_context_event_json` | the person-relative context window — `context.deviation.*`, which is where CFI's wired sub-components come from |
 *
 * Sending both for one action is **not** a double count: separate buffers,
 * separate consumers. Sending the *same* event twice on the *same* channel is.
 *
 * ## Why this type exists
 *
 * The runtime deserializes this channel into `synheart_context_runtime::
 * DesktopEvent` — a privacy-preserving keyboard / pointer / shortcut event. It
 * is **not** an app-category channel: there is no app-category variant, and a
 * `{ts_ms, app_id, category}` object does not parse. App identity travels on
 * [BehaviorEventInput.appForeground] instead.
 *
 * A payload that does not parse returns non-zero and buffers nothing, and the
 * failure is indistinguishable from "this runtime was built without the
 * `app-context` cargo feature" (an inert stub that always returns `1`). So the
 * wire shape is not a place to guess — the factories below are pinned to the
 * runtime's documented form by `ContextEventInputTest`.
 *
 * ## Wire shape
 *
 * An externally-tagged enum — the variant name is the single top-level key:
 *
 * ```json
 * { "Keyboard": { "timestamp_ms": 1712345678000, "is_key_down": true, "event_type": "TypingTap" } }
 * { "Mouse":    { "timestamp_ms": 1712345679000, "event_type": "Scroll", "delta_magnitude": null,
 *                 "scroll_direction": "Down", "scroll_magnitude": "Medium" } }
 * { "Shortcut": { "timestamp_ms": 1712345680000, "shortcut_type": "Undo" } }
 * ```
 *
 * Note the explicit `"delta_magnitude": null`. Unlike [BehaviorEventInput],
 * which strips null keys, this channel emits **every** field of the variant it
 * sends — the Rust side is a struct variant, not a bag of optionals, and an
 * omitted key is a parse risk rather than a declared absence. Absence is
 * expressed as JSON `null`, which deserializes to `None`.
 *
 * ## Privacy invariant
 *
 * No typed characters, key codes, cursor coordinates, window titles, URLs or
 * screen content are representable here, by construction. The enums below are
 * the entire vocabulary: a *classification* of the keystroke, never its content.
 */

/**
 * Keyboard event classification.
 *
 * Everything except [MODIFIER_KEY] and [FUNCTION_KEY] counts toward `N_key`,
 * the **denominator** of the context window's error rate
 * (`err_rate = N_corr / N_key`). [BACKSPACE] and [DELETE] additionally count
 * toward `N_corr`, the numerator. That relationship is why corrections must
 * never be pushed without the keystrokes they corrected.
 */
enum class KeyboardEventType(val wire: String) {
    /** A character-producing keystroke. The ordinary case. */
    TYPING_TAP("TypingTap"),
    /** Arrow keys, home/end, page up/down. */
    NAVIGATION_KEY("NavigationKey"),
    /** Counts as a correction. */
    BACKSPACE("Backspace"),
    /**
     * Counts as a correction. A soft keyboard usually cannot tell this from
     * [BACKSPACE]; both count identically, so report [BACKSPACE] and do not
     * invent the split.
     */
    DELETE("Delete"),
    ENTER("Enter"),
    TAB("Tab"),
    ESCAPE("Escape"),
    MODIFIER_KEY("ModifierKey"),
    FUNCTION_KEY("FunctionKey"),
}

/**
 * Pointer event classification. On a touch host a scroll gesture is [SCROLL],
 * a fling or drag is [MOVE], and a discrete pointer tap is [LEFT_CLICK]. A tap
 * that is text entry is not a pointer event — send
 * [KeyboardEventType.TYPING_TAP] instead, or the keystroke inflates `N_click`
 * and never reaches `N_key`.
 */
enum class MouseEventType(val wire: String) {
    /** Carries `delta_magnitude` — a distance, never a coordinate. */
    MOVE("Move"),
    LEFT_CLICK("LeftClick"),
    RIGHT_CLICK("RightClick"),
    /**
     * Carries `scroll_direction` and `scroll_magnitude`. Direction is used only
     * to count *changes* of direction (`R_scrollChange`).
     */
    SCROLL("Scroll"),
}

/** Coarse scroll magnitude bucket, weighted Small = 1, Medium = 2, Large = 3. */
enum class ScrollMagnitude(val wire: String) {
    SMALL("Small"),
    MEDIUM("Medium"),
    LARGE("Large"),
}

/** Recognised editing shortcut. On a touch host these are long-press menu actions. */
enum class ShortcutType(val wire: String) {
    COPY("Copy"),
    PASTE("Paste"),
    CUT("Cut"),
    /** Counts toward `N_corr` alongside backspace and delete. */
    UNDO("Undo"),
    REDO("Redo"),
    SELECT_ALL("SelectAll"),
    SAVE("Save"),
}

/**
 * One privacy-preserving context event, ready for `Synheart.pushContextEvent`.
 *
 * Construct via the factories. There is no public constructor on purpose: the
 * top-level variant key and the field set that goes with it are the part a
 * host cannot get wrong and still see a useful error.
 */
class ContextEventInput private constructor(
    /** The externally-tagged variant name — `Keyboard`, `Mouse` or `Shortcut`. */
    val variant: String,
    /** The variant's fields, complete. Absent values are present as JSON null. */
    val fields: JSONObject,
) {
    /** Epoch milliseconds, on the same clock as every other `push_*`. */
    val tsMs: Long get() = fields.getLong("timestamp_ms")

    fun toJson(): JSONObject = JSONObject().put(variant, fields)

    override fun toString(): String = "ContextEventInput($variant, $fields)"

    companion object {
        /**
         * The context channel spells directions `Up`/`Down`/`Left`/`Right`,
         * where the behaviour channel spells them lowercase. One concept, two
         * wire vocabularies — so the SDK keeps one public [ScrollDirection] and
         * maps here.
         */
        private fun ScrollDirection.contextWire(): String = when (this) {
            ScrollDirection.UP -> "Up"
            ScrollDirection.DOWN -> "Down"
            ScrollDirection.LEFT -> "Left"
            ScrollDirection.RIGHT -> "Right"
        }

        /**
         * A keystroke, classified but never identified.
         *
         * [isKeyDown] distinguishes a press from a release: only presses count
         * as physical input for the window's idle clock. A host that sees only
         * committed text changes (a text watcher, most soft keyboards) has no
         * release to report and should leave this `true`.
         */
        fun keyboard(tsMs: Long, eventType: KeyboardEventType, isKeyDown: Boolean = true) =
            ContextEventInput(
                "Keyboard",
                JSONObject()
                    .put("timestamp_ms", tsMs)
                    .put("is_key_down", isKeyDown)
                    .put("event_type", eventType.wire),
            )

        /**
         * A pointer event. Pass [deltaMagnitude] for [MouseEventType.MOVE] (a
         * distance in pixels, never a coordinate), and [scrollDirection] /
         * [scrollMagnitude] for [MouseEventType.SCROLL]. Unused fields are
         * emitted as JSON null rather than omitted — see the file doc.
         */
        fun mouse(
            tsMs: Long,
            eventType: MouseEventType,
            deltaMagnitude: Double? = null,
            scrollDirection: ScrollDirection? = null,
            scrollMagnitude: ScrollMagnitude? = null,
        ) = ContextEventInput(
            "Mouse",
            JSONObject()
                .put("timestamp_ms", tsMs)
                .put("event_type", eventType.wire)
                .put("delta_magnitude", deltaMagnitude ?: JSONObject.NULL)
                .put("scroll_direction", scrollDirection?.contextWire() ?: JSONObject.NULL)
                .put("scroll_magnitude", scrollMagnitude?.wire ?: JSONObject.NULL),
        )

        /** An editing shortcut. */
        fun shortcut(tsMs: Long, shortcutType: ShortcutType) = ContextEventInput(
            "Shortcut",
            JSONObject().put("timestamp_ms", tsMs).put("shortcut_type", shortcutType.wire),
        )

        /**
         * Convenience for the commonest pair a text field can report: an
         * insertion is a [KeyboardEventType.TYPING_TAP], a deletion a
         * [KeyboardEventType.BACKSPACE].
         *
         * This is what gives the context window an error *rate* rather than an
         * error count with no denominator, so send it for **both** directions —
         * corrections alone are worse than nothing.
         */
        fun textChange(tsMs: Long, isDeletion: Boolean) = keyboard(
            tsMs,
            if (isDeletion) KeyboardEventType.BACKSPACE else KeyboardEventType.TYPING_TAP,
        )
    }
}
