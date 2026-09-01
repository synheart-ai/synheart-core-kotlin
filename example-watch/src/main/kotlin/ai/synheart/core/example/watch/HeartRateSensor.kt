package ai.synheart.core.example.watch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A heart-rate reading, or the reason there isn't one. */
sealed interface HeartRate {
    data class Bpm(val value: Int) : HeartRate

    /** The sensor is on but has not produced a trustworthy reading yet. */
    data object Warming : HeartRate

    /** No heart-rate sensor on this device. */
    data object Unavailable : HeartRate
}

/**
 * The watch's heart-rate sensor as a [Flow].
 *
 * Reads `TYPE_HEART_RATE` directly through [SensorManager] rather than through a
 * health store: this is the live sensor, sampled while the screen is on, which
 * is what a session on the wrist actually wants. Health Connect would give
 * historical records written by other apps.
 */
class HeartRateSensor(context: Context) {

    private val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? = manager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)

    val isAvailable: Boolean get() = sensor != null

    /**
     * Emits while collected; unregisters on cancellation.
     *
     * A reading of 0 with `ACCURACY_NO_CONTACT`/`UNRELIABLE` is the sensor
     * saying it has not locked on yet — surfaced as [HeartRate.Warming] rather
     * than a bpm of 0, which would read as a measurement.
     */
    fun stream(): Flow<HeartRate> = callbackFlow {
        val s = sensor
        if (manager == null || s == null) {
            trySend(HeartRate.Unavailable)
            close()
            return@callbackFlow
        }

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val bpm = event.values.firstOrNull()?.toInt() ?: return
                trySend(if (bpm > 0) HeartRate.Bpm(bpm) else HeartRate.Warming)
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                if (accuracy == SensorManager.SENSOR_STATUS_NO_CONTACT ||
                    accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE
                ) {
                    trySend(HeartRate.Warming)
                }
            }
        }

        manager.registerListener(listener, s, SensorManager.SENSOR_DELAY_NORMAL)
        awaitClose { manager.unregisterListener(listener) }
    }
}
