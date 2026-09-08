package ai.synheart.core.models

import org.json.JSONObject

/**
 * Typed payloads for `synheart_core_push_behavior_event`.
 *
 * The legacy `synheart_core_push_behavior(ts, code, value)` carries a scalar
 * and nothing else, so a notification pushed that way reaches the engine with
 * `action: None` and a typing event with an all-`None` `TypingSessionData`.
 * These types are the other half — the `data` object the rich FFI parses into
 * the engine's variant-specific fields.
 *
 * ## The null contract
 *
 * Every optional field here is emitted **only when non-null**. That is not a
 * serialization nicety: the engine withholds a `null` and renormalises it out
 * of the feature set, whereas `0.0` is a *measured zero* that moves the score.
 * Reporting a field you did not measure as `0` fabricates evidence. Set what
 * you can measure and leave the rest alone.
 *
 * ## Key names
 *
 * The JSON keys below match core-runtime's `behavior::engine_event`
 * translation table exactly. Two are easy to get wrong because the engine's
 * Rust field names differ from the wire keys: `AppSwitch` reads `from_app` /
 * `to_app` (not `from_app_id` / `to_app_id`) and `NotificationReceived` reads
 * `source_app` (not `source_app_id`). An unrecognised key is silently ignored,
 * so a mis-spelling costs the whole field with no error.
 *
 * An unknown `kind` is dropped by the runtime rather than defaulted, which is
 * why this file models kinds as a closed set instead of a free string.
 */

/** Scroll / swipe direction. Anything outside this set is read as absent. */
enum class ScrollDirection(val wire: String) {
    UP("up"),
    DOWN("down"),
    LEFT("left"),
    RIGHT("right"),
    ;

    companion object {
        /** Parse a native collector's direction string; null for anything else. */
        fun fromWire(raw: String?): ScrollDirection? =
            entries.firstOrNull { it.wire == raw?.lowercase() }
    }
}

/**
 * How the person responded to an interruption (notification or call).
 *
 * This is the field that makes an interruption measurable: without it the
 * engine sees that something arrived but not what it cost.
 */
enum class InterruptionAction(val wire: String) {
    IGNORED("ignored"),
    OPENED("opened"),
    ANSWERED("answered"),
    DISMISSED("dismissed"),
    ;

    companion object {
        fun fromWire(raw: String?): InterruptionAction? =
            entries.firstOrNull { it.wire == raw?.lowercase() }
    }
}

/**
 * A 10-second typing micro-window summary.
 *
 * The richest input the engine takes. Desktop aggregates raw key events into
 * 10 s micro-windows and emits one `Typing` event per window stamped at
 * `window_start_ms`; mobile hosts mirror that shape.
 *
 * Do not also push the raw keystrokes that fed this summary — the engine
 * would count both and every rate feature roughly doubles.
 *
 * Highest-value fields, in order: [typingTapCount]; [numberOfBackspace] and
 * [numberOfDelete] (these two produce `typing.correction_rate`, which feeds the
 * `TypingFluency` reading); [typingSpeedCpm] and [durationSec]; then cadence
 * and pauses.
 *
 * **This is not the channel that feeds CFI.** Cognitive Load's friction index
 * reads `context.deviation.err_elevation`, computed from context-channel
 * keyboard events (`synheart_core_push_context_event`) — a separate push on a
 * separate buffer, not yet bound in this SDK. A rich typing summary with no
 * context events leaves CFI with nothing, however complete the summary is.
 */
