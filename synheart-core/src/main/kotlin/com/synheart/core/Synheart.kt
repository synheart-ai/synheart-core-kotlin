package com.synheart.core

import android.content.Context
import com.synheart.core.models.*
import com.synheart.core.modules.base.ModuleManager
import com.synheart.core.modules.capabilities.CapabilityModule
import com.synheart.core.modules.consent.ConsentModule
import com.synheart.core.modules.consent.ConsentSnapshot
import com.synheart.core.modules.wear.WearModule
import com.synheart.core.modules.phone.PhoneModule
import com.synheart.core.modules.behavior.BehaviorModule
import com.synheart.core.modules.hsi_runtime.HSIRuntimeModule
import com.synheart.core.modules.hsi_runtime.ChannelCollector
import com.synheart.core.heads.EmotionHead
import com.synheart.core.heads.FocusHead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/**
 * Synheart Core SDK - Main Entry Point
 *
 * This is the main entry point for the Synheart Core SDK.
 * It orchestrates all core modules and optional interpretation modules.
 *
 * Core modules:
 * - Capabilities Module (feature gating)
 * - Consent Module (permission management)
 * - Wear Module (biosignal collection)
 * - Phone Module (motion/context)
 * - Behavior Module (interaction patterns)
 * - HSI Runtime (signal fusion & state computation)
 * - Cloud Connector (secure uploads)
 *
 * Optional interpretation modules:
 * - Emotion (affect modeling)
 * - Focus (engagement/focus estimation)
 *
 * Example usage:
 * ```kotlin
 * // Initialize
 * Synheart.initialize(
 *     context = context,
 *     userId = "anon_user_123",
 *     config = SynheartConfig(
 *         enableWear = true,
 *         enablePhone = true,
 *         enableBehavior = true
 *     )
 * )
 *
 * // Subscribe to HSI updates (core state representation)
 * Synheart.onHSIUpdate.collect { hsi ->
 *     println("Arousal Index: ${hsi.affect?.arousalIndex}")
 *     println("Engagement Stability: ${hsi.engagement?.engagementStability}")
 * }
 *
 * // Optional: Enable interpretation modules
 * Synheart.enableFocus()
 * Synheart.onFocusUpdate.collect { focus ->
 *     println("Focus Score: ${focus.score}")
 * }
 *
 * Synheart.enableEmotion()
 * Synheart.onEmotionUpdate.collect { emotion ->
 *     println("Stress Index: ${emotion.stress}")
 * }
 *
 * // Enable cloud upload (with consent)
 * Synheart.enableCloud()
 * ```
 */
object Synheart {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Module manager
    private val moduleManager = ModuleManager()

    // Core modules
    private var capabilityModule: CapabilityModule? = null
    private var consentModule: ConsentModule? = null
    private var wearModule: WearModule? = null
    private var phoneModule: PhoneModule? = null
    private var behaviorModule: BehaviorModule? = null
    private var hsiRuntimeModule: HSIRuntimeModule? = null
    // TODO: CloudConnectorModule
    // TODO: SyniHooksModule

    // Optional interpretation modules
    private var emotionHead: EmotionHead? = null
    private var focusHead: FocusHead? = null

    // State
    private var context: Context? = null
    private var isConfigured = false
    private var isRunning = false
    private var userId: String? = null

    // Streams
    private val _hsiFlow = MutableStateFlow<HumanStateVector?>(null)
    private val _emotionFlow = MutableStateFlow<EmotionState?>(null)
    private val _focusFlow = MutableStateFlow<FocusState?>(null)

    /**
     * Stream of HSI updates (core state representation)
     *
     * HSI contains:
     * - State axes (affect, engagement, activity, context)
     * - State indices (arousalIndex, engagementStability, etc.)
     * - 64D state embedding
     *
     * HSI does NOT contain interpretation (emotion, focus).
     */
    val onHSIUpdate: Flow<HumanStateVector> = _hsiFlow.asStateFlow().filterNotNull()

    /**
     * Stream of emotion updates (optional interpretation)
     *
     * Only emits if emotion module is enabled via enableEmotion().
     */
    val onEmotionUpdate: Flow<EmotionState> = _emotionFlow.asStateFlow().filterNotNull()

    /**
     * Stream of focus updates (optional interpretation)
     *
     * Only emits if focus module is enabled via enableFocus().
     */
    val onFocusUpdate: Flow<FocusState> = _focusFlow.asStateFlow().filterNotNull()

