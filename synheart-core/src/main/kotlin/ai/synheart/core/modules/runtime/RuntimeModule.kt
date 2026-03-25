package ai.synheart.core.modules.runtime

import ai.synheart.core.SynheartDefaults
import ai.synheart.core.modules.base.BaseSynheartModule
import ai.synheart.core.modules.behavior.BehaviorEventType
import ai.synheart.core.modules.behavior.BehaviorModule
import ai.synheart.core.modules.wear.WearModule
import ai.synheart.core.modules.wear.WearSample
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
import ai.synheart.core.SynheartLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * Runtime Module -- streams wear and behavior data into the synheart-runtime
 * C ABI and periodically ticks to produce HSI JSON frames.
 *
 * When [bridge] is null (native library unavailable) the pipeline is
 * gracefully inert: subscriptions are skipped and no HSI is produced.
 * This is the standard pattern for all synheart-runtime consumers.
 *
 * @param loadSrmSnapshot Optional lambda to load a saved SRM snapshot JSON on start.
 *   On Android, wire to SharedPreferences: `{ prefs.getString("srm_snapshot", null) }`.
 *   Pass `null` to disable auto-restore.
 * @param saveSrmSnapshot Optional lambda to persist the SRM snapshot JSON on stop.
 *   On Android, wire to SharedPreferences: `{ json -> prefs.edit().putString("srm_snapshot", json).apply() }`.
 *   Pass `null` to disable auto-save.
 */
