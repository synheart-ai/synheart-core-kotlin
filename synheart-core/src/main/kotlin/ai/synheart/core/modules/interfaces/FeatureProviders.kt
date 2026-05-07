package ai.synheart.core.modules.interfaces

import kotlin.time.Duration

enum class WindowType {
    WINDOW_30S,
    WINDOW_5M,
    WINDOW_1H,
    WINDOW_24H
}

enum class SleepStage {
    AWAKE,
    LIGHT,
    DEEP,
    REM
}

/** Biosignal features from wearables. */
data class WearWindowFeatures(
    val windowDuration: Duration,
    val hrAverage: Double? = null,
    val hrMin: Double? = null,
    val hrMax: Double? = null,
    val hrvRmssd: Double? = null,
    val motionIndex: Double? = null,
    val sleepStage: SleepStage? = null,
    val respRate: Double? = null
)

/** Phone context features. */
data class PhoneWindowFeatures(
    val motionLevel: Double,
    val appSwitchRate: Double,
    val screenOnRatio: Double,
    val notificationRate: Double
)

/** Behavioral interaction features. */
data class BehaviorWindowFeatures(
    val tapRateNorm: Double,
    val keystrokeRateNorm: Double,
    val scrollVelocityNorm: Double,
    val idleRatio: Double,
    val switchRateNorm: Double,
    val burstiness: Double,
    val sessionFragmentation: Double,
    val notificationLoad: Double,
    val distractionScore: Double,
    val focusHint: Double
)
