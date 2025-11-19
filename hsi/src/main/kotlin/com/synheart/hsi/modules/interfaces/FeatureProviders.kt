package com.synheart.hsi.modules.interfaces

import kotlin.time.Duration

/// Window types for time-based aggregation
enum class WindowType {
    /// 30-second window
    WINDOW_30S,

    /// 5-minute window
    WINDOW_5M,

    /// 1-hour window
    WINDOW_1H,

    /// 24-hour window
    WINDOW_24H
}

/// Sleep stage information
enum class SleepStage {
    AWAKE,
    LIGHT,
    DEEP,
    REM
}

// MARK: - Wear Module

/// Wear module feature provider
interface WearFeatureProvider {
    /// Get biosignal features for a specific window
    fun features(window: WindowType): WearWindowFeatures?
}

/// Biosignal features from wearables
data class WearWindowFeatures(
    /// Window duration
    val windowDuration: Duration,

    /// Average heart rate (bpm)
    val hrAverage: Double? = null,

    /// Minimum heart rate (bpm)
    val hrMin: Double? = null,

    /// Maximum heart rate (bpm)
    val hrMax: Double? = null,

    /// Heart rate variability - RMSSD (ms)
    val hrvRmssd: Double? = null,

    /// Motion index (0.0 - 1.0)
    val motionIndex: Double? = null,

    /// Sleep stage
    val sleepStage: SleepStage? = null,

    /// Respiration rate (breaths per minute)
    val respRate: Double? = null
)

// MARK: - Phone Module

/// Phone module feature provider
interface PhoneFeatureProvider {
    /// Get phone features for a specific window
    fun features(window: WindowType): PhoneWindowFeatures?
}

/// Phone context features
data class PhoneWindowFeatures(
    /// Motion level (0.0 - 1.0)
    val motionLevel: Double,

    /// App switch rate (normalized)
    val appSwitchRate: Double,

    /// Screen on ratio (proportion of window)
    val screenOnRatio: Double,

    /// Notification rate (per minute)
    val notificationRate: Double
)

// MARK: - Behavior Module

/// Behavior module feature provider
interface BehaviorFeatureProvider {
    /// Get behavioral features for a specific window
    fun features(window: WindowType): BehaviorWindowFeatures?
}

/// Behavioral interaction features
data class BehaviorWindowFeatures(
    /// Typing cadence (normalized 0.0 - 1.0)
    val tapRateNorm: Double,

    /// Keystroke rate (normalized 0.0 - 1.0)
    val keystrokeRateNorm: Double,

    /// Scroll velocity (normalized 0.0 - 1.0)
    val scrollVelocityNorm: Double,

    /// Idle ratio (0.0 - 1.0)
    val idleRatio: Double,

    /// App/context switch rate (normalized)
    val switchRateNorm: Double,

    /// Burstiness (0.0 - 1.0)
    val burstiness: Double,

    /// Session fragmentation (0.0 - 1.0)
    val sessionFragmentation: Double,

    /// Notification load (0.0 - 1.0)
    val notificationLoad: Double,

    /// Distraction score from MLP (0.0 - 1.0)
    val distractionScore: Double,

    /// Focus hint from MLP (0.0 - 1.0)
    val focusHint: Double
)
