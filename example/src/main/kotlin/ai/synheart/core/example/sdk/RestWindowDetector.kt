package ai.synheart.core.example.sdk

import java.time.LocalDateTime

/**
 * Decides when to call `declareRestWindow` (mobile host guide §6.5).
 *
 * ## What breaks without it
 *
 * There is no context module on mobile to derive rest, so an undeclared host
 * scores every break window as engaged: Focus is never zeroed and Capacity
 * never takes the recovery path. Someone who put the phone down for twenty
 * minutes looks, to the engine, like someone who worked through it.
 *
 * ## Rest is a composite, not a screen-off event
 *
 * Screen off alone is wrong in both directions: a person watching a video is
 * screen-on and resting, and a person in a meeting is screen-off and working.
 * The definition this implements is the one the guide specifies — screen off
 * for at least [screenOffThresholdMs] **and** no interaction **and** low
 * motion — with a wall-clock sleep window as an override for the case where a
 * phone on a nightstand registers no motion but also no screen event.
 *
 * ## One-shot per window, and why that is not a detail
 *
 * The ABI call is deliberately one-shot. A sticky flag a host forgot to clear
 * would pin Focus at exactly `0.0`, stop Capacity depleting and freeze Mental
 * Fatigue's engaged clock for the rest of the session — silently, with a
 * plausible-looking `0.0` on the wire the whole time. So this class hands out
 * at most one declaration per window and remembers which ones it has spent.
 *
 * ## The window-alignment caveat
 *
 * Window identity here is `ts / windowMs`, which tiles epoch time. The
 * engine's windows are aligned to its own pipeline clock, so the two grids
 * need not coincide. The consequence is bounded and one-directional: at a
 * boundary this may declare twice inside one engine window (idempotent in
 * effect) or once for a window already emitted (discarded by the runtime, not
 * misapplied). What it cannot do is leave a rest period undeclared.
 */
class RestWindowDetector(
    /** Must match the `window_ms` the config declared. */
    val windowMs: Long = 60_000L,
    /** How long the screen must have been off. The guide's floor is 2 minutes. */
    val screenOffThresholdMs: Long = 120_000L,
    /**
     * How long since the last interaction. Separate from the screen-off clock:
     * the screen can be off while a person is still handling the device.
     */
    val interactionQuietMs: Long = 120_000L,
    /**
     * Accelerometer RMS below which motion counts as low, m/s² gravity removed.
     * Above this the person is moving, which is not rest even with the screen
     * off and no taps — walking with the phone pocketed hits both of those.
     */
    val motionRmsCeiling: Double = 0.35,
    /** Wall-clock sleep window, local hours, `[start, end)` crossing midnight. */
    val sleepWindowStartHour: Int = 23,
    val sleepWindowEndHour: Int = 6,
) {
    private var screenOffSinceMs: Long? = null
    private var lastInteractionMs: Long? = null
    private var latestMotionRms: Double? = null

    /** Windows already declared. Bounded below. */
    private val declaredWindows = LinkedHashSet<Long>()

    /** Declarations made since construction, for the UI. */
    var declaredCount: Int = 0
        private set

    /** Why the last [evaluate] declined. Null when the last call declared. */
    var lastDeclineReason: String? = null
        private set

    /**
     * The screen turned off. On a plain Activity host this is `onPause`/`onStop`,
     * which is a proxy rather than a true screen-off: the app also pauses when
     * the person switches to another app with the screen very much on. A
     * production host reads `ACTION_SCREEN_OFF` instead, and the difference
     * matters — this proxy will call an app switch "rest" if nothing else
     * contradicts it, which is why the interaction and motion clauses are not
     * optional.
     */
    fun noteScreenOff(tsMs: Long) {
        if (screenOffSinceMs == null) screenOffSinceMs = tsMs
    }

    fun noteScreenOn(tsMs: Long) {
        screenOffSinceMs = null
        // Waking the screen is itself an interaction; without this the quiet
        // clock would still read as two minutes idle the instant the person
        // picks the phone up.
        lastInteractionMs = tsMs
    }

    /** Any behavior event — tap, scroll, keystroke. */
    fun noteInteraction(tsMs: Long) {
        val current = lastInteractionMs
        if (current == null || tsMs > current) lastInteractionMs = tsMs
    }

    /** Latest gravity-removed accelerometer RMS. */
    fun noteMotionRms(rms: Double) {
        latestMotionRms = rms
    }

    fun reset() {
        screenOffSinceMs = null
        lastInteractionMs = null
        latestMotionRms = null
        declaredWindows.clear()
        lastDeclineReason = null
    }

    /**
     * The timestamp to hand `declareRestWindow`, or null when this moment is
     * not rest or its window has already been declared.
     */
    fun evaluate(nowMs: Long, localNow: LocalDateTime = LocalDateTime.now()): Long? {
        val windowIndex = nowMs / windowMs
        if (windowIndex in declaredWindows) {
            lastDeclineReason = "already declared for this window"
            return null
        }

        if (!isRestingNow(nowMs, localNow)) return null

        declaredWindows.add(windowIndex)
        // A few windows of history is enough for the one-shot guard and keeps
        // this from growing across a long session.
        while (declaredWindows.size > 4) {
            val oldest = declaredWindows.first()
            declaredWindows.remove(oldest)
        }
        declaredCount++
        lastDeclineReason = null
        return nowMs
    }

    private fun isRestingNow(nowMs: Long, localNow: LocalDateTime): Boolean {
        // The sleep-window override comes first, because it exists precisely
        // for the case the composite cannot see: a phone face-down on a
        // nightstand fires no screen-off transition after the first one and
        // reports no motion, so the composite is satisfied only by accident.
        if (isInSleepWindow(localNow)) return true

        val screenOffSince = screenOffSinceMs
        if (screenOffSince == null) {
            lastDeclineReason = "screen is on"
            return false
        }
        if (nowMs - screenOffSince < screenOffThresholdMs) {
            val held = (nowMs - screenOffSince) / 1000
            lastDeclineReason = "screen off for ${held}s, needs ${screenOffThresholdMs / 1000}s"
            return false
        }

        val lastInteraction = lastInteractionMs
        if (lastInteraction != null && nowMs - lastInteraction < interactionQuietMs) {
            lastDeclineReason = "interaction ${(nowMs - lastInteraction) / 1000}s ago"
            return false
        }

        // A null motion reading is not treated as low motion. With no
        // accelerometer forwarding the clause is simply unverifiable, and
        // declaring rest on two of three conditions would call a walk with the
        // phone pocketed a rest window.
        val motion = latestMotionRms
        if (motion == null) {
            lastDeclineReason = "no motion reading — cannot verify low motion"
            return false
        }
        if (motion > motionRmsCeiling) {
            lastDeclineReason = "motion %.2f m/s² too high".format(motion)
            return false
        }
        return true
    }

    private fun isInSleepWindow(localNow: LocalDateTime): Boolean {
        val hour = localNow.hour
        return if (sleepWindowStartHour <= sleepWindowEndHour) {
            hour >= sleepWindowStartHour && hour < sleepWindowEndHour
        } else {
            hour >= sleepWindowStartHour || hour < sleepWindowEndHour
        }
    }
}
