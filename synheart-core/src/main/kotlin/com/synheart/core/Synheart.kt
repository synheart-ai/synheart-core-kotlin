package com.synheart.core

import android.content.Context
import com.synheart.core.config.ActivationManager
import com.synheart.core.config.SynheartConfig
import com.synheart.core.config.SynheartFeature
import com.synheart.core.models.*
import com.synheart.core.modules.base.ModuleManager
import com.synheart.core.modules.capabilities.CapabilityModule
import com.synheart.core.modules.consent.ConsentModule
import com.synheart.core.modules.consent.ConsentStorage
import com.synheart.core.modules.interfaces.CapabilityLevel
import com.synheart.core.modules.interfaces.ConsentSnapshot
import com.synheart.core.modules.interfaces.FeatureFlag
import com.synheart.core.modules.interfaces.Module
import com.synheart.core.modules.wear.WearModule
import com.synheart.core.modules.phone.PhoneModule
import com.synheart.core.modules.behavior.BehaviorModule
import com.synheart.core.modules.runtime.RuntimeBridge
import com.synheart.core.modules.runtime.RuntimeConfig
import com.synheart.core.modules.runtime.RuntimeModule
import com.synheart.core.modules.srm.SRMModule
import com.synheart.core.modules.srm.SRMSnapshotStorage
import com.synheart.core.modules.cloud.CloudConnectorModule
import com.synheart.core.modules.cloud.ConsentRequiredError
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
 * - Runtime (synheart-runtime C ABI bridge for signal fusion & HSI production)
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
 * // Subscribe to HSI JSON updates from synheart-runtime
 * Synheart.onHSIUpdate.collect { hsiJson ->
 *     SynheartLogger.log("HSI frame: $hsiJson")
 * }
 *
 * // Optional: Enable interpretation modules
 * Synheart.enableFocus()
 * Synheart.onFocusUpdate.collect { focus ->
 *     SynheartLogger.log("Focus Score: ${focus.score}")
 * }
 *
 * Synheart.enableEmotion()
 * Synheart.onEmotionUpdate.collect { emotion ->
 *     SynheartLogger.log("Stress Index: ${emotion.stress}")
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
    private var runtimeModule: RuntimeModule? = null
    private var srmModule: SRMModule? = null
    private var cloudConnector: CloudConnectorModule? = null

    // Optional interpretation modules
    private var emotionHead: EmotionHead? = null
    private var focusHead: FocusHead? = null

    // Activation manager (RFC-0005 four-authority model)
    private var activationManager: ActivationManager? = null

    // State
    private var context: Context? = null
    private var isConfigured = false
    private var isRunning = false
    private var userId: String? = null
    private var previousConsent: ConsentSnapshot? = null

    // Streams
    private val _hsiJsonFlow = MutableStateFlow<String?>(null)
    private val _emotionFlow = MutableStateFlow<EmotionState?>(null)
    private val _focusFlow = MutableStateFlow<FocusState?>(null)

    /**
     * Stream of HSI JSON updates produced by synheart-runtime.
     *
     * Each emission is a raw JSON string representing one HSI frame.
     * Returns non-null values only.
     */
    val onHSIUpdate: Flow<String> = _hsiJsonFlow.asStateFlow().filterNotNull()

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

    // Activation API (RFC-0005)

    /** Activate a feature. If all four authorities are satisfied, the feature's module starts. */
    fun activate(feature: SynheartFeature) {
        activationManager?.activate(feature)
        reevaluateFeature(feature)
    }

    /** Deactivate a feature. Stops the feature's module if running. */
    fun deactivate(feature: SynheartFeature) {
        activationManager?.deactivate(feature)
        reevaluateFeature(feature)
    }

    /** Check whether a feature is currently activated by the developer. */
    fun isActivated(feature: SynheartFeature): Boolean {
        return activationManager?.isActivated(feature) ?: false
    }

    /** Return the set of all currently activated features. */
    fun activatedFeatures(): Set<SynheartFeature> {
        return activationManager?.activatedFeatures() ?: emptySet()
    }

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
            SynheartLogger.log("[Synheart] Initializing...")

            // 1. Initialize capability module with token validation
            SynheartLogger.log("[Synheart] Initializing capability module...")
            capabilityModule = CapabilityModule()
            val resolvedConfig = config ?: SynheartConfig()
            if (resolvedConfig.capabilityToken != null && resolvedConfig.capabilitySecret != null) {
                capabilityModule!!.loadFromToken(resolvedConfig.capabilityToken, resolvedConfig.capabilitySecret)
            } else if (resolvedConfig.allowUnsignedCapabilities) {
                SynheartLogger.log("[Synheart] WARNING: Running with unsigned default capabilities. Do not use in production.")
                capabilityModule!!.loadDefaults()
            } else {
                throw IllegalStateException("Capability token and secret are required. Set allowUnsignedCapabilities=true for debug/testing.")
            }

            // 2. Initialize consent module
            SynheartLogger.log("[Synheart] Initializing consent module...")
            val consentStorage = ConsentStorage(context = this.context!!)
            consentModule = ConsentModule(storage = consentStorage)

            // 3. Register modules
            moduleManager.registerModule(capabilityModule!!)
            moduleManager.registerModule(consentModule!!)

            // 4. Initialize data collection modules
            SynheartLogger.log("[Synheart] Initializing data modules...")
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

            // 5. Initialize SRM (personal reference model)
            SynheartLogger.log("[Synheart] Initializing SRM...")
            srmModule = SRMModule(storage = SRMSnapshotStorage(this.context!!))
            moduleManager.registerModule(
                srmModule!!,
                dependsOn = listOf("capabilities", "consent")
            )

            // 6. Initialize Runtime Module (synheart-runtime C ABI bridge)
            //    RuntimeBridge is null when the native library is not bundled;
            //    the pipeline is then gracefully inert.
            SynheartLogger.log("[Synheart] Initializing Runtime Module...")
            val runtimeBridge = RuntimeBridge.createIfAvailable(
                RuntimeConfig(
                    subjectId = userId,
                    sessionId = java.util.UUID.randomUUID().toString()
                )
            )
            runtimeModule = RuntimeModule(
                bridge = runtimeBridge,
                wearModule = wearModule,
                behaviorModule = behaviorModule
            )
            moduleManager.registerModule(
                runtimeModule!!,
                dependsOn = listOf("wear", "behavior")
            )

            // 7. Initialize Cloud Connector (optional, depends on config)
            if (config?.cloudConfig != null) {
                SynheartLogger.log("[Synheart] Initializing Cloud Connector...")
                cloudConnector = CloudConnectorModule(
                    context = this.context,
                    capabilities = capabilityModule!!,
                    consent = consentModule!!,
                    runtimeModule = runtimeModule!!,
                    config = config.cloudConfig!!
                )
                moduleManager.registerModule(
                    cloudConnector!!,
                    dependsOn = listOf("capabilities", "consent", "runtime")
                )
            }

            // 8. Initialize all modules
            SynheartLogger.log("[Synheart] Initializing all modules...")
            moduleManager.initializeAll()

            // 9. Register consent change listener
            previousConsent = consentModule!!.current()
            consentModule!!.addListener { newConsent ->
                handleConsentChange(newConsent)
            }

            // 10. Subscribe to HSI stream from RuntimeModule (consent-gated)
            scope.launch {
                runtimeModule?.hsiFlow?.collect { hsiJson ->
                    if (consentModule?.current()?.biosignals != true) return@collect
                    _hsiJsonFlow.value = hsiJson
                }
            }

            // 11. Create activation manager and auto-activate from config
            activationManager = ActivationManager()
            activationManager!!.activateFromConfig(resolvedConfig)

            isConfigured = true
            // Modules are initialized but NOT started.
            // Call startSession() to begin data collection.
            // Per RFC §5.1: initialize() must NOT start collecting signals.
            SynheartLogger.log("[Synheart] Initialization complete")
        } catch (e: Exception) {
            SynheartLogger.log("[Synheart] Initialization failed: $e")
            e.printStackTrace()
            throw e
        }
    }

    // MARK: - Session Lifecycle

    /**
     * Start a session — activates permitted modules and begins signal collection.
     *
     * Per RFC §5.2: Core must activate permitted modules, route normalized
     * signals to synheart-runtime, enable HSV updates, and enable optional HSI export.
     *
     * Must be called after initialize(). No data collection occurs until
     * this method is called (RFC §3.3).
     */
    suspend fun startSession() {
        if (!isConfigured) {
            throw IllegalStateException("Synheart must be initialized before starting session")
        }
        if (isRunning) {
            return // Already running
        }

        SynheartLogger.log("[Synheart] Starting session...")
        moduleManager.startAll()
        isRunning = true
        reevaluateAllFeatures()
        SynheartLogger.log("[Synheart] Session started")
    }

    /**
     * Stop the current session — halts module streaming and clears ephemeral buffers.
     *
     * Per RFC §5.2: Core must halt module streaming, stop synheart-runtime updates,
     * clear ephemeral buffers, and prevent further HSI export.
     */
    suspend fun stopSession() {
        if (!isRunning) {
            return
        }

        SynheartLogger.log("[Synheart] Stopping session...")
        isRunning = false
        reevaluateAllFeatures()
        moduleManager.stopAll()
        SynheartLogger.log("[Synheart] Session stopped")
    }


    /**
     * Force upload of queued snapshots now
     *
     * @throws ConsentRequiredError if cloudUpload consent not granted
     */
    suspend fun uploadNow() {
        if (!isConfigured) {
            throw IllegalStateException("Synheart must be initialized before uploading")
        }

        if (cloudConnector == null) {
            throw IllegalStateException("Cloud connector not enabled")
        }

        cloudConnector?.uploadNow()
    }

    /**
     * Flush entire upload queue
     *
     * Attempts to upload all queued snapshots while online.
     */
    suspend fun flushUploadQueue() {
        if (!isConfigured) {
            throw IllegalStateException("Synheart must be initialized before flushing queue")
        }

        if (cloudConnector == null) {
            throw IllegalStateException("Cloud connector not enabled")
        }

        cloudConnector?.flushQueue()
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
            "phoneContext", "motion" -> consent.phoneContext
            "cloudUpload" -> consent.cloudUpload
            "focusEstimation" -> consent.focusEstimation
            "emotionEstimation" -> consent.emotionEstimation
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

        val current = consentModule?.current() ?: ConsentSnapshot.none()
        val updated = when (consentType) {
            "biosignals" -> current.copy(biosignals = true)
            "behavior" -> current.copy(behavior = true)
            "motion", "phoneContext" -> current.copy(phoneContext = true)
            "cloudUpload" -> current.copy(cloudUpload = true)
            "focusEstimation" -> current.copy(focusEstimation = true)
            "emotionEstimation" -> current.copy(emotionEstimation = true)
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

        val current = consentModule?.current() ?: ConsentSnapshot.none()
        val updated = when (consentType) {
            "biosignals" -> current.copy(biosignals = false)
            "behavior" -> current.copy(behavior = false)
            "motion", "phoneContext" -> current.copy(phoneContext = false)
            "cloudUpload" -> current.copy(cloudUpload = false)
            "focusEstimation" -> current.copy(focusEstimation = false)
            "emotionEstimation" -> current.copy(emotionEstimation = false)
            "syni" -> current.copy(syni = false)
            else -> current
        }

        consentModule?.updateConsent(updated)
    }

    /**
     * Get current HSI JSON state (latest)
     */
    val currentState: String?
        get() = _hsiJsonFlow.value

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

    // MARK: - synheart-runtime SRM API (baselines live in the native Rust engine)

    /**
     * Get baseline summary from the native synheart-runtime (if available).
     *
     * Returns a JSON string like `{"total":14,"ready":0,"warming":5,"empty":9}`
     * or `null` if the native runtime is not linked.
     */
    val runtimeBaselineSummary: String?
        get() = runtimeModule?.bridge?.baselineSummary()

    /**
     * Get all native runtime baselines as JSON, or `null`.
     */
    val runtimeBaselinesJson: String?
        get() = runtimeModule?.bridge?.baselinesJson()

    /**
     * Export the native runtime SRM snapshot as JSON for cross-session persistence.
     */
    fun exportRuntimeSRMSnapshot(): String? {
        return runtimeModule?.bridge?.exportSrmSnapshot()
    }

    /**
     * Load a native runtime SRM snapshot from JSON.
     * Returns 0 on success, non-zero error code on failure, or `null` if runtime unavailable.
     */
    fun loadRuntimeSRMSnapshot(json: String): Int? {
        return runtimeModule?.bridge?.loadSrmSnapshot(json)
    }

    /**
     * Get the native synheart-runtime version, or `null` if unavailable.
     */
    val runtimeVersion: String?
        get() = RuntimeBridge.version()

    // Consent Change Handling

    private fun handleConsentChange(newConsent: ConsentSnapshot) {
        previousConsent = newConsent
        reevaluateAllFeatures()
    }

    // Feature Reevaluation (RFC-0005 Four-Authority Model)

    /**
     * Reevaluate whether a single feature should be operational.
     *
     * isOperational = activated AND hasConsent AND capabilityAllowed AND isRunning
     */
    private fun reevaluateFeature(feature: SynheartFeature) {
        val activated = activationManager?.isActivated(feature) ?: false
        val hasConsent = hasConsentForFeature(feature)
        val capabilityAllowed = isCapabilityAllowed(feature)
        val isOperational = activated && hasConsent && capabilityAllowed && isRunning

        when (feature) {
            SynheartFeature.WEAR -> {
                if (isOperational && wearModule?.status != com.synheart.core.modules.base.ModuleStatus.RUNNING) {
                    scope.launch { try { wearModule?.start() } catch (e: Exception) { SynheartLogger.log("[Synheart] Failed to start wear module: $e") } }
                } else if (!isOperational && wearModule?.status == com.synheart.core.modules.base.ModuleStatus.RUNNING) {
                    scope.launch { try { wearModule?.stop() } catch (e: Exception) { SynheartLogger.log("[Synheart] Failed to stop wear module: $e") } }
                }
            }
            SynheartFeature.BEHAVIOR -> {
                if (isOperational && behaviorModule?.status != com.synheart.core.modules.base.ModuleStatus.RUNNING) {
                    scope.launch { try { behaviorModule?.start() } catch (e: Exception) { SynheartLogger.log("[Synheart] Failed to start behavior module: $e") } }
                } else if (!isOperational && behaviorModule?.status == com.synheart.core.modules.base.ModuleStatus.RUNNING) {
                    scope.launch { try { behaviorModule?.stop() } catch (e: Exception) { SynheartLogger.log("[Synheart] Failed to stop behavior module: $e") } }
                }
            }
            SynheartFeature.PHONE_CONTEXT -> {
                if (isOperational && phoneModule?.status != com.synheart.core.modules.base.ModuleStatus.RUNNING) {
                    scope.launch { try { phoneModule?.start() } catch (e: Exception) { SynheartLogger.log("[Synheart] Failed to start phone module: $e") } }
                } else if (!isOperational && phoneModule?.status == com.synheart.core.modules.base.ModuleStatus.RUNNING) {
                    scope.launch { try { phoneModule?.stop() } catch (e: Exception) { SynheartLogger.log("[Synheart] Failed to stop phone module: $e") } }
                }
            }
            SynheartFeature.FOCUS -> {
                if (isOperational && focusHead == null) {
                    focusHead = FocusHead()
                } else if (!isOperational && focusHead != null) {
                    focusHead = null
                    _focusFlow.value = null
                }
            }
            SynheartFeature.EMOTION -> {
                if (isOperational && emotionHead == null) {
                    emotionHead = EmotionHead()
                } else if (!isOperational && emotionHead != null) {
                    emotionHead = null
                    _emotionFlow.value = null
                }
            }
            SynheartFeature.CLOUD -> {
                if (isOperational && cloudConnector != null) {
                    scope.launch { try { cloudConnector?.start() } catch (e: Exception) { SynheartLogger.log("[Synheart] Failed to start cloud connector: $e") } }
                } else if (!isOperational && cloudConnector != null) {
                    scope.launch { try { cloudConnector?.stop() } catch (e: Exception) { SynheartLogger.log("[Synheart] Failed to stop cloud connector: $e") } }
                }
            }
            SynheartFeature.SYNI -> { }
        }
    }

    /** Reevaluate all features (e.g. after consent change or session start/stop). */
    private fun reevaluateAllFeatures() {
        for (feature in SynheartFeature.entries) {
            reevaluateFeature(feature)
        }
    }

    /** Check consent for a feature's required consent type. */
    private fun hasConsentForFeature(feature: SynheartFeature): Boolean {
        val consent = consentModule?.current() ?: return false
        return when (feature.requiredConsent) {
            "biosignals" -> consent.biosignals
            "behavior" -> consent.behavior
            "motion" -> consent.phoneContext
            "cloudUpload" -> consent.cloudUpload
            "syni" -> consent.syni
            else -> false
        }
    }

    /** Check whether the CapabilityModule allows a given feature. */
    private fun isCapabilityAllowed(feature: SynheartFeature): Boolean {
        val cap = capabilityModule ?: return false
        return when (feature) {
            SynheartFeature.WEAR -> cap.capability(Module.WEAR) != CapabilityLevel.NONE
            SynheartFeature.BEHAVIOR -> cap.capability(Module.BEHAVIOR) != CapabilityLevel.NONE
            SynheartFeature.PHONE_CONTEXT -> cap.capability(Module.PHONE) != CapabilityLevel.NONE
            SynheartFeature.FOCUS -> cap.isEnabled(FeatureFlag.HSI_EMOTION_FOCUS)
            SynheartFeature.EMOTION -> cap.isEnabled(FeatureFlag.HSI_EMOTION_FOCUS)
            SynheartFeature.CLOUD -> cap.capability(Module.CLOUD) != CapabilityLevel.NONE
            SynheartFeature.SYNI -> true // no capability gate for syni yet
        }
    }

    /**
     * Stop Synheart Core SDK
     */
    suspend fun stop() {
        stopSession()
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
            runtimeModule = null
            srmModule = null
            cloudConnector = null
            emotionHead = null
            focusHead = null
            activationManager = null
            previousConsent = null
            isConfigured = false
            isRunning = false

            SynheartLogger.log("[Synheart] Disposed")
        } catch (e: Exception) {
            SynheartLogger.log("[Synheart] Dispose failed: $e")
            e.printStackTrace()
        }
    }
}
