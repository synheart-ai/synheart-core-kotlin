package ai.synheart.core.modules.behavior

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Unified event bus for all user-device interactions. */
class BehaviorEventStream {
    private val _events = MutableSharedFlow<BehaviorEvent>()

    val events: Flow<BehaviorEvent> = _events.asSharedFlow()

    fun recordTap(x: Double, y: Double) {
        _events.tryEmit(BehaviorEvent.tap(x, y))
    }

    fun recordScroll(delta: Double) {
        _events.tryEmit(BehaviorEvent.scroll(delta))
    }

    fun recordKeyDown() {
        _events.tryEmit(BehaviorEvent.keyDown())
    }

    fun recordKeyUp() {
        _events.tryEmit(BehaviorEvent.keyUp())
    }

    fun recordAppSwitch() {
        _events.tryEmit(BehaviorEvent.appSwitch())
    }

    fun recordNotificationReceived() {
        _events.tryEmit(BehaviorEvent.notificationReceived())
    }

    fun recordNotificationOpened() {
        _events.tryEmit(BehaviorEvent.notificationOpened())
    }

    suspend fun dispose() {
        // Flow completes when scope is cancelled
    }
}
