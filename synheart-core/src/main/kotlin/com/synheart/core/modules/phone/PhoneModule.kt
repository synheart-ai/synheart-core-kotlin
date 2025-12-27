package com.synheart.core.modules.phone

import com.synheart.core.modules.base.BaseSynheartModule
import com.synheart.core.modules.interfaces.CapabilityLevel
import com.synheart.core.modules.interfaces.CapabilityProvider
import com.synheart.core.modules.interfaces.ConsentProvider
import com.synheart.core.modules.interfaces.Module
import com.synheart.core.modules.interfaces.PhoneFeatureProvider
import com.synheart.core.modules.interfaces.PhoneWindowFeatures
import com.synheart.core.modules.interfaces.WindowType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/// Phone Module
///
/// Captures device-level motion and context signals.
/// Provides window-based features to HSI Runtime.
class PhoneModule(
    private val capabilities: CapabilityProvider,
    private val consent: ConsentProvider
) : BaseSynheartModule("phone"), PhoneFeatureProvider {
    
    private val motionCollector = MotionCollector()
    private val screenTracker = ScreenStateTracker()
    private val appTracker = AppFocusTracker()
    private val notificationTracker = NotificationTracker()
    private val cache = PhoneCache()
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobSet = mutableSetOf<kotlinx.coroutines.Job>()
    
    override fun features(window: WindowType): PhoneWindowFeatures? {
        // Check consent
        if (!consent.current().motion) {
            return null // Return null if consent denied
        }
        
        val features = cache.getFeatures(window) ?: return null
        
        // Filter based on capability level
        return filterByCapability(features)
    }
    
    /// Filter features based on capability level
    private fun filterByCapability(features: PhoneWindowFeatures): PhoneWindowFeatures? {
        val level = capabilities.capability(Module.PHONE)
        
        return when (level) {
            CapabilityLevel.NONE -> null
            
            CapabilityLevel.CORE -> {
                // Core: Motion and screen only
                PhoneWindowFeatures(
                    motionLevel = features.motionLevel,
                    screenOnRatio = features.screenOnRatio,
                    appSwitchRate = 0.0, // No app switching at core level
                    notificationRate = 0.0 // No notifications at core level
                )
            }
            
            CapabilityLevel.EXTENDED, CapabilityLevel.RESEARCH -> {
                // Extended/Research: Full access
                features
            }
        }
    }
    
    override suspend fun onInitialize() {
        println("[PhoneModule] Initializing phone collectors...")
        // Nothing to initialize for mock collectors
    }
    
    override suspend fun onStart() {
        println("[PhoneModule] Starting phone data collection...")
        
        // Start motion collection
        motionCollector.start()
        val motionJob = motionCollector.motionFlow
            .onEach { motion ->
                cache.addMotionData(motion)
            }
            .launchIn(scope)
        jobSet.add(motionJob)
        
        // Start screen state tracking
        screenTracker.start()
        val screenJob = screenTracker.screenFlow
            .onEach { state ->
                cache.addScreenState(state, timestamp = System.currentTimeMillis())
            }
            .launchIn(scope)
        jobSet.add(screenJob)
        
        // Start app tracking (if capability allows)
        if (capabilities.capability(Module.PHONE) != CapabilityLevel.NONE) {
            appTracker.start()
            val appJob = appTracker.appSwitchFlow
                .onEach {
                    cache.addAppSwitch(timestamp = System.currentTimeMillis())
                }
                .launchIn(scope)
            jobSet.add(appJob)
        }
        
        // Start notification tracking (if capability allows)
        if (capabilities.capability(Module.PHONE) != CapabilityLevel.NONE) {
            notificationTracker.start()
            val notifJob = notificationTracker.notificationFlow
                .onEach { event ->
                    cache.addNotification(event)
                }
                .launchIn(scope)
            jobSet.add(notifJob)
        }
        
        println("[PhoneModule] Started ${jobSet.size} collectors")
    }
    
    override suspend fun onStop() {
        println("[PhoneModule] Stopping phone data collection...")
        
        jobSet.forEach { it.cancel() }
        jobSet.clear()
        
        // Stop all collectors
        motionCollector.stop()
        screenTracker.stop()
        appTracker.stop()
        notificationTracker.stop()
    }
    
    override suspend fun onDispose() {
        println("[PhoneModule] Disposing phone module...")
        
        motionCollector.dispose()
        screenTracker.dispose()
        appTracker.dispose()
        notificationTracker.dispose()
        
        scope.cancel()
    }
}

