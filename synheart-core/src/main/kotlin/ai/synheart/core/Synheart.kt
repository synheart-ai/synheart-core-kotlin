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
import ai.synheart.core.modules.consent.wireKey
import org.json.JSONObject
import ai.synheart.core.modules.interfaces.CapabilityLevel
import ai.synheart.core.modules.interfaces.ConsentSnapshot
import ai.synheart.core.modules.interfaces.Module
import ai.synheart.core.modules.wear.SynheartWearSourceHandler
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
import ai.synheart.core.modules.session.WatchSessionModule
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
import kotlinx.coroutines.flow.asSharedFlow
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
                records.add(SessionRecord.fromJson(arr.getJSONObject(i)))
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

            // Route the runtime's own logs wherever the host asked, before any
            // native work. Opt-in and easy to miss: with no filter the runtime
            // logs nowhere, so the lines that explain a stalled integration do
            // not exist and the silence reads as "nothing happened".
            (config ?: SynheartConfig()).runtimeLogEnvFilter
                ?.takeIf { it.isNotBlank() }
                ?.let { filter ->
                    val rc = runCatching { initRuntimeLogging(filter) }.getOrDefault(-1)
                    SynheartLogger.log("[Synheart] runtime logging filter='$filter' rc=$rc")
                }

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
            // Attach a REAL biosignal source when the host declared wearConfig.
            //
            // Until now nothing in this SDK registered one, so the wear module's
            // only source was the synthetic generator — and with that correctly
            // disabled it had no source at all, leaving biosignals reachable
            // only through the host's own pushWearHr / pushRr calls.
            // `synheart-wear` is already a dependency and ships the Health
            // Connect and BLE adapters, so the bridge is all that was missing.
            //
            // Built only when wearConfig is declared: constructing SynheartWear
            // touches Health Connect, which a host that never asked for
            // biosignals should not pay for.
            val wearSources = if (resolvedConfig.wearConfig != null) {
                runCatching { listOf(SynheartWearSourceHandler(this.context!!)) }
                    .onFailure {
                        SynheartLogger.log(
                            "[Synheart] could not attach the wear source: ${it.message}",
                        )
                    }
                    .getOrNull()
            } else {
                null
            }
            wearModule = WearModule(
                capabilities = capabilityModule!!,
                consent = consentModule!!,
                sources = wearSources,
                allowSynthetic = resolvedConfig.allowSyntheticBiosignals,
            )
            phoneModule = PhoneModule(
                capabilities = capabilityModule!!,
                consent = consentModule!!
            )
            behaviorModule = BehaviorModule(
                capabilities = capabilityModule!!,
                consent = consentModule!!
            )

            // The watch relay needs no consent gate of its own: the watch runs
            // its own session and applies its own, and nothing is collected on
            // this device. Registered outside moduleManager for the same reason
            // — it has no start/stop tied to a phone session.
            watchSessionModule = WatchSessionModule(this.context!!, scope).apply { initialize() }

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

            // Config supplies the default; setBatchIngestOnStop still overrides
            // it at runtime.
            if (batchIngestOnStop == null) {
                batchIngestOnStop = resolvedConfig.batchIngestOnStop
            }

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
            // Device attestation + signing. The origin comes from the host's
            // DeviceAuthConfig, falling back to the resolved auth base URL —
            // the SDK ships no built-in host (see ApiEndpoints).
            val authBaseUrl = resolvedConfig.deviceAuthConfig?.authBaseUrl
                ?.takeIf { it.isNotEmpty() }
                ?: ai.synheart.core.config.ApiEndpoints.resolvedAuthBaseUrl
            if (resolvedConfig.appId.isNotEmpty() && this.context != null &&
                authBaseUrl.isNotEmpty()
            ) {
                ai.synheart.auth.SynheartAuth.shared.configure(authBaseUrl)
            }

            try {
                // Single-sourced with the secondary-instance path so the two
                // cannot drift; see `buildRuntimeConfigMap` for why the
                // `ingest` / `device_auth` gates matter.
                coreRuntime = ai.synheart.core.bridge.CoreRuntimeBridge.create(
                    ai.synheart.core.config.buildRuntimeConfigMap(
                        resolvedConfig,
                        dataDir = this.context?.filesDir?.absolutePath,
                    ).toString()
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
                        // Empty means the host named no environment; the
                        // runtime keeps whatever origin its own config resolved.
                        val cloudBaseUrl = resolvedConfig.cloudConfig?.baseUrl
                            ?.takeIf { it.isNotEmpty() }
                            ?: ai.synheart.core.config.ApiEndpoints.resolvedConsentBaseUrl
                        if (cloudBaseUrl.isNotEmpty()) {
                            coreRuntime!!.consentConfigureCloud(cloudBaseUrl, resolvedConfig.appId)
                        }
                    } catch (e: Exception) {
                        SynheartLogger.log("[Synheart] consentConfigureCloud failed: ${e.message}")
                    }

                    // Adopt the runtime's persisted consent into the SDK's own
                    // mirror. This has to happen on EVERY launch, not just after
                    // a submit.
                    //
                    // The runtime stores consent offline-first and restores it at
                    // startup; the SDK mirror starts empty. Only
                    // consentSubmitForm synced the two, so on the second launch
                    // the runtime reported every channel granted while
                    // `consentModule.current()` said none was. Everything that
                    // gates on the mirror then failed closed:
                    // `reevaluateAllFeatures` started each module from
                    // `startAll()` and stopped it again a millisecond later, so a
                    // session collected NOTHING after a restart while the consent
                    // screen showed all-granted. The self-heal below was dead for
                    // the same reason — `cloudUpload` was never seen as true.
                    try {
                        syncConsentModuleFromRuntime()
                    } catch (e: Exception) {
                        SynheartLogger.log(
                            "[Synheart] consent mirror sync failed: ${e.message}; " +
                                "modules will not start until consent is re-submitted",
                        )
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
                        // A window completed by a per-event push arrives twice —
                        // once here and once as an ingest return value. Drop the
                        // repeat so subscribers and the session buffer see it once.
                        if (!hsiDeduper.shouldDeliver(hsiJson)) return@setHsiCallback
                        _hsiJsonFlow.value = hsiJson
                        synchronized(sessionHsiWindows) { sessionHsiWindows.add(hsiJson) }

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

                    // Feed the engine.
                    //
                    // Without these the modules collect into their own caches
                    // and the runtime receives nothing, so a correctly
                    // configured session produces no HSI at all — and an idle
                    // engine looks exactly like a starved one from outside.
                    // Behavior is the one source needing no sensor and no
                    // wearable, so on a bare phone it is the whole input.
                    behaviorModule?.pushBehaviorToRuntime = { tsMs, eventType, value ->
                        coreRuntime?.pushBehavior(tsMs, eventType, value)
                    }
                    behaviorModule?.pushAccelToRuntime = { tsMs, ax, ay, az ->
                        coreRuntime?.pushAccel(tsMs, ax, ay, az)
                    }

                    // Breathing compliance rides the same RR stream the fusion
                    // pipeline already receives, so it only needs the bridge.
                    breathingModule =
                        ai.synheart.core.modules.breathing.BreathingModule(coreRuntime!!)

                    // Hydrate typed baseline snapshots from local storage so the
                    // per-kind getters return real data immediately after a cold
                    // start, without waiting for a producer to run.
                    baselineSnapshots.wireLocalHydrator { coreRuntime?.baselineHydrateLocal() }
                    try {
                        baselineSnapshots.hydrateFromLocal()
                    } catch (e: Exception) {
                        SynheartLogger.log("[Synheart] baseline hydrate failed: ${e.message}")
                    }
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

        // Start clean: a window id left over from the previous session would
        // otherwise suppress this session's first window, and its buffered
        // windows would show up in `getSessionHsiWindows`.
        resetSessionBuffers()

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
                maybeFlushOnStop()
                currentSessionHandle = null
                isRunning = false
                reevaluateAllFeatures()
                moduleManager.stopAll()
                resetSessionBuffers()
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
        // The runtime is the authority when it is loaded: it applies the cloud
        // gate on top of the user's choice, which is exactly what a caller
        // gating an upload needs. Both spellings are accepted and translated —
        // passing the caller's through unchanged made neither work, since the
        // runtime does not define `cloudUpload` and the SDK's own switch does
        // not match `cloud_upload`.
        coreRuntime?.let { return it.hasConsent(runtimeConsentKey(consentType)) }

        val consent = consentModule?.current() ?: return false
        return when (sdkConsentKey(consentType)) {
            "biosignals" -> consent.biosignals
            "behavior" -> consent.behavior
            "phoneContext" -> consent.phoneContext
            "cloudUpload" -> consent.cloudUpload
            "focusEstimation" -> consent.focusEstimation
            "emotionEstimation" -> consent.emotionEstimation
            "vendorSync" -> consent.vendorSync
            "research" -> consent.research
            "syni" -> consent.syni
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
        // The runtime keys consent on snake_case; a camelCase key reaches it
        // unrecognized and is silently ignored, leaving the SDK's mirror and
        // the runtime's gate disagreeing.
        val runtimeKey = runtimeConsentKey(consentType)
        val sdkKey = sdkConsentKey(consentType)

        // Delegate to native core runtime if available
        coreRuntime?.let { cr ->
            if (cr.grantConsent(runtimeKey)) {
                SynheartLogger.log("[Synheart] Consent '$runtimeKey' granted via CoreRuntimeBridge")
            }
            // Fall through to also update Kotlin-side consent module for module gating
        }

        if (consentModule == null) {
            throw IllegalStateException("Consent module not initialized")
        }

        val current = consentModule?.current() ?: ConsentSnapshot.none()
        val updated = when (sdkKey) {
            "biosignals" -> current.copy(biosignals = true)
            "behavior" -> current.copy(behavior = true)
            "phoneContext" -> current.copy(phoneContext = true)
            "cloudUpload" -> current.copy(cloudUpload = true)
            "focusEstimation" -> current.copy(focusEstimation = true)
            "emotionEstimation" -> current.copy(emotionEstimation = true)
            "vendorSync" -> current.copy(vendorSync = true)
            "research" -> current.copy(research = true)
            "syni" -> current.copy(syni = true)
            else -> current
        }

        consentModule?.updateConsent(updated)

        // Granting cloud upload should immediately mint a consent token for the
        // current subject so pending data can flush. Best-effort.
        if (sdkKey == "cloudUpload") {
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
        val runtimeKey = runtimeConsentKey(consentType)
        val sdkKey = sdkConsentKey(consentType)

        // Delegate to native core runtime if available
        coreRuntime?.let { cr ->
            if (cr.revokeConsent(runtimeKey)) {
                SynheartLogger.log("[Synheart] Consent '$runtimeKey' revoked via CoreRuntimeBridge")
            }
            // Fall through to also update Kotlin-side consent module for module gating
        }

        if (consentModule == null) {
            throw IllegalStateException("Consent module not initialized")
        }

        val current = consentModule?.current() ?: ConsentSnapshot.none()
        val updated = when (sdkKey) {
            "biosignals" -> current.copy(biosignals = false)
            "behavior" -> current.copy(behavior = false)
            "phoneContext" -> current.copy(phoneContext = false)
            "cloudUpload" -> current.copy(cloudUpload = false)
            "focusEstimation" -> current.copy(focusEstimation = false)
            "emotionEstimation" -> current.copy(emotionEstimation = false)
            "vendorSync" -> current.copy(vendorSync = false)
            "research" -> current.copy(research = false)
            "syni" -> current.copy(syni = false)
            else -> current
        }

        consentModule?.updateConsent(updated)
    }

    /**
     * Consent keys the SDK accepts, mapped to the snake_case spelling the
     * runtime defines.
     *
     * The two layers disagree on spelling: hosts and the SDK's own snapshot
     * use camelCase, the runtime's consent gate uses snake_case. Passing a
     * camelCase key across the FFI boundary leaves the runtime unaware of a
     * grant the SDK believes it made.
     */
    private val consentKeyCamelToSnake = mapOf(
        "biosignals" to "biosignals",
        "behavior" to "behavior",
        "phoneContext" to "phone_context",
        "cloudUpload" to "cloud_upload",
        "focusEstimation" to "focus_estimation",
        "emotionEstimation" to "emotion_estimation",
        "vendorSync" to "vendor_sync",
        "research" to "research",
        "syni" to "syni",
    )

    /**
     * Accept either spelling, return the snake_case key the runtime defines.
     * `motion` is a long-standing alias for `phoneContext`. An unrecognized
     * value passes through so a newer consent type still reaches the runtime
     * rather than being silently rewritten.
     */
    internal fun runtimeConsentKey(consentType: String): String {
        if (consentType == "motion") return "phone_context"
        return consentKeyCamelToSnake[consentType] ?: consentType
    }

    /** Accept either spelling, return the camelCase key the SDK switches on. */
    internal fun sdkConsentKey(consentType: String): String {
        if (consentType == "motion") return "phoneContext"
        if (consentKeyCamelToSnake.containsKey(consentType)) return consentType
        return consentKeyCamelToSnake.entries
            .firstOrNull { it.value == consentType }?.key
            ?: consentType
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
     * The native runtime's semantic version, or null when the shared library
     * is absent.
     *
     * Distinct from [SYNHEART_CORE_VERSION], which is this Kotlin SDK's own
     * version. For the full build metadata use [buildInfo]; for the runtime's
     * live state use [runtimeDiagnostics].
     */
    val runtimeVersion: String?
        get() = CoreRuntimeBridge.runtimeVersion()

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
            // Releases the relay, which unregisters the Data Layer listener.
            // Left dangling it would hold this Context and keep receiving watch
            // events into a module nothing reads.
            watchSessionModule?.dispose()
            watchSessionModule = null

            currentSessionHandle = null
            synheartConfig = null

            breathingModule = null
            baselineSnapshots.wireLocalHydrator(null)
            baselineSnapshots.reset()
            resetSessionBuffers()
            lastUploadBatchIdValue = null
            lastUploadAtValue = null
            lastUploadErrorValue = null
            lastUploadAttemptAtValue = null
            deviceAuthUsedCoreRuntime = false

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

    // ══════════════════════════════════════════════════════════════════ //
    // Runtime bridge, diagnostics and logging                            //
    // ══════════════════════════════════════════════════════════════════ //

    /**
     * The live runtime bridge, or null before [initialize] / after [dispose].
     *
     * Internal to the SDK — hosts should use the typed facades above. Exposed
     * so sibling facades (`SynheartIngestion`, backfill sinks) reach one
     * handle rather than opening their own.
     */
    val runtimeBridge: CoreRuntimeBridge? get() = coreRuntime

    /** True once [initialize] has completed successfully. */
    val isInitialized: Boolean get() = isConfigured

    /**
     * Whether the native runtime is loaded and usable.
     *
     * False means the shared library was never bundled — the usual cause is an
     * app that did not point `jniLibs.srcDirs` at the vendored runtime. The SDK
     * does not throw in that state: every FFI call degrades to a null/false
     * result, so nothing fails loudly and no HSI ever arrives. Check this
     * first when state does not show up.
     */
    val isRuntimeAvailable: Boolean
        get() = coreRuntime?.isOpen == true && CoreRuntimeBridge.isAvailable()

    /** Native runtime build metadata (profile, features, commit), or null. */
    fun buildInfo(): JSONObject? = CoreRuntimeBridge.buildInfo()

    /**
     * Full runtime diagnostics, annotated with `missingSymbols` — the symbols
     * the loaded native library turned out not to export. Check that list
     * before concluding a feature is merely disabled.
     */
    fun runtimeDiagnostics(): JSONObject? = coreRuntime?.diagnosticsMap()

    /**
     * Install the runtime's `tracing` subscriber. Call before [initialize] if
     * you need a custom filter or sink; [initialize] does not install logging
     * on your behalf.
     *
     * @param envFilter `RUST_LOG` syntax; null uses the SDK default.
     * @param onLine invoked on a native thread for each line — post to the
     *   main dispatcher before touching UI state.
     */
    fun initRuntimeLogging(envFilter: String? = null, onLine: ((String) -> Unit)? = null): Int =
        CoreRuntimeBridge.initLogging(envFilter, onLine)

    /**
     * Install logging in buffered mode and drain it yourself with
     * [drainRuntimeLogs]. No callback pointer crosses the boundary, so nothing
     * can dangle across a teardown.
     */
    fun initRuntimeLoggingBuffered(envFilter: String? = null): Int =
        CoreRuntimeBridge.initLoggingBuffered(envFilter)

    /** Drain buffered runtime log lines. Empty unless buffered mode is installed. */
    fun drainRuntimeLogs(): List<String> = CoreRuntimeBridge.drainLogs()

    /** Runtime log lines dropped because the ring buffer was full. */
    fun runtimeDroppedLogLines(): Long = CoreRuntimeBridge.droppedLogLines()

    /** Tear the runtime log subscriber down. */
    fun shutdownRuntimeLogging(): Int = CoreRuntimeBridge.shutdownLogging()

    /**
     * Spin the fusion pipeline up before the first sample arrives, so the
     * first window closes on schedule rather than one window late.
     */
    fun ensureRuntimePipeline() {
        coreRuntime?.ensurePipeline()
    }

    /**
     * Advance the pipeline clock to [nowMs]. Returns an HSI window JSON when
     * one closed on this tick, null otherwise.
     *
     * Only needed by hosts driving the runtime manually; normal collection
     * ticks itself.
     */
    fun tick(nowMs: Long = System.currentTimeMillis()): String? = coreRuntime?.tick(nowMs)

    /** Last computed feature vector as JSON, or null. */
    fun lastFeatures(): String? = coreRuntime?.lastFeatures()

    // ══════════════════════════════════════════════════════════════════ //
    // Session history and orphan cleanup                                 //
    // ══════════════════════════════════════════════════════════════════ //

    /**
     * Upper bound on HSI windows retained in memory for the active session.
     * The durable record lives in the runtime's storage — read it back with
     * [getHSIWindows].
     */
    const val MAX_SESSION_HSI_WINDOWS: Int = 2000

    /** Upper bound on wear samples retained in memory for the active session. */
    const val MAX_SESSION_WEAR_SAMPLES: Int = 5000

    private val sessionHsiWindows = ai.synheart.core.core.BoundedBuffer<String>(
        MAX_SESSION_HSI_WINDOWS,
    )

    /**
     * Suppresses HSI windows that reach the SDK twice — once from the native
     * callback and once as a batch-ingest return value.
     */
    private val hsiDeduper = ai.synheart.core.core.HsiDeliveryDeduper()

    /** All sessions the runtime has on record. Empty when the runtime is absent. */
    fun listSessions(): List<SessionRecord> = listLocalSessions()

    /**
     * Mark a stranded `state='active'` session as closed without going through
     * the normal stop-session lifecycle. Returns true on success (or when the
     * session was already closed).
     *
     * Used by [sweepOrphanSessions]; prefer that helper unless you know
     * exactly which session id to close.
     */
    fun closeOrphanSession(sessionId: String): Boolean =
        coreRuntime?.closeOrphanSession(sessionId) ?: false

    /**
     * Close every `state='active'` session that started longer than
     * [olderThanMs] ago. Returns the number actually closed.
     *
     * Call once on app start to clean up sessions the host failed to finalize
     * cleanly (force-kill mid-session, OS reclaim, sudden reboot). Without
     * this they accumulate in [listSessions] as eternally-active and pollute
     * downstream summaries and histories.
     *
     * The 6-hour default is generous for app sessions (typical ones are
     * minutes) and avoids racing a session the user just started and
     * backgrounded briefly.
     */
    fun sweepOrphanSessions(olderThanMs: Long = 6 * 60 * 60 * 1000L): Int {
        val sessions = listSessions()
        if (sessions.isEmpty()) return 0
        val cutoffMs = System.currentTimeMillis() - olderThanMs
        val orphans = sessions.filter { it.isActive && it.startUtc > 0 && it.startUtc < cutoffMs }
        if (orphans.isEmpty()) return 0
        SynheartLogger.log(
            "[Synheart] sweepOrphanSessions: closing ${orphans.size} stranded " +
                "session(s) older than ${olderThanMs / 3_600_000}h",
        )
        var closed = 0
        for (s in orphans) {
            try {
                if (closeOrphanSession(s.sessionId)) closed += 1
            } catch (e: Exception) {
                SynheartLogger.log(
                    "[Synheart] sweepOrphanSessions: closeOrphanSession(${s.sessionId}) threw: $e",
                )
            }
        }
        return closed
    }

    /**
     * HSI windows produced during the active session, oldest first. Capped at
     * [MAX_SESSION_HSI_WINDOWS]; older entries are evicted silently because
     * the durable copy lives in runtime storage.
     */
    fun getSessionHsiWindows(): List<String> = sessionHsiWindows.snapshot()

    /** True while a collection session is running. */
    val isSessionRunning: Boolean
        get() = currentSessionHandle != null || (coreRuntime?.isRunning() ?: false)

    // ══════════════════════════════════════════════════════════════════ //
    // Sensor push                                                        //
    // ══════════════════════════════════════════════════════════════════ //

    /**
     * Push one R-R interval in milliseconds.
     *
     * [provider] tags the source for the multi-source priority resolver, so a
     * chest strap and a watch feeding the same metric can be ranked rather
     * than interleaved.
     */
    fun pushRr(tsMs: Long, rrMs: Double, provider: String = "default_sensor") {
        coreRuntime?.pushRr(tsMs, rrMs, provider)
    }

    /**
     * Push a burst of R-R intervals that arrived in one sensor notification.
     *
     * [order] is 0 for oldest-first (the BLE Heart Rate Measurement
     * convention) and 1 for newest-first. Prefer this over a loop of [pushRr]:
     * pushing the burst in one call preserves the inter-beat timing that
     * per-sample pushes lose, which is what RMSSD is computed from.
     */
    fun pushRrBatch(
        anchorTsMs: Long,
        rrMs: DoubleArray,
        order: Int = 0,
        provider: String = "default_sensor",
    ) {
        coreRuntime?.pushRrBatch(anchorTsMs, rrMs, order, provider)
    }

    /** Push a heart-rate sample in BPM. */
    fun pushWearHr(tsMs: Long, bpm: Double) {
        coreRuntime?.pushHr(tsMs, bpm)
    }

    /** Push a 3-axis accelerometer sample. */
    fun pushAccel(tsMs: Long, x: Double, y: Double, z: Double) {
        coreRuntime?.pushAccel(tsMs, x, y, z)
    }

    /** Push vendor-derived HRV metrics the wearable already computed. */
    fun pushVendorHrv(
        tsMs: Long,
        rmssdMs: Double,
        sdnnMs: Double,
        stress: Double,
        recovery: Double,
    ) {
        coreRuntime?.pushVendorHrv(tsMs, rmssdMs, sdnnMs, stress, recovery)
    }

    /** Push vendor-derived vitals (SpO2 %, respiration rate). */
    fun pushVendorVitals(tsMs: Long, spo2: Double, respiration: Double) {
        coreRuntime?.pushVendorVitals(tsMs, spo2, respiration)
    }

    /**
     * Record a touch interaction. Interaction alone is enough to produce an
     * HSI digital axis, so this is worth wiring even with no wearable present.
     */
    fun pushBehaviorTouch(tsMs: Long = System.currentTimeMillis(), value: Double = 1.0) {
        coreRuntime?.pushBehavior(
            tsMs,
            ai.synheart.core.modules.behavior.RuntimeBehaviorEvent.INPUT.code,
            value,
        )
    }

    /** Record a received notification. */
    fun pushBehaviorNotificationReceived(
        tsMs: Long = System.currentTimeMillis(),
        value: Double = 1.0,
    ) {
        coreRuntime?.pushBehavior(
            tsMs,
            ai.synheart.core.modules.behavior.RuntimeBehaviorEvent.NOTIFICATION.code,
            value,
        )
    }

    /**
     * Ingest a pre-built batch of samples. Returns the runtime's result JSON,
     * which carries any HSI window the batch completed.
     */
    fun ingestBatch(batchJson: String, nowMs: Long = System.currentTimeMillis()): String? =
        coreRuntime?.ingestBatch(batchJson, nowMs)

    // ══════════════════════════════════════════════════════════════════ //
    // Personalization — task, focus, workout                              //
    // ══════════════════════════════════════════════════════════════════ //

    /**
     * Tell the engine what the user is doing, so Stage-5 confidence modulation
     * can account for it (mid-workout [TaskType.MOVEMENT] dampens cognitive
     * head confidence).
     */
    fun setTaskType(taskType: TaskType) {
        coreRuntime?.setTaskType(taskType.discriminant)
    }

    /** The active task type, or [TaskType.UNKNOWN] when none is set. */
    fun currentTaskType(): TaskType =
        TaskType.fromDiscriminant(coreRuntime?.currentTaskType() ?: 0)

    /** Grade the cognitive load of the active [TaskType.FOCUS] task. */
    fun setFocusKind(focusKind: FocusKind) {
        coreRuntime?.setFocusKind(focusKind.discriminant)
    }

    /** The active focus kind, or [FocusKind.UNKNOWN] when none is set. */
    fun currentFocusKind(): FocusKind =
        FocusKind.fromDiscriminant(coreRuntime?.currentFocusKind() ?: 0)

    /**
     * Push a completed workout window. [vendorStrain] / [vendorRecovery] are
     * optional; pass [Double.NaN] when the vendor supplied neither.
     */
    fun pushWorkoutEvent(
        startMs: Long,
        endMs: Long,
        workoutKind: Int,
        vendorStrain: Double = Double.NaN,
        vendorRecovery: Double = Double.NaN,
    ) {
        coreRuntime?.pushWorkoutEvent(startMs, endMs, workoutKind, vendorStrain, vendorRecovery)
    }

    /** Discriminant of the workout kind the engine currently considers active. */
    fun currentWorkoutKind(): Int = coreRuntime?.currentWorkoutKind() ?: -1

    /** Full personalization context as JSON — task, focus, workout, explanation trace. */
    fun personalizationContextJson(): String? = coreRuntime?.personalizationContextJson()

    /**
     * Convert an epoch-millisecond timestamp to the unix-epoch day index the
     * SRM APIs key on.
     */
    fun epochDayFor(timestampMs: Long = System.currentTimeMillis()): Int =
        (timestampMs / 86_400_000L).toInt()

    /**
     * Feed one day of a wearable-derived dimension into the SRM — the path
     * historical import uses to populate baselines.
     *
     * Recognized dimensions include `hrv_rmssd_ms`, `resting_hr_bpm`,
     * `sleep_efficiency`, `recovery_score`, `deep_sleep_min`, `rem_sleep_min`
     * and `daily_strain`.
     *
     * [dayIndex] is the unix-epoch day (see [epochDayFor]). [fidelity] is
     * `0 = raw observation`, `1 = vendor summary`; most vendor backfill pushes
     * are `1` with a confidence of 0.80–0.90.
     *
     * Call [srmTriggerWearableRecompute] once after a bulk push rather than
     * after every day.
     */
    fun srmPushWearableDaily(
        dimension: String,
        dayIndex: Int,
        value: Double,
        confidence: Double = 0.85,
        fidelity: Int = 1,
    ) {
        coreRuntime?.srmPushWearableDaily(dimension, dayIndex, value, confidence, fidelity)
    }

    /**
     * Recompute SRM baselines and propagate the resulting wearable reference
     * to the state runtime.
     *
     * [triggerType] is `0 = Window` (incremental, recommended),
     * `1 = AffectedWindow`, `2 = Full` (rebuild everything). [asOfDay]
     * defaults to today's epoch day.
     */
    fun srmTriggerWearableRecompute(triggerType: Int = 0, asOfDay: Int? = null) {
        coreRuntime?.srmTriggerWearableRecompute(triggerType, asOfDay ?: epochDayFor())
    }

    // ══════════════════════════════════════════════════════════════════ //
    // Scores — sleep, recovery, readiness                                 //
    // ══════════════════════════════════════════════════════════════════ //

    /** Compute a sleep score from a typed input. Null when the runtime declines. */
    fun computeSleepScore(input: SleepScoreInput): SleepScoreResult? =
        coreRuntime?.sleepScoreComputeJson(input.toJsonString())
            ?.let { runCatching { SleepScoreResult.fromJsonString(it) }.getOrNull() }

    /** Raw JSON form of [computeSleepScore], for hosts that forward the payload verbatim. */
    fun computeSleepScoreJson(inputJson: String): String? =
        coreRuntime?.sleepScoreComputeJson(inputJson)

    /** [computeSleepScoreJson] tagged with a correlation id for cross-service tracing. */
    fun computeSleepScoreJsonTraced(inputJson: String, correlationId: String): String? =
        coreRuntime?.sleepScoreComputeJsonTraced(inputJson, correlationId)

    /** The last sleep score the runtime computed, as JSON. */
    fun lastSleepScoreJson(): String? = coreRuntime?.lastSleepScoreJson()

    /**
     * Attach a sleep-score result to today's longitudinal record so downstream
     * recovery and readiness can see it. Returns true on success.
     */
    fun attachSleepScore(result: SleepScoreResult): Boolean =
        attachSleepScoreJson(result.toJson().toString())

    /** Raw-JSON form of [attachSleepScore]. */
    fun attachSleepScoreJson(resultJson: String): Boolean =
        coreRuntime?.attachSleepScoreJson(resultJson) == 0

    /** Compute a recovery score from a typed input. */
    fun computeRecoveryScore(input: RecoveryScoreInput): RecoveryScoreResult? =
        coreRuntime?.recoveryScoreComputeJson(input.toJsonString())
            ?.let { runCatching { RecoveryScoreResult.fromJsonString(it) }.getOrNull() }

    /** Raw JSON form of [computeRecoveryScore]. */
    fun computeRecoveryScoreJson(inputJson: String): String? =
        coreRuntime?.recoveryScoreComputeJson(inputJson)

    /** [computeRecoveryScoreJson] tagged with a correlation id. */
    fun computeRecoveryScoreJsonTraced(inputJson: String, correlationId: String): String? =
        coreRuntime?.recoveryScoreComputeJsonTraced(inputJson, correlationId)

    /**
     * Pin today's recovery score (0..100) so readiness picks it up without
     * recomputing. Returns true on success.
     */
    fun attachRecoveryScoreToday(score: Int): Boolean =
        coreRuntime?.attachRecoveryScoreToday(score) == 0

    /** Clear today's pinned recovery score. Returns true on success. */
    fun clearRecoveryScoreToday(): Boolean =
        coreRuntime?.clearRecoveryScoreToday() == 0

    /** Compute a readiness score from a typed input. */
    fun computeReadinessScore(input: ReadinessScoreInput): ReadinessScoreResult? =
        coreRuntime?.readinessScoreComputeJson(input.toJsonString())
            ?.let { runCatching { ReadinessScoreResult.fromJsonString(it) }.getOrNull() }

    /** Raw JSON form of [computeReadinessScore]. */
    fun computeReadinessScoreJson(inputJson: String): String? =
        coreRuntime?.readinessScoreComputeJson(inputJson)

    /** [computeReadinessScoreJson] tagged with a correlation id. */
    fun computeReadinessScoreJsonTraced(inputJson: String, correlationId: String): String? =
        coreRuntime?.readinessScoreComputeJsonTraced(inputJson, correlationId)

    /**
     * Per-dimension wearable baseline reference — what "normal" currently
     * means for this user. Null before the SRM has enough history.
     */
    fun wearableReference(): WearableReferenceView? =
        coreRuntime?.wearableReferenceJson()
            ?.let { runCatching { WearableReferenceView.fromJsonString(it) }.getOrNull() }

    /** Export the longitudinal (multi-day) snapshot as JSON, for backup or transfer. */
    fun longitudinalSnapshotJson(): String? = coreRuntime?.exportLongitudinalSnapshot()

    /** Restore a longitudinal snapshot produced by [longitudinalSnapshotJson]. */
    fun loadLongitudinalSnapshot(json: String): Boolean =
        coreRuntime?.loadLongitudinalSnapshot(json) ?: false

    // ══════════════════════════════════════════════════════════════════ //
    // Cross-device sync                                                   //
    //                                                                     //
    // Every call below throws SyncNativeException when the runtime returns //
    // a failure envelope, so the reason survives instead of collapsing to  //
    // a bare null. Check readiness first with checkSyncReadiness.          //
    // ══════════════════════════════════════════════════════════════════ //

    /** Enable or disable background sync. */
    fun setSyncEnabled(enabled: Boolean) {
        coreRuntime?.setSyncEnabled(enabled)
    }

    /**
     * Trigger an immediate sync cycle and report what moved.
     *
     * @throws IllegalStateException when the native sync runtime is
     *   unavailable — silently reporting "0 pushed, 0 pulled" would read as a
     *   successful no-op sync.
     * @throws SyncNativeException when the runtime reports a failure envelope.
     */
    fun syncNow(): ai.synheart.core.sync.SyncResult {
        val runtime = coreRuntime
            ?: throw IllegalStateException("The native sync runtime is unavailable.")
        return ai.synheart.core.sync.SyncResult.fromRuntimeResponse(runtime.syncNow())
    }

    /** Raw sync-cycle response, for hosts that want the full payload. */
    fun syncNowRaw(): JSONObject? = coreRuntime?.syncNow()

    /** Create a new sync space owned by this device. */
    fun syncCreateSpace(deviceName: String? = null): JSONObject? =
        coreRuntime?.syncCreateSpace(deviceName)

    /** Mint a short-lived pairing token for another device to join with. */
    fun syncGeneratePairing(): JSONObject? = coreRuntime?.syncGeneratePairing()

    /** Join an existing space using a pairing token. */
    fun syncJoinSpace(pairingToken: String, deviceName: String? = null): JSONObject? =
        coreRuntime?.syncJoinSpace(pairingToken, deviceName)

    /** Raw sync-engine status snapshot. */
    fun syncStatusSnapshot(): JSONObject? = coreRuntime?.syncStatus()

    /** Raw sync-readiness snapshot — the native half of [checkSyncReadiness]. */
    fun syncReadinessSnapshot(): JSONObject? = coreRuntime?.syncReadiness()

    /** Recover space access from a recovery key. */
    fun syncRecoverSpace(recoveryKey: String, spaceId: String): JSONObject? =
        coreRuntime?.syncRecoverSpace(recoveryKey, spaceId)

    /** Leave the current space; it stays alive for the other devices. */
    fun syncLeaveSpace(): JSONObject? = coreRuntime?.syncLeaveSpace()

    /** List devices in the current space. */
    fun syncListDevices(): JSONObject? = coreRuntime?.syncListDevices()

    /** Revoke another device's membership. */
    fun syncRevokeDevice(deviceId: String): JSONObject? = coreRuntime?.syncRevokeDevice(deviceId)

    /** Delete the space for everyone. Owner only. */
    fun syncDeleteSpace(): JSONObject? = coreRuntime?.syncDeleteSpace()

    /**
     * Forget local space state without touching the server. A recovery hatch:
     * it stays available after consent withdrawal, capability change,
     * registration loss, or device revocation.
     */
    fun syncClearLocalSpace(): JSONObject? = coreRuntime?.syncClearLocalSpace()

    /**
     * Whether [operation] can run right now, and if not, precisely why.
     *
     * Composes SDK-side authorization (activation, cloud consent, capability)
     * with the native readiness snapshot, so a host can render a specific
     * blocker rather than a generic "sync unavailable".
     */
    fun checkSyncReadiness(operation: ai.synheart.core.sync.SyncOperation):
        ai.synheart.core.sync.SyncReadiness {
        val snapshot = runCatching { syncReadinessSnapshot() }.getOrNull()
        return ai.synheart.core.sync.SyncReadiness.evaluate(
            operation = operation,
            activated = isActivated(SynheartFeature.CLOUD),
            cloudConsentGranted = consentEffectiveStateTyped()?.cloudUpload ?: false,
            capabilityAllowed = isCapabilityAllowed(SynheartFeature.CLOUD),
            nativeSnapshot = snapshot,
        )
    }

    /**
     * Whether background sync is enabled, preferring the runtime's own view
     * and falling back to the configured value before the runtime loads.
     */
    fun getSyncStatus(): ai.synheart.core.sync.SyncStatus {
        val nativeEnabled = runCatching { syncStatusSnapshot() }.getOrNull()
            ?.takeIf { it.has("enabled") }
            ?.optBoolean("enabled")
        return ai.synheart.core.sync.SyncStatus(
            enabled = nativeEnabled ?: (synheartConfig?.sync?.enabled ?: false),
        )
    }

    /**
     * Export an encrypted, passphrase-protected baseline blob (base64) for
     * offline transfer to another device.
     */
    fun baselineExportOffline(passphrase: String): String? =
        coreRuntime?.baselineExportOffline(passphrase)

    /** Import a blob produced by [baselineExportOffline]. Returns the report JSON. */
    fun baselineImportOffline(passphrase: String, blobB64: String): JSONObject? =
        coreRuntime?.baselineImportOffline(passphrase, blobB64)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

    // ══════════════════════════════════════════════════════════════════ //
    // Cloud upload queue and HSI history                                  //
    // ══════════════════════════════════════════════════════════════════ //

    /**
     * Depth of the outbound upload queue.
     *
     * Diagnostic only — do not surface it to end users. It fluctuates on every
     * flush tick and reads as scary noise; drive user-facing copy from
     * [cloudSyncStatus] or [lastIngestSuccessAtMs] instead.
     */
    val uploadQueueLength: Int get() = coreRuntime?.uploadQueueLength() ?: 0

    /**
     * Wall-clock time of the most recent successful ingest, or null when
     * nothing has uploaded yet. This is what a "Synced N min ago" badge reads.
     */
    val lastIngestSuccessAtMs: Long? get() = coreRuntime?.lastIngestSuccessAtMs()

    @Volatile
    private var lastUploadBatchIdValue: String? = null

    @Volatile
    private var lastUploadAtValue: java.time.Instant? = null

    @Volatile
    private var lastUploadErrorValue: String? = null

    @Volatile
    private var lastUploadAttemptAtValue: java.time.Instant? = null

    /** Batch id from the last successful cloud ingest, or null. */
    val lastUploadBatchId: String? get() = lastUploadBatchIdValue

    /** Time of the last successful cloud ingest, or null. */
    val lastUploadAt: java.time.Instant? get() = lastUploadAtValue

    /** Last upload error, or null when the last attempt succeeded. */
    val lastUploadError: String? get() = lastUploadErrorValue

    /** Time of the last upload attempt, success or failure. */
    val lastUploadAttemptAt: java.time.Instant? get() = lastUploadAttemptAtValue

    /** Record an upload attempt. Internal bookkeeping for [SynheartIngestion]. */
    internal fun recordUploadAttempt(error: String?) {
        lastUploadAttemptAtValue = java.time.Instant.now()
        lastUploadErrorValue = error
    }

    /** Record a successful flush. Internal bookkeeping for [SynheartIngestion]. */
    internal fun recordUploadSuccess(batchId: String?, uploaded: Int) {
        val now = java.time.Instant.now()
        lastUploadAtValue = now
        lastUploadErrorValue = null
        if (uploaded > 0) {
            lastUploadBatchIdValue = batchId ?: "flush_${now.toEpochMilli()}"
        }
    }

    /**
     * Aggregate cloud-sync state for host UI — one pill, not three signals to
     * combine by hand. See [CloudSyncStatus].
     */
    val cloudSyncStatus: ai.synheart.core.modules.cloud.CloudSyncStatus
        get() {
            val cloudOn = consentEffectiveStateTyped()?.cloudUpload ?: false
            if (!cloudOn) return ai.synheart.core.modules.cloud.CloudSyncStatus.LOCAL_ONLY
            if (uploadQueueLength > 0) {
                return ai.synheart.core.modules.cloud.CloudSyncStatus.SYNCING
            }
            if (lastIngestSuccessAtMs != null) {
                return ai.synheart.core.modules.cloud.CloudSyncStatus.SYNCED
            }
            return ai.synheart.core.modules.cloud.CloudSyncStatus.PENDING
        }

    /** Bridge-first ingestion facade for queue + upload orchestration. */
    val ingestion: ai.synheart.core.modules.cloud.SynheartIngestion
        get() = ai.synheart.core.modules.cloud.SynheartIngestion

    // ── HSI history (on-device mirror of uploaded payloads) ──────────────
    //
    // The native ingest connector deletes rows from the outbound upload queue
    // on HTTP 200 — that table is pure "pending uploads". `hsi_history` is a
    // separate on-device table keeping a copy of each successfully uploaded
    // HSI payload, so apps can render offline timelines and users keep access
    // to their data. Retention is age-based (default 30 days, enforced
    // natively on each archive pass). These are pure on-device operations —
    // no network I/O.

    /**
     * Archived HSI payloads, oldest first. [sinceMs] of 0 returns all;
     * [limit] of 0 is unbounded. Empty when no cloud connector is wired.
     */
    fun listHsiHistory(sinceMs: Long = 0, limit: Long = 0): List<JSONObject> {
        val arr = coreRuntime?.hsiHistoryList(sinceMs, limit) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /**
     * Fetch normalized HSI windows from the **cloud archive** for
     * `[fromMs, toMs]`.
     *
     * Unlike [listHsiHistory] (the on-device 30-day mirror) this pulls the
     * user's archived windows from the cloud — the source of truth for
     * historical (>30-day) and cross-device HSI. Consent- and
     * device-auth-gated runtime-side; empty when cloud is not
     * configured/consented, the range is empty, or the vendored runtime
     * predates this capability.
     *
     * The underlying FFI call performs the network round-trip synchronously
     * (native `block_on`), so drive it from a background dispatcher.
     */
    fun fetchCloudHsiWindows(fromMs: Long, toMs: Long): List<JSONObject> {
        val arr = coreRuntime?.fetchCloudHsi(fromMs, toMs) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /** Number of archived HSI payloads currently on-device. */
    fun hsiHistoryCount(): Long = coreRuntime?.hsiHistoryCount() ?: 0L

    /**
     * Wipe the on-device HSI history, for user-initiated "delete my data"
     * flows. Does NOT clear the outbound upload queue — anything already
     * pending will still be sent.
     */
    fun clearHsiHistory(): Boolean = coreRuntime?.hsiHistoryClear() ?: false

    /**
     * Whether a full batch ingest runs when a session stops. Null leaves the
     * decision to the runtime's own configuration.
     */
    @Volatile
    var batchIngestOnStop: Boolean? = null
        private set

    /** Set the [batchIngestOnStop] policy for subsequent sessions. */
    fun setBatchIngestOnStop(enabled: Boolean?) {
        batchIngestOnStop = enabled
    }

    // ══════════════════════════════════════════════════════════════════ //
    // Consent                                                            //
    // ══════════════════════════════════════════════════════════════════ //

    /** Configure the runtime's cloud consent client. Returns true on success. */
    fun consentConfigureCloud(baseUrl: String, appId: String? = null): Boolean =
        coreRuntime?.consentConfigureCloud(baseUrl, appId ?: synheartConfig?.appId.orEmpty())
            ?: false

    /** The editable consent form as raw JSON. */
    fun consentGetEditableForm(): JSONObject? =
        coreRuntime?.consentGetEditableForm()
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** The editable consent form, typed. Prefer this over the raw JSON form. */
    fun consentGetEditableFormTyped(): ai.synheart.core.modules.consent.ConsentForm? =
        consentGetEditableForm()?.let {
            runCatching { ai.synheart.core.modules.consent.ConsentForm.fromJson(it) }.getOrNull()
        }

    /**
     * Submit a consent form: mints or refreshes the cloud consent token under
     * the runtime's current subject. Returns the service result, including the
     * issued token or an `error`.
     */
    suspend fun consentSubmitForm(
        formJson: String,
        deviceId: String? = null,
        platform: String = "android",
        userId: String? = null,
    ): JSONObject? {
        val cr = coreRuntime ?: return null
        val raw = cr.consentSubmitForm(deviceId, platform, userId, formJson) ?: return null
        val result = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        // Keep the SDK's consent mirror in step with what the runtime just
        // minted, so a host reading `currentConsent` right after submitting
        // does not see the pre-submit snapshot.
        runCatching { syncConsentModuleFromRuntime() }
        return result
    }

    /** Typed form of [consentSubmitForm]. */
    suspend fun consentSubmitFormTyped(
        form: ai.synheart.core.modules.consent.ConsentForm,
        deviceId: String? = null,
        platform: String = "android",
        userId: String? = null,
    ): JSONObject? = consentSubmitForm(form.toJson().toString(), deviceId, platform, userId)

    /** Cloud consent status (e.g. `{"status":"granted"}`). */
    fun consentStatus(): JSONObject? =
        coreRuntime?.consentStatus()?.let { runCatching { JSONObject(it) }.getOrNull() }

    /**
     * Effective consent state as raw JSON — what the user actually chose,
     * token-authoritative when a token is present.
     */
    fun consentEffectiveState(): JSONObject? =
        coreRuntime?.consentEffectiveState()?.let { runCatching { JSONObject(it) }.getOrNull() }

    /**
     * Effective consent state, typed.
     *
     * This is the right call for rendering consent UI. [hasConsent] answers a
     * different question — whether a channel is *enforceable* right now, which
     * a cloud-configured app reads as false until the consent service issues a
     * token, whatever the user chose.
     */
    fun consentEffectiveStateTyped(): ai.synheart.core.modules.consent.ConsentEffectiveState? =
        consentEffectiveState()?.let {
            runCatching {
                ai.synheart.core.modules.consent.ConsentEffectiveState.fromJson(it)
            }.getOrNull()
        }

    /** Stream of consent snapshots, emitting on every change. */
    val consentChanges: Flow<ConsentSnapshot>
        get() = consentModule?.observe() ?: kotlinx.coroutines.flow.emptyFlow()

    /** True when the consent token is close enough to expiry to re-mint. */
    fun consentNeedsTokenRefresh(): Boolean = coreRuntime?.consentNeedsTokenRefresh() ?: false

    /** Clear the stored consent token and snapshot. */
    fun consentClearStored(): Boolean = coreRuntime?.consentClearStored() ?: false

    /** Revoke one consent channel by its wire key (e.g. `"cloudUpload"`). */
    suspend fun revokeConsentType(consentType: String) = revokeConsent(consentType)

    /**
     * Record that the user explicitly declined, as distinct from never having
     * been asked — so a host does not re-prompt someone who already said no.
     */
    suspend fun denyConsent() {
        consentModule?.denyConsent()
    }

    /** True when no consent decision has been recorded yet. */
    fun needsConsent(): Boolean {
        val current = consentModule?.current() ?: return true
        return !current.explicitlyDenied &&
            !current.biosignals &&
            !current.behavior &&
            !current.phoneContext &&
            !current.cloudUpload &&
            !current.vendorSync &&
            !current.research &&
            !current.syni
    }

    /** The SDK's current consent snapshot as a channel-keyed map. */
    fun getConsentStatusMap(): Map<String, Boolean> {
        val current = consentModule?.current() ?: return emptyMap()
        return ai.synheart.core.modules.interfaces.ConsentType.entries.associate {
            it.wireKey to current.allows(it)
        }
    }

    /** Cloud consent status string (`granted` / `pending` / …), or null. */
    fun getConsentStatus(): String? = consentStatus()?.optString("status")?.takeIf {
        it.isNotEmpty()
    }

    /** Mint-time metadata about the stored consent token, or null when none is stored. */
    fun getConsentInfo(): JSONObject? = consentStatus()

    /** The stored cloud consent token, or null. */
    fun getCurrentConsentToken(): String? =
        consentStatus()?.optString("token")?.takeIf { it.isNotEmpty() }

    /**
     * Sync the SDK's consent mirror from the runtime's effective state, so the
     * two cannot disagree after a token mint.
     */
    private suspend fun syncConsentModuleFromRuntime() {
        val effective = consentEffectiveStateTyped() ?: return
        val current = consentModule?.current() ?: ConsentSnapshot.none()
        consentModule?.updateConsent(
            current.copyWith(
                biosignals = effective.biosignals,
                phoneContext = effective.phoneContext,
                behavior = effective.behavior,
                cloudUpload = effective.cloudUpload,
                syni = effective.syni,
                vendorSync = effective.vendorSync,
                research = effective.research,
            ),
        )
    }

    /** Record a signed study-consent affirmation payload. */
    fun recordStudyConsent(payloadJson: String): JSONObject? =
        coreRuntime?.recordStudyConsent(payloadJson)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Current research-study enrolment status. */
    fun researchStudyStatus(): JSONObject? =
        coreRuntime?.researchStudyStatus()?.let { runCatching { JSONObject(it) }.getOrNull() }

    // ══════════════════════════════════════════════════════════════════ //
    // Data deletion (GDPR Article 17)                                     //
    // ══════════════════════════════════════════════════════════════════ //

    /**
     * File an erasure request for this subject's cloud-held data.
     *
     * [dryRun] returns an inventory of what would be deleted without deleting
     * anything — worth showing the user before the irreversible call.
     */
    fun requestDataDeletion(
        reason: String? = null,
        contact: String? = null,
        dryRun: Boolean = false,
    ): DataDeletionRequest? =
        coreRuntime?.requestDataDeletion(reason, contact, dryRun)
            ?.let { runCatching { DataDeletionRequest.fromJson(JSONObject(it)) }.getOrNull() }

    /** Status of one erasure request, by its `request_id`. */
    fun dataDeletionStatus(requestId: String): DataDeletionRequest? =
        coreRuntime?.getDataDeletion(requestId)
            ?.let { runCatching { DataDeletionRequest.fromJson(JSONObject(it)) }.getOrNull() }

    /** Page through this subject's erasure requests. */
    fun listDataDeletions(limit: Int = 20, offset: Int = 0): DataDeletionList =
        coreRuntime?.listDataDeletions(limit, offset)
            ?.let { runCatching { DataDeletionList.fromJson(JSONObject(it)) }.getOrNull() }
            ?: DataDeletionList(emptyList(), 0)

    /** Erase everything held on this device. Local only — the cloud is untouched. */
    suspend fun deleteLocalData(): Boolean = coreRuntime?.wipeLocalData() ?: false

    /**
     * Request cloud-side erasure. A convenience wrapper over
     * [requestDataDeletion] for hosts that just want the request filed.
     */
    fun deleteCloudData(reason: String? = null, contact: String? = null): DataDeletionRequest? =
        requestDataDeletion(reason = reason, contact = contact, dryRun = false)

    /**
     * Clear one module's cached data without touching the rest. Returns false
     * for an unknown module name.
     */
    suspend fun deleteModuleData(module: String): Boolean = when (module.lowercase()) {
        "wear" -> { wearModule?.clearCache(); true }
        "behavior" -> { behaviorModule?.clearCache(); true }
        "phone" -> { phoneModule?.clearCache(); true }
        else -> false
    }

    // ══════════════════════════════════════════════════════════════════ //
    // Device auth                                                        //
    // ══════════════════════════════════════════════════════════════════ //

    /** Device-auth status as reported by the runtime. */
    fun coreDeviceAuthStatus(): JSONObject? =
        coreRuntime?.deviceAuthStatus()?.let { runCatching { JSONObject(it) }.getOrNull() }

    /**
     * Build a device-signed proof header for an outbound request the host
     * makes itself. Null when no device key is registered.
     */
    fun buildProofHeader(method: String, absoluteUrl: String): String? =
        coreRuntime?.buildProofHeader(method, absoluteUrl)

    /** True when the loaded runtime exposes the SDK device-auth entry points. */
    val coreSdkDeviceAuthAvailable: Boolean
        get() = coreRuntime?.let { it.isOpen && "sdk_build_proof_header" !in it.missingSymbols }
            ?: false

    /** True once a registration has completed through the runtime in this process. */
    @Volatile
    var deviceAuthUsedCoreRuntime: Boolean = false
        private set

    /**
     * Make sure this device is registered for the current subject, registering
     * it if not. Returns true when the device ends up registered.
     *
     * Safe to call repeatedly — an already-registered device short-circuits.
     */
    suspend fun ensureDeviceAuthRegistered(): Boolean {
        val cr = coreRuntime ?: return false
        val status = runCatching { coreDeviceAuthStatus() }.getOrNull()
        if (status?.optBoolean("registered", false) == true) {
            deviceAuthUsedCoreRuntime = true
            return true
        }
        val clientId = subjectId ?: return false
        val reg = runCatching { cr.registerDevice(clientId) }.getOrNull()
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val ok = reg?.optString("device_id")?.isNotEmpty() == true
        if (ok) deviceAuthUsedCoreRuntime = true
        return ok
    }

    /**
     * Force a fresh registration even when one already exists — for recovering
     * from a server-side revocation or a key rotation.
     */
    suspend fun reregisterDeviceAuth(): Boolean {
        val cr = coreRuntime ?: return false
        val clientId = subjectId ?: return false
        val reg = runCatching { cr.registerDevice(clientId) }.getOrNull()
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
        val ok = reg?.optString("device_id")?.isNotEmpty() == true
        if (ok) deviceAuthUsedCoreRuntime = true
        return ok
    }

    // ══════════════════════════════════════════════════════════════════ //
    // Vendor event store                                                  //
    // ══════════════════════════════════════════════════════════════════ //

    /** Most recent stored event for `(provider, eventType)`, or null. */
    fun getLatestVendorEvent(provider: String, eventType: String): JSONObject? =
        coreRuntime?.getLatestVendorEvent(provider, eventType)

    /** Query the stored vendor events. */
    fun queryVendorEvents(queryJson: String): List<JSONObject> {
        val arr = coreRuntime?.queryVendorEvents(queryJson) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
    }

    /**
     * Drop every stored event for a provider — the unlink path. Returns the
     * number of rows deleted.
     */
    fun deleteVendorEventsForProvider(provider: String): Long =
        coreRuntime?.deleteVendorEventsForProvider(provider) ?: 0L

    /** Persist a canonical vendor event. Returns true on success. */
    fun ingestVendorEvent(eventJson: String): Boolean =
        coreRuntime?.ingestVendorEvent(eventJson) ?: false

    // ══════════════════════════════════════════════════════════════════ //
    // Lab protocol                                                        //
    // ══════════════════════════════════════════════════════════════════ //

    /** True when the lab module is compiled into the loaded runtime. */
    val isLabAvailable: Boolean get() = coreRuntime?.isLabAvailable() ?: false

    /** True when this runtime exposes the lab metadata entry points. */
    val isLabMetadataAvailable: Boolean
        get() = coreRuntime?.let { "lab_ensure_metadata" !in it.missingSymbols } ?: false

    /** True when this runtime can re-enqueue a finalized lab session. */
    val isLabReenqueueAvailable: Boolean
        get() = coreRuntime?.let { "reenqueue_lab_session" !in it.missingSymbols } ?: false

    /** Start a lab protocol. */
    fun labStart(protocolJson: String, startedAtMs: Long = System.currentTimeMillis()): Boolean =
        coreRuntime?.labStart(protocolJson, startedAtMs) ?: false

    /** Open a lab window; returns its id. */
    fun labOpenWindow(
        windowType: String,
        parentId: String? = null,
        label: String? = null,
        startedAtMs: Long = System.currentTimeMillis(),
    ): String? = coreRuntime?.labOpenWindow(parentId, windowType, label, startedAtMs)

    /** Close a lab window. */
    fun labCloseWindow(windowId: String, endedAtMs: Long = System.currentTimeMillis()): Boolean =
        coreRuntime?.labCloseWindow(windowId, endedAtMs) ?: false

    /** Set values on a lab window. */
    fun labSetWindowValues(windowId: String, valuesJson: String): Boolean =
        coreRuntime?.labSetWindowValues(windowId, valuesJson) ?: false

    /** Merge a patch into the protocol's extra data. */
    fun labMergeSessionExtraData(patchJson: String): Boolean =
        coreRuntime?.labMergeExtraData(patchJson) ?: false

    /** Set state overrides on a lab window. */
    fun labSetWindowStateOverrides(windowId: String, overridesJson: String): Boolean =
        coreRuntime?.labSetStateOverrides(windowId, overridesJson) ?: false

    /** Finalize the protocol and return the session JSON. */
    fun labFinalize(endedAtMs: Long = System.currentTimeMillis()): String? =
        coreRuntime?.labFinalize(endedAtMs)

    /** Export the in-progress protocol as JSON. */
    fun labExportJson(): String? = coreRuntime?.labExportJson()

    /** Ensure a lab metadata record exists for this device/user. */
    fun labEnsureMetadata(
        deviceId: String,
        platform: String = "android",
        osVersion: String = android.os.Build.VERSION.RELEASE ?: "",
        userInfoJson: String? = null,
        deviceExtraJson: String? = null,
    ): String? = coreRuntime?.labEnsureMetadata(
        deviceId,
        platform,
        osVersion,
        userInfoJson,
        deviceExtraJson,
    )

    /** Id of the current lab metadata record, or null. */
    fun labCurrentMetadataId(): String? = coreRuntime?.labCurrentMetadataId()

    /** Mark the metadata record dirty so it re-uploads. */
    fun labMarkMetadataDirty(reason: String): Boolean =
        coreRuntime?.labMarkMetadataDirty(reason) ?: false

    /** Re-enqueue a finalized lab session for upload. Returns true on success. */
    fun labReenqueueSession(sessionJson: String): Boolean =
        coreRuntime?.reenqueueLabSession(sessionJson) == 0

    // ══════════════════════════════════════════════════════════════════ //
    // Typed baseline snapshots                                            //
    // ══════════════════════════════════════════════════════════════════ //

    /**
     * Host-facing facade for typed baseline-snapshot access — one cached
     * envelope per [ai.synheart.core.baseline.BaselineKind].
     */
    val baselineSnapshots: ai.synheart.core.baseline.BaselineSnapshots =
        ai.synheart.core.baseline.BaselineSnapshots()

    // ══════════════════════════════════════════════════════════════════ //
    // Feature modules                                                     //
    // ══════════════════════════════════════════════════════════════════ //

    /** The breathing-compliance module, or null before [initialize]. */
    val breathing: ai.synheart.core.modules.breathing.BreathingModule?
        get() = breathingModule

    private var breathingModule: ai.synheart.core.modules.breathing.BreathingModule? = null

    /**
     * The wear module, for hosts wiring their own sources or reading module
     * status. Null before [initialize].
     */
    @get:JvmName("wearModule")
    val wearModulePublic: WearModule? get() = wearModule

    // ══════════════════════════════════════════════════════════════════ //
    // Watch session API                                                   //
    //                                                                     //
    // Runs a session on a paired Wear OS watch. The watch owns the session //
    // and computes the metrics; the phone only relays commands and events. //
    // ══════════════════════════════════════════════════════════════════ //

    private var watchSessionModule: WatchSessionModule? = null

    /** Whether a watch session is currently running. */
    val isWatchSessionActive: Boolean get() = watchSessionModule?.isActive ?: false

    /** The active watch session's id, if any. */
    val activeWatchSessionId: String? get() = watchSessionModule?.activeSessionId

    /**
     * Events from the active watch session.
     *
     * `SessionStarted` → `SessionFrame*` → `SessionSummary`; each frame carries
     * the HR metrics the watch computed. Empty before [initialize] rather than
     * throwing, so a host can subscribe during composition.
     */
    val watchSessionEvents: Flow<ai.synheart.session.SessionEvent>
        get() = watchSessionModule?.events ?: kotlinx.coroutines.flow.emptyFlow()

    /**
     * Watch connectivity, or null before [initialize].
     *
     * Null means this SDK was never initialized; a returned
     * `WatchStatus(supported = false)` means the device has no watch transport
     * at all. Worth telling apart — the first is a wiring mistake, the second is
     * the hardware.
     */
    suspend fun getWatchStatus(): ai.synheart.session.WatchStatus? =
        watchSessionModule?.getWatchStatus()

    /**
     * Start a session on the companion watch.
     *
     * @throws IllegalStateException before [initialize], or when a watch session
     *   is already running.
     */
    fun startWatchSession(
        config: ai.synheart.session.SessionConfig,
    ): Flow<ai.synheart.session.SessionEvent> {
        val mod = checkNotNull(watchSessionModule) {
            "Synheart must be initialized before starting a watch session"
        }
        return mod.startSession(config)
    }

    /** Stop the active watch session. No-op when none is running. */
    suspend fun stopWatchSession() {
        watchSessionModule?.stopSession()
    }

    /**
     * The real biosignal source, when one is attached.
     *
     * Present only when the config declared `wearConfig`; null otherwise, and
     * null when the host is driving biosignals itself through [pushWearHr] /
     * [pushRr].
     */
    private val wearSourceHandler: SynheartWearSourceHandler?
        get() = wearModule?.attachedSources
            ?.filterIsInstance<SynheartWearSourceHandler>()
            ?.firstOrNull()

    /**
     * Whether a real biosignal source is attached.
     *
     * False when the config declared no `wearConfig`, or when the source failed
     * to construct. Distinct from "no samples yet": an attached source that is
     * emitting nothing is a permission or availability problem, not a wiring
     * one, and the two have completely different fixes.
     */
    val hasWearSource: Boolean get() = wearSourceHandler != null

    /**
     * Whether the platform health store can be queried at all.
     *
     * False when Health Connect is not installed — the permission map comes back
     * empty rather than all-false, so an absent store is otherwise
     * indistinguishable from a store that simply denied everything.
     */
    val isWearPlatformAvailable: Boolean get() = wearPermissionStatus().isNotEmpty()

    /**
     * Ask `synheart-wear` to request the health permissions.
     *
     * **This cannot show a prompt on Health Connect today.** Health Connect
     * grants are driven by an `ActivityResultContract`, and synheart-wear keeps
     * its contract `internal` — so the call resolves without any UI appearing
     * and returns the grants as they already stood. A host that treats the
     * returned map as "the user answered" will conclude they declined.
     *
     * Use [openHealthPermissionSettings] instead until synheart-wear publishes
     * its contract, or register Health Connect's own
     * `PermissionController.createRequestPermissionResultContract()` in your
     * Activity.
     *
     * Returns the grant map, empty when no wear source is attached.
     */
    suspend fun requestWearPermissions(): Map<String, Boolean> =
        wearSourceHandler?.requestPermissions()?.mapKeys { it.key.name } ?: emptyMap()

    /**
     * Open Health Connect's permission screen for this app.
     *
     * The working grant path on Android today, and the reason it exists here:
     * manifest declarations are not grants, Health Connect gates reads behind a
     * user decision, and [requestWearPermissions] cannot raise that decision
     * itself. Without a grant the wear source polls an empty store forever and
     * every sample arrives carrying nothing — indistinguishable from a
     * paired-but-silent wearable.
     *
     * Returns false when Health Connect is not present to open, so a caller can
     * say so rather than appearing to do nothing.
     */
    fun openHealthPermissionSettings(context: Context): Boolean {
        val intent = android.content.Intent(ACTION_MANAGE_HEALTH_PERMISSIONS)
            .putExtra(android.content.Intent.EXTRA_PACKAGE_NAME, context.packageName)
            // Callable from a non-Activity context (a ViewModel, say).
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }
            .onFailure {
                SynheartLogger.log(
                    "[Synheart] could not open Health Connect permissions: ${it.message}",
                )
            }
            .isSuccess
    }

    /**
     * Health Connect's per-app permission screen.
     *
     * Declared here rather than taken from `androidx.health.connect` so this
     * does not add a dependency for one string constant.
     */
    private const val ACTION_MANAGE_HEALTH_PERMISSIONS =
        "android.health.connect.action.MANAGE_HEALTH_PERMISSIONS"

    /**
     * Current health-permission grants, without prompting.
     *
     * Keyed by permission NAME rather than `synheart-wear`'s `PermissionType`:
     * that library is an `implementation` dependency, so its types are not on a
     * consumer's compile classpath and returning one would make this method
     * uncallable from a host that does not itself depend on synheart-wear.
     */
    fun wearPermissionStatus(): Map<String, Boolean> =
        wearSourceHandler?.permissionStatus()?.mapKeys { it.key.name } ?: emptyMap()

    /** True when heart rate and HRV are both readable. */
    val hasWearPermissions: Boolean
        get() = wearPermissionStatus().let { st ->
            st.isNotEmpty() && st.values.all { it }
        }

    /**
     * Live stream of raw wear samples. Empty before [initialize] rather than
     * throwing, so a host can subscribe during composition and have it start
     * producing once the module is up.
     */
    val wearSampleStream: Flow<WearSample>
        get() = wearModule?.sampleFlow ?: kotlinx.coroutines.flow.emptyFlow()


    /**
     * Clear the in-memory session history and the delivery deduper.
     *
     * Both are per-session: a retained window id would suppress the next
     * session's first window, and retained windows would leak across the
     * session boundary in [getSessionHsiWindows].
     */
    private fun resetSessionBuffers() {
        synchronized(sessionHsiWindows) { sessionHsiWindows.clear() }
        hsiDeduper.reset()
    }

    /**
     * Flush the upload queue on session stop when the host asked for it.
     *
     * Opt-in via [setBatchIngestOnStop]: null leaves the decision to the
     * runtime's own upload cadence, which is the default.
     */
    private suspend fun maybeFlushOnStop() {
        if (batchIngestOnStop != true) return
        try {
            ingestion.flushIfEligible()
        } catch (e: Exception) {
            SynheartLogger.log("[Synheart] batch ingest on stop failed: ${e.message}")
        }
    }


    // ══════════════════════════════════════════════════════════════════ //
    // Per-module collection control                                       //
    //                                                                     //
    // These start and stop one collector without tearing down the module, //
    // so a host can run behavior-only, wear-only, or any combination      //
    // without a full session restart.                                     //
    // ══════════════════════════════════════════════════════════════════ //

    @Volatile
    private var wearCollecting = false

    @Volatile
    private var behaviorCollecting = false

    @Volatile
    private var phoneCollecting = false

    /** True while the wear module is collecting. */
    val isWearCollecting: Boolean get() = wearCollecting

    /** True while the behavior module is collecting. */
    val isBehaviorCollecting: Boolean get() = behaviorCollecting

    /** True while the phone module is collecting. */
    val isPhoneCollecting: Boolean get() = phoneCollecting

    /** Start biosignal collection. No-op when the module is absent. */
    suspend fun startWearCollection() {
        val m = wearModule ?: return
        m.start()
        wearCollecting = true
    }

    /** Stop biosignal collection, leaving the module initialized. */
    suspend fun stopWearCollection() {
        val m = wearModule ?: return
        m.stop()
        wearCollecting = false
    }

    /** Start behavioral-interaction collection. */
    suspend fun startBehaviorCollection() {
        val m = behaviorModule ?: return
        m.start()
        behaviorCollecting = true
    }

    /** Stop behavioral-interaction collection, leaving the module initialized. */
    suspend fun stopBehaviorCollection() {
        val m = behaviorModule ?: return
        m.stop()
        behaviorCollecting = false
    }

    /** Start phone motion / context collection. */
    suspend fun startPhoneCollection() {
        val m = phoneModule ?: return
        m.start()
        phoneCollecting = true
    }

    /** Stop phone motion / context collection, leaving the module initialized. */
    suspend fun stopPhoneCollection() {
        val m = phoneModule ?: return
        m.stop()
        phoneCollecting = false
    }

    /**
     * Live stream of behavior events as the host records them.
     *
     * Empty before [initialize]. Recording is done through
     * [behaviorEvents]; this is the read side.
     */
    val behaviorEventStream: Flow<ai.synheart.core.modules.behavior.BehaviorEvent>
        get() = behaviorModule?.eventStreamInstance?.events ?: kotlinx.coroutines.flow.emptyFlow()

    // ── Automatic interaction capture ────────────────────────────────────
    //
    // The Android counterpart of the Flutter SDK's `wrapWithBehaviorDetector`.
    // Flutter can wrap the widget tree; Android has no equivalent hook, so the
    // host forwards its touch dispatch here and this derives the events.
    //
    // Without it every host reimplements the same gesture bookkeeping — and
    // getting it wrong is easy in one specific way: recording every ACTION_MOVE
    // floods the aggregator, because a single drag dispatches dozens.

    private var touchDownX: Float = 0f
    private var touchDownY: Float = 0f
    private var touchTracking = false

    /**
     * Derive behavior events from an Android touch dispatch.
     *
     * Forward every event from your activity and nothing else is required:
     *
     * ```kotlin
     * override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
     *     Synheart.recordTouchEvent(ev)
     *     return super.dispatchTouchEvent(ev)
     * }
     * ```
     *
     * Records one tap on press, and at most one scroll per gesture — measured
     * from press to release, and only when the pointer actually travelled past
     * [TOUCH_SCROLL_THRESHOLD_PX]. Recording per ACTION_MOVE instead would emit
     * dozens of events for one drag and swamp the interaction window.
     *
     * A no-op before [initialize], and the behavior module drops what consent
     * does not cover, so this needs no gating of its own. Safe to call from the
     * UI thread: it only appends to a buffered flow.
     *
     * Touches are a genuine signal, so recording them is safe — and interaction
     * alone is enough to ground the HSI digital axes. Contrast the biosignal
     * push APIs, which must never carry invented readings.
     */
    fun recordTouchEvent(event: android.view.MotionEvent) {
        val sink = behaviorEvents ?: return
        when (event.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                touchDownX = event.x
                touchDownY = event.y
                touchTracking = true
                sink.recordTap(event.x.toDouble(), event.y.toDouble())
            }

            android.view.MotionEvent.ACTION_UP -> {
                if (touchTracking) {
                    val dx = (event.x - touchDownX).toDouble()
                    val dy = (event.y - touchDownY).toDouble()
                    val travelled = kotlin.math.sqrt(dx * dx + dy * dy)
                    if (travelled > TOUCH_SCROLL_THRESHOLD_PX) {
                        sink.recordScroll(travelled)
                    }
                }
                touchTracking = false
            }

            // A gesture the system took over — a parent view claiming the
            // pointer, say. No release will arrive, so drop the tracking rather
            // than attributing the next ACTION_UP's distance to this press.
            android.view.MotionEvent.ACTION_CANCEL -> touchTracking = false
        }
    }

    /** Below this travel, in pixels, a touch is a tap rather than a scroll. */
    const val TOUCH_SCROLL_THRESHOLD_PX: Double = 24.0

    /**
     * The behavior event sink. Call `recordTap`, `recordScroll`, and friends
     * to feed interaction data in; interaction alone is enough to produce an
     * HSI digital axis.
     */
    val behaviorEvents: ai.synheart.core.modules.behavior.BehaviorEventStream?
        get() = behaviorModule?.eventStreamInstance

    /**
     * Whether notification-listener access is granted — required for the
     * behavior module's notification metrics.
     *
     * Android routes this through system settings rather than a runtime
     * permission, so the host has to send the user to
     * [openNotificationListenerSettings] rather than showing a dialog.
     */
    fun checkNotificationListenerEnabled(): Boolean {
        val ctx = context ?: return false
        return try {
            val enabled = android.provider.Settings.Secure.getString(
                ctx.contentResolver,
                "enabled_notification_listeners",
            ) ?: return false
            enabled.split(":").any { it.contains(ctx.packageName) }
        } catch (e: Exception) {
            SynheartLogger.log("[Synheart] notification listener check failed: ${e.message}")
            false
        }
    }

    /**
     * Open the system screen where the user can grant notification access.
     * There is no in-app path for this permission on Android.
     */
    fun openNotificationListenerSettings() {
        val ctx = context ?: return
        try {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            SynheartLogger.log("[Synheart] opening notification settings failed: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════ //
    // Consent UI                                                          //
    // ══════════════════════════════════════════════════════════════════ //

    private val consentUIManager = ai.synheart.core.modules.consent.ConsentUIManager()

    /**
     * Install the host's consent UI. Without one, [requestConsent] cannot
     * present anything and returns null — the SDK deliberately ships no
     * built-in consent screen.
     */
    fun setConsentUIProvider(
        provider: ai.synheart.core.modules.consent.ConsentUIProvider?,
    ) {
        consentUIManager.customUIProvider = provider
    }

    /** The consent profiles this app offers, as resolved from the runtime. */
    fun getAvailableConsentProfiles(): List<ai.synheart.core.modules.consent.ConsentProfile> =
        consentModule?.availableProfiles() ?: emptyList()

    /**
     * Present the consent flow through the host's provider and apply the
     * profile the user picked. Returns the selected profile, or null when the
     * user declined or no provider is installed.
     */
    suspend fun requestConsent(): ai.synheart.core.modules.consent.ConsentProfile? {
        val profiles = getAvailableConsentProfiles()
        val selected = consentUIManager.presentConsentFlow(profiles)
        if (selected == null) {
            // Distinguishes "declined" from "never asked" for the next launch.
            denyConsent()
            return null
        }
        consentModule?.applyProfile(selected)
        return selected
    }

    // ══════════════════════════════════════════════════════════════════ //
    // Syni — adaptive on-device agent (gated feature)                     //
    // ══════════════════════════════════════════════════════════════════ //

    private var syniModule: ai.synheart.core.modules.syni.SyniModule? = null

    /**
     * The Syni agent facade, or null until [configureSyni] runs. Gated on
     * `SYNI` consent — the module refuses to chat without it.
     */
    val syni: ai.synheart.core.modules.syni.SyniModule? get() = syniModule

    /**
     * Build the Syni module for this process.
     *
     * Separate from [initialize] because Syni pulls in a model runtime that a
     * host without the feature should not pay for. Pass [cloudConfig] to allow
     * hybrid local/cloud execution.
     */
    fun configureSyni(
        installer: ai.synheart.syni.SyniInstaller? = null,
        cloudConfig: ai.synheart.syni.SyniCloudConfig? = null,
    ): ai.synheart.core.modules.syni.SyniModule? {
        val ctx = context ?: return null
        val consent = consentModule ?: return null
        syniModule = ai.synheart.core.modules.syni.SyniModule(
            context = ctx,
            consent = consent,
            installer = installer,
            cloudConfig = cloudConfig,
        )
        return syniModule
    }

    /**
     * Point Syni at a cloud peer, rebuilding the module so the new config
     * takes effect. Cloud Syni should still be gated on cloud-upload consent
     * by the host, for the same reason the ingest path is.
     */
    fun configureSyniCloud(
        cloudConfig: ai.synheart.syni.SyniCloudConfig,
    ): ai.synheart.core.modules.syni.SyniModule? = configureSyni(cloudConfig = cloudConfig)


    // ══════════════════════════════════════════════════════════════════ //
    // Realtime event stream (via the native stream runtime)               //
    // ══════════════════════════════════════════════════════════════════ //

    /**
     * True when the stream callback should auto-route `vendor.*` events
     * through [processVendorEvent]. Set by [startVendorSync], cleared by
     * [stopEventStream]. Other consumers — [onDataDeletionUpdate] — ride the
     * same connection regardless.
     */
    @Volatile
    private var vendorAutoRouteEnabled = false

    // Captured from the config the stream was started with. The runtime's
    // event envelope carries only event-level fields; app id and user id are
    // connection-level, so they are stamped on here before surfacing.
    @Volatile
    private var streamAppId: String = ""

    @Volatile
    private var streamUserId: String = ""

    private val rawStreamEventsFlow =
        kotlinx.coroutines.flow.MutableSharedFlow<
            ai.synheart.core.modules.cloud.RuntimeStreamEvent,
            >(
            replay = 0,
            extraBufferCapacity = 64,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

    private val dataDeletionUpdatesFlow =
        kotlinx.coroutines.flow.MutableSharedFlow<DataDeletionEvent>(
            replay = 0,
            extraBufferCapacity = 16,
            onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
        )

    /**
     * Raw events as they arrive from the runtime, with the capability-flavored
     * [ai.synheart.core.modules.cloud.DeliveryHint] parsed.
     *
     * Subscribe here rather than [vendorEvents] when you want client-side
     * control over ping-vs-stream delivery: a ping-flavored event arrives
     * payload-less by design and needs a follow-up REST pull before it is
     * worth anything.
     */
    val rawRamenEvents: Flow<ai.synheart.core.modules.cloud.RuntimeStreamEvent>
        get() = rawStreamEventsFlow.asSharedFlow()

    /** Canonical vendor events, emitted after normalization and storage. */
    val vendorEvents: Flow<CanonicalWearableEvent>
        get() = wearModule?.canonicalEvents ?: kotlinx.coroutines.flow.emptyFlow()

    /**
     * Real-time status transitions for [requestDataDeletion]. The cloud
     * publishes one event per lifecycle change, so a host can drop the
     * polling loop over [dataDeletionStatus].
     *
     * Requires an active connection — call [startEventStream] first.
     */
    val onDataDeletionUpdate: Flow<DataDeletionEvent>
        get() = dataDeletionUpdatesFlow.asSharedFlow()

    /**
     * Current stream connection state (`connecting`, `connected`,
     * `disconnected`, `reconnecting`), or null when the runtime is absent.
     */
    val vendorSyncState: String? get() = coreRuntime?.streamState()

    /**
     * Start the streaming connection — the primitive behind every real-time
     * surface (data-deletion updates, vendor sync, future account events).
     *
     * Use this directly when you only want non-vendor events; use
     * [startVendorSync] when you also want vendor data auto-routed through
     * [processVendorEvent].
     *
     * [config] must carry `host`, `port`, `app_id`, `device_id` and `user_id`;
     * `api_key`, `use_tls`, `providers` and `event_types` are optional.
     *
     * Connection-level consent is cloud-upload only — the umbrella "we connect
     * to your cloud" grant. Per-event-type consent is enforced at dispatch.
     */
    fun startEventStream(config: JSONObject) {
        val bridge = coreRuntime
        if (bridge == null) {
            SynheartLogger.log("[Synheart] Cannot start event stream: runtime not initialized")
            return
        }

        streamAppId = config.optString("app_id")
        streamUserId = config.optString("user_id")

        SynheartLogger.log(
            "[Synheart] Starting event stream host=${config.optString("host")}:" +
                "${config.optInt("port")} app_id=$streamAppId user_id=$streamUserId",
        )

        bridge.setStreamCallback { eventJson ->
            try {
                val event = JSONObject(eventJson)
                val eventType = event.optString("event_type")

                // Surface the raw event first, so an app doing its own ping
                // handling sees it before the auto-route below runs.
                rawStreamEventsFlow.tryEmit(
                    ai.synheart.core.modules.cloud.RuntimeStreamEvent.fromRuntimeJson(
                        event,
                        appId = streamAppId,
                        userId = streamUserId,
                    ),
                )

                // Data-deletion events are not vendor data — they have their
                // own typed stream, so return instead of falling through.
                if (eventType.startsWith("user.data_deletion.")) {
                    val payload = event.optString("payload_json")
                        .takeIf { it.isNotEmpty() }
                        ?.let { runCatching { JSONObject(it) }.getOrNull() }
                        ?: JSONObject()
                    dataDeletionUpdatesFlow.tryEmit(
                        DataDeletionEvent.fromRuntimeJson(
                            envelope = event,
                            payload = payload,
                            appId = streamAppId,
                            userId = streamUserId,
                        ),
                    )
                    return@setStreamCallback
                }

                // Skip the auto-route for ping-flavored events: their inline
                // payload is empty by design. Auto-routing would store an empty
                // payload under the event id, which then blocks the real one
                // when the REST pull completes.
                if (event.optString("delivery_hint") == "ping") {
                    SynheartLogger.log(
                        "[Synheart] ping event ${event.optString("provider")}/$eventType " +
                            "— deferring to a rawRamenEvents subscriber",
                    )
                    return@setStreamCallback
                }

                if (!vendorAutoRouteEnabled) return@setStreamCallback

                val payload = event.optString("payload_json")
                    .takeIf { it.isNotEmpty() }
                    ?.let { runCatching { JSONObject(it) }.getOrNull() }
                    ?: JSONObject()
                val payloadMap = payload.keys().asSequence().associateWith { payload.opt(it) }

                processVendorEvent(
                    provider = event.optString("provider"),
                    eventType = eventType,
                    payload = payloadMap,
                    eventId = event.optString("event_id"),
                    seq = event.optInt("seq", 0),
                )
            } catch (e: Exception) {
                SynheartLogger.log("[Synheart] stream event parse failed: $e")
            }
        }

        bridge.startStream(config.toString())
    }

    /**
     * Stop the streaming connection. Clears the vendor auto-route flag as a
     * side effect — call [startVendorSync] again to re-enable it.
     */
    fun stopEventStream() {
        SynheartLogger.log("[Synheart] Stopping event stream")
        coreRuntime?.stopStream()
        coreRuntime?.clearStreamCallback()
        vendorAutoRouteEnabled = false
        streamAppId = ""
        streamUserId = ""
    }

    /**
     * Start the connection AND enable vendor-event auto-routing through
     * [processVendorEvent] — [startEventStream] plus one flag on the dispatch
     * layer.
     *
     * Use this when your app pulls vendor data (Whoop / Garmin / Oura /
     * Fitbit) and wants the runtime to normalize and store events as they
     * arrive.
     */
    fun startVendorSync(config: JSONObject) {
        vendorAutoRouteEnabled = true
        startEventStream(config)
    }

    /** Stop the streaming connection. Alias of [stopEventStream]. */
    fun stopVendorSync() = stopEventStream()

    // ══════════════════════════════════════════════════════════════════ //
    // Behavior sessions                                                   //
    // ══════════════════════════════════════════════════════════════════ //

    /**
     * Open a behavior-only session and return its id.
     *
     * Independent of the main collection session: a host can measure one
     * interaction episode (a task, a screen) without starting biosignal
     * collection.
     */
    suspend fun startBehaviorSession(): String {
        val sessionId = "behavior_${System.currentTimeMillis()}"
        startBehaviorCollection()
        activeBehaviorSessionId = sessionId
        behaviorSessionStartedAtMs = System.currentTimeMillis()
        return sessionId
    }

    @Volatile
    private var activeBehaviorSessionId: String? = null

    @Volatile
    private var behaviorSessionStartedAtMs: Long = 0

    /**
     * Close the behavior session opened by [startBehaviorSession] and return
     * its aggregate metrics. Returns null for an unknown session id.
     */
    suspend fun stopBehaviorSession(sessionId: String): BehaviorSessionResults? {
        if (activeBehaviorSessionId != sessionId) return null
        val durationMs = System.currentTimeMillis() - behaviorSessionStartedAtMs
        val events = behaviorModule?.rawEvents(WindowType.WINDOW_1H) ?: emptyList()
        stopBehaviorCollection()
        activeBehaviorSessionId = null
        behaviorSessionStartedAtMs = 0
        return BehaviorSessionResults.fromEvents(
            sessionId = sessionId,
            durationMs = durationMs,
            events = events,
        )
    }

    // ══════════════════════════════════════════════════════════════════ //
    // Bridge lifecycle                                                    //
    // ══════════════════════════════════════════════════════════════════ //

    /**
     * Make sure a runtime bridge exists, creating one from [config] if the SDK
     * has not been initialized yet.
     *
     * For hosts that need the runtime before a full [initialize] — a cold-boot
     * baseline restore, say. Returns true when a live bridge is available.
     */
    fun ensureRuntimeBridge(config: SynheartConfig, dataDir: String? = null): Boolean {
        if (coreRuntime != null) return true
        val resolved = synheartConfig ?: config
        coreRuntime = CoreRuntimeBridge.create(
            ai.synheart.core.config.buildRuntimeConfigMap(
                resolved,
                dataDir = dataDir ?: context?.filesDir?.absolutePath,
            ).toString(),
        )
        if (coreRuntime != null) syncSubjectFromNative()
        return coreRuntime != null
    }

    /**
     * Whether a feature is fully operational — activated by the developer,
     * consented by the user, allowed by the capability token, and with a
     * session running. All four have to hold.
     */
    fun isFeatureOperational(feature: SynheartFeature): Boolean {
        val activated = activationManager?.isActivated(feature) ?: false
        return activated &&
            hasConsentForFeature(feature) &&
            isCapabilityAllowed(feature) &&
            isRunning
    }

}
