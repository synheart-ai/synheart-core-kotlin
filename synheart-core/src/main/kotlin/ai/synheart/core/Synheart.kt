package ai.synheart.core

import android.content.Context
import ai.synheart.core.config.ActivationManager
import ai.synheart.core.config.SynheartConfig
import ai.synheart.core.config.SynheartFeature
import ai.synheart.core.models.*
import ai.synheart.core.modules.base.ModuleManager
import ai.synheart.core.modules.capabilities.CapabilityModule
import ai.synheart.core.modules.consent.ConsentModule
import ai.synheart.core.modules.consent.ConsentStorage
import ai.synheart.core.modules.interfaces.CapabilityLevel
import ai.synheart.core.modules.interfaces.ConsentSnapshot
import ai.synheart.core.modules.interfaces.FeatureFlag
import ai.synheart.core.modules.interfaces.Module
import ai.synheart.core.modules.wear.WearModule
import ai.synheart.core.modules.phone.PhoneModule
import ai.synheart.core.modules.behavior.BehaviorModule
import ai.synheart.core.modules.runtime.RuntimeBridge
import ai.synheart.core.modules.runtime.RuntimeConfig
import ai.synheart.core.modules.runtime.RuntimeModule
import ai.synheart.core.artifacts.ArtifactPipeline
import ai.synheart.core.config.SynheartMode
import ai.synheart.core.crypto.SMK
import ai.synheart.core.models.HSIState
import ai.synheart.core.models.SessionHandle
import ai.synheart.core.storage.StorageManager
import ai.synheart.core.storage.StoragePolicy
import ai.synheart.core.storage.SessionRecord
import ai.synheart.core.storage.storagePolicyForMode
import ai.synheart.core.modules.platform_ingest.PlatformIngestClient
import ai.synheart.core.modules.platform_ingest.PlatformIngestModule
import ai.synheart.core.modules.platform_ingest.PlatformIngestResponse
import ai.synheart.core.modules.platform_ingest.PlatformPayloadBuilder
import ai.synheart.core.modules.interfaces.WindowType
import ai.synheart.core.modules.session.BehaviorModuleAdapter
import ai.synheart.core.modules.session.SessionModule
import ai.synheart.core.modules.session.WearModuleBiosignalAdapter
import ai.synheart.core.modules.wear.WearSample
import ai.synheart.session.SessionConfig
import ai.synheart.session.SessionMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
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
 *     config = SynheartConfig(
 *         appId = "com.example.app",
 *         subjectId = "anon_user_123"
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
    private var platformIngestModule: PlatformIngestModule? = null

    // Activation manager (RFC-0005 four-authority model)
    private var activationManager: ActivationManager? = null

    // State
    private var context: Context? = null
    private var isConfigured = false
    private var isRunning = false
    private var userId: String? = null
    private var previousConsent: ConsentSnapshot? = null

    // Phase 1: Storage and artifact pipeline
    private var storageManager: StorageManager? = null
    private var storagePolicy: StoragePolicy? = null
    private var artifactPipeline: ArtifactPipeline? = null
    private var smk: SMK? = null
    private var currentSessionHandle: SessionHandle? = null
    private var artifactHsiJob: Job? = null
    private var synheartConfig: SynheartConfig? = null

    // Phase 3: Auth & Sync
    private var authModule: ai.synheart.core.auth.AuthModule? = null
    private var syncModule: ai.synheart.core.sync.SyncModule? = null

    // Session module (wraps SessionEngine from synheart-session)
    private var sessionModule: SessionModule? = null
    private var activeMainSessionId: String? = null
    private var mainSessionJob: Job? = null
    private var hsiToSessionJob: Job? = null

    // Streams
    private val _hsiJsonFlow = MutableStateFlow<String?>(null)

    /** The currently active session, if any. */
    val currentSession: SessionHandle? get() = currentSessionHandle

    /**
     * Stream of HSI JSON updates produced by synheart-runtime.
     *
     * Each emission is a raw JSON string representing one HSI frame.
     * Returns non-null values only.
     */
    val onHSIUpdate: Flow<String> = _hsiJsonFlow.asStateFlow().filterNotNull()

    /** Stream of typed [HSIState] updates (RFC-CORE-0007 §3). */
    val onStateUpdate: Flow<HSIState> = _hsiJsonFlow.asStateFlow().filterNotNull()
        .map { HSIState.fromJson(it, subjectId = synheartConfig?.subjectId ?: userId ?: "") }

    /** Get the current HSI state as a typed object. */
    val currentHSIState: HSIState?
        get() {
            val json = _hsiJsonFlow.value ?: return null
            return HSIState.fromJson(json, subjectId = synheartConfig?.subjectId ?: userId ?: "")
        }

    // Phase 2: Metrics API

    /** Record a single metric event for the current session. */
    fun recordMetric(event: ai.synheart.core.models.MetricEvent) {
        val handle = currentSessionHandle ?: return
        if (storagePolicy?.canIncludeMetrics() != true) return
        try { storageManager?.insertMetric(handle.sessionId, event) } catch (_: Exception) {}
    }

    // Phase 2: Local Query API

    /** List stored sessions with optional filters. */
    fun listLocalSessions(range: ai.synheart.core.models.SessionRange? = null): List<SessionRecord> {
        val sm = storageManager ?: return emptyList()
        if (!sm.isOpen) return emptyList()
        val mode = range?.mode?.let { m ->
            SynheartMode.entries.find { it.value == m }
        }
        return sm.listSessions(subjectId = synheartConfig?.subjectId ?: userId ?: "")
    }

    /** Get a session summary (decrypted) for the given session. */
    fun getSessionSummary(sessionId: String): org.json.JSONObject? {
        val sm = storageManager ?: return null
        if (!sm.isOpen) return null

        val cached = sm.getSummaryJson(sessionId)
        if (cached != null) return try { org.json.JSONObject(cached) } catch (_: Exception) { null }

        val smk = this.smk ?: return null
        val artifacts = sm.getArtifactsBySession(sessionId, "session_summary")
        if (artifacts.isEmpty()) return null
        return try {
            val plaintext = ai.synheart.core.crypto.ArtifactCrypto.decrypt(smk, artifacts.first().payload)
            org.json.JSONObject(String(plaintext, Charsets.UTF_8))
        } catch (_: Exception) { null }
    }

    /** Get decrypted HSI window artifacts for a session. */
    fun getHSIWindows(sessionId: String, range: ai.synheart.core.models.WindowRange? = null): List<org.json.JSONObject> {
        val sm = storageManager ?: return emptyList()
        val smk = this.smk ?: return emptyList()
        if (!sm.isOpen) return emptyList()

        val artifacts = sm.getArtifactsBySession(sessionId, "hsi_window")
        val results = mutableListOf<org.json.JSONObject>()
        for (art in artifacts) {
            if (range?.startMs != null && art.startMs < range.startMs) continue
            if (range?.endMs != null && art.endMs > range.endMs) continue
            try {
                val plaintext = ai.synheart.core.crypto.ArtifactCrypto.decrypt(smk, art.payload)
                results.add(org.json.JSONObject(String(plaintext, Charsets.UTF_8)))
            } catch (_: Exception) {}
            if (range?.limit != null && results.size >= range.limit) break
        }
        return results
    }

    // Phase 2: Storage & Retention

    /** Get storage usage statistics. */
    fun getStorageUsage(): ai.synheart.core.models.StorageUsage {
        val sm = storageManager ?: return ai.synheart.core.models.StorageUsage(0, emptyMap())
        if (!sm.isOpen) return ai.synheart.core.models.StorageUsage(0, emptyMap())
        return sm.getStorageUsage()
    }

    /** Set retention policy. Deletes sessions older than the given number of days. */
    fun setRetentionDays(days: Int?) {
        if (days == null) return
        val sm = storageManager ?: return
        if (!sm.isOpen) return
        val cutoffMs = System.currentTimeMillis() - days.toLong() * 86400000
        sm.enforceRetention(cutoffMs)
    }

    // Phase 2: Deletion API

    /** Delete a session and all its artifacts locally. */
    fun deleteLocalSession(sessionId: String) {
        val sm = storageManager ?: return
        if (!sm.isOpen) return
        sm.deleteSession(sessionId, createTombstones = true)
    }

    /** Wipe all local data. */
    suspend fun wipeLocalData() {
        if (isRunning) stopSession()
        storageManager?.let { sm ->
            if (sm.isOpen) {
                sm.wipeAll()
                sm.close()
            }
        }
        storageManager = null
        context?.let { ai.synheart.core.crypto.SMK.delete(it) }
        context?.let { ai.synheart.core.crypto.URK.delete(it) }

        // Phase 3: Clear auth/sync state
        syncModule?.dispose()
        syncModule = null
        authModule?.logout()
        authModule = null

        artifactPipeline = null
        storagePolicy = null
        smk = null
        currentSessionHandle = null
    }

    /** Request account deletion — wipes local data and requests server-side deletion. */
    suspend fun requestAccountDeletion(): ai.synheart.core.models.DeletionRequestResult {
        // POST server-side deletion request if authenticated
        val auth = authModule
        if (auth != null && auth.isAuthenticated && auth.accessToken != null) {
            try {
                val conn = java.net.URL("https://api.synheart.ai/account/v1/delete")
                    .openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("Authorization", "Bearer ${auth.accessToken}")
                conn.doOutput = true
                val body = org.json.JSONObject().put("confirmation", "DELETE_MY_ACCOUNT")
                java.io.OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }
                conn.responseCode // trigger request
            } catch (_: Exception) {
                // Best-effort; still wipe locally
            }
        }
        wipeLocalData()
        return ai.synheart.core.models.DeletionRequestResult(
            status = "accepted",
            message = "Local data wiped. Server deletion pending."
        )
    }

    /** Cancel a pending account deletion request. */
    fun cancelAccountDeletion(): Boolean {
        val auth = authModule ?: return false
        if (!auth.isAuthenticated || auth.accessToken == null) return false
        return try {
            val conn = java.net.URL("https://api.synheart.ai/account/v1/delete/cancel")
                .openConnection() as java.net.HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Authorization", "Bearer ${auth.accessToken}")
            conn.doOutput = false
            conn.responseCode == 200
        } catch (_: Exception) {
            false
        }
    }

    // Phase 3: Auth API

    /** Authenticate with a provider token. */
    fun authenticate(provider: String, token: String): ai.synheart.core.auth.AuthResult {
        val auth = authModule ?: throw IllegalStateException("SDK not initialized")
        return auth.authenticate(provider, token)
    }

    /** Get current auth status. */
    val authStatus: ai.synheart.core.auth.AuthStatus?
        get() = authModule?.status

    /** Log out and clear auth state. */
    fun logout() {
        syncModule?.dispose()
        authModule?.logout()
        context?.let { ai.synheart.core.crypto.URK.delete(it) }
    }

    // Phase 3: Sync API

    /** Enable or disable sync. */
    fun setSyncEnabled(enabled: Boolean) {
        syncModule?.setSyncEnabled(enabled)
    }

    /** Execute a sync cycle (push + pull). */
    fun syncNow(): ai.synheart.core.sync.SyncResult {
        val sync = syncModule ?: return ai.synheart.core.sync.SyncResult()
        return sync.syncNow()
    }

    /** Get current sync status. */
    fun getSyncStatus(): ai.synheart.core.sync.SyncStatus {
        val sync = syncModule ?: return ai.synheart.core.sync.SyncStatus(enabled = false)
        return sync.getStatus()
    }

    /**
     * Returns the session module status, if a session is active.
     */
    fun getSessionStatus(): Map<String, Any>? =
        sessionModule?.getStatus()

    /**
     * Returns raw wear samples from the wear module cache for the given window.
     */
    fun getSessionWearSamples(): List<WearSample> =
        wearModule?.rawSamples(WindowType.WINDOW_1H) ?: emptyList()

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
     *     config = SynheartConfig(
     *         appId = "com.example.app",
     *         subjectId = "anon_user_123"
     *     )
     * )
     * ```
     */
    suspend fun initialize(
        context: Context,
        config: SynheartConfig? = null,
        userId: String? = null,
        autoStart: Boolean = false
    ) {
        if (isConfigured) {
            return // No-op if already initialized
        }

        if (config != null) {
            config.validate()
        }

        this.context = context.applicationContext
        this.userId = userId ?: config?.subjectId

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

            // SRM is handled by the native runtime (RuntimeBridge.exportSrmSnapshot /
            // loadSrmSnapshot). BaselineSnapshot artifacts are produced via ArtifactPipeline.

            // 5. Initialize Runtime Module (synheart-runtime C ABI bridge)
            //    RuntimeBridge is null when the native library is not bundled;
            //    the pipeline is then gracefully inert.
            SynheartLogger.log("[Synheart] Initializing Runtime Module...")
            val runtimeBridge = RuntimeBridge.createIfAvailable(
                RuntimeConfig(
                    subjectId = this.userId ?: "",
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

            // 7. Initialize Platform Ingest (optional, depends on config)
            val platformConfig = config?.platformIngestConfig
            if (platformConfig != null) {
                SynheartLogger.log("[Synheart] Initializing Platform Ingest...")
                platformIngestModule = PlatformIngestModule(
                    consentModule = consentModule!!,
                    config = platformConfig
                )
                moduleManager.registerModule(
                    platformIngestModule!!,
                    dependsOn = listOf("consent")
                )
            }

            // 9. Initialize all modules
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

            // 10b. Create SessionModule with adapted providers
            SynheartLogger.log("[Synheart] Initializing SessionModule...")
            val biosignalAdapter = WearModuleBiosignalAdapter(wearModule!!)
            val behaviorAdapter = BehaviorModuleAdapter(behaviorModule!!)
            sessionModule = SessionModule(
                biosignalProvider = biosignalAdapter,
                behaviorProvider = behaviorAdapter
            )

            // Bridge HSI metrics from runtime -> session engine (HRV is authoritative
            // from session-runtime; the session SDK no longer computes it locally).
            hsiToSessionJob = scope.launch {
                runtimeModule?.hsiFlow?.filterNotNull()?.collect { hsiJson ->
                    val sid = activeMainSessionId
                    if (sid == null || sessionModule == null) return@collect
                    try {
                        val parsed = org.json.JSONObject(hsiJson)
                        val metricsMap = mutableMapOf<String, Any>()
                        parsed.keys().forEach { key ->
                            parsed.opt(key)?.let { metricsMap[key] = it }
                        }
                        sessionModule?.ingestHsiMetrics(metricsMap)
                    } catch (_: Exception) {}
                }
            }
            SynheartLogger.log("[Synheart] SessionModule initialized")

            // 11. Create activation manager and auto-activate from config
            activationManager = ActivationManager()
            activationManager!!.activateFromConfig(resolvedConfig)

            // Phase 1: Initialize storage and artifact pipeline
            synheartConfig = resolvedConfig
            if (resolvedConfig.storage.enabled &&
                resolvedConfig.appId.isNotEmpty() &&
                resolvedConfig.subjectId.isNotEmpty()
            ) {
                try {
                    storagePolicy = storagePolicyForMode(resolvedConfig.mode)
                    smk = SMK.loadOrCreate(this.context!!)
                    storageManager = StorageManager.create(this.context!!)
                    storageManager!!.open()

                    artifactPipeline = ArtifactPipeline(
                        storage = storageManager!!,
                        policy = storagePolicy!!,
                        smk = smk!!,
                        subjectId = resolvedConfig.subjectId,
                        appId = resolvedConfig.appId,
                        appVersion = resolvedConfig.appVersion,
                        deviceId = resolvedConfig.deviceId,
                        platform = resolvedConfig.platform
                    )

                    // Wire HSI stream to artifact pipeline
                    artifactHsiJob = scope.launch {
                        runtimeModule?.hsiFlow?.filterNotNull()?.collect { hsiJson ->
                            if (currentSessionHandle == null) return@collect
                            val nowMs = System.currentTimeMillis()
                            try {
                                artifactPipeline?.ingestHsiFrame(hsiJson, nowMs)
                            } catch (_: Exception) {}
                        }
                    }

                    SynheartLogger.log("[Synheart] Storage and artifact pipeline initialized")
                } catch (e: Exception) {
                    SynheartLogger.log("[Synheart] Storage init failed (non-fatal): $e")
                }
            }

            // Phase 3: Initialize auth and sync modules
            val appId = resolvedConfig.appId
            if (appId.isNotEmpty() && this.context != null) {
                val tokenStorage = ai.synheart.core.auth.TokenStorage(this.context!!)
                authModule = ai.synheart.core.auth.AuthModule(
                    appId = appId,
                    tokenStorage = tokenStorage
                )
                authModule!!.restoreSession()

                if (storageManager?.isOpen == true) {
                    syncModule = ai.synheart.core.sync.SyncModule(
                        auth = authModule!!,
                        storage = storageManager!!,
                        smk = smk,
                        context = this.context!!,
                        baseUrl = "https://api.synheart.ai"
                    )
                }
                SynheartLogger.log("[Synheart] Auth and sync modules initialized")
            }

            isConfigured = true
            SynheartLogger.log("[Synheart] Initialization complete")

            // Auto-start session if requested
            if (autoStart) {
                startSession()
            }
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

        // Open main collection session via Session SDK (RFC: session boundary)
        val nowMs = System.currentTimeMillis()
        val sessionId = "core_$nowMs"
        val sessionConfig = SessionConfig(
            sessionId = sessionId,
            mode = SessionMode.FOCUS,
            durationSec = 86400 // default 24h — long-lived; stop explicitly
        )
        activeMainSessionId = sessionId
        mainSessionJob = scope.launch {
            try {
                sessionModule?.startSession(sessionConfig)?.collect { event ->
                    val eventType = event["type"] as? String
                    if (eventType == "session_summary" || eventType == "session_error") {
                        activeMainSessionId = null
                        if (isRunning) {
                            isRunning = false
                            reevaluateAllFeatures()
                            moduleManager.stopAll()
                            SynheartLogger.log(
                                "[Synheart] Main session ended (duration or stream closed)"
                            )
                        }
                    }
                }
            } catch (e: Exception) {
                SynheartLogger.log("[Synheart] Main session stream error: $e")
                activeMainSessionId = null
            }
        }

        // Phase 1: Create session record and start artifact pipeline
        val mode = synheartConfig?.mode ?: SynheartMode.PERSONAL

        if (storageManager?.isOpen == true) {
            try {
                storageManager!!.insertSession(SessionRecord(
                    sessionId = sessionId,
                    subjectId = synheartConfig?.subjectId ?: userId ?: "",
                    mode = mode.value,
                    createdAtUtc = nowMs / 1000,
                    startUtc = nowMs / 1000,
                    appId = synheartConfig?.appId ?: "",
                    appVersion = synheartConfig?.appVersion ?: "0.0.0",
                    deviceId = synheartConfig?.deviceId ?: "",
                    platform = synheartConfig?.platform ?: "android"
                ))
                artifactPipeline?.onSessionStart(sessionId, mode)
            } catch (e: Exception) {
                SynheartLogger.log("[Synheart] Session record creation failed: $e")
            }
        }
        currentSessionHandle = SessionHandle(sessionId = sessionId, startedAtMs = nowMs, mode = mode)

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

        // Close main collection session via Session SDK
        if (activeMainSessionId != null) {
            sessionModule?.stopSession(activeMainSessionId!!)
            mainSessionJob?.cancel()
            mainSessionJob = null
            activeMainSessionId = null
        }

        // Phase 1: Finalize session summary and baseline snapshot
        val handle = currentSessionHandle
        val pipeline = artifactPipeline
        if (handle != null && pipeline != null) {
            val nowMs = System.currentTimeMillis()

            // SessionSummary artifact
            try {
                pipeline.finalizeSession(handle.startedAtMs, nowMs)
                SynheartLogger.log("[Synheart] Session summary artifact created")
            } catch (e: Exception) {
                SynheartLogger.log("[Synheart] Session summary creation failed: $e")
            }

            // BaselineSnapshot from native SRM export
            try {
                val srmJson = runtimeModule?.bridge?.exportSrmSnapshot()
                if (srmJson != null) {
                    pipeline.produceBaselineSnapshot(srmJson)
                    SynheartLogger.log("[Synheart] Baseline snapshot artifact created")
                }
            } catch (e: Exception) {
                SynheartLogger.log("[Synheart] Baseline snapshot creation failed: $e")
            }
        }
        // Auto-ingest session to platform (opt-in)
        val piConfig = synheartConfig?.platformIngestConfig
        if (piConfig?.autoIngest == true && handle != null && platformIngestModule != null) {
            try {
                autoIngestSession(handle)
                SynheartLogger.log("[Synheart] Auto-ingest completed")
            } catch (e: Exception) {
                SynheartLogger.log("[Synheart] Auto-ingest failed: $e")
            }
        }

        currentSessionHandle = null

        // Auto-sync after session ends
        if (syncModule?.enabled == true) {
            try { syncModule?.syncNow() } catch (_: Exception) {}
        }

        isRunning = false
        reevaluateAllFeatures()
        moduleManager.stopAll()
        SynheartLogger.log("[Synheart] Session stopped")
    }



    // MARK: - Platform Ingestion

    /**
     * Auto-ingest a session payload built from SDK internal data.
     */
    private suspend fun autoIngestSession(session: SessionHandle) {
        val wearSamples = wearModule?.rawSamples(WindowType.WINDOW_1H) ?: emptyList()
        val behaviorEvents = behaviorModule?.rawEvents(WindowType.WINDOW_1H) ?: emptyList()
        val phoneDataPoints = phoneModule?.rawDataPoints(WindowType.WINDOW_1H) ?: emptyList()

        val payload = PlatformPayloadBuilder.buildSession(
            sessionId = session.sessionId,
            deviceId = synheartConfig?.deviceId ?: "",
            appId = synheartConfig?.appId ?: "",
            userId = synheartConfig?.subjectId ?: "",
            startedAtMs = session.startedAtMs,
            endedAtMs = System.currentTimeMillis(),
            dataOnCloud = syncModule?.enabled ?: false,
            wearSamples = wearSamples,
            behaviorEvents = behaviorEvents,
            phoneDataPoints = phoneDataPoints
        )
        platformIngestModule!!.ingestSession(payload)
    }

    /**
     * Ingest a session payload via the Platform Ingest module.
     *
     * Requires `behavior` consent.
     *
     * @throws IllegalStateException if SDK not initialized or platform ingest not configured
     */
    suspend fun ingestSession(payload: Map<String, Any?>): PlatformIngestResponse {
        if (!isConfigured) {
            throw IllegalStateException("Synheart must be initialized before ingesting sessions")
        }
        val module = platformIngestModule
            ?: throw IllegalStateException("Platform ingest not configured")
        return module.ingestSession(payload)
    }

    /**
     * Ingest a metadata payload via the Platform Ingest module.
     *
     * Requires `biosignals` consent.
     *
     * @throws IllegalStateException if SDK not initialized or platform ingest not configured
     */
    suspend fun ingestMetadata(payload: Map<String, Any?>): PlatformIngestResponse {
        if (!isConfigured) {
            throw IllegalStateException("Synheart must be initialized before ingesting metadata")
        }
        val module = platformIngestModule
            ?: throw IllegalStateException("Platform ingest not configured")
        return module.ingestMetadata(payload)
    }

    /**
     * Get the underlying PlatformIngestClient for standalone/background usage.
     *
     * Returns null if platform ingest is not configured.
     */
    val platformIngestClient: PlatformIngestClient?
        get() = platformIngestModule?.client

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
                if (isOperational && wearModule?.status != ai.synheart.core.modules.base.ModuleStatus.RUNNING) {
                    scope.launch { try { wearModule?.start() } catch (e: Exception) { SynheartLogger.log("[Synheart] Failed to start wear module: $e") } }
                } else if (!isOperational && wearModule?.status == ai.synheart.core.modules.base.ModuleStatus.RUNNING) {
                    scope.launch { try { wearModule?.stop() } catch (e: Exception) { SynheartLogger.log("[Synheart] Failed to stop wear module: $e") } }
                }
            }
            SynheartFeature.BEHAVIOR -> {
                if (isOperational && behaviorModule?.status != ai.synheart.core.modules.base.ModuleStatus.RUNNING) {
                    scope.launch { try { behaviorModule?.start() } catch (e: Exception) { SynheartLogger.log("[Synheart] Failed to start behavior module: $e") } }
                } else if (!isOperational && behaviorModule?.status == ai.synheart.core.modules.base.ModuleStatus.RUNNING) {
                    scope.launch { try { behaviorModule?.stop() } catch (e: Exception) { SynheartLogger.log("[Synheart] Failed to stop behavior module: $e") } }
                }
            }
            SynheartFeature.PHONE_CONTEXT -> {
                if (isOperational && phoneModule?.status != ai.synheart.core.modules.base.ModuleStatus.RUNNING) {
                    scope.launch { try { phoneModule?.start() } catch (e: Exception) { SynheartLogger.log("[Synheart] Failed to start phone module: $e") } }
                } else if (!isOperational && phoneModule?.status == ai.synheart.core.modules.base.ModuleStatus.RUNNING) {
                    scope.launch { try { phoneModule?.stop() } catch (e: Exception) { SynheartLogger.log("[Synheart] Failed to stop phone module: $e") } }
                }
            }
            SynheartFeature.CLOUD -> { }
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

            mainSessionJob?.cancel()
            mainSessionJob = null
            hsiToSessionJob?.cancel()
            hsiToSessionJob = null
            activeMainSessionId = null
            sessionModule = null

            // Phase 1: Clean up storage and artifact pipeline
            artifactHsiJob?.cancel()
            artifactHsiJob = null
            storageManager?.close()
            storageManager = null
            artifactPipeline = null
            storagePolicy = null
            smk = null
            currentSessionHandle = null
            synheartConfig = null

            // Phase 3
            syncModule?.dispose()
            syncModule = null
            authModule?.logout()
            authModule = null

            consentModule = null
            capabilityModule = null
            wearModule = null
            phoneModule = null
            behaviorModule = null
            runtimeModule = null
            platformIngestModule = null
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
