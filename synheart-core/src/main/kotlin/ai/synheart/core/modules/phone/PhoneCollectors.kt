package ai.synheart.core.modules.phone

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

/// Motion data from accelerometer/gyroscope
data class MotionData(
    val x: Double,
    val y: Double,
    val z: Double,
    val energy: Double,
    val timestamp: Long
)

/// Screen state information
enum class ScreenState {
    ON,
    OFF,
    LOCKED,
    UNLOCKED
}

/// Notification event
data class NotificationEvent(
    val timestamp: Long,
    val opened: Boolean // true if opened, false if just received
)

/// Collects motion data from device sensors
class MotionCollector {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _motionFlow = MutableSharedFlow<MotionData>()
    private var currentMotionLevel: Double = 0.0
    private var isCollecting = false
    
    val motionFlow: Flow<MotionData> = _motionFlow.asSharedFlow()
    
    /// Current normalized motion level (0.0 - 1.0)
    val currentMotionLevelValue: Double
        get() = currentMotionLevel
    
    suspend fun start() {
        if (isCollecting) return
        isCollecting = true
        
        scope.launch {
            while (isActive && isCollecting) {
                // Simulate varying motion levels
                currentMotionLevel += (Random.nextDouble() - 0.5) * 0.1
                currentMotionLevel = currentMotionLevel.coerceIn(0.0, 1.0)
                
                val x = (Random.nextDouble() - 0.5) * 2
                val y = (Random.nextDouble() - 0.5) * 2
                val z = (Random.nextDouble() - 0.5) * 2
                val energy = kotlin.math.sqrt(x * x + y * y + z * z)
                
                val motion = MotionData(
                    x = x,
                    y = y,
                    z = z,
                    energy = energy,
                    timestamp = System.currentTimeMillis()
                )
                
                _motionFlow.emit(motion)
                delay(100) // Every 100ms
            }
        }
    }
    
    suspend fun stop() {
        isCollecting = false
    }
    
    suspend fun dispose() {
        stop()
        scope.cancel()
    }
}

/// Tracks screen state (on/off/locked/unlocked)
class ScreenStateTracker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _screenFlow = MutableSharedFlow<ScreenState>()
    private var currentState: ScreenState = ScreenState.UNLOCKED
    private var isTracking = false
    
    val screenFlow: Flow<ScreenState> = _screenFlow.asSharedFlow()
    
    val isScreenOn: Boolean
        get() = currentState == ScreenState.ON || currentState == ScreenState.UNLOCKED
    
    suspend fun start() {
        if (isTracking) return
        isTracking = true
        
        // Emit initial state
        _screenFlow.emit(currentState)
        
        scope.launch {
            while (isActive && isTracking) {
                delay(30000) // Every 30 seconds
                
                // Randomly change screen state
                if (Random.nextDouble() < 0.3) {
                    val states = ScreenState.values()
                    currentState = states.random()
                    _screenFlow.emit(currentState)
                }
            }
        }
    }
    
    suspend fun stop() {
        isTracking = false
    }
    
    suspend fun dispose() {
        stop()
        scope.cancel()
    }
}

/// Tracks app focus and switching
class AppFocusTracker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _appSwitchFlow = MutableSharedFlow<String>()
    private var switchCount = 0
    private var lastSwitch = System.currentTimeMillis()
    private val mockApps = listOf("app1", "app2", "app3", "app4")
    private var isTracking = false
    
    val appSwitchFlow: Flow<String> = _appSwitchFlow.asSharedFlow()
    
    /// Get app switch rate (switches per minute)
    val switchRate: Double
        get() {
            val elapsed = (System.currentTimeMillis() - lastSwitch) / 60000.0
            if (elapsed == 0.0) return 0.0
            return switchCount / elapsed
        }
    
    suspend fun start() {
        if (isTracking) return
        isTracking = true
        
        scope.launch {
            while (isActive && isTracking) {
                delay(15000) // Every 15 seconds
                
                // Randomly switch apps
                if (Random.nextDouble() < 0.4) {
                    val app = mockApps.random()
                    switchCount++
                    lastSwitch = System.currentTimeMillis()
                    _appSwitchFlow.emit(app)
                }
            }
        }
    }
    
    suspend fun stop() {
        isTracking = false
    }
    
    suspend fun dispose() {
        stop()
        scope.cancel()
    }
}

/// Tracks notifications
class NotificationTracker {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _notificationFlow = MutableSharedFlow<NotificationEvent>()
    private val recentNotifications = mutableListOf<NotificationEvent>()
    private var isTracking = false
    
    val notificationFlow: Flow<NotificationEvent> = _notificationFlow.asSharedFlow()
    
    /// Get notification count in last minute
    val recentNotificationCount: Int
        get() {
            val cutoff = System.currentTimeMillis() - 60000
            return recentNotifications.count { it.timestamp > cutoff }
        }
    
    suspend fun start() {
        if (isTracking) return
        isTracking = true
        
        scope.launch {
            while (isActive && isTracking) {
                delay(20000) // Every 20 seconds
                
                // Randomly emit notifications
                if (Random.nextDouble() < 0.3) {
                    val event = NotificationEvent(
                        timestamp = System.currentTimeMillis(),
                        opened = Random.nextDouble() < 0.5
                    )
                    recentNotifications.add(event)
                    _notificationFlow.emit(event)
                    
                    // Clean old notifications
                    val cutoff = System.currentTimeMillis() - (5 * 60 * 1000)
                    recentNotifications.removeAll { it.timestamp < cutoff }
                }
            }
        }
    }
    
    suspend fun stop() {
        isTracking = false
    }
    
    suspend fun dispose() {
        stop()
        scope.cancel()
    }
}

