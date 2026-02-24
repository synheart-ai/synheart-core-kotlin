package com.synheart.core.modules.runtime

import com.synheart.core.modules.base.BaseSynheartModule
import com.synheart.core.modules.behavior.BehaviorEventType
import com.synheart.core.modules.behavior.BehaviorModule
import com.synheart.core.modules.wear.WearModule
import com.synheart.core.modules.wear.WearSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Runtime Module -- streams wear and behavior data into the synheart-runtime
 * C ABI and periodically ticks to produce HSI JSON frames.
 *
 * When [bridge] is null (native library unavailable) the pipeline is
 * gracefully inert: subscriptions are skipped and no HSI is produced.
 * This is the standard pattern for all synheart-runtime consumers.
 */
class RuntimeModule(
    val bridge: RuntimeBridge?,
    private val wearModule: WearModule?,
    private val behaviorModule: BehaviorModule?,
) : BaseSynheartModule("runtime") {

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val runtimeDispatcher = Dispatchers.Default.limitedParallelism(1)
    private val moduleScope = CoroutineScope(SupervisorJob() + runtimeDispatcher)

    private val _hsiFlow = MutableStateFlow<String?>(null)

    /** Flow of HSI JSON strings emitted each time the runtime produces a frame. */
    val hsiFlow: StateFlow<String?> = _hsiFlow.asStateFlow()

    private var tickJob: Job? = null
    private var wearJob: Job? = null
    private var behaviorJob: Job? = null

    override suspend fun onInitialize() {
        println("[RuntimeModule] Initialized (native bridge ${if (bridge != null) "available" else "unavailable"})")
    }

    override suspend fun onStart() {
        println("[RuntimeModule] Starting...")

        if (bridge == null) {
            println("[RuntimeModule] No native bridge -- pipeline inert until synheart_runtime is linked")
            return
        }

        // Subscribe to wear samples
        if (wearModule != null) {
            wearJob = moduleScope.launch {
                wearModule.sampleFlow.collect { sample ->
                    handleWearSample(sample)
                }
            }
        }

        // Subscribe to behavior events
        if (behaviorModule != null) {
            behaviorJob = moduleScope.launch {
                behaviorModule.eventStreamInstance.events.collect { event ->
                    handleBehaviorEvent(event)
                }
            }
        }

        // Tick every 5 seconds
        tickJob = moduleScope.launch {
            while (isActive) {
                delay(5_000)
                val hsiJson = bridge.tick(System.currentTimeMillis())
                if (hsiJson != null) {
                    _hsiFlow.value = hsiJson
                }
            }
        }

        println("[RuntimeModule] Started")
    }

    override suspend fun onStop() {
        println("[RuntimeModule] Stopping...")

        tickJob?.cancel()
        tickJob = null

        wearJob?.cancel()
        wearJob = null

        behaviorJob?.cancel()
        behaviorJob = null

        println("[RuntimeModule] Stopped")
    }

    override suspend fun onDispose() {
        println("[RuntimeModule] Disposing...")

        tickJob?.cancel()
        wearJob?.cancel()
        behaviorJob?.cancel()
        moduleScope.cancel()

        bridge?.close()
    }

    // --- Private helpers ---

    private fun handleWearSample(sample: WearSample) {
        val tsMs = sample.timestamp

        // Push individual RR intervals
        sample.rrIntervals?.forEach { rr ->
            bridge!!.pushRr(tsMs, rr)
        }

        // Push heart rate
        sample.hr?.let { hr ->
            bridge!!.pushHr(tsMs, hr)
        }
    }

    private fun handleBehaviorEvent(event: com.synheart.core.modules.behavior.BehaviorEvent) {
        val tsMs = event.timestamp

        when (event.type) {
            // Touch / input -- event_type 2
            BehaviorEventType.TAP,
            BehaviorEventType.SCROLL,
            BehaviorEventType.KEY_DOWN,
            BehaviorEventType.KEY_UP -> bridge!!.pushBehavior(tsMs, 2, 1.0)

            // App switch -- event_type 3
            BehaviorEventType.APP_SWITCH -> bridge!!.pushBehavior(tsMs, 3, 1.0)

            // Notification -- event_type 4
            BehaviorEventType.NOTIFICATION_RECEIVED,
            BehaviorEventType.NOTIFICATION_OPENED -> bridge!!.pushBehavior(tsMs, 4, 1.0)
        }
    }
}
