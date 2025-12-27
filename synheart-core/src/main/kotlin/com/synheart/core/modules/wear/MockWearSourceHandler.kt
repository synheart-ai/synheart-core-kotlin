package com.synheart.core.modules.wear

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.random.Random

/// Mock wearable data source for testing and development
class MockWearSourceHandler : WearSourceHandler {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val _sampleFlow = MutableSharedFlow<WearSample>()
    
    // Simulate realistic HR/HRV patterns
    private var baseHr: Double = 70.0
    private var baseHrv: Double = 50.0
    private var sampleCount = 0
    private var isGenerating = false
    
    override val sourceType: WearSourceType = WearSourceType.MOCK
    override val isAvailable: Boolean = true
    
    override val sampleFlow: Flow<WearSample> = _sampleFlow.asSharedFlow()
    
    override suspend fun initialize() {
        // Mock initialization - nothing to do
    }
    
    fun startGenerating() {
        if (isGenerating) return
        isGenerating = true
        
        scope.launch {
            while (isGenerating) {
                generateSample()
                delay(1000) // Every second
            }
        }
    }
    
    override suspend fun dispose() {
        isGenerating = false
        scope.cancel()
    }
    
    private suspend fun generateSample() {
        sampleCount++
        
        // Simulate HR variation (±5 bpm)
        val hrVariation = Random.nextDouble(-5.0, 5.0)
        val hr = baseHr + hrVariation + kotlin.math.sin(sampleCount * 0.1) * 3
        
        // Simulate HRV variation
        val hrvVariation = Random.nextDouble(-10.0, 10.0)
        val hrvRmssd = baseHrv + hrvVariation
        
        // Simulate motion (0-1)
        val motionLevel = Random.nextDouble(0.0, 0.3)
        
        val sample = WearSample(
            timestamp = System.currentTimeMillis(),
            hr = hr,
            hrvRmssd = hrvRmssd,
            respRate = Random.nextDouble(12.0, 18.0),
            motionLevel = motionLevel,
            sleepStage = null,
            rrIntervals = null
        )
        
        _sampleFlow.emit(sample)
    }
}

