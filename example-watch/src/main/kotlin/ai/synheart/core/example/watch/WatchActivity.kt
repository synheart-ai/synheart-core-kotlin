package ai.synheart.core.example.watch

import ai.synheart.session.SessionStarted
import ai.synheart.session.SessionSummary
import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import kotlinx.coroutines.launch

/**
 * One screen: the heart rate, and a button that starts or stops.
 *
 * The session state is shared with [SessionCommandService], so the phone can
 * start or stop this and the screen follows — and starting here tells the phone.
 */
class WatchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { WatchScreen() } }
    }
}

@Composable
private fun WatchScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sensor = remember { HeartRateSensor(context) }

    // The shared state, not a local flag: a session the PHONE started must show
    // here too, and the service that receives that command runs whether or not
    // this screen exists.
    val session by WatchSessionState.session.collectAsState()
    val running = session != null

    // Both, and BOTH must be granted: Wear OS 4+ gates TYPE_HEART_RATE behind
    // the health permission while older releases use the legacy one, and having
    // only one leaves the listener registered but silent.
    val needed = remember {
        arrayOf(Manifest.permission.BODY_SENSORS, PERMISSION_READ_HEART_RATE)
    }
    fun allGranted() = needed.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }

    var granted by remember { mutableStateOf(allGranted()) }
    val ask = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { granted = allGranted() }

    var reading by remember { mutableStateOf<HeartRate>(HeartRate.Warming) }

    // Sample only while a session is running: the sensor is a battery cost, and
    // a reading with nothing to attribute it to is not worth taking.
    LaunchedEffect(running, granted) {
        if (running && granted) {
            sensor.stream().collect { hr ->
                reading = hr
                // Forward real readings only. A Warming tick carries no number,
                // and pushing a placeholder would enter the engine's
                // longitudinal baselines as if it were measured.
                if (hr is HeartRate.Bpm) {
                    PhoneNotifier.sendHr(context, System.currentTimeMillis(), hr.value)
                }
            }
        } else {
            reading = HeartRate.Warming
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = bpmText(running, granted, sensor.isAvailable, reading),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.display1,
        )
        Text(
            text = statusText(running, granted, sensor.isAvailable, reading),
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.caption1,
        )

        Spacer(Modifier.height(12.dp))
        Button(
            onClick = {
                if (running) {
                    scope.launch {
                        val stopped = WatchSessionState.stop() ?: return@launch
                        PhoneNotifier.send(
                            context,
                            SessionSummary(
                                sessionId = stopped.sessionId,
                                durationActualSec = 0,
                                metrics = emptyMap(),
                            ),
                        )
                    }
                } else {
                    if (!granted) {
                        ask.launch(needed)
                        return@Button
                    }
                    val id = "watch_${System.currentTimeMillis()}"
                    if (!WatchSessionState.start(id)) return@Button
                    scope.launch {
                        PhoneNotifier.send(
                            context,
                            SessionStarted(
                                sessionId = id,
                                startedAtMs = System.currentTimeMillis(),
                            ),
                        )
                    }
                }
            },
        ) {
            Text(if (running) "Stop" else "Start")
        }
    }
}

/**
 * The large number, or a dash.
 *
 * A dash rather than 0 whenever there is no reading: a zero styled like the
 * measurement is indistinguishable from one at a glance.
 */
private fun bpmText(
    running: Boolean,
    granted: Boolean,
    sensorPresent: Boolean,
    reading: HeartRate,
): String = when {
    !running || !granted || !sensorPresent -> "—"
    reading is HeartRate.Bpm -> "${reading.value}"
    else -> "—"
}

/**
 * Health Connect's heart-rate read permission.
 *
 * A literal rather than a constant from `androidx.health.connect`, so this
 * module does not take that dependency for one string.
 */
private const val PERMISSION_READ_HEART_RATE = "android.permission.health.READ_HEART_RATE"

private fun statusText(
    running: Boolean,
    granted: Boolean,
    sensorPresent: Boolean,
    reading: HeartRate,
): String = when {
    !sensorPresent || reading is HeartRate.Unavailable -> "no sensor"
    !granted -> "tap to allow"
    !running -> "stopped"
    reading is HeartRate.Bpm -> "bpm"
    else -> "measuring…"
}
