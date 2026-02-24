package com.synheart.core.modules.wear

import com.synheart.core.modules.interfaces.WindowType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours

/// Cache for wear raw samples
///
/// RFC-CORE-0007 compliant: buffers raw data only.
/// Feature computation is delegated to synheart-runtime.
class WearCache {
    private val windowSamples = mutableMapOf<WindowType, MutableList<WearSample>>()

    /// Add a new sample to the cache
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

    /// Get raw samples for a specific window
    fun getSamples(window: WindowType): List<WearSample> {
        return windowSamples[window]?.toList() ?: emptyList()
    }

    /// Clear old data
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
