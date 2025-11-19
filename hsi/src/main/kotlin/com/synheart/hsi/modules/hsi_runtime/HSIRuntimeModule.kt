package com.synheart.hsi.modules.hsi_runtime

import com.synheart.hsi.modules.base.BaseSynheartModule
import com.synheart.hsi.modules.interfaces.WindowType
import com.synheart.hsi.heads.EmotionHead
import com.synheart.hsi.heads.FocusHead
import com.synheart.hsi.models.HumanStateVector
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

/// HSI Runtime Module
///
/// Orchestrates the HSI pipeline:
/// 1. Schedules windows (30s, 5m, 1h, 24h)
/// 2. Collects features from Wear, Phone, Behavior
/// 3. Fuses features into base HSV
/// 4. Runs Emotion and Focus heads
/// 5. Publishes final HSV
class HSIRuntimeModule(
    private val collector: ChannelCollector
) : BaseSynheartModule("hsi_runtime") {
    
    private val fusion = FusionEngineV2()
    private val emotionHead = EmotionHead()
    private val focusHead = FocusHead()
    
    private var scheduler: WindowScheduler? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    private val _baseHsvFlow = MutableStateFlow<HumanStateVector?>(null)
    private val _finalHsvFlow = MutableStateFlow<HumanStateVector?>(null)
    
    /// Flow of base HSV (before emotion/focus)
    val baseHsvFlow: Flow<HumanStateVector> = _baseHsvFlow.asStateFlow().filterNotNull()
    
    /// Flow of final HSV (with emotion and focus)
    val finalHsvFlow: Flow<HumanStateVector> = _finalHsvFlow.asStateFlow().filterNotNull()
    
    /// Get current state
    val currentState: HumanStateVector?
        get() = _finalHsvFlow.value
    
    override suspend fun onInitialize() {
        println("[HSIRuntime] Initializing HSI Runtime...")
        
        // Set up emotion and focus heads pipeline
        scope.launch {
            baseHsvFlow.collect { baseHsv ->
                // Process through emotion head
                val hsvWithEmotion = emotionHead.processOne(baseHsv)
                
                // Process through focus head
                val finalHsv = focusHead.processOne(hsvWithEmotion)
                
                // Emit final HSV
                _finalHsvFlow.value = finalHsv
            }
        }
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
    
    /// Compute state for a window
    private suspend fun computeState(window: WindowType) {
        try {
            // Collect features from all modules
            val features = collector.collect(window)
            
            if (!features.hasAnyFeatures) {
                println("[HSIRuntime] No features available for $window")
                return
            }
            
            // Fuse into base HSV
            val timestamp = System.currentTimeMillis()
            val baseHsv = fusion.fuse(features, window, timestamp)
            
            // Emit base HSV (will flow through emotion -> focus heads)
            _baseHsvFlow.value = baseHsv
            
            println("[HSIRuntime] Computed state for $window")
        } catch (e: Exception) {
            println("[HSIRuntime] Error computing state: $e")
            e.printStackTrace()
        }
    }
}

