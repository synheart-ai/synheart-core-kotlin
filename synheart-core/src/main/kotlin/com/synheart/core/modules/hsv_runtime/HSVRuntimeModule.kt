package com.synheart.core.modules.hsv_runtime

import com.synheart.core.modules.base.BaseSynheartModule
import com.synheart.core.modules.interfaces.WindowType
import com.synheart.core.models.HumanStateVector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * HSV Runtime Module
 *
 * Orchestrates the HSV (internal) pipeline:
 * 1. Schedules windows (30s, 5m, 1h, 24h)
 * 2. Collects features from Wear, Phone, Behavior
 * 3. Fuses features into HSV (state axes/indices/embeddings — implementation-defined)
 * 4. Publishes HSV updates
 *
 * IMPORTANT: This module does NOT include emotion or focus interpretation.
 * Those are optional downstream modules that consume HSV output.
 *
 * RFC alignment:
 * - HSV is internal state representation (RFC-0001).
 * - HSI is an external, canonical JSON contract (RFC-0005) produced via export.
 */
class HSVRuntimeModule(
    private val collector: ChannelCollector
) : BaseSynheartModule("hsv_runtime") {

    private val fusion = FusionEngine()

    private var scheduler: WindowScheduler? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _hsvFlow = MutableStateFlow<HumanStateVector?>(null)

    /**
     * Flow of HSV updates (internal state representation only)
     */
    val hsvFlow: Flow<HumanStateVector> = _hsvFlow.asStateFlow().filterNotNull()

    /**
     * Get current HSV state
     */
    val currentState: HumanStateVector?
        get() = _hsvFlow.value

    override suspend fun onInitialize() {
        println("[HSVRuntime] Initializing HSV Runtime...")
    }

    override suspend fun onStart() {
        println("[HSVRuntime] Starting HSV Runtime...")

        // Start window scheduler
        scheduler = WindowScheduler { window ->
            // Only compute for 30s window (primary window)
            if (window == WindowType.WINDOW_30S) {
                scope.launch {
                    computeState(window)
                }
            }
        }

        scheduler?.start()
        println("[HSVRuntime] HSV Runtime started")
    }

    override suspend fun onStop() {
        println("[HSVRuntime] Stopping HSV Runtime...")

        scheduler?.stop()
        scheduler = null
    }

    override suspend fun onDispose() {
        println("[HSVRuntime] Disposing HSV Runtime...")

        scheduler?.stop()
        scheduler = null
        scope.cancel()
    }

    /**
     * Compute HSV state for a window
     */
    private suspend fun computeState(window: WindowType) {
        try {
            // Collect features from all modules
            val features = collector.collect(window)

            if (!features.hasAnyFeatures) {
                println("[HSVRuntime] No features available for $window")
                return
            }

            // Fuse into HSV (internal state representation)
            val timestamp = System.currentTimeMillis()
            val hsv = fusion.fuse(features, window, timestamp)

            // Emit HSV (state representation only, no interpretation)
            _hsvFlow.value = hsv

            println("[HSVRuntime] Computed HSV for $window")
        } catch (e: Exception) {
            println("[HSVRuntime] Error computing HSV: $e")
            e.printStackTrace()
        }
    }
}


