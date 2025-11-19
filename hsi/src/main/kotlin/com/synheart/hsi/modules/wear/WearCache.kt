package com.synheart.hsi.modules.wear

import com.synheart.hsi.modules.interfaces.SleepStage
import com.synheart.hsi.modules.interfaces.WindowType
import com.synheart.hsi.modules.interfaces.WearWindowFeatures
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours

/// Cache for wear window features
///
/// Maintains aggregated biosignal features for different time windows
class WearCache {
    private val windowSamples = mutableMapOf<WindowType, MutableList<WearSample>>()
    private val cachedFeatures = mutableMapOf<WindowType, WearWindowFeatures>()
    
    /// Add a new sample to the cache
    fun addSample(sample: WearSample) {
        val now = sample.timestamp
        
        // Add to each window type
        WindowType.values().forEach { windowType ->
            val windowDuration = getWindowDuration(windowType)
            val cutoffTime = now - windowDuration.inWholeMilliseconds
            
            // Initialize if needed
            if (windowSamples[windowType] == null) {
                windowSamples[windowType] = mutableListOf()
            }
            
            // Add new sample
            windowSamples[windowType]?.add(sample)
            
            // Remove old samples
            windowSamples[windowType]?.removeAll { it.timestamp < cutoffTime }
            
            // Recompute features for this window
            windowSamples[windowType]?.let { samples ->
                cachedFeatures[windowType] = computeFeatures(windowType, samples)
            }
        }
    }
    
    /// Get features for a specific window
    fun getFeatures(window: WindowType): WearWindowFeatures? {
        return cachedFeatures[window]
    }
    
    /// Clear old data
    fun clearOldData() {
        val now = System.currentTimeMillis()
        
        WindowType.values().forEach { windowType ->
            val windowDuration = getWindowDuration(windowType)
            val cutoffTime = now - (windowDuration.inWholeMilliseconds * 2) // Keep 2x window
            
            windowSamples[windowType]?.removeAll { it.timestamp < cutoffTime }
        }
    }
    
    // MARK: - Private Helpers
    
    private fun getWindowDuration(windowType: WindowType): Duration {
        return when (windowType) {
            WindowType.WINDOW_30S -> 30.seconds
            WindowType.WINDOW_5M -> 5.minutes
            WindowType.WINDOW_1H -> 1.hours
            WindowType.WINDOW_24H -> 24.hours
        }
    }
    
    private fun computeFeatures(windowType: WindowType, samples: List<WearSample>): WearWindowFeatures {
        val windowDuration = getWindowDuration(windowType)
        
        if (samples.isEmpty()) {
            return WearWindowFeatures(windowDuration = windowDuration)
        }
        
        // Compute HR statistics
        val hrValues = samples.mapNotNull { it.hr }
        val hrAverage = hrValues.takeIf { it.isNotEmpty() }?.average()
        val hrMin = hrValues.minOrNull()
        val hrMax = hrValues.maxOrNull()
        
        // Compute HRV
        val hrvValues = samples.mapNotNull { it.hrvRmssd }
        val hrvRmssd = hrvValues.takeIf { it.isNotEmpty() }?.average()
        
        // Compute motion
        val motionValues = samples.mapNotNull { it.motionLevel }
        val motionIndex = motionValues.takeIf { it.isNotEmpty() }?.average()
        
        // Compute respiration
        val respValues = samples.mapNotNull { it.respRate }
        val respRate = respValues.takeIf { it.isNotEmpty() }?.average()
        
        // Get most common sleep stage
        val sleepStages = samples.mapNotNull { it.sleepStage }
        val sleepStage = sleepStages.takeIf { it.isNotEmpty() }?.let { mostCommon(it) }
        
        return WearWindowFeatures(
            windowDuration = windowDuration,
            hrAverage = hrAverage,
            hrMin = hrMin,
            hrMax = hrMax,
            hrvRmssd = hrvRmssd,
            motionIndex = motionIndex,
            sleepStage = sleepStage,
            respRate = respRate
        )
    }
    
    private fun <T> mostCommon(items: List<T>): T? {
        return items.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }
}

