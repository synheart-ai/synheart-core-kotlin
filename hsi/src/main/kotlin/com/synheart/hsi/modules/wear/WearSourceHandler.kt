package com.synheart.hsi.modules.wear

import com.synheart.hsi.modules.interfaces.SleepStage
import kotlinx.coroutines.flow.Flow

/// Types of wear data sources
enum class WearSourceType {
    APPLE_HEALTH,
    GOOGLE_FIT,
    WHOOP,
    GARMIN,
    MOCK
}

/// Raw wear sample from a data source
data class WearSample(
    val timestamp: Long, // Unix timestamp in milliseconds
    val hr: Double? = null,
    val hrvRmssd: Double? = null,
    val respRate: Double? = null,
    val motionLevel: Double? = null,
    val sleepStage: SleepStage? = null,
    val rrIntervals: List<Double>? = null
)

/// Abstract handler for wearable data sources
///
/// Each vendor (Apple Health, Google Fit, WHOOP, etc.) implements this interface
interface WearSourceHandler {
    /// Source type identifier
    val sourceType: WearSourceType
    
    /// Whether this source is available on the current platform
    val isAvailable: Boolean
    
    /// Initialize the data source
    suspend fun initialize()
    
    /// Flow of wear samples
    val sampleFlow: Flow<WearSample>
    
    /// Stop and cleanup
    suspend fun dispose()
}