class RuntimeModule(
    val bridge: RuntimeBridge?,
    private val wearModule: WearModule?,
    private val behaviorModule: BehaviorModule?,
    private val loadSrmSnapshot: (() -> String?)? = null,
    private val saveSrmSnapshot: ((String) -> Unit)? = null,
    val batchIngestOnStop: Boolean = false,
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

    /** Buffered events for batch ingest (only used when [batchIngestOnStop] is true). */
    private val batchEventBuffer = mutableListOf<Map<String, Any>>()

    override suspend fun onInitialize() {
        SynheartLogger.log("[RuntimeModule] Initialized (native bridge ${if (bridge != null) "available" else "unavailable"})")
    }

    override suspend fun onStart() {
        SynheartLogger.log("[RuntimeModule] Starting...")

        if (bridge == null) {
            SynheartLogger.log("[RuntimeModule] No native bridge -- pipeline inert until synheart_runtime is linked")
            return
        }

        // Restore SRM baselines from previous session
        if (loadSrmSnapshot != null) {
            try {
                val saved = loadSrmSnapshot.invoke()
                if (saved != null) {
                    val rc = bridge.loadSrmSnapshot(saved)
                    if (rc == 0) {
                        SynheartLogger.log("[RuntimeModule] Restored SRM baselines from snapshot")
                    } else {
                        SynheartLogger.log("[RuntimeModule] SRM snapshot load failed (code $rc), starting fresh")
                    }
                }
            } catch (e: Exception) {
                SynheartLogger.log("[RuntimeModule] SRM snapshot restore error: $e")
            }
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
                delay(SynheartDefaults.RUNTIME_TICK_INTERVAL_MS)
                val hsiJson = bridge.tick(System.currentTimeMillis())
                if (hsiJson != null) {
                    _hsiFlow.value = hsiJson
                }
            }
        }

        SynheartLogger.log("[RuntimeModule] Started")
    }

    override suspend fun onStop() {
        SynheartLogger.log("[RuntimeModule] Stopping...")

        if (bridge != null && batchIngestOnStop && batchEventBuffer.isNotEmpty()) {
            flushBatchOnStop()
        }

        // Persist SRM baselines for next session
        if (bridge != null && saveSrmSnapshot != null) {
            try {
                val snapshot = bridge.exportSrmSnapshot()
                if (snapshot != null) {
                    saveSrmSnapshot.invoke(snapshot)
                    SynheartLogger.log("[RuntimeModule] Saved SRM baselines snapshot")
                }
            } catch (e: Exception) {
                SynheartLogger.log("[RuntimeModule] SRM snapshot save error: $e")
            }
        }

        tickJob?.cancel()
        tickJob = null

        wearJob?.cancel()
        wearJob = null

        behaviorJob?.cancel()
        behaviorJob = null

        SynheartLogger.log("[RuntimeModule] Stopped")
    }

    override suspend fun onDispose() {
        SynheartLogger.log("[RuntimeModule] Disposing...")

        tickJob?.cancel()
        wearJob?.cancel()
        behaviorJob?.cancel()
        moduleScope.cancel()

        bridge?.close()
    }

    // --- Private helpers ---

    private fun handleWearSample(sample: WearSample) {
        val tsMs = sample.timestamp

        if (batchIngestOnStop) {
            appendWearToBatch(sample, tsMs)
            return
        }

        // Push individual RR intervals
        sample.rrIntervals?.forEach { rr ->
            bridge!!.pushRr(tsMs, rr)
        }

        // Push heart rate
        sample.hr?.let { hr ->
            bridge!!.pushHr(tsMs, hr)
        }
    }

    private fun appendWearToBatch(sample: WearSample, tsMs: Long) {
        if (sample.rrIntervals != null && sample.rrIntervals.isNotEmpty()) {
            val rrs = sample.rrIntervals
            val totalRrMs = rrs.sum()
            var rrTs = tsMs - totalRrMs.toLong()
            for (rr in rrs) {
                batchEventBuffer.add(mapOf("type" to "rr", "ts_ms" to rrTs, "rr_ms" to rr))
                rrTs += rr.toLong()
            }
        } else if (sample.hr != null && sample.hr > 0) {
            batchEventBuffer.add(mapOf("type" to "hr", "ts_ms" to tsMs, "bpm" to sample.hr))
        }
    }

    private fun handleBehaviorEvent(event: ai.synheart.core.modules.behavior.BehaviorEvent) {
        val tsMs = event.timestamp

        if (batchIngestOnStop) {
            batchEventBuffer.add(behaviorEventToBatchMap(tsMs, event))
            return
        }

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

    private fun behaviorEventToBatchMap(tsMs: Long, event: ai.synheart.core.modules.behavior.BehaviorEvent): Map<String, Any> {
        val eventName: String
        val options = mutableMapOf<String, Any>()
        val meta = event.metadata

        when (event.type) {
            BehaviorEventType.TAP,
            BehaviorEventType.KEY_DOWN,
            BehaviorEventType.KEY_UP -> {
                eventName = "touch"
                if (meta != null) {
                    (meta["x"] as? Number)?.let { options["x"] = it.toDouble() }
                    (meta["y"] as? Number)?.let { options["y"] = it.toDouble() }
                }
            }
            BehaviorEventType.APP_SWITCH -> {
                eventName = "app_switch"
                if (meta != null) {
                    meta["from_app_id"]?.let { options["from_app_id"] = it }
                    meta["to_app_id"]?.let { options["to_app_id"] = it }
                }
            }
            BehaviorEventType.NOTIFICATION_RECEIVED,
            BehaviorEventType.NOTIFICATION_OPENED -> {
                eventName = "notification"
                if (meta != null) {
                    meta["action"]?.let { options["action"] = it }
                    meta["source_app_id"]?.let { options["source_app_id"] = it }
                }
            }
            BehaviorEventType.SCROLL -> {
                eventName = "scroll"
                if (meta != null) {
                    (meta["delta"] as? Number)?.let { options["delta"] = it.toDouble() }
                    meta["velocity"]?.let { options["velocity"] = it }
                    meta["direction"]?.let { options["direction"] = it }
                }
            }
        }

        val result = mutableMapOf<String, Any>(
            "type" to "behavior",
            "ts_ms" to tsMs,
            "event" to eventName,
            "provider" to "behavior_app"
        )
        if (options.isNotEmpty()) {
            result["options"] = options
        }
        return result
    }

    private fun flushBatchOnStop() {
        if (bridge == null || batchEventBuffer.isEmpty()) return

        batchEventBuffer.sortBy { (it["ts_ms"] as? Long) ?: 0L }

        val nowMs = System.currentTimeMillis()
        val batchJson = JSONArray(batchEventBuffer).toString()
        val resultJson = bridge.ingestBatch(batchJson, nowMs)

        if (resultJson == null) {
            SynheartLogger.log(
                "[RuntimeModule] Batch ingest not available (synheart_runtime_ingest_batch_json missing). " +
                "HSI will not be produced for this session."
            )
            batchEventBuffer.clear()
            return
        }

        try {
            val result = JSONObject(resultJson)
            val frames = result.optJSONArray("frames")
            if (result.optBoolean("ok") && frames != null && frames.length() > 0) {
                for (i in 0 until frames.length()) {
                    val frame = frames.getJSONObject(i)
                    val hsi = frame.optJSONObject("hsi")
                    if (hsi != null) {
                        val hsiJson = hsi.toString()
                        SynheartLogger.log("[Runtime] HSI (batch on stop, frame): $hsiJson")
                        _hsiFlow.value = hsiJson
                    }
                }
            } else if (result.optBoolean("ok") && result.has("hsi")) {
                // Legacy: single top-level hsi
                val hsiJson = result.getJSONObject("hsi").toString()
                SynheartLogger.log("[Runtime] HSI (batch on stop): $hsiJson")
                _hsiFlow.value = hsiJson
                // Drain remaining frames
                val maxDrain = 256
                var drainCount = 0
                val drainNowMs = System.currentTimeMillis()
                while (drainCount < maxDrain) {
                    val drainHsi = bridge.tick(drainNowMs) ?: break
                    drainCount++
                    SynheartLogger.log("[Runtime] HSI (batch drain): $drainHsi")
                    _hsiFlow.value = drainHsi
                }
                if (drainCount >= maxDrain) {
                    SynheartLogger.log("[RuntimeModule] Batch drain hit max iterations ($maxDrain)")
                }
            }
        } catch (e: Exception) {
            SynheartLogger.log("[RuntimeModule] Batch result parse error: $e")
        }
        batchEventBuffer.clear()
    }
}
