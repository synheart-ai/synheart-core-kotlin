package ai.synheart.core.modules.session

import ai.synheart.core.modules.wear.WearModule
import ai.synheart.core.modules.wear.WearSample
import ai.synheart.session.BiosignalProvider
import ai.synheart.session.BiosignalSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Adapts [WearModule.sampleFlow] into the callback-based [BiosignalProvider]
 * interface expected by [ai.synheart.session.SessionEngine].
 *
 * Converts each [WearSample] from the core wear module into a
 * [BiosignalSample] and forwards it to the session engine's callback.
 */
class WearModuleBiosignalAdapter(
    private val wearModule: WearModule
) : BiosignalProvider {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var collectJob: Job? = null

    override val isAvailable: Boolean
        get() = true // WearModule availability is handled by the activation manager

    override val name: String = "wear_module"

    override fun startStreaming(onSample: (BiosignalSample) -> Unit) {
        collectJob?.cancel()
        collectJob = scope.launch {
            wearModule.sampleFlow.collect { wearSample ->
                val biosignalSample = wearSample.toBiosignalSample()
                onSample(biosignalSample)
            }
        }
    }

    override fun stopStreaming() {
        collectJob?.cancel()
        collectJob = null
    }

    companion object {
        /** Convert a core [WearSample] to a session [BiosignalSample]. */
        internal fun WearSample.toBiosignalSample(): BiosignalSample {
            return BiosignalSample(
                timestampMs = timestamp,
                bpm = hr ?: 0.0,
                rrIntervalsMs = rrIntervals,
                deviceId = null,
                source = "wear_module"
            )
        }
    }
}
