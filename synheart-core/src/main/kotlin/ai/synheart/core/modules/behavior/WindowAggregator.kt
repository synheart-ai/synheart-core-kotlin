package ai.synheart.core.modules.behavior

import ai.synheart.core.modules.interfaces.WindowType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours

/** Aggregates behavior events into time windows. */
class WindowAggregator {
    private val windows = mutableMapOf<WindowType, MutableList<BehaviorEvent>>()

    fun addEvent(event: BehaviorEvent) {
        val now = event.timestamp

        WindowType.values().forEach { windowType ->
            val windowDuration = getWindowDuration(windowType)
            val cutoffTime = now - windowDuration.inWholeMilliseconds

            if (windows[windowType] == null) {
                windows[windowType] = mutableListOf()
            }

            windows[windowType]?.add(event)
            windows[windowType]?.removeAll { it.timestamp < cutoffTime }
        }
    }

    fun getEvents(window: WindowType): List<BehaviorEvent> {
        return windows[window]?.toList() ?: emptyList()
    }

    fun cleanOldWindows() {
        val now = System.currentTimeMillis()

        WindowType.values().forEach { windowType ->
            val windowDuration = getWindowDuration(windowType)
            val cutoffTime = now - (windowDuration.inWholeMilliseconds * 2)

            windows[windowType]?.removeAll { it.timestamp < cutoffTime }
        }
    }

    private fun getWindowDuration(windowType: WindowType): Duration {
        return when (windowType) {
            WindowType.WINDOW_30S -> 30.seconds
            WindowType.WINDOW_5M -> 5.minutes
            WindowType.WINDOW_1H -> 1.hours
            WindowType.WINDOW_24H -> 24.hours
        }
    }
}