    /**
     * Initialize Synheart Core SDK
     *
     * This must be called before any other operations.
     *
     * Example:
     * ```kotlin
     * Synheart.initialize(
     *     context = context,
     *     userId = "anon_user_123",
     *     config = SynheartConfig(
     *         enableWear = true,
     *         enablePhone = true,
     *         enableBehavior = true
     *     )
     * )
     * ```
     */
    suspend fun initialize(
        context: Context,
        userId: String,
        config: SynheartConfig? = null,
        appKey: String = "mock_app_key"
    ) {
        if (isConfigured) {
            throw IllegalStateException("Synheart already configured")
        }

        this.context = context.applicationContext
        this.userId = userId

        try {
            println("[Synheart] Initializing...")

            // 1. Initialize capability module
            println("[Synheart] Initializing capability module...")
            capabilityModule = CapabilityModule()
            capabilityModule?.loadDefaults() // TODO: Load from token in production

            // 2. Initialize consent module
            println("[Synheart] Initializing consent module...")
            consentModule = ConsentModule()

            // 3. Register modules
            moduleManager.registerModule(capabilityModule!!)
            moduleManager.registerModule(consentModule!!)

            // 4. Initialize data collection modules
            println("[Synheart] Initializing data modules...")
            wearModule = WearModule(
                capabilities = capabilityModule!!,
                consent = consentModule!!
            )
            phoneModule = PhoneModule(
                capabilities = capabilityModule!!,
                consent = consentModule!!
            )
            behaviorModule = BehaviorModule(
                capabilities = capabilityModule!!,
                consent = consentModule!!
            )

            moduleManager.registerModule(wearModule!!, dependsOn = listOf("capabilities", "consent"))
            moduleManager.registerModule(phoneModule!!, dependsOn = listOf("capabilities", "consent"))
            moduleManager.registerModule(behaviorModule!!, dependsOn = listOf("capabilities", "consent"))

            // 5. Initialize HSI Runtime (NO emotion/focus here - they're optional)
            println("[Synheart] Initializing HSI Runtime...")
            val collector = ChannelCollector(
                wear = wearModule!!,
                phone = phoneModule!!,
                behavior = behaviorModule!!
            )
            hsiRuntimeModule = HSIRuntimeModule(collector = collector)
            moduleManager.registerModule(
                hsiRuntimeModule!!,
                dependsOn = listOf("wear", "phone", "behavior")
            )

            // 6. Initialize all modules
            println("[Synheart] Initializing all modules...")
            moduleManager.initializeAll()

            // 7. Subscribe to HSI stream (core state only)
            scope.launch {
                hsiRuntimeModule?.hsiFlow?.collect { hsi ->
                    _hsiFlow.value = hsi
                }
            }

            // 8. Start modules
            println("[Synheart] Starting all modules...")
            moduleManager.startAll()

            isConfigured = true
            isRunning = true
            println("[Synheart] Initialization complete")
        } catch (e: Exception) {
            println("[Synheart] Initialization failed: $e")
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Enable focus interpretation module
     *
     * This is an optional interpretation module that consumes HSI
     * and produces focus estimates.
     *
     * Example:
     * ```kotlin
     * Synheart.enableFocus()
     * Synheart.onFocusUpdate.collect { focus ->
     *     println("Focus Score: ${focus.score}")
     * }
     * ```
     */
    suspend fun enableFocus() {
        if (!isConfigured) {
            throw IllegalStateException("Synheart must be initialized before enabling focus")
        }

        if (focusHead != null) {
            println("[Synheart] Focus module already enabled")
            return
        }

        try {
            println("[Synheart] Enabling focus module...")

            focusHead = FocusHead()

            // Focus head subscribes to HSI stream
            scope.launch {
                onHSIUpdate.collect { hsi ->
                    val hsvWithFocus = focusHead?.processOne(hsi)
                    hsvWithFocus?.focus?.let { focus ->
                        _focusFlow.value = focus
                    }
                }
            }

            println("[Synheart] Focus module enabled")
        } catch (e: Exception) {
            println("[Synheart] Failed to enable focus: $e")
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Enable emotion interpretation module
     *
     * This is an optional interpretation module that consumes HSI
     * and produces emotion estimates.
     *
     * Example:
     * ```kotlin
     * Synheart.enableEmotion()
     * Synheart.onEmotionUpdate.collect { emotion ->
     *     println("Stress Index: ${emotion.stress}")
     * }
     * ```
     */
    suspend fun enableEmotion() {
        if (!isConfigured) {
            throw IllegalStateException("Synheart must be initialized before enabling emotion")
        }

        if (emotionHead != null) {
            println("[Synheart] Emotion module already enabled")
            return
        }

        try {
            println("[Synheart] Enabling emotion module...")

            emotionHead = EmotionHead()

            // Emotion head subscribes to HSI stream
            scope.launch {
                onHSIUpdate.collect { hsi ->
                    val hsvWithEmotion = emotionHead?.processOne(hsi)
                    hsvWithEmotion?.emotion?.let { emotion ->
                        _emotionFlow.value = emotion
                    }
                }
            }

            println("[Synheart] Emotion module enabled")
        } catch (e: Exception) {
            println("[Synheart] Failed to enable emotion: $e")
            e.printStackTrace()
            throw e
        }
    }

    /**
     * Enable cloud uploads (requires cloudUpload consent)
     *
     * Example:
     * ```kotlin
     * Synheart.enableCloud()
     * ```
     */
    suspend fun enableCloud() {
        // TODO: Implement cloud sync
        throw NotImplementedError("Cloud sync not yet implemented")
    }

    /**
     * Check if user has granted a specific consent
     *
     * Example:
     * ```kotlin
     * val hasConsent = Synheart.hasConsent("biosignals")
     * ```
     */
    suspend fun hasConsent(consentType: String): Boolean {
        if (consentModule == null) {
            return false
        }

        val consent = consentModule?.current() ?: return false
        return when (consentType) {
            "biosignals" -> consent.biosignals
            "behavior" -> consent.behavior
            "phoneContext", "motion" -> consent.motion
            "cloudUpload" -> consent.cloudUpload
            else -> false
        }
    }

    /**
     * Grant consent for a specific data type
     *
     * Example:
     * ```kotlin
     * Synheart.grantConsent("biosignals")
     * ```
     */
    suspend fun grantConsent(consentType: String) {
        if (consentModule == null) {
            throw IllegalStateException("Consent module not initialized")
        }

        val current = consentModule?.current() ?: ConsentSnapshot()
        val updated = when (consentType) {
            "biosignals" -> current.copy(biosignals = true)
            "behavior" -> current.copy(behavior = true)
            "motion", "phoneContext" -> current.copy(motion = true)
            "cloudUpload" -> current.copy(cloudUpload = true)
            "syni" -> current.copy(syni = true)
            else -> current
        }

        consentModule?.updateConsent(updated)
    }

    /**
     * Revoke consent for a specific data type
     *
     * Example:
     * ```kotlin
     * Synheart.revokeConsent("biosignals")
     * ```
     */
    suspend fun revokeConsent(consentType: String) {
        if (consentModule == null) {
            throw IllegalStateException("Consent module not initialized")
        }

        val current = consentModule?.current() ?: ConsentSnapshot()
        val updated = when (consentType) {
            "biosignals" -> current.copy(biosignals = false)
            "behavior" -> current.copy(behavior = false)
            "motion", "phoneContext" -> current.copy(motion = false)
            "cloudUpload" -> current.copy(cloudUpload = false)
            "syni" -> current.copy(syni = false)
            else -> current
        }

        consentModule?.updateConsent(updated)
    }

    /**
     * Get current HSI state (latest)
     */
    val currentState: HumanStateVector?
        get() = _hsiFlow.value

    /**
     * Get current consent snapshot
     */
    val currentConsent: ConsentSnapshot?
        get() = consentModule?.current()

    /**
     * Update consent
     */
    suspend fun updateConsent(consent: ConsentSnapshot) {
        if (consentModule == null) {
            throw IllegalStateException("Consent module not initialized")
        }
        consentModule?.updateConsent(consent)
    }

    /**
     * Stop Synheart Core SDK
     */
    suspend fun stop() {
        if (!isRunning) {
            return
        }

        try {
            println("[Synheart] Stopping...")
            moduleManager.stopAll()
            isRunning = false
            println("[Synheart] Stopped")
        } catch (e: Exception) {
            println("[Synheart] Stop failed: $e")
            e.printStackTrace()
        }
    }

    /**
     * Dispose all resources
     */
    suspend fun dispose() {
        try {
            stop()
            moduleManager.disposeAll()

            consentModule = null
            capabilityModule = null
            wearModule = null
            phoneModule = null
            behaviorModule = null
            hsiRuntimeModule = null
            emotionHead = null
            focusHead = null
            isConfigured = false
            isRunning = false

            println("[Synheart] Disposed")
        } catch (e: Exception) {
            println("[Synheart] Dispose failed: $e")
            e.printStackTrace()
        }
    }
}
