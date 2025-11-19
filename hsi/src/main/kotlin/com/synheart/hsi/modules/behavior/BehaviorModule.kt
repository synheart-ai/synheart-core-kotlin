package com.synheart.hsi.modules.behavior

import com.synheart.hsi.modules.base.BaseSynheartModule
import com.synheart.hsi.modules.interfaces.BehaviorFeatureProvider
import com.synheart.hsi.modules.interfaces.BehaviorWindowFeatures
import com.synheart.hsi.modules.interfaces.CapabilityLevel
import com.synheart.hsi.modules.interfaces.CapabilityProvider
import com.synheart.hsi.modules.interfaces.ConsentProvider
import com.synheart.hsi.modules.interfaces.Module
import com.synheart.hsi.modules.interfaces.WindowType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/// Behavior Module
///
/// Captures user-device interaction patterns.
/// Provides window-based behavioral features to HSI Runtime.
class BehaviorModule(
    private val capabilities: CapabilityProvider,
    private val consent: ConsentProvider
) : BaseSynheartModule("behavior"), BehaviorFeatureProvider {
    
    private val eventStream = BehaviorEventStream()
    private val aggregator = WindowAggregator()
    private val extractor = BehaviorFeatureExtractor()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var eventJob: kotlinx.coroutines.Job? = null
    private var cleanupJob: kotlinx.coroutines.Job? = null
    
    /// Get the event stream for recording events
    val eventStreamInstance: BehaviorEventStream
        get() = eventStream
    
    override fun features(window: WindowType): BehaviorWindowFeatures? {
        // Check consent
        if (!consent.current().behavior) {
            return null // Return null if consent denied
        }
        
        val events = aggregator.getEvents(window)
        val features = extractor.extract(events)
        
        // Filter based on capability level
        return filterByCapability(features)
    }
    
    /// Filter features based on capability level
    private fun filterByCapability(features: BehaviorWindowFeatures): BehaviorWindowFeatures? {
        val level = capabilities.capability(Module.BEHAVIOR)
        
        return when (level) {
            CapabilityLevel.NONE -> null
            
            CapabilityLevel.CORE -> {
                // Core: Only basic metrics
                features.copy(
                    burstiness = 0.0, // Not available at core
                    sessionFragmentation = 0.0, // Not available at core
                    notificationLoad = 0.0 // Not available at core
                )
            }
            
            CapabilityLevel.EXTENDED, CapabilityLevel.RESEARCH -> {
                // Extended/Research: Full access
                features
            }
        }
    }
    
    override suspend fun onInitialize() {
        println("[BehaviorModule] Initializing behavior tracking...")
        // Nothing to initialize
    }
    
    override suspend fun onStart() {
        println("[BehaviorModule] Starting behavior tracking...")
        
        // Subscribe to event stream
        eventJob = eventStream.events
            .onEach { event ->
                // Check consent before adding event
                if (consent.current().behavior) {
                    aggregator.addEvent(event)
                }
            }
            .launchIn(scope)
        
        // Start cleanup timer (every minute)
        cleanupJob = scope.launch {
            while (isActive) {
                delay(60000) // Every minute
                aggregator.cleanOldWindows()
            }
        }
        
        println("[BehaviorModule] Behavior tracking started")
    }
    
    override suspend fun onStop() {
        println("[BehaviorModule] Stopping behavior tracking...")
        
        eventJob?.cancel()
        eventJob = null
        
        cleanupJob?.cancel()
        cleanupJob = null
    }
    
    override suspend fun onDispose() {
        println("[BehaviorModule] Disposing behavior module...")
        eventStream.dispose()
        scope.cancel()
    }
}

