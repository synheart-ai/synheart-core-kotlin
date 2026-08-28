package ai.synheart.core.models

import ai.synheart.core.modules.behavior.BehaviorEvent
import ai.synheart.core.modules.behavior.BehaviorEventType
import kotlin.math.sqrt

/**
 * Results from one behavior session.
 *
 * Simplified access to the metrics a host is likely to render. The richer
 * per-window aggregates live in the native runtime's HSI output; these are the
 * session-level roll-ups.
 */
data class BehaviorSessionResults(
    val sessionId: String,
    /** Session duration in milliseconds. */
    val durationMs: Long,
    /** Taps per second over the session. */
    val tapRate: Double,
    /** Keystrokes per second over the session. */
    val keystrokeRate: Double,
    /** Focus hint in `[0, 1]`; higher means more sustained attention. */
    val focusHint: Double,
    /** Interaction intensity in `[0, 1]`. */
    val interactionIntensity: Double,
    /** Burstiness in `[0, 1]`; higher means more clustered interaction. */
    val burstiness: Double,
    /** Total events observed in the session. */
    val totalEvents: Int,
) {
    companion object {
        /**
         * Roll a session's raw events up into the summary above.
         *
         * `focusHint`, `interactionIntensity` and `burstiness` are derived from
         * inter-event timing here so a host gets usable numbers without a
         * runtime round-trip; the engine computes its own, higher-fidelity
         * versions for the HSI axes.
         */
        fun fromEvents(
            sessionId: String,
            durationMs: Long,
            events: List<BehaviorEvent>,
        ): BehaviorSessionResults {
            val durationSec = durationMs / 1000.0
            val total = events.size
            if (durationSec <= 0.0 || total == 0) {
                return BehaviorSessionResults(
                    sessionId = sessionId,
                    durationMs = durationMs,
                    tapRate = 0.0,
                    keystrokeRate = 0.0,
                    focusHint = 0.0,
                    interactionIntensity = 0.0,
                    burstiness = 0.0,
                    totalEvents = total,
                )
            }

            val taps = events.count { it.type == BehaviorEventType.TAP }
            val keys = events.count {
                it.type == BehaviorEventType.KEY_DOWN || it.type == BehaviorEventType.KEY_UP
            }

            // Burstiness from the coefficient of variation of inter-event
            // gaps: a Poisson-ish stream sits near 0, a clustered one near 1.
            val gaps = events
                .map { it.timestamp }
                .sorted()
                .zipWithNext { a, b -> (b - a).toDouble() }
                .filter { it >= 0 }
            val burstiness = if (gaps.size < 2) {
                0.0
            } else {
                val mean = gaps.average()
                if (mean <= 0.0) {
                    0.0
                } else {
                    val variance = gaps.sumOf { (it - mean) * (it - mean) } / gaps.size
                    val cv = sqrt(variance) / mean
                    (cv / (cv + 1.0)).coerceIn(0.0, 1.0)
                }
            }

            // App switches fragment attention, so focus falls as their share
            // of the session's events rises.
            val switches = events.count { it.type == BehaviorEventType.APP_SWITCH }
            val focusHint = (1.0 - switches.toDouble() / total).coerceIn(0.0, 1.0)

            // Normalized against 2 events/sec, which is brisk sustained use.
            val eventsPerSec = total / durationSec
            val interactionIntensity = (eventsPerSec / 2.0).coerceIn(0.0, 1.0)

            return BehaviorSessionResults(
                sessionId = sessionId,
                durationMs = durationMs,
                tapRate = taps / durationSec,
                keystrokeRate = keys / durationSec,
                focusHint = focusHint,
                interactionIntensity = interactionIntensity,
                burstiness = burstiness,
                totalEvents = total,
            )
        }
    }
}
