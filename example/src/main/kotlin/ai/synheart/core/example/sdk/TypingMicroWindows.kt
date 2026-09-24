package ai.synheart.core.example.sdk

import ai.synheart.core.models.TypingSessionData
import kotlin.math.sqrt

/** A completed micro-window: the summary and the window it describes. */
data class TypingWindow(val windowStartMs: Long, val session: TypingSessionData)

/**
 * Aggregates keystrokes into the 10-second micro-window summaries the engine's
 * `Typing` variant expects (mobile host guide §5.3).
 *
 * ## Why a host has to do this at all
 *
 * `TypingSessionData` is the richest input the engine takes, and there is no
 * call that accepts a raw keystroke into it. Desktop aggregates key events
 * into 10 s micro-windows and emits one `Typing` event per window stamped at
 * `window_start_ms`; this mirrors that shape so the two platforms produce
 * comparable features. Pushing a single keystroke as a `Typing` event instead
 * would land a session whose every field is null — an observation with no
 * evidence behind it, which is worse for the feature group than sending
 * nothing.
 *
 * ## Absent stays absent
 *
 * Only fields this host can actually measure from a text field are populated.
 * Everything else is left null, and that is a deliberate report rather than
 * an omission: the engine withholds a null and renormalises it out of the
 * feature set, whereas `0.0` is a *measured zero* that moves the score.
 *
 * What is genuinely unavailable here, and why:
 *
 * * `number_of_delete` — a soft keyboard reports a backspace and a forward
 *   delete identically through a text watcher. Collapsing both into
 *   `number_of_backspace` keeps `typing.correction_rate` correct (it reads the
 *   sum) without asserting a split this host cannot see.
 * * `hold_time_mean`, `latency_variability` — need key-down/key-up pairs. A
 *   text field reports committed value changes, not physical key events.
 * * `number_of_cut` / `paste` / `copy`, `shortcut_count` — no clipboard or
 *   modifier visibility from inside a text field.
 *
 * A host with a real IME sees all of those and should fill them in; a host
 * reading a text field should not pretend to.
 */
