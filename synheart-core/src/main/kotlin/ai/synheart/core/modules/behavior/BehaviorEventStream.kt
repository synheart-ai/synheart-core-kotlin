package ai.synheart.core.modules.behavior

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Unified event bus for all user-device interactions. */
class BehaviorEventStream {
    /**
     * Buffered on purpose. This was `MutableSharedFlow()` — replay 0, no extra
     * capacity — which is a *rendezvous* flow, and every `record*` below reaches
     * it through `tryEmit`.
     *
     * `tryEmit` on a rendezvous SharedFlow fails unless a subscriber is
     * suspended awaiting a value at that exact instant, and its Boolean result
     * was discarded. Recording happens from `dispatchTouchEvent` — a
     * non-suspending UI callback — so in practice every tap, scroll and
     * keystroke was silently dropped: `behaviorEventStream` stayed empty, the
     * aggregator never saw an event, nothing reached
     * `synheart_core_push_behavior`, and the HSI digital modality never
     * appeared. Behavior is the one source that needs no sensor, so on a phone
     * with no wearable this made the SDK look inert while reporting itself
     * healthy.
     *
     * With capacity, `tryEmit` always succeeds. [BufferOverflow.DROP_OLDEST]
     * suits interaction telemetry: it is high-rate and lossy-tolerant, the
     * aggregator wants recent rhythm rather than a complete log, and the
     * alternative (SUSPEND) cannot be honoured from a synchronous callback
     * anyway.
     */
    private val _events = MutableSharedFlow<BehaviorEvent>(
        replay = 0,
        extraBufferCapacity = EVENT_BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

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

    private companion object {
        /**
         * Room for a burst of interaction without dropping.
         *
         * Sized for a fast scroll or a burst of typing between collector turns;
         * a sustained flood drops the oldest rather than blocking the UI thread.
         */
        const val EVENT_BUFFER_CAPACITY = 64
    }
}
