package com.synheart.core.modules.hsi_runtime

import com.synheart.core.modules.base.BaseSynheartModule
import com.synheart.core.modules.interfaces.WindowType
import com.synheart.core.models.HumanStateVector
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * HSI Runtime Module
 *
 * Orchestrates the HSI pipeline:
 * 1. Schedules windows (30s, 5m, 1h, 24h)
 * 2. Collects features from Wear, Phone, Behavior
 * 3. Fuses features into HSI (state axes, indices, embeddings)
 * 4. Publishes HSI updates
 *
 * IMPORTANT: This module does NOT include emotion or focus interpretation.
 * Those are optional downstream modules that consume HSI output.
 */
class HSIRuntimeModule(
    private val collector: ChannelCollector
) : BaseSynheartModule("hsi_runtime") {

    private val fusion = FusionEngineV2()

    private var scheduler: WindowScheduler? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _hsiFlow = MutableStateFlow<HumanStateVector?>(null)

    /**
     * Flow of HSI updates (state representation only)
     *
     * HSI contains:
     * - State axes (affect, engagement, activity, context)
     * - State indices (arousalIndex, engagementStability, etc.)
     * - 64D state embedding
     *
     * HSI does NOT contain interpretation (emotion, focus).
     */
    val hsiFlow: Flow<HumanStateVector> = _hsiFlow.asStateFlow().filterNotNull()

    /**
     * Get current HSI state
     */
    val currentState: HumanStateVector?
        get() = _hsiFlow.value

    override suspend fun onInitialize() {
        println("[HSIRuntime] Initializing HSI Runtime...")
        // No emotion/focus heads here - they're optional modules
    }
    
    override suspend fun onStart() {
        println("[HSIRuntime] Starting HSI Runtime...")
        
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
        println("[HSIRuntime] HSI Runtime started")
    }
    
    override suspend fun onStop() {
        println("[HSIRuntime] Stopping HSI Runtime...")

        scheduler?.stop()
        scheduler = null
    }

    override suspend fun onDispose() {
        println("[HSIRuntime] Disposing HSI Runtime...")

        scheduler?.stop()
        scheduler = null
        scope.cancel()
    }

    /**
     * Compute HSI state for a window
     */
    private suspend fun computeState(window: WindowType) {
        try {
            // Collect features from all modules
            val features = collector.collect(window)

            if (!features.hasAnyFeatures) {
                println("[HSIRuntime] No features available for $window")
                return
            }

            // Fuse into HSI (state representation)
            val timestamp = System.currentTimeMillis()
            val hsi = fusion.fuse(features, window, timestamp)

            // Emit HSI (state representation only, no interpretation)
            _hsiFlow.value = hsi

            println("[HSIRuntime] Computed HSI for $window")
        } catch (e: Exception) {
            println("[HSIRuntime] Error computing HSI: $e")
            e.printStackTrace()
        }
    }
}

