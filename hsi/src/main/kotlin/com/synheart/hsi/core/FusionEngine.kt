package com.synheart.hsi.core

import com.synheart.hsi.models.HumanStateVector
import com.synheart.hsi.models.MetaState
import com.synheart.hsi.models.DeviceInfo
import com.synheart.hsi.models.BehaviorState
import com.synheart.hsi.models.ContextState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.filter
import android.os.Build

/**
 * Fusion Engine: Combines processed signals into base HSV
 */
class FusionEngine(
    private val sessionId: String
) {
    
    /**
     * Fuse a single processed signal into base HSV
     */
    fun fuse(processed: ProcessedSignal): HumanStateVector {
        // Extract behavioral metrics
        val behavior = extractBehaviorMetrics(processed)
        
        // Generate HSI embedding (placeholder - replace with actual model)
        val embedding = generateHSIEmbedding(processed)
        
        // Create base HSV
        return HumanStateVector(
            timestamp = processed.timestamp,
            meta = MetaState(
                device = DeviceInfo(
                    osVersion = Build.VERSION.RELEASE,
                    model = Build.MODEL,
                    manufacturer = Build.MANUFACTURER
                ),
                sessionId = sessionId
            ),
            heartRate = processed.heartRate,
            hrv = processed.hrv,
            hrvSdnn = processed.hrvSdnn,
            hsiEmbedding = embedding,
            behavior = behavior
        )
    }
    
    /**
     * Fuse processed signals into base HSV (Flow version)
     */
    suspend fun fuse(processedSignals: Flow<ProcessedSignal>): Flow<HumanStateVector> = flow {
        processedSignals.collect { processed ->
            emit(fuse(processed))
        }
    }
    
    /**
     * Extract behavioral metrics from processed signals
     */
    private fun extractBehaviorMetrics(processed: ProcessedSignal): BehaviorState? {
        val typingSignals = processed.rawSignals.filter { 
            it.type == com.synheart.hsi.models.SignalData.SignalType.TYPING 
        }
        val scrollingSignals = processed.rawSignals.filter { 
            it.type == com.synheart.hsi.models.SignalData.SignalType.SCROLLING 
        }
        val appSwitchSignals = processed.rawSignals.filter { 
            it.type == com.synheart.hsi.models.SignalData.SignalType.APP_SWITCH 
        }
        
        if (typingSignals.isEmpty() && scrollingSignals.isEmpty() && appSwitchSignals.isEmpty()) {
            return null
        }
        
        // Calculate typing speed (characters per second)
        val typingSpeed = if (typingSignals.isNotEmpty()) {
            val timeSpan = (typingSignals.last().timestamp - typingSignals.first().timestamp) / 1000f
            val charCount = typingSignals.sumOf { 
                it.values["char_count"]?.toDouble() ?: 0.0 
            }.toFloat()
            if (timeSpan > 0) charCount / timeSpan else null
        } else null
        
        // Calculate scrolling velocity
        val scrollingVelocity = if (scrollingSignals.isNotEmpty()) {
            val timeSpan = (scrollingSignals.last().timestamp - scrollingSignals.first().timestamp) / 1000f
            val distance = scrollingSignals.sumOf { 
                it.values["distance"]?.toDouble() ?: 0.0 
            }.toFloat()
            if (timeSpan > 0) distance / timeSpan else null
        } else null
        
        // Calculate app switch frequency (switches per minute)
        val appSwitchFrequency = if (appSwitchSignals.isNotEmpty()) {
            val timeSpanMinutes = (appSwitchSignals.last().timestamp - appSwitchSignals.first().timestamp) / 60000f
            if (timeSpanMinutes > 0) appSwitchSignals.size / timeSpanMinutes else null
        } else null
        
        // Calculate interaction intensity (normalized)
        val interactionIntensity = calculateInteractionIntensity(
            typingSignals.size,
            scrollingSignals.size,
            appSwitchSignals.size
        )
        
        // Calculate engagement level
        val engagementLevel = calculateEngagementLevel(
            typingSpeed,
            scrollingVelocity,
            appSwitchFrequency
        )
        
        return BehaviorState(
            typingSpeed = typingSpeed,
            typingBurstiness = processed.burstiness,
            scrollingVelocity = scrollingVelocity,
            appSwitchFrequency = appSwitchFrequency,
            interactionIntensity = interactionIntensity,
            engagementLevel = engagementLevel
        )
    }
    
    /**
     * Generate HSI embedding (placeholder - replace with actual Tiny Transformer or CNN-LSTM)
     */
    private fun generateHSIEmbedding(processed: ProcessedSignal): List<Float> {
        // Placeholder: simple feature vector
        // TODO: Replace with actual model inference (TensorFlow Lite)
        return listOf(
            processed.heartRate?.let { it / 200f } ?: 0f, // Normalized HR
            processed.hrv?.let { it / 100f } ?: 0f, // Normalized HRV
            processed.hrvSdnn?.let { it / 100f } ?: 0f, // Normalized SDNN
            processed.burstiness,
            // Add more features as needed
            0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f // Padding to 12 dimensions
        ).take(12) // Fixed size embedding
    }
    
    private fun calculateInteractionIntensity(
        typingCount: Int,
        scrollingCount: Int,
        appSwitchCount: Int
    ): Float {
        // Normalize based on expected ranges
        val totalInteractions = typingCount + scrollingCount + appSwitchCount
        return (totalInteractions / 100f).coerceIn(0f, 1f)
    }
    
    private fun calculateEngagementLevel(
        typingSpeed: Float?,
        scrollingVelocity: Float?,
        appSwitchFrequency: Float?
    ): Float {
        var score = 0f
        var count = 0
        
        typingSpeed?.let {
            score += (it / 10f).coerceIn(0f, 1f) // Normalize typing speed
            count++
        }
        
        scrollingVelocity?.let {
            score += (it / 1000f).coerceIn(0f, 1f) // Normalize scrolling velocity
            count++
        }
        
        appSwitchFrequency?.let {
            // Lower frequency = higher engagement (fewer distractions)
            score += (1f - (it / 10f).coerceIn(0f, 1f))
            count++
        }
        
        return if (count > 0) score / count else 0f
    }
}