data class TypingSessionData(
    /**
     * Measured first-tap→last-tap span, **not** the window length. A window
     * with two taps 1.2 s apart has `durationSec = 1.2`, not `10.0`.
     */
    val durationSec: Double? = null,
    val typingSpeedCpm: Double? = null,
    val typingTapCount: Int? = null,
    /** Gaps longer than 500 ms. */
    val pauseCount: Int? = null,
    val meanInterTapIntervalMs: Double? = null,
    val typingCadenceStability: Double? = null,
    val typingCadenceVariability: Double? = null,
    /** Legacy alias the engine reads alongside [typingCadenceStability]. */
    val cadenceStability: Double? = null,
    val typingGapCount: Int? = null,
    val typingGapRatio: Double? = null,
    val typingBurstiness: Double? = null,
    val typingActivityRatio: Double? = null,
    val typingInteractionIntensity: Double? = null,
    val deepTyping: Boolean? = null,
    val numberOfBackspace: Int? = null,
    val numberOfDelete: Int? = null,
    val numberOfCut: Int? = null,
    val numberOfPaste: Int? = null,
    val numberOfCopy: Int? = null,
    val keyboardScrollRate: Double? = null,
    val shortcutCount: Int? = null,
    val shortcutRate: Double? = null,
    val typingEfficiency: Double? = null,
    val holdTimeMean: Double? = null,
    val latencyVariability: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        durationSec?.let { put("duration_sec", it) }
        typingSpeedCpm?.let { put("typing_speed_cpm", it) }
        typingTapCount?.let { put("typing_tap_count", it) }
        pauseCount?.let { put("pause_count", it) }
        meanInterTapIntervalMs?.let { put("mean_inter_tap_interval_ms", it) }
        typingCadenceStability?.let { put("typing_cadence_stability", it) }
        typingCadenceVariability?.let { put("typing_cadence_variability", it) }
        cadenceStability?.let { put("cadence_stability", it) }
        typingGapCount?.let { put("typing_gap_count", it) }
        typingGapRatio?.let { put("typing_gap_ratio", it) }
        typingBurstiness?.let { put("typing_burstiness", it) }
        typingActivityRatio?.let { put("typing_activity_ratio", it) }
        typingInteractionIntensity?.let { put("typing_interaction_intensity", it) }
        deepTyping?.let { put("deep_typing", it) }
        numberOfBackspace?.let { put("number_of_backspace", it) }
        numberOfDelete?.let { put("number_of_delete", it) }
        numberOfCut?.let { put("number_of_cut", it) }
        numberOfPaste?.let { put("number_of_paste", it) }
        numberOfCopy?.let { put("number_of_copy", it) }
        keyboardScrollRate?.let { put("keyboard_scroll_rate", it) }
        shortcutCount?.let { put("shortcut_count", it) }
        shortcutRate?.let { put("shortcut_rate", it) }
        typingEfficiency?.let { put("typing_efficiency", it) }
        holdTimeMean?.let { put("hold_time_mean", it) }
        latencyVariability?.let { put("latency_variability", it) }
    }
}

/**
 * One rich behavioral event, ready for `Synheart.pushBehaviorEvent`.
 *
 * Construct via the factories on the companion rather than the constructor —
 * they pin the `kind` string to the engine's table, which is the one part a
 * host cannot get wrong and still see an error.
 */
