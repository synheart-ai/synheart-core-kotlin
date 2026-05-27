package ai.synheart.core.modules.phone

import ai.synheart.core.modules.base.BaseSynheartModule
import ai.synheart.core.modules.interfaces.CapabilityLevel
import ai.synheart.core.modules.interfaces.CapabilityProvider
import ai.synheart.core.modules.interfaces.ConsentProvider
import ai.synheart.core.modules.interfaces.Module
import ai.synheart.core.modules.interfaces.RawPhoneDataProvider
import ai.synheart.core.modules.interfaces.WindowType
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import ai.synheart.core.SynheartLogger

/** Captures device-level motion and context signals. */
class PhoneModule(
    private val capabilities: CapabilityProvider,
    private val consent: ConsentProvider
) : BaseSynheartModule("phone"), RawPhoneDataProvider {

    private val motionCollector = MotionCollector()
    private val screenTracker = ScreenStateTracker()
    private val appTracker = AppFocusTracker()
    private val notificationTracker = NotificationTracker()
    private val cache = PhoneCache()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val jobSet = mutableSetOf<kotlinx.coroutines.Job>()

    override fun rawDataPoints(window: WindowType): List<PhoneDataPoint> {
        if (!consent.current().phoneContext) return emptyList()
        return cache.getDataPoints(window)
    }

    override suspend fun onInitialize() {
        SynheartLogger.log("[PhoneModule] Initializing phone collectors...")
    }

    override suspend fun onStart() {
        SynheartLogger.log("[PhoneModule] Starting phone data collection...")

        motionCollector.start()
        val motionJob = motionCollector.motionFlow
            .onEach { motion ->
                cache.addMotionData(motion)
            }
            .launchIn(scope)
        jobSet.add(motionJob)

        screenTracker.start()
        val screenJob = screenTracker.screenFlow
            .onEach { state ->
                cache.addScreenState(state, timestamp = System.currentTimeMillis())
            }
            .launchIn(scope)
        jobSet.add(screenJob)

        if (capabilities.capability(Module.PHONE) != CapabilityLevel.NONE) {
            appTracker.start()
            val appJob = appTracker.appSwitchFlow
                .onEach {
                    cache.addAppSwitch(timestamp = System.currentTimeMillis())
                }
                .launchIn(scope)
            jobSet.add(appJob)
        }

        if (capabilities.capability(Module.PHONE) != CapabilityLevel.NONE) {
            notificationTracker.start()
            val notifJob = notificationTracker.notificationFlow
                .onEach { event ->
                    cache.addNotification(event)
                }
                .launchIn(scope)
            jobSet.add(notifJob)
        }

        SynheartLogger.log("[PhoneModule] Started ${jobSet.size} collectors")
    }

    override suspend fun onStop() {
        SynheartLogger.log("[PhoneModule] Stopping phone data collection...")

        jobSet.forEach { it.cancel() }
        jobSet.clear()

        motionCollector.stop()
        screenTracker.stop()
        appTracker.stop()
        notificationTracker.stop()
    }

    override suspend fun onDispose() {
        SynheartLogger.log("[PhoneModule] Disposing phone module...")

        motionCollector.dispose()
        screenTracker.dispose()
        appTracker.dispose()
        notificationTracker.dispose()

        scope.cancel()
    }
}
