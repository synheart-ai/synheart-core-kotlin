package com.synheart.hsi.modules.wear

import com.synheart.hsi.modules.base.BaseSynheartModule
import com.synheart.hsi.modules.interfaces.CapabilityLevel
import com.synheart.hsi.modules.interfaces.CapabilityProvider
import com.synheart.hsi.modules.interfaces.ConsentProvider
import com.synheart.hsi.modules.interfaces.FeatureProviders
import com.synheart.hsi.modules.interfaces.Module
import com.synheart.hsi.modules.interfaces.SleepStage
import com.synheart.hsi.modules.interfaces.WearFeatureProvider
import com.synheart.hsi.modules.interfaces.WindowType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/// Wear Module
///
/// Collects and normalizes biosignals from wearables.
/// Provides window-based features to HSI Runtime.
class WearModule(
    private val capabilities: CapabilityProvider,
    private val consent: ConsentProvider,
    private val sources: List<WearSourceHandler>? = null
) : BaseSynheartModule("wear"), WearFeatureProvider {
    
    private val actualSources = sources ?: listOf(MockWearSourceHandler())
    private val cache = WearCache()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobSet = mutableSetOf<kotlinx.coroutines.Job>()
    
    override fun features(window: WindowType): WearWindowFeatures? {
        // Check consent first
        if (!consent.current().biosignals) {
            return null // Return null if consent denied
        }
        
        val features = cache.getFeatures(window) ?: return null
        
        // Filter based on capability level
        return filterByCapability(features)
    }
    
    /// Filter features based on capability level
    private fun filterByCapability(features: WearWindowFeatures): WearWindowFeatures? {
        val level = capabilities.capability(Module.WEAR)
        
        return when (level) {
            CapabilityLevel.NONE -> null
            
            CapabilityLevel.CORE -> {
                // Core: Only derived metrics (average HR, HRV)
                features.copy(
                    hrMin = null,
                    hrMax = null
                    // No min/max for core level
                )
            }
            
            CapabilityLevel.EXTENDED, CapabilityLevel.RESEARCH -> {
                // Extended/Research: Full access
                features
            }
        }
    }
    
    override suspend fun onInitialize() {
        println("[WearModule] Initializing wear sources...")
        
        actualSources.forEach { source ->
            if (source.isAvailable) {
                try {
                    source.initialize()
                    println("[WearModule] Initialized ${source.sourceType} source")
                } catch (e: Exception) {
                    println("[WearModule] Failed to initialize ${source.sourceType}: $e")
                }
            }
        }
    }
    
    override suspend fun onStart() {
        println("[WearModule] Starting wear data collection...")
        
        // Subscribe to each source
        actualSources.forEach { source ->
            if (source.isAvailable) {
                val job = source.sampleFlow
                    .onEach { sample ->
                        cache.addSample(sample)
                    }
                    .launchIn(scope)
                
                jobSet.add(job)
                
                // Start mock source if needed
                if (source is MockWearSourceHandler) {
                    source.startGenerating()
                }
            }
        }
        
        println("[WearModule] Started ${jobSet.size} wear sources")
    }
    
    override suspend fun onStop() {
        println("[WearModule] Stopping wear data collection...")
        
        jobSet.forEach { it.cancel() }
        jobSet.clear()
    }
    
    override suspend fun onDispose() {
        println("[WearModule] Disposing wear module...")
        
        // Dispose all sources
        actualSources.forEach { source ->
            try {
                source.dispose()
            } catch (e: Exception) {
                println("[WearModule] Error disposing ${source.sourceType}: $e")
            }
        }
        
        scope.cancel()
    }
}

