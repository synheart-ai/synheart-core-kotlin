package com.synheart.core.modules.phone

import com.synheart.core.modules.interfaces.WindowType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours

/// Cache for phone raw data points
///
/// RFC-CORE-0007 compliant: buffers raw data only.
/// Feature computation is delegated to synheart-runtime.
class PhoneCache {
    private val windowData = mutableMapOf<WindowType, MutableList<PhoneDataPoint>>()

    /// Add motion data
    fun addMotionData(motion: MotionData) {
        addDataPoint(PhoneDataPoint(
            timestamp = motion.timestamp,
            motionLevel = motion.energy / 3.0,
            screenOn = null,
            appSwitch = null,
            notification = null
        ))
    }

    /// Add screen state change
    fun addScreenState(state: ScreenState, timestamp: Long) {
        addDataPoint(PhoneDataPoint(
            timestamp = timestamp,
            motionLevel = null,
            screenOn = state == ScreenState.ON || state == ScreenState.UNLOCKED,
            appSwitch = null,
            notification = null
        ))
    }

    /// Add app switch event
    fun addAppSwitch(timestamp: Long) {
        addDataPoint(PhoneDataPoint(
            timestamp = timestamp,
            motionLevel = null,
            screenOn = null,
            appSwitch = true,
            notification = null
        ))
    }

    /// Add notification event
    fun addNotification(event: NotificationEvent) {
        addDataPoint(PhoneDataPoint(
            timestamp = event.timestamp,
            motionLevel = null,
            screenOn = null,
            appSwitch = null,
            notification = true
        ))
    }

    /// Get raw data points for a window
    fun getDataPoints(window: WindowType): List<PhoneDataPoint> {
        return windowData[window]?.toList() ?: emptyList()
    }

    /// Add a data point to all windows
    private fun addDataPoint(point: PhoneDataPoint) {
        val now = point.timestamp

        WindowType.values().forEach { windowType ->
            val windowDuration = getWindowDuration(windowType)
            val cutoffTime = now - windowDuration.inWholeMilliseconds

            if (windowData[windowType] == null) {
                windowData[windowType] = mutableListOf()
            }

            windowData[windowType]?.add(point)
            windowData[windowType]?.removeAll { it.timestamp < cutoffTime }
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

/// Data point for phone data
data class PhoneDataPoint(
    val timestamp: Long,
    val motionLevel: Double?,
    val screenOn: Boolean?,
    val appSwitch: Boolean?,
    val notification: Boolean?
)
