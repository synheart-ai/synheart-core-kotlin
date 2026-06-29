package ai.synheart.core

import android.content.Context
import ai.synheart.core.config.ActivationManager
import ai.synheart.core.config.SynheartConfig
import ai.synheart.core.config.SynheartFeature
import ai.synheart.core.models.*
import ai.synheart.core.modules.base.ModuleManager
import ai.synheart.core.modules.capabilities.CapabilityModule
import ai.synheart.core.modules.consent.CloudConsentLogic
import ai.synheart.core.modules.consent.ConsentModule
import ai.synheart.core.modules.consent.ConsentStorage
import org.json.JSONObject
import ai.synheart.core.modules.interfaces.CapabilityLevel
import ai.synheart.core.modules.interfaces.ConsentSnapshot
import ai.synheart.core.modules.interfaces.Module
import ai.synheart.core.modules.wear.WearModule
import ai.synheart.core.modules.phone.PhoneModule
import ai.synheart.core.modules.behavior.BehaviorModule
import ai.synheart.core.bridge.CoreRuntimeBridge
import ai.synheart.core.bridge.DeviceAuthCallbacks
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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

    /**
     * Serializes lifecycle transitions (initialize, dispose) so concurrent
     * callers from different threads can't double-init or interleave teardown.
     * Per-method state mutations remain unguarded; this only protects the
     * coarse lifecycle phases.
     */
    private val lifecycleMutex = Mutex()

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

    /**
     * The subject id this SDK instance is bound to — the value HSI uploads are
     * attributed under (`meta.user_id`) and the consent token is minted for.
     * Prefers the canonical subject reported by the native runtime (which may
     * derive it from `client_id` per RFC-0008); falls back to the configured
     * value before the runtime loads. Null when not configured.
     */
    val subjectId: String?
        get() = nativeSubjectIdOverride?.takeIf { it.isNotEmpty() }
            ?: synheartConfig?.subjectId ?: userId

    /**
     * Canonical subject captured from the native runtime after init and after a
     * [rebindSubjectId]. Source of truth over the immutable configured value.
     */
    @Volatile
    private var nativeSubjectIdOverride: String? = null

    /**
     * Capture the runtime's canonical subject so [subjectId] /
     * [consentTokenSubjectStale] agree with native. A null/empty value (e.g. an
     * older runtime without the symbol) leaves the override unchanged.
     */
    private fun syncSubjectFromNative() {
        val native = runCatching { coreRuntime?.runtimeSubjectId() }.getOrNull()
        if (!native.isNullOrEmpty()) nativeSubjectIdOverride = native
    }

    /**
     * Subject (`user_id` claim) of the most recently issued cloud consent token,
     * tracked so a token issued for a previous subject can be detected. Null
     * until a token is issued in this process.
     */
    @Volatile
    private var currentTokenSubject: String? = null

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

    /**
     * Record a single metric event for the current session.
     *
     * No-op if no session is active or the native runtime is unavailable.
     * Failures inside the runtime are silently logged — the call always
     * returns synchronously and never throws.
     *
     * @param event The metric event to record. The `kind` field determines
     *   how the runtime routes it (e.g. user-reported state, app event).
     */
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

    /** Record a batch of metric events. Loops over the singular path. */
    fun recordMetrics(events: List<ai.synheart.core.models.MetricEvent>) {
        for (event in events) {
            recordMetric(event)
        }
    }

    /**
     * Enable/disable ambient capture: when on, the runtime forwards every
     * closed HSI window to the host's HSI callback regardless of session
     * state. When off (default), windows are forwarded only while a session
     * is active.
     */
    fun setAmbientCapture(enabled: Boolean) {
        coreRuntime?.setAmbientCapture(enabled)
    }

    /** Read the ambient-capture flag. */
    fun getAmbientCapture(): Boolean = coreRuntime?.getAmbientCapture() ?: false

    /**
     * List sessions stored on this device.
     *
     * Returns an empty list if the SDK isn't initialized or storage is
     * unavailable. Cloud-only sessions are not returned.
     *
     * @param range Optional time-bounded filter. `null` returns all stored
     *   sessions in reverse-chronological order.
     */
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

    // ------------------------------------------------------------------ //
    // Research studies                                                    //
    // ------------------------------------------------------------------ //

    /**
     * Enrol the device in a research study by redeeming an access + study code.
     * Enrolment rides the device's signed cloud credential — no tokens are
     * handled by the caller. Returns the service response (enrolment on success,
     * or an `error` key), or null when the runtime is unavailable.
     */
    fun enrolResearchStudy(accessCode: String, studyCode: String): org.json.JSONObject? {
        val cr = coreRuntime ?: return null
        val json = cr.enrolResearchStudy(accessCode, studyCode) ?: return null
        return try { org.json.JSONObject(json) } catch (_: Exception) { null }
    }

    /** Preview an access + study code pair without redeeming the code. */
    fun validateResearchStudyCodes(accessCode: String, studyCode: String): org.json.JSONObject? {
        val cr = coreRuntime ?: return null
        val json = cr.validateResearchStudyCodes(accessCode, studyCode) ?: return null
        return try { org.json.JSONObject(json) } catch (_: Exception) { null }
    }

    /**
     * Withdraw from the device's active research study for this app. No codes —
     * the participant + app come from the device's signed credential. Idempotent.
     */
    fun withdrawResearchStudy(): org.json.JSONObject? {
        val cr = coreRuntime ?: return null
        val json = cr.withdrawResearchStudy() ?: return null
        return try { org.json.JSONObject(json) } catch (_: Exception) { null }
    }

    /**
     * Request erasure of the data the participant contributed to their study for
     * this app — the deletion the consent copy promises alongside withdrawal. No
     * identifiers are passed; the participant + app come from the device's signed
     * credential. When [dryRun] is true the response is an inventory preview and
     * nothing is deleted; a real request is accepted asynchronously and carries a
     * `request_id`. Idempotent. Returns null when the runtime is unavailable.
     */
    fun requestStudyDataDeletion(dryRun: Boolean = false): org.json.JSONObject? {
        val cr = coreRuntime ?: return null
        val json = cr.requestStudyDataDeletion(dryRun) ?: return null
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
    suspend fun cancelAccountDeletion(): ai.synheart.core.models.DeletionRequestResult {
        val ok = coreRuntime?.cancelAccountDeletion() ?: false
        return ai.synheart.core.models.DeletionRequestResult(
            status = if (ok) "cancelled" else "error",
            message = if (ok) "Account deletion cancelled via CoreRuntimeBridge."
                      else "Account deletion cancellation failed."
        )
    }

    /** Log out — revoke consent and clear credentials. */
    suspend fun logout() {
        try { consentModule?.revokeAll() } catch (_: Exception) {}
    }

    // The dedicated `sync` subsystem (push/pull) was removed from the Kotlin
    // SDK to match Flutter's surface — uploads now go through the cloud
    // module on its own schedule, gated by consent. Use the runtime bridge
    // directly if you need the raw FFI sync hooks (`coreRuntime.syncNow()`).

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
    ) = lifecycleMutex.withLock {
        if (isConfigured) {
            return@withLock // No-op if already initialized
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
            consentModule = ConsentModule(context = this.context!!)
            // Device-signing for outbound requests is owned by the cloud /
            // upload path (DeviceAuthProvider) directly against
            // SynheartAuth.shared — the consent module no longer carries
            // a per-request signer hook, matching Flutter's surface.

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
                        // Base URL for the runtime's cloud consent + upload clients.
                        // Without it those clients are unconfigured and uploads no-op.
                        put(
                            "api_base_url",
                            resolvedConfig.cloudConfig?.baseUrl
                                ?: ai.synheart.core.config.ApiEndpoints.DEFAULT_CLOUD_BASE_URL,
                        )
                    }.toString()
                )
                if (coreRuntime != null) {
                    SynheartLogger.log("[Synheart] Native CoreRuntimeBridge initialized")

                    // Capture the canonical subject the runtime resolved (a
                    // device-auth derive may have changed it) so SDK subject
                    // checks match the native source of truth.
                    syncSubjectFromNative()

                    // Device auth: hand the runtime its Keystore crypto + secure
                    // storage callbacks before any registration so consent tokens
                    // persist and can be minted. Best-effort.
                    try {
                        this.context?.let { DeviceAuthCallbacks.attachContext(it) }
                        val storageRc = coreRuntime!!.setStorageCallbacks()
                        if (storageRc != 0) {
                            SynheartLogger.log("[Synheart] set_storage_callbacks rc=$storageRc; state will not persist")
                        }
                        val cryptoRc = coreRuntime!!.setSdkCryptoCallbacks()
                        if (cryptoRc != 0) {
                            SynheartLogger.log("[Synheart] set_crypto_callbacks rc=$cryptoRc; device auth unavailable")
                        }
                    } catch (e: Exception) {
                        SynheartLogger.log("[Synheart] device-auth callback wiring failed: ${e.message}")
                    }

                    // Configure the runtime's cloud consent client (base URL + app
                    // id) so it can mint/refresh consent tokens. Best-effort.
                    try {
                        val cloudBaseUrl = resolvedConfig.cloudConfig?.baseUrl
                            ?: ai.synheart.core.config.ApiEndpoints.DEFAULT_CLOUD_BASE_URL
                        coreRuntime!!.consentConfigureCloud(cloudBaseUrl, resolvedConfig.appId)
                    } catch (e: Exception) {
                        SynheartLogger.log("[Synheart] consentConfigureCloud failed: ${e.message}")
                    }

                    // Self-heal: if cloud upload was granted in a prior session,
                    // ensure a consent token exists for the CURRENT subject so
                    // uploads resume immediately (reissues a token left by a
                    // different subject). Best-effort — never blocks init.
                    try {
                        if (consentModule?.current()?.cloudUpload == true) {
                            ensureCloudConsentReady()
                        }
                    } catch (e: Exception) {
                        SynheartLogger.log("[Synheart] init consent self-heal failed: ${e.message}")
                    }

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
                    SynheartLogger.log("[Synheart] Native CoreRuntimeBridge not available — using Kotlin fallback")
                }
            } catch (e: Exception) {
                SynheartLogger.log("[Synheart] Native CoreRuntimeBridge init failed (non-fatal): $e")
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
     */
    suspend fun startSession() {
        if (!isConfigured) {
            throw IllegalStateException("Synheart must be initialized before starting session")
        }
        if (isRunning) {
            return // Already running
        }

        // Delegate to native core runtime if available
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
                    return
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

        val mode = synheartConfig?.mode ?: SynheartMode.PERSONAL
        currentSessionHandle = SessionHandle(sessionId = sessionId, startedAtMs = nowMs, mode = mode)

        isRunning = true
        reevaluateAllFeatures()
        SynheartLogger.log("[Synheart] Session started")
    }

    /**
     * Stop the current session -- halts module streaming and clears ephemeral buffers.
     */
    suspend fun stopSession() {
        if (!isRunning) {
            return
        }

        // Delegate to native core runtime if available
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
     * Check if user has granted a specific consent.
     *
     * @param consentType One of: `"biosignals"`, `"behavior"`,
     *   `"phoneContext"` (alias `"motion"`), `"cloudUpload"`,
     *   `"focusEstimation"`, `"emotionEstimation"`, `"syni"`.
     *   Unknown values return `false`.
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
     * Grant consent for a specific data type.
     *
     * @param consentType One of: `"biosignals"`, `"behavior"`,
     *   `"phoneContext"` (alias `"motion"`), `"cloudUpload"`,
     *   `"focusEstimation"`, `"emotionEstimation"`, `"syni"`.
     *   Unknown values are silently ignored.
     * @throws IllegalStateException if the consent module isn't initialized.
     *
     * Example:
     * ```kotlin
     * Synheart.grantConsent("biosignals")
     * ```
     */
    suspend fun grantConsent(consentType: String) {
        // Delegate to native core runtime if available
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

        // Granting cloud upload should immediately mint a consent token for the
        // current subject so pending data can flush. Best-effort.
        if (consentType == "cloudUpload") {
            try {
                ensureCloudConsentReady()
            } catch (e: Exception) {
                SynheartLogger.log("[Synheart] ensureCloudConsentReady after grant failed: ${e.message}")
            }
        }
    }

    /**
     * Ensure a cloud consent token exists for the current subject so pending data
     * can upload. Short-circuits when a valid, subject-matched token is already
     * granted; otherwise reissues it by submitting the current consent form.
     * Returns true when a usable token is in place. Safe to call repeatedly; never
     * throws fatally.
     */
    suspend fun ensureCloudConsentReady(): Boolean {
        val cr = coreRuntime ?: return false

        // Cloud upload must be granted (effective state is token-authoritative).
        val effective = cr.consentEffectiveState()?.let { runCatching { JSONObject(it) }.getOrNull() }
        val cloudGranted =
            effective?.optBoolean("cloud_upload", effective.optBoolean("cloudUpload", false)) == true
        if (!cloudGranted) return false

        val status = cr.consentStatus()?.let { runCatching { JSONObject(it).optString("status") }.getOrNull() }
        val needsRefresh = runCatching { cr.consentNeedsTokenRefresh() }.getOrDefault(false)
        if (CloudConsentLogic.isReadyWithoutReissue(status, needsRefresh, consentTokenSubjectStale())) {
            return true
        }

        // The runtime can only mint once the device is registered (device-signed).
        // Register on first use; idempotent — skip when already registered.
        val clientId = subjectId
        val authStatus = cr.deviceAuthStatus()?.let {
            runCatching { JSONObject(it).optString("status") }.getOrNull()
        }
        if (authStatus != "registered" && !clientId.isNullOrEmpty()) {
            val reg = cr.registerDevice(clientId)?.let { runCatching { JSONObject(it) }.getOrNull() }
            val deviceId = reg?.optString("device_id")?.takeIf { it.isNotEmpty() }
            if (reg == null || reg.has("error") || deviceId == null) {
                SynheartLogger.log("[Synheart] device registration failed: ${reg?.optString("error") ?: "no device_id"}")
                return false
            }
        }

        // Reissue: take the editable form, force allow_cloud, submit to mint.
        val formJson = cr.consentGetEditableForm() ?: return false
        val form = runCatching { JSONObject(formJson) }.getOrNull() ?: return false
        form.put("allow_cloud", true)

        val resultJson = cr.consentSubmitForm(
            synheartConfig?.deviceId,
            "android",
            subjectId,
            form.toString(),
        ) ?: return false
        val result = runCatching { JSONObject(resultJson) }.getOrNull() ?: return false
        if (result.has("error")) {
            SynheartLogger.log("[Synheart] consent submit error: ${result.optString("error")}")
            return false
        }
        val tokenObj = result.optJSONObject("token")
        if (!CloudConsentLogic.submitIssuedToken(result.optBoolean("synced", false), tokenObj != null)) {
            SynheartLogger.log("[Synheart] consent submit accepted but no token issued (cloud unavailable / device not registered?)")
            return false
        }
        // Record the subject the token was minted under (the runtime mints under
        // the configured subject_id) for the subject-stale check.
        currentTokenSubject =
            tokenObj?.optString("user_id")?.takeIf { it.isNotEmpty() }
                ?: tokenObj?.optJSONObject("claims")?.optString("user_id")?.takeIf { it.isNotEmpty() }
                ?: subjectId

        val refreshed = cr.consentStatus()?.let { runCatching { JSONObject(it).optString("status") }.getOrNull() }
        return refreshed?.lowercase() == "granted" &&
            !runCatching { cr.consentNeedsTokenRefresh() }.getOrDefault(true)
    }

    /**
     * True when the issued cloud consent token was minted for a DIFFERENT subject
     * than the current one (e.g. after an account re-key). Conservative: false
     * when there's no token or the subject is unknown.
     */
    fun consentTokenSubjectStale(): Boolean =
        CloudConsentLogic.isTokenSubjectStale(currentTokenSubject, subjectId)

    /**
     * Rebind the runtime subject id when the signed-in identity changes, then
     * re-mint cloud consent for the new subject if needed — without a full
     * dispose/reinit. Prefer this over re-initializing the SDK on sign-in.
     *
     * The native runtime atomically re-points consent (`cached_subject_id` +
     * token slot) and the cloud connector; this syncs the SDK subject and runs
     * the self-heal so a stale token is reissued before the next upload. Returns
     * true when applied (false on a runtime that lacks the symbol).
     */
    suspend fun rebindSubjectId(subjectId: String): Boolean {
        val cr = coreRuntime ?: return false
        val trimmed = subjectId.trim()
        if (trimmed.isEmpty()) return false
        val rc = cr.rebindSubjectId(trimmed)
        if (rc < 0) {
            SynheartLogger.log("[Synheart] rebindSubjectId failed (rc=$rc)")
            return false
        }
        // Keep the SDK subject in lockstep with the native runtime.
        syncSubjectFromNative()
        // rc == 1 => re-mint required; rc == 0 => valid token already loaded.
        // Self-heal regardless: cheap no-op when ready, reissues otherwise.
        runCatching { ensureCloudConsentReady() }
        return true
    }

    /**
     * Revoke consent for a specific data type. Any modules gated on this
     * consent are stopped and queued data discarded per retention policy.
     *
     * @param consentType One of: `"biosignals"`, `"behavior"`,
     *   `"phoneContext"` (alias `"motion"`), `"cloudUpload"`,
     *   `"focusEstimation"`, `"emotionEstimation"`, `"syni"`.
     *   Unknown values are silently ignored.
     * @throws IllegalStateException if the consent module isn't initialized.
     *
     * Example:
     * ```kotlin
     * Synheart.revokeConsent("biosignals")
     * ```
     */
    suspend fun revokeConsent(consentType: String) {
        // Delegate to native core runtime if available
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

    /**
     * Stop Synheart Core SDK
     */
    suspend fun stop() {
        stopSession()
    }

    /**
     * Dispose all resources
     */
    suspend fun dispose() = lifecycleMutex.withLock {
        try {
            stop()

            // Tell the native runtime to stop firing HSI callbacks BEFORE we
            // null the modules they reference. Without this, an in-flight
            // callback can null-deref sessionModule/consentModule mid-teardown.
            coreRuntime?.clearHsiCallback()
            coreRuntime?.close()
            coreRuntime = null
            nativeSubjectIdOverride = null
            currentTokenSubject = null

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
