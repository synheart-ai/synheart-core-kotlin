package com.synheart.hsi.modules.phone

import com.synheart.hsi.modules.interfaces.PhoneWindowFeatures
import com.synheart.hsi.modules.interfaces.WindowType
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.hours

/// Cache for phone window features
class PhoneCache {
    private val windowData = mutableMapOf<WindowType, MutableList<PhoneDataPoint>>()
    private val cachedFeatures = mutableMapOf<WindowType, PhoneWindowFeatures>()
    
    /// Add motion data
    fun addMotionData(motion: MotionData) {
        addDataPoint(PhoneDataPoint(
            timestamp = motion.timestamp,
            motionLevel = motion.energy / 3.0, // Normalize to 0-1
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
    
    /// Get features for a window
    fun getFeatures(window: WindowType): PhoneWindowFeatures? {
        return cachedFeatures[window]
    }
    
    /// Add a data point and recompute features
    private fun addDataPoint(point: PhoneDataPoint) {
        val now = point.timestamp
        
        WindowType.values().forEach { windowType ->
            val windowDuration = getWindowDuration(windowType)
            val cutoffTime = now - windowDuration.inWholeMilliseconds
            
            // Initialize if needed
            if (windowData[windowType] == null) {
                windowData[windowType] = mutableListOf()
            }
            
            // Add new point
            windowData[windowType]?.add(point)
            
            // Remove old points
            windowData[windowType]?.removeAll { it.timestamp < cutoffTime }
            
            // Recompute features
            windowData[windowType]?.let { data ->
                cachedFeatures[windowType] = computeFeatures(windowType, data)
            }
        }
    }
    
    /// Compute features from data points
    private fun computeFeatures(windowType: WindowType, data: List<PhoneDataPoint>): PhoneWindowFeatures {
        if (data.isEmpty()) {
            return PhoneWindowFeatures(
                motionLevel = 0.0,
                appSwitchRate = 0.0,
                screenOnRatio = 0.0,
                notificationRate = 0.0
            )
        }
        
        // Motion level (average)
        val motionValues = data.mapNotNull { it.motionLevel }
        val motionLevel = motionValues.takeIf { it.isNotEmpty() }?.average() ?: 0.0
        
        // Screen on ratio
        val screenOnCount = data.count { it.screenOn == true }
        val screenOnRatio = if (data.isNotEmpty()) screenOnCount.toDouble() / data.size else 0.0
        
        // App switch rate (switches per minute)
        val appSwitches = data.count { it.appSwitch == true }
        val windowMinutes = getWindowDuration(windowType).inWholeMinutes.toDouble()
        val appSwitchRate = if (windowMinutes > 0) appSwitches / windowMinutes else 0.0
        
        // Notification rate (per minute)
        val notifications = data.count { it.notification == true }
        val notificationRate = if (windowMinutes > 0) notifications / windowMinutes else 0.0
        
        return PhoneWindowFeatures(
            motionLevel = motionLevel,
            appSwitchRate = appSwitchRate.coerceIn(0.0, 1.0),
            screenOnRatio = screenOnRatio,
            notificationRate = notificationRate.coerceIn(0.0, 1.0)
        )
    }
    
    /// Get window duration
    private fun getWindowDuration(windowType: WindowType): Duration {
        return when (windowType) {
            WindowType.WINDOW_30S -> 30.seconds
            WindowType.WINDOW_5M -> 5.minutes
            WindowType.WINDOW_1H -> 1.hours
            WindowType.WINDOW_24H -> 24.hours
        }
    }
}

/// Internal data point for phone data
private data class PhoneDataPoint(
    val timestamp: Long,
    val motionLevel: Double?,
    val screenOn: Boolean?,
    val appSwitch: Boolean?,
    val notification: Boolean?
)

