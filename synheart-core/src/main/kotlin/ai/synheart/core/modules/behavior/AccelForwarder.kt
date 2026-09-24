package ai.synheart.core.modules.behavior

import ai.synheart.core.SynheartLogger
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock

/**
 * Forwards raw accelerometer samples into the runtime at 50 Hz.
 *
 * ## Why this exists in the core SDK
 *
 * [BehaviorModule.pushAccelToRuntime] has been wired since the runtime learned
 * to take `push_accel`, and nothing ever invoked it: the Kotlin behavior path
 * is fed by the host through `Synheart.recordTouchEvent`, not by the
 * `synheart-behavior` collectors, so `BehaviorConfig.emitRawMotionSamples`
 * turned on a forwarder that had no sensor behind it. The kinematic modality
 * could not reach the runtime on Android at all.
 *
 * This is the sensor behind it: the platform accelerometer, registered at an
 * explicit 20 000 µs period, on its own thread.
 *
 * ## Rate
 *
 * The engine wants ≥ 25 Hz to reach a Ready motion baseline; 50 Hz matches iOS
 * (`accelerometerUpdateInterval = 0.02`). The period is requested explicitly
 * rather than as `SENSOR_DELAY_NORMAL`, which is ~200 ms (5 Hz) — an order of
 * magnitude too slow, and the exact bug the Flutter plugin shipped for a
 * release. `SENSOR_DELAY_GAME` also lands near 50 Hz, but the named constants
 * are documented as hints the device may ignore; a microsecond period is the
 * unambiguous request.
 *
 * ## Units and clock
 *
 * `TYPE_ACCELEROMETER` reports m/s² with gravity included. The engine's
 * `push_accel` takes **g** and multiplies by `9.80665` internally, so this
 * divides before pushing. Forwarded raw, a phone at rest reports ~9.81 and the
 * engine stores ~96 m/s² — it clears the FFI's ±50 sanity gate because that
 * gate runs on the pre-multiply value, so nothing complains, and every
 * magnitude-based cut-point in `activity_state` and `locomotion_state` is ~9.8×
 * off. The RulePack still-gate never sees stillness, so no personal
 * physiological baseline can accumulate, and a host reading `motion.accel_rms`
 * back for its own rest detection never satisfies a low-motion clause.
 * `synheart-wear-rust` converts for the same reason (Polar's milli-g ÷ 1000):
 * g is the engine's contract across the ecosystem.
 *
 * The sample timestamp is nanoseconds since boot; it is converted to epoch
 * milliseconds so it shares a clock with every other `push_*`, because the
 * `accel` channel rejects a timestamp that goes backwards and a sample stamped
 * on the wrong clock is always backwards.
 */
internal class AccelForwarder(
    private val context: Context,
    private val push: (tsMs: Long, ax: Double, ay: Double, az: Double) -> Unit,
) : SensorEventListener {

    private var sensorManager: SensorManager? = null
    private var thread: HandlerThread? = null

    /** Samples forwarded since [start]. For diagnostics. */
    @Volatile
    var forwarded: Long = 0
        private set

    val isRunning: Boolean get() = sensorManager != null

    /** Register the listener. Returns false when the device has no accelerometer. */
    fun start(): Boolean {
        if (sensorManager != null) return true
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return false
        val accel = manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: run {
            SynheartLogger.log("[AccelForwarder] no accelerometer on this device")
            return false
        }

        // Off the main thread: 50 samples a second through a JNA call is not
        // work the UI thread should be doing, and a stalled UI thread would
        // also stall the SensorEvent queue behind it.
        val t = HandlerThread("synheart-accel").also { it.start() }
        val ok = manager.registerListener(this, accel, SAMPLING_PERIOD_US, Handler(t.looper))
        if (!ok) {
            t.quitSafely()
            SynheartLogger.log("[AccelForwarder] registerListener refused")
            return false
        }
        thread = t
        sensorManager = manager
        return true
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        sensorManager = null
        thread?.quitSafely()
        thread = null
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        // event.timestamp is nanoseconds since boot on the same clock as
        // elapsedRealtimeNanos, so the offset to wall time is exact.
        val ageNs = SystemClock.elapsedRealtimeNanos() - event.timestamp
        val tsMs = System.currentTimeMillis() - ageNs / 1_000_000L
        // m/s² → g. Not cosmetic — see the class doc.
        push(
            tsMs,
            event.values[0] / STANDARD_GRAVITY_MS2,
            event.values[1] / STANDARD_GRAVITY_MS2,
            event.values[2] / STANDARD_GRAVITY_MS2,
        )
        forwarded++
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    private companion object {
        /** 20 ms → 50 Hz. */
        const val SAMPLING_PERIOD_US = 20_000

        /** Standard gravity, m/s². The engine multiplies by the same constant. */
        const val STANDARD_GRAVITY_MS2 = 9.80665
    }
}
