package ai.synheart.core.modules.wear

import ai.synheart.core.modules.interfaces.WindowType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours

/** Cache for raw wear samples. Buffers raw data only; feature computation is delegated to synheart-engine. */
class WearCache {
    private val windowSamples = mutableMapOf<WindowType, MutableList<WearSample>>()

    fun addSample(sample: WearSample) {
        val now = sample.timestamp

        WindowType.values().forEach { windowType ->
            val windowDuration = getWindowDuration(windowType)
            val cutoffTime = now - windowDuration.inWholeMilliseconds

            if (windowSamples[windowType] == null) {
                windowSamples[windowType] = mutableListOf()
            }

            windowSamples[windowType]?.add(sample)
            windowSamples[windowType]?.removeAll { it.timestamp < cutoffTime }
        }
    }

    fun getSamples(window: WindowType): List<WearSample> {
        return windowSamples[window]?.toList() ?: emptyList()
    }

    fun clearOldData() {
        val now = System.currentTimeMillis()

        WindowType.values().forEach { windowType ->
            val windowDuration = getWindowDuration(windowType)
            val cutoffTime = now - (windowDuration.inWholeMilliseconds * 2)

            windowSamples[windowType]?.removeAll { it.timestamp < cutoffTime }
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