class BehaviorEventInput private constructor(
    /** UTC epoch milliseconds, on the same clock as every other `push_*`. */
    val tsMs: Long,
    /** Engine event kind. Closed set — an unknown kind is dropped, not defaulted. */
    val kind: String,
    /** Back-compat scalar. Only `system_failure` reads it as a fallback. */
    val value: Double = 0.0,
    /** Variant-specific payload, already null-stripped. */
    val data: JSONObject = JSONObject(),
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("ts_ms", tsMs)
        put("kind", kind)
        put("value", value)
        if (data.length() > 0) put("data", data)
    }

    override fun toString(): String = toJson().toString()

    companion object {
        private inline fun data(build: JSONObject.() -> Unit): JSONObject =
            JSONObject().apply(build)

        /** `Touch { duration_ms, long_press }`. */
        fun touch(tsMs: Long, durationMs: Int? = null, longPress: Boolean? = null) =
            BehaviorEventInput(
                tsMs = tsMs,
                kind = "touch",
                data = data {
                    durationMs?.let { put("duration_ms", it) }
                    longPress?.let { put("long_press", it) }
                },
            )

        /**
         * `Scroll { velocity, direction, direction_reversal }`.
         *
         * All three matter. A scroll with direction and velocity stripped tells
         * the engine only that scrolling happened, which is the state mobile
         * hosts have historically shipped in.
         */
        fun scroll(
            tsMs: Long,
            velocity: Double? = null,
            direction: ScrollDirection? = null,
            directionReversal: Boolean? = null,
        ) = BehaviorEventInput(
            tsMs = tsMs,
            kind = "scroll",
            data = data {
                velocity?.let { put("velocity", it) }
                direction?.let { put("direction", it.wire) }
                directionReversal?.let { put("direction_reversal", it) }
            },
        )

        /** `Swipe { direction, velocity }`. */
        fun swipe(tsMs: Long, direction: ScrollDirection? = null, velocity: Double? = null) =
            BehaviorEventInput(
                tsMs = tsMs,
                kind = "swipe",
                data = data {
                    direction?.let { put("direction", it.wire) }
                    velocity?.let { put("velocity", it) }
                },
            )

        /**
         * `AppSwitch { from_app_id, to_app_id }` — wire keys `from_app` / `to_app`.
         *
         * Both ids are what make the switch identifiable; a duration alone
         * gives the engine no app identity and therefore no context row.
         * Android sources them from `UsageStatsManager`.
         */
        fun appSwitch(tsMs: Long, fromApp: String? = null, toApp: String? = null) =
            BehaviorEventInput(
                tsMs = tsMs,
                kind = "app_switch",
                data = data {
                    fromApp?.let { put("from_app", it) }
                    toApp?.let { put("to_app", it) }
                },
            )

        /**
         * `app_foreground` — the host's foreground **resolve**, as opposed to the
         * [appSwitch] *edge*.
         *
         * This is the call that gives the engine an app identity at all, and
         * without it a whole class of readings is silently zeroed. [appSwitch]
         * only fires on a transition, so a session where the person opened one
         * app and stayed in it produces no edge — the runtime's `current_app`
         * stays `None`, `None` resolves to the `Unknown` app category, and
         * `Unknown`'s interpretation-mask row is **all zeros**. CFI / Cognitive
         * Load, Stress `B`, Mental Fatigue `B` and Focus's deviation sub-terms
         * then read `0` for someone who was working the whole time.
         *
         * Send it at session start, on every foreground resume, and
         * periodically. Repeats are cheap and safe: the runtime treats an
         * unchanged app as a steady-state observation and does not bump the
         * switch count; a resolve revealing a *different* app does count as a
         * switch. [app] is an Android package name, matched case-insensitively.
         */
        fun appForeground(tsMs: Long, app: String) = BehaviorEventInput(
            tsMs = tsMs,
            kind = "app_foreground",
            data = data { put("app", app) },
        )

        /** `NotificationReceived { action, source_app_id }` — wire key `source_app`. */
        fun notification(
            tsMs: Long,
            action: InterruptionAction? = null,
            sourceApp: String? = null,
        ) = BehaviorEventInput(
            tsMs = tsMs,
            kind = "notification",
            data = data {
                action?.let { put("action", it.wire) }
                sourceApp?.let { put("source_app", it) }
            },
        )

        /** `Call { action }`. */
        fun call(tsMs: Long, action: InterruptionAction? = null) = BehaviorEventInput(
            tsMs = tsMs,
            kind = "call",
            data = data { action?.let { put("action", it.wire) } },
        )

        /**
         * `Typing { session }` — one 10 s micro-window summary.
         *
         * Stamp [windowStartMs] at the window **start**, matching desktop.
         */
        fun typing(windowStartMs: Long, session: TypingSessionData) = BehaviorEventInput(
            tsMs = windowStartMs,
            kind = "typing",
            data = session.toJson(),
        )

        fun screenOn(tsMs: Long) = BehaviorEventInput(tsMs = tsMs, kind = "screen_on")
        fun screenOff(tsMs: Long) = BehaviorEventInput(tsMs = tsMs, kind = "screen_off")
        fun taskSuccess(tsMs: Long) = BehaviorEventInput(tsMs = tsMs, kind = "task_success")
        fun taskFailure(tsMs: Long) = BehaviorEventInput(tsMs = tsMs, kind = "task_failure")
        fun taskReturn(tsMs: Long) = BehaviorEventInput(tsMs = tsMs, kind = "task_return")
        fun taskAbandonment(tsMs: Long) =
            BehaviorEventInput(tsMs = tsMs, kind = "task_abandonment")

        /** `SystemFailure { duration_secs }` — a stall the person waited through. */
        fun systemFailure(tsMs: Long, durationSecs: Double) = BehaviorEventInput(
            tsMs = tsMs,
            kind = "system_failure",
            value = durationSecs,
            data = data { put("duration_secs", durationSecs) },
        )
    }
}