class TypingMicroWindowAggregator(
    /**
     * Micro-window length. 10 s matches desktop; changing it makes this host's
     * rate features incomparable with desktop's.
     */
    val windowMs: Long = 10_000L,
) {
    /** Start of the accumulating window, aligned to [windowMs] so windows tile. */
    private var windowStartMs: Long? = null

    private val tapTimestamps = ArrayList<Long>()
    private var backspaces = 0
    private var charactersAdded = 0

    /** Previous text length, so a change is classified as insertion or deletion. */
    private var previousLength = 0

    /**
     * The text length this aggregator last saw. Exposed so a caller can classify
     * the *next* change as insertion or deletion before handing it over — what
     * the context channel needs (`ContextEventInput.textChange`). Read it
     * before [onTextChanged], which updates it.
     */
    val currentLength: Int get() = previousLength

    val hasPendingKeystrokes: Boolean get() = tapTimestamps.isNotEmpty()
    val pendingTapCount: Int get() = tapTimestamps.size

    /**
     * Reset to a clean state, dropping whatever is buffered — rather than
     * flushing it, because a partial window flushed against a new session's
     * clock would attribute one session's keystrokes to another.
     */
    fun reset() {
        windowStartMs = null
        tapTimestamps.clear()
        backspaces = 0
        charactersAdded = 0
        previousLength = 0
    }

    /**
     * Seed the length baseline without recording keystrokes — for a field that
     * is pre-populated or programmatically set, so the first real edit is not
     * measured against a length of 0 and a paste of 40 characters does not
     * read as 40 taps.
     */
    fun syncLength(length: Int) {
        previousLength = length
    }

    /**
     * Record a text change. Returns a completed window when this change closed
     * one, otherwise null. [nowMs] is epoch ms on the same clock as every
     * `push_*`.
     */
    fun onTextChanged(text: String, nowMs: Long): TypingWindow? {
        val delta = text.length - previousLength
        previousLength = text.length

        // A zero-length change (autocorrect swapping one word for another of
        // the same length) is an edit but not a countable tap, and guessing
        // which it was would corrupt the correction rate.
        if (delta == 0) return null

        val completed = closeWindowIfElapsed(nowMs)

        if (windowStartMs == null) windowStartMs = nowMs - (nowMs % windowMs)
        tapTimestamps.add(nowMs)
        if (delta < 0) {
            // One backspace per removed character. A held-down backspace
            // deleting a run arrives as several changes, each counted once.
            backspaces += -delta
        } else {
            charactersAdded += delta
        }
        return completed
    }

    /**
     * Close the current window if [nowMs] has passed its end.
     *
     * Exposed so the host can call it from its own tick loop: a person who
     * stops typing mid-window would otherwise leave that window buffered until
     * their next keystroke, and it would then be stamped and emitted long
     * after the interaction it describes. §6.4's rule — drain before the tick
     * that closes the window the events belong to — applies to this buffer as
     * much as to a platform one.
     */
    fun flushIfElapsed(nowMs: Long): TypingWindow? = closeWindowIfElapsed(nowMs)

    /** Force the buffered window out. For session end and backgrounding. */
    fun flushNow(): TypingWindow? {
        val start = windowStartMs ?: return null
        if (tapTimestamps.isEmpty()) return null
        val session = buildSession()
        startFreshWindow(null)
        return TypingWindow(start, session)
    }

    private fun closeWindowIfElapsed(nowMs: Long): TypingWindow? {
        val start = windowStartMs ?: return null
        if (nowMs < start + windowMs) return null
        if (tapTimestamps.isEmpty()) {
            // An empty elapsed window carries no evidence, so it is dropped
            // rather than emitted as an all-zero session. Silence and
            // "measured zero typing" are different claims.
            startFreshWindow(nowMs)
            return null
        }
        val session = buildSession()
        startFreshWindow(nowMs)
        return TypingWindow(start, session)
    }

    private fun startFreshWindow(nowMs: Long?) {
        windowStartMs = nowMs?.let { it - (it % windowMs) }
        tapTimestamps.clear()
        backspaces = 0
        charactersAdded = 0
    }

    private fun buildSession(): TypingSessionData {
        val taps = tapTimestamps.size

        // Measured first-tap → last-tap span, NOT the window length. A window
        // holding two taps 1.2 s apart has durationSec 1.2; reporting 10.0
        // would make every derived rate wrong by the ratio of the two.
        val spanMs = tapTimestamps.last() - tapTimestamps.first()
        val durationSec = spanMs / 1000.0

        val intervals = (1 until tapTimestamps.size).map {
            (tapTimestamps[it] - tapTimestamps[it - 1]).toDouble()
        }

        var meanIti: Double? = null
        var cv: Double? = null
        if (intervals.isNotEmpty()) {
            val mean = intervals.average()
            meanIti = mean
            if (intervals.size > 1 && mean > 0) {
                val variance = intervals.sumOf { (it - mean) * (it - mean) } / (intervals.size - 1)
                cv = sqrt(variance) / mean
            }
        }

        // Gaps longer than 500 ms, per the field's definition.
        val pauseCount = intervals.count { it > 500 }

        return TypingSessionData(
            typingTapCount = taps,
            // A single-tap window has no measurable span; 0.0 there would be a
            // measured zero duration, which is not what happened.
            durationSec = if (spanMs > 0) durationSec else null,
            typingSpeedCpm = if (spanMs > 0) charactersAdded / (durationSec / 60.0) else null,
            meanInterTapIntervalMs = meanIti,
            pauseCount = if (intervals.isEmpty()) null else pauseCount,
            typingCadenceVariability = cv,
            // Stability as the complement of variability, clamped — derived
            // from the same measurement rather than invented independently.
            typingCadenceStability = cv?.let { (1.0 - it).coerceIn(0.0, 1.0) },
            // The correction-rate input. Focus and CFI both read it, and
            // without it the friction index has nothing — so this is the one
            // count worth reporting even when it is genuinely zero: a window
            // with taps and no backspaces is a *measured* zero correction rate,
            // which is real evidence of fluent typing.
            numberOfBackspace = backspaces,
        )
    }
}
