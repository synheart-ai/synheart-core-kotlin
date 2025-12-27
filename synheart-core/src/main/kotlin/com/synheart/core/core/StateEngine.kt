package com.synheart.core.core

import com.synheart.core.models.HumanStateVector
import com.synheart.core.models.SignalData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * State Engine: Orchestrates ingestion, processing, and fusion
 */
class StateEngine(
    private val ingestionService: IngestionService,
    private val signalProcessor: SignalProcessor,
    private val fusionEngine: FusionEngine
) {
    
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _baseHsvFlow = MutableStateFlow<HumanStateVector?>(null)
    val baseHsvFlow: StateFlow<HumanStateVector?> = _baseHsvFlow.asStateFlow()
    
    private var isRunning = false
    
    /**
     * Start the state engine pipeline
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        
        engineScope.launch {
            // Pipeline: Signals -> Processing -> Fusion -> Base HSV
            signalProcessor.process(ingestionService.signalFlow)
                .collect { processed ->
                    // Fuse processed signal into HSV
                    val hsv = fusionEngine.fuse(processed)
                    _baseHsvFlow.value = hsv
                }
        }
    }
    
    /**
     * Stop the state engine
     */
    fun stop() {
        isRunning = false
        // Flow collection will stop automatically
    }
    
    /**
     * Get current base HSV
     */
    fun getCurrentBaseHsv(): HumanStateVector? = _baseHsvFlow.value
}

