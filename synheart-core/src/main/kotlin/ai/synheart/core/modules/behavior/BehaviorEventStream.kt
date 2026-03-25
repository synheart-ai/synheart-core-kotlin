package ai.synheart.core.modules.behavior

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/// Behavior event stream
///
/// Unified event bus for all user-device interactions
class BehaviorEventStream {
    private val _events = MutableSharedFlow<BehaviorEvent>()
    
    val events: Flow<BehaviorEvent> = _events.asSharedFlow()
    
    /// Record a tap event
    fun recordTap(x: Double, y: Double) {
        _events.tryEmit(BehaviorEvent.tap(x, y))
    }
    
    /// Record a scroll event
    fun recordScroll(delta: Double) {
        _events.tryEmit(BehaviorEvent.scroll(delta))
    }
    
    /// Record a key down event
    fun recordKeyDown() {
        _events.tryEmit(BehaviorEvent.keyDown())
    }
    
    /// Record a key up event
    fun recordKeyUp() {
        _events.tryEmit(BehaviorEvent.keyUp())
    }
    
    /// Record an app switch event
    fun recordAppSwitch() {
        _events.tryEmit(BehaviorEvent.appSwitch())
    }
    
    /// Record a notification received event
    fun recordNotificationReceived() {
        _events.tryEmit(BehaviorEvent.notificationReceived())
    }
    
    /// Record a notification opened event
    fun recordNotificationOpened() {
        _events.tryEmit(BehaviorEvent.notificationOpened())
    }
    
    suspend fun dispose() {
        // Flow will complete when scope is cancelled
    }
}

