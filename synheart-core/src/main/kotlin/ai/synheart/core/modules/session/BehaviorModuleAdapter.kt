package ai.synheart.core.modules.session

import ai.synheart.core.modules.behavior.BehaviorModule
import ai.synheart.core.modules.behavior.BehaviorEvent
import ai.synheart.core.modules.behavior.BehaviorEventType
import ai.synheart.core.modules.interfaces.WindowType
import ai.synheart.session.BehaviorProvider
import ai.synheart.session.BehaviorSnapshot

/**
 * Adapts [BehaviorModule] into the pull-based [BehaviorProvider] interface
 * expected by [ai.synheart.session.SessionEngine].
 *
 * On each frame tick the session engine calls [currentSnapshot], which
 * aggregates the most recent behavior events from the core module into a
 * [BehaviorSnapshot].
 */
class BehaviorModuleAdapter(
    private val behaviorModule: BehaviorModule
) : BehaviorProvider {

    override val isAvailable: Boolean
        get() = true // BehaviorModule availability is handled by the activation manager

    override val name: String = "behavior_module"

    override fun currentSnapshot(): BehaviorSnapshot? {
        val events = behaviorModule.rawEvents(WindowType.WINDOW_1M)
        if (events.isEmpty()) return null

        val now = System.currentTimeMillis()
        val windowMs = 60_000L
        val recentEvents = events.filter { it.timestamp >= now - windowMs }
        if (recentEvents.isEmpty()) return null

        // Compute behavior metrics from raw events
        val taps = recentEvents.filter { it.type == BehaviorEventType.TAP }
        val scrolls = recentEvents.filter { it.type == BehaviorEventType.SCROLL }
        val keyDowns = recentEvents.filter { it.type == BehaviorEventType.KEY_DOWN }
        val appSwitches = recentEvents.filter { it.type == BehaviorEventType.APP_SWITCH }

        val durationSec = windowMs / 1000.0

        // Typing cadence: keys per second
        val typingCadence = if (keyDowns.isNotEmpty()) keyDowns.size / durationSec else null

        // Inter-key latency: average ms between consecutive key events
        val interKeyLatency = if (keyDowns.size >= 2) {
            val sorted = keyDowns.sortedBy { it.timestamp }
            val deltas = sorted.zipWithNext { a, b -> (b.timestamp - a.timestamp).toDouble() }
            deltas.average()
        } else null

        // Tap rate: taps per second
        val tapRate = if (taps.isNotEmpty()) taps.size / durationSec else null

        // Scroll velocity: average absolute delta per second
        val scrollVelocity = if (scrolls.isNotEmpty()) {
            val totalDelta = scrolls.sumOf { event ->
                val delta = event.metadata?.get("delta") as? Number
                kotlin.math.abs(delta?.toDouble() ?: 0.0)
            }
            totalDelta / durationSec
        } else null

        // App switches per minute
        val appSwitchesPerMinute = appSwitches.size

        return BehaviorSnapshot(
            typingCadence = typingCadence,
            interKeyLatency = interKeyLatency,
            burstLength = null,
            scrollVelocity = scrollVelocity,
            scrollAcceleration = null,
            scrollJitter = null,
            tapRate = tapRate,
            appSwitchesPerMinute = appSwitchesPerMinute,
            foregroundDuration = null,
            idleGapSeconds = null,
            stabilityIndex = null,
            fragmentationIndex = null,
            timestamp = now
        )
    }
}
