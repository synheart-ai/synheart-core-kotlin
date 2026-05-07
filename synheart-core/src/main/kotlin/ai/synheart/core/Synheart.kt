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
import ai.synheart.core.modules.interfaces.Module
import ai.synheart.core.modules.wear.WearModule
import ai.synheart.core.modules.phone.PhoneModule
import ai.synheart.core.modules.behavior.BehaviorModule
import ai.synheart.core.bridge.CoreRuntimeBridge
import ai.synheart.core.config.SynheartMode
import ai.synheart.core.storage.SessionRecord
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
 * - Runtime (synheart-engine C ABI bridge for signal fusion & HSI production)
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
 * // Activate modules
 * Synheart.activate(SynheartFeature.WEAR)
 * Synheart.activate(SynheartFeature.BEHAVIOR)
 *
 * // Subscribe to HSI JSON updates from the runtime
 * Synheart.onHSIUpdate.collect { hsiJson ->
 *     SynheartLogger.log("HSI frame: $hsiJson")
 * }
 *
 * // Or use the typed projection
 * Synheart.onStateUpdate.collect { state ->
 *     SynheartLogger.log("HSI state: $state")
 * }
 * ```
 */
object Synheart {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var coreRuntime: CoreRuntimeBridge? = null
    private val moduleManager = ModuleManager()
    private var capabilityModule: CapabilityModule? = null
    private var consentModule: ConsentModule? = null
    private var wearModule: WearModule? = null
    private var phoneModule: PhoneModule? = null
    private var behaviorModule: BehaviorModule? = null

    private var activationManager: ActivationManager? = null

    private var context: Context? = null
    private var isConfigured = false
    private var isRunning = false
    private var userId: String? = null
    private var previousConsent: ConsentSnapshot? = null

    private var currentSessionHandle: SessionHandle? = null
    private var synheartConfig: SynheartConfig? = null

    private var sessionModule: SessionModule? = null
    private var activeMainSessionId: String? = null
    private var mainSessionJob: Job? = null
    private var hsiToSessionJob: Job? = null

    private val _hsiJsonFlow = MutableStateFlow<String?>(null)

    /** The currently active session, if any. */
    val currentSession: SessionHandle? get() = currentSessionHandle

    /**
     * Stream of HSI JSON updates produced by synheart-engine.
     *
     * Each emission is a raw JSON string representing one HSI frame.
     * Returns non-null values only.
     */
    val onHSIUpdate: Flow<String> = _hsiJsonFlow.asStateFlow().filterNotNull()

    /** Stream of typed [HSIState] updates. */
    val onStateUpdate: Flow<HSIState> = _hsiJsonFlow.asStateFlow().filterNotNull()
        .map { HSIState.fromJson(it, subjectId = synheartConfig?.subjectId ?: userId ?: "") }

    /** Get the current HSI state as a typed object. */
    val currentHSIState: HSIState?
        get() {
            val json = _hsiJsonFlow.value ?: return null
            return HSIState.fromJson(json, subjectId = synheartConfig?.subjectId ?: userId ?: "")
        }

    /** Record a single metric event for the current session. */
    fun recordMetric(event: ai.synheart.core.models.MetricEvent) {
        val cr = coreRuntime ?: return
        try {
            val json = org.json.JSONObject().apply {
                put("name", event.name)
                put("value", event.value)
                put("timestamp_ms", event.timestampMs)
                event.tags?.let { t -> if (t.isNotEmpty()) put("tags", org.json.JSONObject(t as Map<*, *>)) }
            }.toString()
            cr.recordMetric(json)
        } catch (_: Exception) {}
    }

    /**
     * Record a batch of metric events for the current session.
     *
     * Mirrors the Flutter SDK's `recordMetrics(List<MetricEvent>)`.
     */
    fun recordMetrics(events: List<ai.synheart.core.models.MetricEvent>) {
        for (event in events) recordMetric(event)
    }

    /**
     * List stored sessions with optional filters. Mirrors the Flutter SDK's
     * `listSessions({SessionRange? range})`.
     */
    fun listSessions(range: ai.synheart.core.models.SessionRange? = null): List<SessionRecord> =
        listLocalSessions(range)

    /** @deprecated Use [listSessions] for Flutter SDK parity. */
    @Deprecated("Use listSessions() for Flutter SDK parity", ReplaceWith("listSessions(range)"))
    fun listLocalSessions(range: ai.synheart.core.models.SessionRange? = null): List<SessionRecord> {
        val cr = coreRuntime ?: return emptyList()
        val json = cr.listSessions() ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            val records = mutableListOf<SessionRecord>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                records.add(SessionRecord(
                    sessionId = obj.optString("session_id", ""),
                    subjectId = obj.optString("subject_id", ""),
                    mode = obj.optString("mode", "personal"),
                    createdAtUtc = obj.optLong("created_at_utc", 0),
                    startUtc = obj.optLong("start_utc", 0),
                    appId = obj.optString("app_id", ""),
                    appVersion = obj.optString("app_version", "0.0.0"),
                    deviceId = obj.optString("device_id", ""),
                    platform = obj.optString("platform", "android")
                ))
            }
            records
        } catch (_: Exception) { emptyList() }
    }

    /** Get a session summary (decrypted) for the given session. */
    fun getSessionSummary(sessionId: String): org.json.JSONObject? {
        val cr = coreRuntime ?: return null
        val json = cr.getSessionSummary(sessionId) ?: return null
        return try { org.json.JSONObject(json) } catch (_: Exception) { null }
    }

    /** Get decrypted HSI window artifacts for a session. */
    fun getHSIWindows(sessionId: String, range: ai.synheart.core.models.WindowRange? = null): List<org.json.JSONObject> {
        val cr = coreRuntime ?: return emptyList()
        val json = cr.getHsiWindows(
            sessionId,
            startMs = range?.startMs ?: 0,
            endMs = range?.endMs ?: 0,
            limit = range?.limit ?: 0
        ) ?: return emptyList()
        return try {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (_: Exception) { emptyList() }
    }

    /** Get storage usage statistics. */
    fun getStorageUsage(): ai.synheart.core.models.StorageUsage {
        val cr = coreRuntime ?: return ai.synheart.core.models.StorageUsage(0, emptyMap())
        val json = cr.getStorageUsage() ?: return ai.synheart.core.models.StorageUsage(0, emptyMap())
        return try {
            val obj = org.json.JSONObject(json)
            val totalBytes = obj.optLong("total_bytes", 0)
            val bySession = mutableMapOf<String, Long>()
            obj.optJSONObject("by_session_bytes")?.let { bs ->
                bs.keys().forEach { key -> bySession[key] = bs.optLong(key, 0) }
            }
            ai.synheart.core.models.StorageUsage(totalBytes, bySession)
        } catch (_: Exception) { ai.synheart.core.models.StorageUsage(0, emptyMap()) }
    }

    /** Set retention policy. Deletes sessions older than the given number of days. */
    fun setRetentionDays(days: Int?) {
        if (days == null) return
        coreRuntime?.setRetentionDays(days)
    }

    /** Delete a session and all its artifacts locally. */
    fun deleteLocalSession(sessionId: String) {
        coreRuntime?.deleteSession(sessionId)
    }

    /** Wipe all local data. */
    suspend fun wipeLocalData() {
        if (isRunning) stopSession()
        coreRuntime?.wipeLocalData()
        currentSessionHandle = null
        SynheartLogger.log("[Synheart] Local data wiped via CoreRuntimeBridge")
    }

    /** Wipe all local data. Alias of [wipeLocalData] matching the Flutter SDK. */
    suspend fun deleteLocalData() = wipeLocalData()

    /** Request account deletion -- wipes local data and requests server-side deletion. */
    suspend fun requestAccountDeletion(): ai.synheart.core.models.DeletionRequestResult {
        coreRuntime?.requestAccountDeletion()
        wipeLocalData()
        return ai.synheart.core.models.DeletionRequestResult(
            status = "accepted",
            message = "Account deletion requested via CoreRuntimeBridge. Local data wiped."
        )
    }

    /** Cancel a pending account deletion request. */
    fun cancelAccountDeletion(): Boolean {
        return coreRuntime?.cancelAccountDeletion() ?: false
    }

    /** Log out -- revoke consent and clear credentials. */
    fun logout() {
        try { consentModule?.revokeConsent() } catch (_: Exception) {}
    }

    /** Enable or disable sync. */
    fun setSyncEnabled(enabled: Boolean) {
        coreRuntime?.setSyncEnabled(enabled)
    }

    /** Execute a sync cycle (push + pull). */
    fun syncNow(): ai.synheart.core.sync.SyncResult {
        val cr = coreRuntime ?: return ai.synheart.core.sync.SyncResult()
        val json = cr.syncNow() ?: return ai.synheart.core.sync.SyncResult()
        return try {
            val obj = org.json.JSONObject(json)
            val errorsList = mutableListOf<String>()
            obj.optJSONArray("errors")?.let { arr ->
                for (i in 0 until arr.length()) errorsList.add(arr.optString(i, ""))
            }
            ai.synheart.core.sync.SyncResult(
                pushed = obj.optInt("pushed", 0),
                pulled = obj.optInt("pulled", 0),
                conflictsResolved = obj.optInt("conflicts_resolved", 0),
                errors = errorsList
            )
        } catch (_: Exception) { ai.synheart.core.sync.SyncResult() }
    }

    /** Get current sync status. */
    fun getSyncStatus(): ai.synheart.core.sync.SyncStatus {
        return ai.synheart.core.sync.SyncStatus(enabled = false)
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

    /**
     * Process a raw RAMEN vendor event (from the wear SDK) through the
     * wearable event pipeline: normalize -> store -> SRM push -> runtime.
     *
     * @param provider e.g. "whoop", "garmin", "oura"
     * @param eventType e.g. "sleep.updated", "recovery.updated"
     * @param payload decoded JSON payload from the RAMEN EventEnvelope
     * @param eventId RAMEN event ID (used for dedup)
     * @param seq RAMEN sequence number
     * @return the canonical event if processed, null if skipped
     */
    fun processVendorEvent(
        provider: String,
        eventType: String,
        payload: Map<String, Any?>,
        eventId: String,
        seq: Int
    ): ai.synheart.core.models.CanonicalWearableEvent? {
        return wearModule?.processVendorEvent(provider, eventType, payload, eventId, seq)
    }

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

            // Wire device signing into consent module so all consent-token requests
            // are signed with device identity (X-Synheart-* headers).
            consentModule!!.setDeviceSigner { method, path, bodyBytes ->
                try {
                    ai.synheart.auth.SynheartAuth.shared.signRequest(
                        appId = resolvedConfig.appId,
                        method = method,
                        path = path,
                        bodyBytes = bodyBytes
                    ).toMap()
                } catch (e: Exception) {
                    SynheartLogger.log("[Synheart] Device signing unavailable for consent: ${e.message}")
                    emptyMap()
                }
            }

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

            // 5. Initialize all modules
            SynheartLogger.log("[Synheart] Initializing all modules...")
            moduleManager.initializeAll()

            // 6. Register consent change listener
            previousConsent = consentModule!!.current()
            consentModule!!.addListener { newConsent ->
                handleConsentChange(newConsent)
            }

            // 7. Create SessionModule with adapted providers
            SynheartLogger.log("[Synheart] Initializing SessionModule...")
            val biosignalAdapter = WearModuleBiosignalAdapter(wearModule!!)
            val behaviorAdapter = BehaviorModuleAdapter(behaviorModule!!)
            sessionModule = SessionModule(
                biosignalProvider = biosignalAdapter,
                behaviorProvider = behaviorAdapter
            )
            SynheartLogger.log("[Synheart] SessionModule initialized")

            // 8. Create activation manager and auto-activate from config
            activationManager = ActivationManager()
            activationManager!!.activateFromConfig(resolvedConfig)

            synheartConfig = resolvedConfig

            // 9. Attach WearableEventProcessor (bridge wired after coreRuntime init)
            if (wearModule != null) {
                val processor = ai.synheart.core.modules.wear.WearableEventProcessor(
                    bridge = null, // will be updated after coreRuntime init
                    subjectId = resolvedConfig.subjectId,
                    deviceInstallId = resolvedConfig.deviceId
                )
                wearModule!!.setEventProcessor(processor)
            }

            // Configure synheart-auth for device attestation + signing
            if (resolvedConfig.appId.isNotEmpty() && this.context != null) {
                ai.synheart.auth.SynheartAuth.shared.configure("https://api.synheart.ai/auth")
            }

            try {
                coreRuntime = ai.synheart.core.bridge.CoreRuntimeBridge.create(
                    org.json.JSONObject().apply {
                        put("app_id", resolvedConfig.appId)
                        put("subject_id", resolvedConfig.subjectId)
                        put("mode", resolvedConfig.mode.name.lowercase())
                        put("device_id", resolvedConfig.deviceId)
                        put("app_version", resolvedConfig.appVersion)
                        put("platform", "android")
                    }.toString()
                )
                if (coreRuntime != null) {
                    SynheartLogger.log("[Synheart] Rust CoreRuntimeBridge initialized")

                    // Wire HSI callback (consent-gated) + bridge to session engine
                    coreRuntime!!.setHsiCallback { hsiJson ->
                        if (consentModule?.current()?.biosignals != true) return@setHsiCallback
                        _hsiJsonFlow.value = hsiJson

                        // Bridge HSI metrics to session engine
                        val sid = activeMainSessionId
                        if (sid != null && sessionModule != null) {
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

                    // Update WearableEventProcessor with the live bridge
                    wearModule?.eventProcessor?.updateBridge(coreRuntime)
                } else {
                    SynheartLogger.log("[Synheart] Rust CoreRuntimeBridge not available — using Kotlin fallback")
                }
            } catch (e: Exception) {
                SynheartLogger.log("[Synheart] Rust CoreRuntimeBridge init failed (non-fatal): $e")
                coreRuntime = null
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

    /**
     * Start a session -- activates permitted modules and begins signal collection.
     *
     * Must be called after [initialize]. No data collection occurs until
     * this method is called.
     *
     * @param durationSec Session duration in seconds. `null` uses the default
     *   24h (86400). Mirrors the Flutter SDK's optional duration.
     * @return the newly created [SessionHandle], or `null` if a session was
     *   already running.
     */
    suspend fun startSession(durationSec: Int? = null): SessionHandle? {
        if (!isConfigured) {
            throw IllegalStateException("Synheart must be initialized before starting session")
        }
        if (isRunning) {
            return null // Already running
        }

        val resolvedDuration = durationSec ?: 86400

        // Delegate to Rust core runtime if available
        coreRuntime?.let { cr ->
            val resultJson = cr.startSession()
            if (resultJson != null) {
                try {
                    val obj = org.json.JSONObject(resultJson)
                    val sessionId = obj.optString("session_id", "")
                    val startedAtMs = obj.optLong("started_at_ms", System.currentTimeMillis())
                    val mode = synheartConfig?.mode ?: SynheartMode.PERSONAL
                    currentSessionHandle = SessionHandle(sessionId = sessionId, startedAtMs = startedAtMs, mode = mode)
                    isRunning = true
                    SynheartLogger.log("[Synheart] Session started via CoreRuntimeBridge")
                    // Still start Kotlin-side modules for data collection pipeline
                    moduleManager.startAll()
                    reevaluateAllFeatures()
                    return currentSessionHandle
                } catch (e: Exception) {
                    SynheartLogger.log("[Synheart] CoreRuntimeBridge startSession parse failed, falling back: $e")
                }
            }
        }

        SynheartLogger.log("[Synheart] Starting session...")
        moduleManager.startAll()

        // Open main collection session via Session SDK
        val nowMs = System.currentTimeMillis()
        val sessionId = "core_$nowMs"
        val sessionConfig = SessionConfig(
            sessionId = sessionId,
            mode = SessionMode.FOCUS,
            durationSec = resolvedDuration
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

        val mode = synheartConfig?.mode ?: SynheartMode.PERSONAL
        currentSessionHandle = SessionHandle(sessionId = sessionId, startedAtMs = nowMs, mode = mode)

        isRunning = true
        reevaluateAllFeatures()
        SynheartLogger.log("[Synheart] Session started")
        return currentSessionHandle
    }

    /**
     * Stop the current session -- halts module streaming and clears ephemeral buffers.
     */
    suspend fun stopSession() {
        if (!isRunning) {
            return
        }

        // Delegate to Rust core runtime if available
        coreRuntime?.let { cr ->
            if (cr.stopSession()) {
                SynheartLogger.log("[Synheart] Session stopped via CoreRuntimeBridge")
                currentSessionHandle = null
                isRunning = false
                reevaluateAllFeatures()
                moduleManager.stopAll()
                return
            }
        }

        SynheartLogger.log("[Synheart] Stopping session...")

        // Close main collection session via Session SDK
        if (activeMainSessionId != null) {
            sessionModule?.stopSession(activeMainSessionId!!)
            mainSessionJob?.cancel()
            mainSessionJob = null
            activeMainSessionId = null
        }

        // Auto-ingest session to platform (opt-in)
        val handle = currentSessionHandle
        val piConfig = synheartConfig?.labIngestConfig
        if (piConfig?.autoIngest == true && handle != null) {
            try {
                autoIngestSession(handle)
                SynheartLogger.log("[Synheart] Auto-ingest completed")
            } catch (e: Exception) {
                SynheartLogger.log("[Synheart] Auto-ingest failed: $e")
            }
        }

        currentSessionHandle = null
        isRunning = false
        reevaluateAllFeatures()
        moduleManager.stopAll()
        SynheartLogger.log("[Synheart] Session stopped")
    }

    /**
     * Auto-ingest a session payload built from SDK internal data.
     */
    private suspend fun autoIngestSession(session: SessionHandle) {
        coreRuntime?.flushUploads()
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
        // Delegate to Rust core runtime if available
        coreRuntime?.let { cr ->
            if (cr.grantConsent(consentType)) {
                SynheartLogger.log("[Synheart] Consent '$consentType' granted via CoreRuntimeBridge")
            }
            // Fall through to also update Kotlin-side consent module for module gating
        }

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
        // Delegate to Rust core runtime if available
        coreRuntime?.let { cr ->
            if (cr.revokeConsent(consentType)) {
                SynheartLogger.log("[Synheart] Consent '$consentType' revoked via CoreRuntimeBridge")
            }
            // Fall through to also update Kotlin-side consent module for module gating
        }

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

    /**
     * Get baseline summary from the native synheart-engine (if available).
     *
     * Returns a JSON string like `{"total":14,"ready":0,"warming":5,"empty":9}`
     * or `null` if the native runtime is not linked.
     */
    val runtimeBaselineSummary: String?
        get() = coreRuntime?.srmOverallStatus()

    /**
     * Get all native runtime baselines as JSON, or `null`.
     */
    val runtimeBaselinesJson: String?
        get() = coreRuntime?.baselinesJson()

    /**
     * Export the native runtime SRM snapshot as JSON for cross-session persistence.
     */
    fun exportRuntimeSRMSnapshot(): String? {
        return coreRuntime?.exportSrmSnapshot()
    }

    /**
     * Load a native runtime SRM snapshot from JSON.
     * Returns true on success, false on failure, or `null` if runtime unavailable.
     */
    fun loadRuntimeSRMSnapshot(json: String): Boolean? {
        return coreRuntime?.loadSrmSnapshot(json)
    }

    /**
     * Get the native synheart-engine version, or `null` if unavailable.
     */
    val runtimeVersion: String?
        get() = coreRuntime?.diagnostics()

    private fun handleConsentChange(newConsent: ConsentSnapshot) {
        previousConsent = newConsent
        reevaluateAllFeatures()
    }

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

    // ----- Module Status -----

    /**
     * Per-module readiness flags. Mirrors the Flutter SDK's `getModuleStatuses()`.
     * Keys: `"wear"`, `"behavior"`, `"phoneContext"`.
     */
    fun getModuleStatuses(): Map<String, Boolean> {
        return mapOf(
            "wear" to (wearModule?.status == ai.synheart.core.modules.base.ModuleStatus.RUNNING),
            "behavior" to (behaviorModule?.status == ai.synheart.core.modules.base.ModuleStatus.RUNNING),
            "phoneContext" to (phoneModule?.status == ai.synheart.core.modules.base.ModuleStatus.RUNNING),
        )
    }

    // ----- Diagnostics & Upload State -----

    /**
     * Full native runtime diagnostics as a parsed map, or `null` if the runtime
     * is unavailable. Mirrors the Flutter SDK's `runtimeDiagnostics()`.
     */
    fun runtimeDiagnostics(): Map<String, Any>? {
        val json = coreRuntime?.diagnostics() ?: return null
        return parseJsonObjectAsMap(json)
    }

    /** Whether lab metadata is available for the current session. */
    val isLabMetadataAvailable: Boolean
        get() = coreRuntime?.isLabAvailable() ?: false

    /** Number of HSI snapshots queued locally awaiting cloud upload. */
    val uploadQueueLength: Int
        get() = coreRuntime?.uploadQueueLength() ?: 0

    /** Last error code emitted by the native runtime; 0 if no error. */
    val lastErrorCode: Int
        get() = coreRuntime?.lastErrorCode() ?: 0

    /** Whether the native runtime library is loaded and responsive. */
    val isRuntimeAvailable: Boolean
        get() = coreRuntime?.isRuntimeAvailable() ?: false

    /** Whether the network is currently reachable per the runtime. */
    val isNetworkReachable: Boolean
        get() = coreRuntime?.isNetworkReachable() ?: false

    /**
     * Force-flush any pending uploads. Returns the runtime's response JSON,
     * or `null` if the runtime is unavailable.
     */
    fun flushUploads(): String? = coreRuntime?.flushUploads()

    /** Raw JSON describing the most recent upload attempt, or `null`. */
    val uploadMetadata: String?
        get() = coreRuntime?.uploadMetadata()

    /** Timestamp of the most recent successful cloud ingest, or `null`. */
    val lastIngestSuccessAtMs: Long?
        get() = parsedUploadMetadata()?.optLong("lastIngestSuccessAtMs", -1L)
            ?.takeIf { it >= 0L }

    /** ID of the most recent upload batch, or `null`. */
    val lastUploadBatchId: String?
        get() = parsedUploadMetadata()?.optString("lastUploadBatchId")
            ?.takeIf { it.isNotEmpty() }

    /** Error message from the most recent failed upload, or `null`. */
    val lastUploadError: String?
        get() = parsedUploadMetadata()?.optString("lastUploadError")
            ?.takeIf { it.isNotEmpty() }

    private fun parsedUploadMetadata(): org.json.JSONObject? {
        val json = coreRuntime?.uploadMetadata() ?: return null
        return try { org.json.JSONObject(json) } catch (_: Exception) { null }
    }

    private fun parseJsonObjectAsMap(json: String): Map<String, Any>? {
        return try {
            val obj = org.json.JSONObject(json)
            val out = HashMap<String, Any>()
            obj.keys().forEach { k -> obj.opt(k)?.let { out[k] = it } }
            out
        } catch (_: Exception) { null }
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

            coreRuntime?.close()
            coreRuntime = null

            moduleManager.disposeAll()

            mainSessionJob?.cancel()
            mainSessionJob = null
            hsiToSessionJob?.cancel()
            hsiToSessionJob = null
            activeMainSessionId = null
            sessionModule = null

            currentSessionHandle = null
            synheartConfig = null

            consentModule = null
            capabilityModule = null
            wearModule = null
            phoneModule = null
            behaviorModule = null
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
