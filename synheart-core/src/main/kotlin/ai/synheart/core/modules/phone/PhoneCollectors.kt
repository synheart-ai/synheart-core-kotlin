package ai.synheart.core.modules.phone

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Motion data from accelerometer/gyroscope. */
data class MotionData(
    val x: Double,
    val y: Double,
    val z: Double,
    val energy: Double,
    val timestamp: Long
)

/** Screen state information. */
enum class ScreenState {
    ON,
    OFF,
    LOCKED,
    UNLOCKED
}

/** Notification event. */
data class NotificationEvent(
    val timestamp: Long,
    val opened: Boolean // true if opened, false if just received
)

// ── Phone-context collectors ─────────────────────────────────────────────────
//
// These four collectors have no platform source in this module. They used to
// emit `Random` values on timers — fake accelerometer samples every 100 ms,
// random screen-state flips, invented app switches and notifications — which
// reached hosts through `PhoneModule.rawDataPoints` as if they were measured.
//
// They now emit nothing. An empty flow is the honest answer: no source, no
// data, so a consumer sees absence rather than a plausible-looking fabrication.
//
// The real signals arrive elsewhere:
// - accelerometer: the behavior module's `AccelForwarder`, which pushes 50 Hz
//   samples to `push_accel` when raw motion samples are enabled (see
//   `BehaviorConfig.emitRawMotionSamples`; on in research capture);
// - foreground app, notifications, screen state: the behavior module's event
//   stream and the host's own `pushBehaviorEvent` / `pushAppForeground`.

/** Phone motion source. Emits nothing: there is no sensor binding here. */
class MotionCollector {
    private val _motionFlow = MutableSharedFlow<MotionData>()

    val motionFlow: Flow<MotionData> = _motionFlow.asSharedFlow()

    suspend fun start() {}

    suspend fun stop() {}

    suspend fun dispose() {}
}

/** Screen-state source. Emits nothing: there is no platform binding here. */
class ScreenStateTracker {
    private val _screenFlow = MutableSharedFlow<ScreenState>()

    val screenFlow: Flow<ScreenState> = _screenFlow.asSharedFlow()

    suspend fun start() {}

    suspend fun stop() {}

    suspend fun dispose() {}
}

/** App-switch source. Emits nothing: there is no platform binding here. */
class AppFocusTracker {
    private val _appSwitchFlow = MutableSharedFlow<String>()

    val appSwitchFlow: Flow<String> = _appSwitchFlow.asSharedFlow()

    suspend fun start() {}

    suspend fun stop() {}

    suspend fun dispose() {}
}

/** Notification source. Emits nothing: there is no platform binding here. */
class NotificationTracker {
    private val _notificationFlow = MutableSharedFlow<NotificationEvent>()

    val notificationFlow: Flow<NotificationEvent> = _notificationFlow.asSharedFlow()

    suspend fun start() {}

    suspend fun stop() {}

    suspend fun dispose() {}
}
