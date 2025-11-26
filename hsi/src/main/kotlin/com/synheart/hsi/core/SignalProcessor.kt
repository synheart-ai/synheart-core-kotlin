package com.synheart.hsi.core

import com.synheart.hsi.models.SignalData
import com.synheart.hsi.models.SignalType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Processes raw signals: synchronization, normalization, noise reduction
 */
class SignalProcessor {
    
    private val signalBuffer = mutableMapOf<SignalType, MutableList<SignalData>>()
    private val baselineValues = mutableMapOf<String, Float>()
    
    /**
     * Process raw signals: synchronize, normalize, clean
     */
    suspend fun process(signals: Flow<SignalData>): Flow<ProcessedSignal> = flow {
        signals.collect { signal ->
            // Add to buffer for synchronization
            signalBuffer.getOrPut(signal.type) { mutableListOf() }.add(signal)
            
            // Synchronize signals within a time window
            val synchronized = synchronizeSignals()
            
            synchronized.forEach { processed ->
                emit(processed)
            }
        }
    }
    
    /**
     * Synchronize signals within a time window (e.g., 1 second)
     */
    private suspend fun synchronizeSignals(): List<ProcessedSignal> {
        val windowMs = 1000L // 1 second window
        val now = System.currentTimeMillis()
        val windowStart = now - windowMs
        
        val processed = mutableListOf<ProcessedSignal>()
        
        // Process each signal type
        signalBuffer.forEach { (type, signals) ->
            val windowSignals = signals.filter { it.timestamp >= windowStart }
            
            if (windowSignals.isNotEmpty()) {
                val cleaned = cleanSignals(windowSignals)
                val normalized = normalizeSignals(cleaned)
                val derived = calculateDerivedMetrics(normalized)
                
                processed.add(derived)
                
                // Remove old signals
                signals.removeAll { it.timestamp < windowStart }
            }
        }
        
        return processed
    }
    
    /**
     * Clean signals: remove noise and artifacts
     */
    private fun cleanSignals(signals: List<SignalData>): List<SignalData> {
        if (signals.isEmpty()) return emptyList()

        // Simple outlier removal (values beyond 3 standard deviations)
        val values = signals.flatMap { it.values.values }
        if (values.isEmpty()) return signals

        val mean = values.average().toFloat()
        val stdDev = calculateStdDev(values, mean)
        val upperThreshold = mean + 3 * stdDev
        val lowerThreshold = mean - 3 * stdDev

        return signals.filter { signal ->
            signal.values.values.all { it <= upperThreshold && it >= lowerThreshold }
        }
    }
    
    /**
     * Normalize signals to vendor-agnostic format
     */
    private fun normalizeSignals(signals: List<SignalData>): List<SignalData> {
        return signals.map { signal ->
            val normalizedValues = signal.values.mapValues { (key, value) ->
                normalizeValue(key, value, signal.type)
            }
            signal.copy(values = normalizedValues)
        }
    }
    
    /**
     * Normalize a single value based on its type and baseline
     */
    private fun normalizeValue(key: String, value: Float, type: SignalType): Float {
        val baseline = baselineValues.getOrPut(key) { value }
        
        return when (type) {
            SignalType.HEART_RATE -> {
                // Normalize HR: (value - baseline) / baseline
                (value - baseline) / baseline
            }
            SignalType.HRV -> {
                // Normalize HRV: (value - baseline) / baseline
                (value - baseline) / baseline
            }
            else -> {
                // Generic normalization
                if (baseline > 0) (value - baseline) / baseline else value
            }
        }
    }
    
    /**
     * Calculate derived metrics (RMSSD, SDNN, burstiness)
     */
    private fun calculateDerivedMetrics(signals: List<SignalData>): ProcessedSignal {
        val heartRateValues = signals
            .filter { it.type == SignalType.HEART_RATE }
            .flatMap { it.values.values }
        
        val hrvValues = signals
            .filter { it.type == SignalType.HRV }
            .flatMap { it.values.values }
        
        val rmssd = calculateRMSSD(hrvValues)
        val sdnn = calculateSDNN(hrvValues)
        val burstiness = calculateBurstiness(signals)
        
        return ProcessedSignal(
            timestamp = System.currentTimeMillis(),
            heartRate = heartRateValues.average().toFloat().takeIf { heartRateValues.isNotEmpty() },
            hrv = rmssd,
            hrvSdnn = sdnn,
            burstiness = burstiness,
            rawSignals = signals
        )
    }
    
    /**
     * Calculate RMSSD (Root Mean Square of Successive Differences)
     */
    private fun calculateRMSSD(values: List<Float>): Float? {
        if (values.size < 2) return null
        
        val differences = values.zipWithNext { a, b -> (b - a).pow(2) }
        val meanSquaredDiff = differences.average().toFloat()
        return sqrt(meanSquaredDiff)
    }
    
    /**
     * Calculate SDNN (Standard Deviation of NN intervals)
     */
    private fun calculateSDNN(values: List<Float>): Float? {
        if (values.isEmpty()) return null
        
        val mean = values.average().toFloat()
        return calculateStdDev(values, mean)
    }
    
    /**
     * Calculate burstiness index for behavioral signals
     */
    private fun calculateBurstiness(signals: List<SignalData>): Float {
        val typingSignals = signals.filter { it.type == SignalType.TYPING }
        if (typingSignals.size < 2) return 0f
        
        val intervals = typingSignals.zipWithNext { a, b -> 
            b.timestamp - a.timestamp 
        }
        
        if (intervals.isEmpty()) return 0f
        
        val mean = intervals.average()
        val stdDev = calculateStdDev(intervals.map { it.toFloat() }, mean.toFloat())
        
        // Burstiness = (stdDev - mean) / (stdDev + mean)
        return if (mean > 0 && stdDev + mean > 0) {
            ((stdDev - mean) / (stdDev + mean)).toFloat()
        } else 0f
    }
    
    private fun calculateStdDev(values: List<Float>, mean: Float): Float {
        if (values.isEmpty()) return 0f
        val variance = values.map { (it - mean).pow(2) }.average().toFloat()
        return sqrt(variance)
    }
    
    /**
     * Update baseline values (for adaptive normalization)
     */
    fun updateBaseline(key: String, value: Float) {
        val currentBaseline = baselineValues[key] ?: value
        // Exponential moving average
        baselineValues[key] = 0.9f * currentBaseline + 0.1f * value
    }
}

/**
 * Processed signal with derived metrics
 */
data class ProcessedSignal(
    val timestamp: Long,
    val heartRate: Float?,
    val hrv: Float?,
    val hrvSdnn: Float?,
    val burstiness: Float,
    val rawSignals: List<SignalData>
)

