package ai.synheart.core.modules.wear

import ai.synheart.core.modules.interfaces.SleepStage
import kotlinx.coroutines.flow.Flow

enum class WearSourceType {
    APPLE_HEALTH,
    GOOGLE_FIT,
    WHOOP,
    GARMIN,
    MOCK
}

data class WearSample(
    val timestamp: Long,
    val hr: Double? = null,
    val hrvRmssd: Double? = null,
    val respRate: Double? = null,
    val motionLevel: Double? = null,
    val sleepStage: SleepStage? = null,
    val rrIntervals: List<Double>? = null
)

/**
 * Abstract handler for wearable data sources.
 *
 * Each vendor (Google Fit, WHOOP, Garmin, etc.) implements this interface.
 */
interface WearSourceHandler {
    val sourceType: WearSourceType
    val isAvailable: Boolean
    suspend fun initialize()
    val sampleFlow: Flow<WearSample>
    suspend fun dispose()
}
