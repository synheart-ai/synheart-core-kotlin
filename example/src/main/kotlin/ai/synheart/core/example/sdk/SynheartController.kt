package ai.synheart.core.example.sdk

import ai.synheart.core.SYNHEART_CORE_VERSION
import ai.synheart.core.Synheart
import ai.synheart.core.SynheartLogger
import ai.synheart.core.config.ApiEndpoints
import ai.synheart.core.config.CloudConfig
import ai.synheart.core.config.BehaviorConfig
import ai.synheart.core.config.ExtraHead
import ai.synheart.core.config.ConsentConfig
import ai.synheart.core.config.DeviceAuthConfig
import ai.synheart.core.config.SynheartConfig
import ai.synheart.core.config.SynheartFeature
import ai.synheart.core.config.SynheartMode
import ai.synheart.core.config.WearConfig
import ai.synheart.core.example.BuildConfig
import ai.synheart.core.models.HSIState
import ai.synheart.core.models.SessionHandle
import ai.synheart.session.SessionConfig
import ai.synheart.session.SessionErrorEvent
import ai.synheart.session.SessionMode
import ai.synheart.session.WatchStatus
import ai.synheart.core.modules.behavior.BehaviorEvent
import ai.synheart.core.modules.behavior.BehaviorEventType
import ai.synheart.core.modules.consent.ConsentEffectiveState
import ai.synheart.core.modules.consent.ConsentForm
import ai.synheart.core.modules.wear.WearSample
import android.content.Context
import android.view.MotionEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.time.Instant

/**
 * The one place in this example that talks to the Synheart SDK.
 *
 * Every screen reads state from here and calls methods here — no screen imports
 * `ai.synheart.core.*` directly. That keeps the SDK integration readable as a
 * single file you can skim top-to-bottom, and makes it obvious what a host app
 * actually has to implement.
 *
 * The lifecycle it models is the one the SDK documents:
 *
 *   1. [initialize]      — validate config, load the native runtime
 *   2. [submitConsent]   — write the user's choices through the runtime form
 *   3. [startSession]    — begin collection; HSI starts arriving
 *   4. [stopSession]     — end collection
 *   5. [shutdown]        — tear down
 *
 * Consent uses the runtime's editable-form flow
 * ([Synheart.consentGetEditableFormTyped] → [Synheart.consentSubmitFormTyped] →
 * [Synheart.consentEffectiveStateTyped]). That is the canonical path: the
 * runtime persists the choice offline-first, then reconciles with the cloud
 * profile when cloud is enabled. The older Kotlin-side helpers
 * (`requestConsent`, `getAvailableConsentProfiles`, `setConsentUIProvider`) are
 * legacy and are deliberately not used here.
 *
 * A [ViewModel] rather than a plain object so it survives configuration
 * changes: recreating it on every rotation would tear down the native runtime
 * and drop the session mid-flight.
 */
class SynheartController : ViewModel() {

    // ── Build-time configuration ───────────────────────────────────────────
    //
    // This example is LOCAL-ONLY by default: no CloudConfig, no
    // DeviceAuthConfig. Everything it demonstrates runs offline on any device
    // with no credentials.
    //
    // Values come from a credentials file rather than hard-coded constants,
    // matching how the other Synheart apps are built:
    //
    //   example/env/synheart.credentials.json
    //
    // `synheart.credentials.example.json` is the checked-in template; a
    // populated file is gitignored, because it carries real organization
    // identifiers. Gradle reads it and surfaces the values as BuildConfig
    // fields. See SETUP.md.

    companion object {
        private const val PREFS = "synheart.example"
        private const val KEY_SUBJECT_ID = "example.subject_id"
        private const val KEY_DEVICE_ID = "example.device_id"

        /**
         * Platform-issued application identifier.
         *
         * Falls back to the installed package id, because
         * [SynheartConfig.validate] rejects an empty `appId` — that fallback is
         * what keeps a credential-free run working.
         *
         * NOT interchangeable with [packageName]. Play Integrity verifies the
         * real installed package; `appId` is the platform-issued `app_…`
         * identifier an ingest scope resolves from. Passing one as the other
         * fails attestation.
         */
        val appId: String =
            BuildConfig.SYNHEART_APP_ID.ifEmpty { "ai.synheart.core.example" }

        /** Package name sent with the attestation request. */
        val packageName: String =
            BuildConfig.SYNHEART_PACKAGE_NAME.ifEmpty { "ai.synheart.core.example" }

        /**
         * Organization id, required only for HSI upload — cloud ingest stays
         * disabled without one. Independent of attestation.
         */
        val orgId: String = BuildConfig.SYNHEART_ORG_ID

        /**
         * The platform origin this build names. Every per-service URL — device
         * auth, consent, ingest — resolves from it through [ApiEndpoints].
         */
        val baseUrl: String = BuildConfig.SYNHEART_BASE_URL

        /**
         * Optional per-service override for device auth, for a split
         * environment.
         *
         * Naming one origin is enough: [ApiEndpoints.resolvedAuthBaseUrl] falls
         * back to [baseUrl] rather than to the runtime's own default, so a build
         * cannot accidentally split one run across two hosts by setting only
         * half of the pair. Set both only for a genuinely split environment.
         */
        val authUrl: String = BuildConfig.SYNHEART_AUTH_URL

        /**
         * Attestation is possible. Every registration trigger in the SDK keys
         * off `DeviceAuthConfig`; none of them consults the cloud config.
         *
         * Read through [ApiEndpoints] rather than off [baseUrl] directly, so a
         * host that set the origin by system property or environment variable
         * is reported accurately.
         */
        val attestationConfigured: Boolean
            get() = ApiEndpoints.resolvedAuthBaseUrl.isNotEmpty()

        /** The origin device registration will actually reach. */
        val authBaseUrl: String get() = ApiEndpoints.resolvedAuthBaseUrl

        /**
         * Upload is possible. Needs an org id AND an attested identity, since
         * the runtime signs every ingest request with the device key.
         */
        val uploadConfigured: Boolean
            get() = orgId.isNotEmpty() && attestationConfigured

        /**
         * Tenant and project identifiers, read so a credentials file can be
         * dropped in whole and the Setup screen can show what was supplied.
         *
         * The SDK does **not** consume either one — and neither does any sibling
         * platform SDK, so this is by design rather than a gap here.
         * `buildRuntimeConfigMap` emits `app_id` and `org_id` and no tenant or
         * project key, and `CloudConfig` has no field for them. The only
         * `tenantId` in the codebase is on `DataDeletionRequest`, parsed *out of*
         * a server response rather than sent in; `projectId` appears only on
         * `CapabilityToken`, which the server signs.
         *
         * So setting them changes no behavior. They are read here only so a
         * credentials file can be dropped in whole and the Setup screen can say
         * that plainly instead of leaving an unexplained empty row.
         */
        val tenantId: String = BuildConfig.SYNHEART_TENANT_ID
        val projectId: String = BuildConfig.SYNHEART_PROJECT_ID

        /**
         * Whether this build asks the server to admit a device that produced no
         * attestation material. Debug only — see [buildConfig].
         */
        val allowsUnattestedDevRegistration: Boolean = BuildConfig.DEBUG
    }

    init {
        // Name the platform origin HERE, not inside initialize().
        //
        // `attestationConfigured` resolves through ApiEndpoints, and the Setup
        // screen reads it while composing — before Initialize is ever pressed.
        // Assigning the override inside initialize() made that card report "off"
        // on a build that had a base_url, and it never corrected itself:
        // ApiEndpoints is a plain object, not Compose state, so nothing
        // recomposed when it changed.
        //
        // A ViewModel's init runs before the first composition and BuildConfig is
        // a compile-time constant, so this needs no context and cannot race the
        // UI. Empty leaves the runtime on its own default, which is what a
        // local-only run wants.
        if (baseUrl.isNotEmpty()) {
            ApiEndpoints.baseUrlOverride = baseUrl
        }
        // Only when it genuinely differs. Assigning it unconditionally would
        // pin the auth origin to a stale value if a later build changed only
        // base_url, which is the split-environment bug this guard avoids.
        if (authUrl.isNotEmpty() && authUrl != baseUrl) {
            ApiEndpoints.authBaseUrlOverride = authUrl
        }
    }

    // ── State the UI renders ───────────────────────────────────────────────
    //
    // Compose `mutableStateOf` rather than a StateFlow bundle: plain fields read
    // top-to-bottom, and each read in a composable subscribes on its own, so
    // there is no change-notification call to forget.

    var subjectId: String? by mutableStateOf(null)
        private set
    var deviceId: String? by mutableStateOf(null)
        private set
    var isInitializing: Boolean by mutableStateOf(false)
        private set
    var isInitialized: Boolean by mutableStateOf(false)
        private set
    var initError: String? by mutableStateOf(null)
        private set

    var consentForm: ConsentForm? by mutableStateOf(null)
        private set
    var consentState: ConsentEffectiveState? by mutableStateOf(null)
        private set
    var isSubmittingConsent: Boolean by mutableStateOf(false)
        private set
    var consentError: String? by mutableStateOf(null)
        private set

    var isSessionRunning: Boolean by mutableStateOf(false)
        private set
    var session: SessionHandle? by mutableStateOf(null)
        private set
    var sessionError: String? by mutableStateOf(null)
        private set

    var latestState: HSIState? by mutableStateOf(null)
        private set
    var hsiWindowCount: Int by mutableStateOf(0)
        private set

    /**
     * The host-driven half of the mobile integration — the tick loop, rest
     * declaration, the three snapshots, the daily loop, the keep-alive, and the
     * simulated cardiac stream.
     *
     * Split out rather than inlined here because it is *driving* rather than
     * configuring: this class ends at `startSession()`, and everything in
     * [MobileHostRunner] is what has to keep happening afterwards. Both halves
     * are still the only code in the example that touches the SDK.
     */
    val host = MobileHostRunner(viewModelScope)

    /**
     * The `device_class` this host stores its SRM snapshot under.
     *
     * Separate from the declaration itself: the runtime resolves `"auto"` to a
     * class through its own tables and rejects a cross-class SRM load with
     * `ERR_SRM_CONFIG_MISMATCH` — so the host needs a stable file key even
     * while it declares `"auto"`, and a phone and a tablet must not share one.
     */
    val hostDeviceClass: String = "phone"

    /** Turn the §2 host declarations on or off. Only meaningful before [initialize]. */
    fun setDeclareHostProfile(value: Boolean) {
        if (isInitialized) return
        host.declareHostProfile = value
    }

    fun setClaimContinuousSensing(value: Boolean) {
        if (isInitialized) return
        host.claimContinuousSensing = value
    }

    private var appContext: Context? = null
    private var hsiJob: Job? = null
    private var wearJob: Job? = null
    private var behaviorJob: Job? = null

    /** SDK version constant, kept in sync with the Gradle coordinates. */
    val sdkVersion: String get() = SYNHEART_CORE_VERSION

    /**
     * True when an enabled feature ALSO has its matching consent granted.
     *
     * Both halves are required. The Kotlin SDK activates wear, phone and
     * behavior together from the config's device role, so any one of the three
     * consents pairs with something here — but the pairing is what matters, not
     * the consent alone: a host that activated only [SynheartFeature.WEAR] and
     * was granted only `behavior` has no working pair, and nothing would
     * collect.
     *
     * Deliberately NOT [ConsentEffectiveState.hasAnyGrant], which also counts
     * cloudUpload, vendorSync, research and syni. Those govern what happens to
     * data once collected; none makes a sensor readable.
     */
    val hasCollectionConsent: Boolean
        get() {
            val c = consentState ?: return false
            return (Synheart.isActivated(SynheartFeature.WEAR) && c.biosignals) ||
                (Synheart.isActivated(SynheartFeature.BEHAVIOR) && c.behavior) ||
                (Synheart.isActivated(SynheartFeature.PHONE_CONTEXT) && c.phoneContext)
        }

    // ── Credential audit ───────────────────────────────────────────────────

    /**
     * One credential and what its absence actually costs.
     *
     * Exists because "attestation: off" on a build that clearly has credentials
     * is confusing: the platform download carries only the four ids, and
     * `base_url` — the one that gates attestation and upload — is not among
     * them. Listing every key with whether it was supplied AND whether the SDK
     * consumes it answers that in one glance instead of a paragraph.
     */
    data class Credential(
        val key: String,
        val value: String,
        val consumed: Boolean,
        /** What is unavailable while this is empty. Null when nothing is. */
        val gates: String?,
    ) {
        val supplied: Boolean get() = value.isNotEmpty()
    }

    val credentials: List<Credential> get() = listOf(
        Credential(
            "app_id",
            BuildConfig.SYNHEART_APP_ID,
            consumed = true,
            // Not a gate: an empty value falls back to the package id, which is
            // what keeps a credential-free run initializing at all.
            gates = null,
        ),
        Credential(
            "base_url",
            baseUrl,
            consumed = true,
            gates = "device attestation and cloud upload",
        ),
        Credential(
            "auth_url",
            authUrl,
            consumed = true,
            // Never a gate: empty means "same host as base_url", which is the
            // right answer for every non-split deployment.
            gates = null,
        ),
        Credential("org_id", orgId, consumed = true, gates = "cloud upload"),
        Credential(
            "package_name",
            BuildConfig.SYNHEART_PACKAGE_NAME,
            consumed = true,
            // Defaults to the real installed package, which is the value Play
            // Integrity verifies anyway — only a build whose platform record
            // names a different package needs to set it.
            gates = null,
        ),
        Credential("tenant_id", tenantId, consumed = false, gates = null),
        Credential("project_id", projectId, consumed = false, gates = null),
    )

    // ── 1. Identity + initialization ───────────────────────────────────────

    /**
     * Load (or mint) the identifiers this install is bound to.
     *
     * `subjectId` MUST be stable across restarts — the runtime scopes storage,
     * baselines, and device identity to it, so a value that changes per launch
     * makes every session look like a new person and baselines never mature.
     * A real app uses its own account id; this example generates one once and
     * persists it.
     */
    fun loadIdentity(context: Context) {
        appContext = context.applicationContext
        val prefs = requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        subjectId = prefs.getString(KEY_SUBJECT_ID, null)?.takeIf { it.isNotEmpty() }
            ?: "demo_${System.currentTimeMillis()}".also {
                prefs.edit().putString(KEY_SUBJECT_ID, it).apply()
            }
        deviceId = prefs.getString(KEY_DEVICE_ID, null)?.takeIf { it.isNotEmpty() }
            ?: "dev_${System.nanoTime()}".also {
                prefs.edit().putString(KEY_DEVICE_ID, it).apply()
            }
    }

    /**
     * Replace the subject id and persist it. Clears SDK state, because the
     * runtime binds storage to the subject — carrying state across a change
     * would attribute one person's data to another.
     */
    fun saveSubjectId(value: String) {
        val trimmed = value.trim()
        if (trimmed.isEmpty() || trimmed == subjectId) return
        requireContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_SUBJECT_ID, trimmed).apply()
        subjectId = trimmed
        if (isInitialized) viewModelScope.launch { shutdown() }
    }

    /**
     * The config this example initializes with. Kept as a function so the setup
     * screen can show developers exactly what is being passed.
     */
    fun buildConfig(): SynheartConfig = SynheartConfig(
        // Both are REQUIRED — validate() rejects an empty value.
        appId = appId,
        subjectId = subjectId.orEmpty(),
        appVersion = "1.0.0",
        appName = "Synheart Core Example",
        deviceId = deviceId.orEmpty(),
        mode = SynheartMode.PERSONAL,

        // Development only. In production the capability lattice is driven by a
        // verified consent token; running unsigned means "allow everything".
        allowUnsignedCapabilities = true,

        // Declaring a module config both activates the feature and tells the SDK
        // which collectors to wire. Omit one and that module never starts.
        wearConfig = WearConfig(),

        // phoneConfig is deliberately NOT declared.
        //
        // Declaring it starts PhoneModule, whose four collectors are Random()
        // generators — motion, screen state, app focus, notifications
        // (`modules/phone/PhoneCollectors.kt`). Cardiac is the only thing this
        // example is allowed to simulate, and those four are not cardiac. It
        // also contradicted the sensing roster: the runner declares
        // screen_state and app_focus unavailable because nothing real observes
        // them, while the phone module was busy inventing exactly those two.
        // The data never reached the runtime anyway — PhoneModule holds no
        // bridge reference — so nothing of value is lost.

        behaviorConfig = BehaviorConfig(
            // §4.2 — without this no accelerometer sample reaches the runtime
            // and the kinematic modality has no input. On Kotlin this now wires
            // the SDK's own AccelForwarder at 50 Hz; before, the flag turned on
            // a callback that nothing fed. Necessary but not sufficient: the
            // kinematic heads also need a body-worn placement (§4.3), and
            // UNKNOWN — the default — withholds all of them.
            emitRawMotionSamples = true,
        ),

        // §2 — the four declarations that change engine output. Off unless the
        // Setup screen turns them on, because declaring device_class invalidates
        // every persisted SRM baseline.
        hostDeclarations = host.buildHostDeclarations(),

        // §2.4 — the kinematic heads are opt-in and withhold until a placement
        // is declared. Requested so the Host tab can show them moving from "not
        // requested" to "withheld, no placement" to a real reading.
        extraHeads = listOf(
            ExtraHead.MOVEMENT_REGULARITY,
            ExtraHead.POSTURAL_STATE,
            ExtraHead.ACTIVITY_STATE,
            ExtraHead.LOCOMOTION_STATE,
        ),

        // The runtime default, stated explicitly because the rest detector's
        // one-shot bookkeeping counts on the same grid.
        windowMs = 60_000,

        // Surface the runtime's own logs. Without a filter the runtime logs
        // nowhere, and the lines that explain a stalled integration — an
        // unattestable device, a closed cloud gate, a failing ingest POST —
        // simply do not exist.
        runtimeLogEnvFilter = "info",

        // Left at its default of false. The wear module would otherwise attach a
        // generator that invents a heart rate every second and feeds it into the
        // runtime's real longitudinal baselines — which is precisely what this
        // example promises never to do.
        allowSyntheticBiosignals = SYNTHETIC_BIOSIGNALS,

        // Required for the runtime consent-form flow: consentSubmitFormTyped
        // needs a non-empty deviceId + platform to stamp on the submission.
        // Without a ConsentConfig the submit call returns null and consent can
        // never be written.
        consentConfig = ConsentConfig(
            deviceId = deviceId,
            platform = "android",
            userId = subjectId,
        ),

        // Both opt-in and independent: an origin alone enables attestation, an
        // org id adds upload on top. With neither, the example is local-only.
        // Granting cloud-upload consent is what actually triggers registration.
        // See SETUP.md.
        deviceAuthConfig = if (attestationConfigured) {
            DeviceAuthConfig(
                authBaseUrl = authBaseUrl,
                packageName = packageName,
                // A debug build, an emulator, or a de-Googled ROM cannot
                // produce a Play Integrity token, so the runtime skips
                // registration and stays local-only:
                //
                //   WARN device auth: no attestation material - device cannot
                //   attest; skipping registration (local-only)
                //
                // This stops the SDK giving up client-side: it sends the
                // registration carrying `format:"none"` and an empty blob, and
                // the server decides. Nothing fake is transmitted.
                //
                // It is NOT a bypass, and the flag alone does nothing.
                // Server-side development mode must also be on for this app id,
                // and it must be a DEVELOPMENT app id — never enable that on a
                // production one. A device admitted this way is recorded
                // `unattested`: it still holds a hardware key and signs every
                // request, it just carries no provenance claim.
                //
                // Gated on BuildConfig.DEBUG so a store build can never ship it
                // enabled.
                allowUnattestedDevRegistration = allowsUnattestedDevRegistration,
            )
        } else {
            null
        },
        cloudConfig = if (uploadConfigured) {
            CloudConfig(
                subjectId = subjectId.orEmpty(),
                instanceId = deviceId.orEmpty(),
                orgId = orgId,
            )
        } else {
            null
        },
    )

    /**
     * Validate the config and load the native runtime.
     *
     * `autoStart = false` is deliberate — initializing must not begin
     * collecting. Collection starts at [startSession], after consent.
     */
    fun initialize(context: Context) {
        if (isInitialized || isInitializing) return
        isInitializing = true
        initError = null
        appContext = context.applicationContext

        viewModelScope.launch {
            try {
                if (subjectId == null) loadIdentity(context)

                // Runtime logging is requested through the config
                // (`runtimeLogEnvFilter`), which initialize() applies before any
                // native work so the runtime's own startup is covered.
                //
                // `Synheart.initRuntimeLogging` remains available for a host that
                // wants to change the filter later, or to capture lines into its
                // own sink rather than logcat.
                //
                // The origin was already named in `init` — see the note there
                // for why it cannot wait until this point.
                Synheart.initialize(
                    context = requireContext(),
                    config = buildConfig(),
                    autoStart = false,
                )
                isInitialized = true

                // §7 — restore the three snapshots now, while there is still no
                // window 1. load_session_state MUST run before the first tick:
                // window 1 writes each head's state slot, so a later restore is
                // overwritten by a cold window. Doing it here rather than in
                // startSession() makes that ordering unconditional.
                host.restore(requireContext(), subjectId.orEmpty(), hostDeviceClass)

                refreshConsent()
                refreshWearPermissions()
                refreshWatchStatus()
                refreshAttestation()
                pollAttestation()
            } catch (e: Exception) {
                // Configuration was rejected. SynheartCoreError messages name
                // the field and the fix; anything else is reported verbatim
                // rather than collapsed into "init failed".
                initError = "${e::class.simpleName}\n\n${e.message ?: e.toString()}"
            } finally {
                isInitializing = false
            }
        }
    }

    // ── 2. Consent (runtime editable-form flow) ────────────────────────────

    /**
     * Read the runtime's current form and effective state.
     *
     * The form is what the user edits; the effective state is what the runtime
     * actually enforces after intersecting the choice with the cloud profile.
     * They can differ — always gate features on the effective state.
     */
    fun refreshConsent() {
        if (!isInitialized) return
        consentForm = Synheart.consentGetEditableFormTyped()
        consentState = Synheart.consentEffectiveStateTyped()
    }

    /**
     * Apply a local edit to the pending form without submitting it, so toggles
     * feel immediate. Nothing is enforced until [submitConsent].
     */
    fun editConsent(
        biosignals: Boolean? = null,
        phoneContext: Boolean? = null,
        behavior: Boolean? = null,
        allowCloud: Boolean? = null,
        allowResearch: Boolean? = null,
        allowVendorSync: Boolean? = null,
    ) {
        val form = consentForm ?: return
        consentForm = form.copy(
            biosignals = biosignals ?: form.biosignals,
            phoneContext = phoneContext ?: form.phoneContext,
            behavior = behavior ?: form.behavior,
            allowCloud = allowCloud ?: form.allowCloud,
            allowResearch = allowResearch ?: form.allowResearch,
            allowVendorSync = allowVendorSync ?: form.allowVendorSync,
        )
    }

    /**
     * Submit the edited form to the runtime.
     *
     * Offline-first: the runtime persists the choice immediately and succeeds
     * even with no network. When `allowCloud` is set it additionally fetches the
     * cloud default profile, intersects it with the choice, and issues a consent
     * token — a step that legitimately fails offline without invalidating the
     * local save.
     */
    fun submitConsent() {
        val form = consentForm ?: return
        if (!isInitialized) return
        isSubmittingConsent = true
        consentError = null

        viewModelScope.launch {
            try {
                val result = Synheart.consentSubmitFormTyped(
                    form = form,
                    deviceId = deviceId,
                    platform = "android",
                    userId = subjectId,
                )
                if (result == null) {
                    consentError =
                        "Submit returned null — the runtime bridge or ConsentConfig is " +
                            "missing. Check that SynheartConfig.consentConfig is set with " +
                            "a non-empty deviceId and platform."
                } else if (result.has("error")) {
                    consentError = result.optString("error")
                }
                refreshConsent()
                // Granting cloud upload is what starts registration, in the
                // background and without awaiting. Start watching for it.
                pollAttestation()
            } catch (e: Exception) {
                consentError = e.toString()
            } finally {
                isSubmittingConsent = false
            }
        }
    }

    // ── 3. Session + HSI ───────────────────────────────────────────────────

    /**
     * Start collecting. HSI windows begin arriving on [latestState] once the
     * runtime closes a window, which it does on a fixed ~60s cadence.
     */
    fun startSession() {
        if (!isInitialized || isSessionRunning) return
        sessionError = null

        viewModelScope.launch {
            try {
                hsiWindowCount = 0
                latestState = null
                wearSampleCount = 0
                wearDataSampleCount = 0
                lastWearSample = null
                behaviorEventCount = 0
                behaviorCounts.clear()
                behaviorBreakdown = emptyList()

                // Subscribe BEFORE starting so the first completed window is
                // not missed. onStateUpdate parses each window once and shares
                // it across collectors.
                if (hsiJob == null) {
                    hsiJob = viewModelScope.launch {
                        Synheart.onStateUpdate.collect { state ->
                            latestState = state
                            hsiWindowCount++
                            // §7 — export session state once per emitted
                            // window. Only on background loses everything since
                            // the last one whenever the process is killed
                            // without a pause callback, which on Android is
                            // routine.
                            host.persistSessionState()
                        }
                    }
                }

                // Raw samples as the wear module produces them. Consent-gated
                // by the SDK: nothing is emitted unless biosignals are granted.
                if (wearJob == null) {
                    wearJob = viewModelScope.launch {
                        Synheart.wearSampleStream.collect { sample ->
                            wearSampleCount++
                            if (carriesBiosignal(sample)) {
                                // Keep the last sample that actually held a
                                // reading, so the UI shows the most recent real
                                // value rather than the most recent empty tick.
                                lastWearSample = sample
                                wearDataSampleCount++
                            }
                        }
                    }
                }

                // Behavior events as the activity's touch dispatch records
                // them. This is the one source that needs no sensor and no
                // wearable, so on a phone with neither it is the only proof the
                // collection path is alive. Counting by type also shows which
                // gestures resolve — a scroll and a tap are distinct events,
                // not one "touch".
                if (behaviorJob == null) {
                    behaviorJob = viewModelScope.launch {
                        Synheart.behaviorEventStream.collect { event ->
                            behaviorEventCount++
                            lastBehaviorEvent = event
                            behaviorCounts[event.type] =
                                (behaviorCounts[event.type] ?: 0) + 1
                            behaviorBreakdown = behaviorCounts.entries
                                .sortedByDescending { it.value }
                                .map { it.key to it.value }
                        }
                    }
                }

                // Starting the session is all a host has to do. startSession()
                // brings up the module manager and then reevaluates every
                // feature, starting each module whose activation, consent and
                // capability all line up.
                //
                // Deliberately NOT followed by startWearCollection() and
                // friends: those are the granular API for a host that wants one
                // collector without a session, and a module's start() THROWS if
                // it is already running — so calling both paths fails the
                // session with "Module must be initialized or stopped before
                // starting".
                Synheart.startSession()

                session = Synheart.currentSession
                isSessionRunning = Synheart.isSessionRunning

                // §6.1 — the tick loop for the WHOLE session, started after
                // startSession() so the pipeline exists for the first tick.
                if (isSessionRunning) host.start(requireContext())
            } catch (e: Exception) {
                sessionError = e.message ?: e.toString()
            }
        }
    }

    fun stopSession() {
        if (!isSessionRunning) return
        viewModelScope.launch {
            try {
                // Before Synheart.stopSession(), not after: flush_pending and
                // the snapshot exports need a live handle, and the SRM export at
                // session end is what stops baselines reporting Warming forever.
                host.stop(requireContext())
                // Symmetrically, stopSession() stops the modules it started;
                // stopWearCollection() and friends would throw on a module that
                // is no longer running.
                Synheart.stopSession()
            } catch (e: Exception) {
                sessionError = e.message ?: e.toString()
            }
            hsiJob?.cancel(); hsiJob = null
            wearJob?.cancel(); wearJob = null
            behaviorJob?.cancel(); behaviorJob = null
            session = null
            isSessionRunning = Synheart.isSessionRunning
        }
    }

    // ── Signal sources ─────────────────────────────────────────────────────
    //
    // Real signal arrives from the modules the config activated:
    //   wear      — Health Connect / BLE strap / watch companion
    //   behavior  — taps and scrolls via the activity's touch dispatch, and
    //               accelerometer via the SDK's AccelForwarder
    //
    // ── And a simulated one, behind a button ───────────────────────────────
    //
    // `host.startCardiacStream()` streams fabricated beats through the real
    // ingest path, because on a bare phone with no strap and no health
    // permission it is the only way to see the cardiac path work at all. Two
    // things keep that a demo rather than a lie: it is tagged sdk_wear
    // (Tier 3), never ble_hrm; and it is never automatic. Cardiac is the ONLY
    // simulated source — no motion, speed, screen state or context is invented.
    //
    // The cost is real: those samples reach the SRM, which builds this
    // person's longitudinal reference ranges on this device. Run it under a
    // throwaway subject_id and use [wipeLocalData] afterwards.

    /**
     * Live raw samples from the wear module, so the UI can show what is actually
     * arriving rather than asserting that something is.
     */
    var lastWearSample: WearSample? by mutableStateOf(null)
        private set

    /** Every sample the wear module emitted, including empty ones. */
    var wearSampleCount: Int by mutableStateOf(0)
        private set

    /**
     * Samples that actually carried a biosignal.
     *
     * Tracked separately because the wear source emits a [WearSample] on every
     * poll tick whether or not a metric resolved. With no wearable paired and no
     * health data available, that is one all-null envelope per tick — so a raw
     * sample count climbing steadily says nothing about whether biosignals are
     * arriving, and reporting it as "receiving" would be false.
     */
    var wearDataSampleCount: Int by mutableStateOf(0)
        private set

    private fun carriesBiosignal(s: WearSample): Boolean =
        s.hr != null || s.hrvRmssd != null || !s.rrIntervals.isNullOrEmpty()

    // ── Behavior signal ────────────────────────────────────────────────────
    //
    // Taps and scrolls need no sensor, so behavior is the one source that works
    // on any phone. The SDK forwards each event into the runtime
    // (`pushBehaviorToRuntime` → `synheart_core_push_behavior`), where it feeds
    // the DIGITAL modality.
    //
    // Worth being precise about, because it is the most common source of "the
    // SDK looks broken": digital signal does not populate focus, capacity,
    // arousal or stress. Those are physiology-derived and stay at zero
    // confidence until heart rate or HRV arrives. Behavior's contribution shows
    // up as `modalities.digital`, which is why the Session screen renders it.

    var behaviorEventCount: Int by mutableStateOf(0)
        private set
    var lastBehaviorEvent: BehaviorEvent? by mutableStateOf(null)
        private set

    /** Per-type tallies, most frequent first. */
    var behaviorBreakdown: List<Pair<BehaviorEventType, Int>> by mutableStateOf(emptyList())
        private set

    private val behaviorCounts = mutableMapOf<BehaviorEventType, Int>()

    // `isFeatureOperational`, not `isWearCollecting`.
    //
    // The `is…Collecting` flags track only the granular startWearCollection() /
    // stopWearCollection() API, which this example does not use — after a plain
    // startSession() they all read false while the modules are in fact running,
    // which is exactly the false negative the Signal sources card exists to
    // avoid. `isFeatureOperational` is the real conjunction the SDK enforces:
    // activated AND consented AND capability-allowed AND a session running.
    val isWearCollecting: Boolean
        get() = Synheart.isFeatureOperational(SynheartFeature.WEAR)
    val isPhoneCollecting: Boolean
        get() = Synheart.isFeatureOperational(SynheartFeature.PHONE_CONTEXT)
    val isBehaviorCollecting: Boolean
        get() = Synheart.isFeatureOperational(SynheartFeature.BEHAVIOR)

    /**
     * True once a sample carrying an actual biosignal has arrived — not merely
     * once the stream is ticking. Until then the runtime has no heart-rate or
     * HRV input, so the physiological axes stay empty no matter how long the
     * session runs.
     */
    val hasBiosignalSource: Boolean get() = wearDataSampleCount > 0

    /**
     * The wear source is emitting, but every sample so far has been empty. This
     * is the normal state on a phone with no wearable paired or with Health
     * Connect permissions not yet granted, and it is worth naming: it looks
     * identical to "working" if you only watch the sample counter.
     */
    val wearEmittingButEmpty: Boolean
        get() = wearSampleCount > 0 && wearDataSampleCount == 0

    /**
     * Why no biosignal is arriving, or null when one is.
     *
     * "no signal" on its own is useless: an ungranted consent, a module that is
     * running with nothing attached, and a paired-but-silent wearable all look
     * identical from the outside, and each has a different fix. This names which
     * one it is.
     */
    val biosignalBlocker: String?
        get() = when {
            hasBiosignalSource -> null
            !isSessionRunning ->
                "No session is running, so nothing is collecting yet."
            consentState?.biosignals != true ->
                "Biosignals consent is not granted, so the wear module is not " +
                    "running — the SDK starts every module on startSession() and " +
                    "then immediately stops the ones consent does not cover. " +
                    "Grant it on the Consent tab and restart the session."
            !Synheart.hasWearSource ->
                "No wear source is attached. Declare `wearConfig` in " +
                    "SynheartConfig to have the SDK attach one, or push your own " +
                    "readings with pushWearHr / pushRr / pushRrBatch. Synthetic " +
                    "data is deliberately NOT substituted — see " +
                    "SynheartConfig.allowSyntheticBiosignals."
            !isWearPlatformAvailable ->
                "A wear source is attached, but Health Connect is not available " +
                    "on this device, so there is no store to read from. Install " +
                    "Health Connect, pair a BLE strap, or push readings yourself. " +
                    "This is not a permission problem — the platform returns no " +
                    "permission state at all."
            !hasWearPermissions ->
                "The wear source is attached and Health Connect is present, but " +
                    "heart rate and HRV are not granted, so every read comes back " +
                    "empty. A manifest declaration is not a grant — tap " +
                    "\"Grant health access\"."
            wearEmittingButEmpty ->
                "The source is emitting and access is granted, but every sample " +
                    "so far is empty — a wearable that is paired but silent, or " +
                    "no wearable at all."
            else ->
                "The source is attached and permitted, but has produced no sample " +
                    "yet. Health Connect returns nothing until a wearable has " +
                    "written data to it."
        }

    /**
     * Whether Health Connect has actually granted heart rate and HRV.
     *
     * Manifest declarations are not grants. Tracked as Compose state because the
     * grant arrives from a system prompt, and the SDK's own getter is a plain
     * call that would leave the screen stale.
     */
    var hasWearPermissions: Boolean by mutableStateOf(false)
        private set

    var isRequestingWearPermissions: Boolean by mutableStateOf(false)
        private set

    /**
     * Send the user to Health Connect to grant heart rate and HRV.
     *
     * Not `Synheart.requestWearPermissions()`: that cannot raise a prompt,
     * because Health Connect grants go through an ActivityResultContract and
     * synheart-wear keeps its contract internal. It returns the existing grants
     * with no UI shown, which reads as "the user declined".
     */
    fun requestWearPermissions() {
        if (isRequestingWearPermissions) return
        isRequestingWearPermissions = true
        val opened = runCatching {
            Synheart.openHealthPermissionSettings(requireContext())
        }.getOrDefault(false)
        if (!opened) {
            SynheartLogger.log("[example] Health Connect settings could not be opened")
        }
        isRequestingWearPermissions = false
    }

    /** Re-read grants after returning from the Health Connect screen. */
    fun onResumed() {
        if (isInitialized) refreshWearPermissions()
        // §6.4 on wake: drain first, then tick — the runner does both.
        host.onForegrounded()
    }

    /**
     * Backgrounding is where §6 gets specific: flush_pending on the way out, or
     * up to one lateness budget's worth of completed windows is stranded.
     */
    fun onPaused() {
        host.onBackgrounded()
    }

    /**
     * Whether Health Connect exists on this device at all.
     *
     * An absent store returns an EMPTY permission map rather than an all-false
     * one, so without this a missing Health Connect looks identical to a user
     * who declined — and the example would tell you to tap a grant button that
     * cannot help.
     */
    var isWearPlatformAvailable: Boolean by mutableStateOf(false)
        private set

    /** Whether the SDK attached a real wear source (i.e. wearConfig was declared). */
    val hasWearSource: Boolean get() = runCatching { Synheart.hasWearSource }.getOrDefault(false)

    fun refreshWearPermissions() {
        hasWearPermissions = runCatching { Synheart.hasWearPermissions }.getOrDefault(false)
        isWearPlatformAvailable =
            runCatching { Synheart.isWearPlatformAvailable }.getOrDefault(false)
    }

    /**
     * True when this build asked for invented biosignals.
     *
     * Surfaced so the UI can label the numbers. Values from the synthetic
     * generator are indistinguishable from real ones once rendered, and they
     * enter the same longitudinal baselines.
     */
    val usesSyntheticBiosignals: Boolean get() = SYNTHETIC_BIOSIGNALS

    // ── Behavior capture ───────────────────────────────────────────────────

    /**
     * Feed real interaction into the SDK from the activity's touch dispatch.
     *
     * Required for behavior collection. Activating the feature and granting
     * behavior consent is not enough on its own — the SDK has no view-tree hook,
     * so the host has to record events itself. Nothing is observed until
     * something calls this.
     *
     * Touches are a genuine signal, so recording them is safe — and interaction
     * alone is enough to ground the HSI digital axes. Contrast the biosignal
     * push APIs, which must never carry invented readings.
     *
     * A no-op before initialize: `Synheart.behaviorEvents` is null until the
     * behavior module exists, and the module drops events that consent does not
     * cover, so this needs no gating of its own.
     */
    fun recordTouch(event: MotionEvent) {
        // One call; the SDK derives the tap and scroll events.
        //
        // This used to be ~25 lines of gesture bookkeeping here, which every
        // host had to reimplement. It now lives in the SDK, including the part that
        // is easy to get wrong: recording per ACTION_MOVE floods the aggregator,
        // since one drag dispatches dozens.
        Synheart.recordTouchEvent(event)
    }

    // ── Companion watch ────────────────────────────────────────────────────
    //
    // The watch runs its own session and computes its own metrics; the phone
    // only relays commands out and events in. Nothing here derives anything.
    //
    // Needs the companion app (synheart-edge-watch-android) installed on the
    // watch — it owns the listener that answers on /synheart/session/command.

    var watchStatus: WatchStatus? by mutableStateOf(null)
        private set
    var watchEventCount: Int by mutableStateOf(0)
        private set

    /**
     * Heart-rate samples the watch sent that reached the engine.
     *
     * The number that matters for HSI: session events only prove the companion
     * is talking. Polled because the SDK counts them on a background collector
     * with nothing to observe.
     */
    var watchHrSampleCount: Int by mutableStateOf(0)
        private set
    var lastWatchEvent: String? by mutableStateOf(null)
        private set
    var watchError: String? by mutableStateOf(null)
        private set

    private var watchJob: Job? = null

    /**
     * True while a session is running on the watch.
     *
     * Compose state, not a getter over `Synheart.isWatchSessionActive`: that is
     * a plain field, so nothing recomposes when it changes. The card read it
     * once and kept offering "Start on watch" against a session that was already
     * running — and the start handler, which checks the live value, silently
     * refused every tap.
     */
    var isWatchSessionRunning: Boolean by mutableStateOf(false)
        private set

    /** Re-read connectivity. Cheap, and the answer changes as the watch moves. */
    fun refreshWatchStatus() {
        if (!isInitialized) return
        watchHrSampleCount = runCatching { Synheart.watchHrSampleCount }.getOrDefault(0)
        viewModelScope.launch {
            // Report the reason rather than collapsing every failure to null.
            // A missing play-services-wearable throws NoClassDefFoundError here,
            // and swallowing it left the card saying "not queried yet" forever
            // with nothing to chase.
            runCatching { Synheart.getWatchStatus() }
                .onSuccess { watchStatus = it; watchError = null }
                .onFailure { watchError = "${it::class.simpleName}: ${it.message}" }
            syncWatchRunning()
        }
    }

    /**
     * Start a session on the watch.
     *
     * Subscribes before sending the command, so the watch's first event cannot
     * arrive before anything is listening.
     */
    fun startWatchSession() {
        if (!isInitialized || isWatchSessionRunning) return
        watchError = null
        watchEventCount = 0
        lastWatchEvent = null

        if (watchJob == null) {
            watchJob = viewModelScope.launch {
                Synheart.watchSessionEvents.collect { event ->
                    watchEventCount++
                    lastWatchEvent = event::class.simpleName
                    // The relay reports failure as an event, not an exception —
                    // an unanswered start arrives here as SessionErrorEvent.
                    if (event is SessionErrorEvent) watchError = event.message
                    syncWatchRunning()
                    watchHrSampleCount = Synheart.watchHrSampleCount
                }
            }
        }

        viewModelScope.launch {
            runCatching {
                Synheart.startWatchSession(
                    SessionConfig(
                        sessionId = "watch_${System.currentTimeMillis()}",
                        mode = SessionMode.FOCUS,
                        durationSec = WATCH_SESSION_SECONDS,
                    ),
                )
            }.onFailure { watchError = it.message ?: it.toString() }
            syncWatchRunning()
            refreshWatchStatus()
        }
    }

    /** Re-read the SDK's live flag into Compose state. */
    private fun syncWatchRunning() {
        isWatchSessionRunning = runCatching { Synheart.isWatchSessionActive }
            .getOrDefault(false)
    }

    fun stopWatchSession() {
        viewModelScope.launch {
            runCatching { Synheart.stopWatchSession() }
                .onFailure { watchError = it.message ?: it.toString() }
            syncWatchRunning()
            refreshWatchStatus()
        }
    }

    // ── 4. Diagnostics ─────────────────────────────────────────────────────

    /**
     * Native runtime health.
     *
     * `missingSymbols` is the field worth understanding, and the caveat is
     * sharp: optional bindings resolve lazily and the bridge has no `probeAll`
     * equivalent, so the list only ever names symbols something already tried
     * to use. An empty list on a screen
     * that called nothing means "nothing was checked", not "everything
     * resolved".
     */
    val diagnostics: JSONObject? get() = Synheart.runtimeDiagnostics()

    /**
     * Compile-time facts about the vendored runtime — crate versions, profile,
     * and the cargo features it was built with. The features list is the one
     * to read when a context event is rejected: without `app-context`,
     * push_context_event is an inert stub returning 1, byte-identical to a
     * rejected payload.
     */
    val buildInfo: JSONObject? get() = Synheart.buildInfo()

    /** True when the FFI bridge loaded and the runtime is answering. */
    val isRuntimeAvailable: Boolean get() = Synheart.isRuntimeAvailable

    val runtimeVersion: String? get() = Synheart.runtimeVersion

    /**
     * Whether the loaded runtime exposes the lab session ABI.
     *
     * Worth surfacing next to `missingSymbols`, because that list does NOT cover
     * it. Most lab operations (`labStart`, `labOpenWindow`, `labFinalize`, …)
     * are bound eagerly rather than through the guarded optional path, so an
     * absent one throws on first access instead of being recorded as missing.
     * `isLabAvailable` is the SDK's documented gate — check it before calling
     * any lab API.
     */
    val isLabAvailable: Boolean get() = Synheart.isLabAvailable

    /** HSI windows buffered during the session, capped by the SDK. */
    val sessionWindows: List<String> get() = Synheart.getSessionHsiWindows()

    // ── Device attestation ─────────────────────────────────────────────────
    //
    // Registration is triggered by CLOUD-UPLOAD CONSENT, not by initialize().
    // Granting it starts the flow in the background (it can park the calling
    // thread for seconds, so the SDK deliberately does not await it) — poll
    // [attestationStatus] to watch it progress.

    // Attestation state is SNAPSHOTTED into Compose state rather than read
    // through to the SDK on each composition.
    //
    // `Synheart.coreDeviceAuthStatus()` and `deviceAuthUsedCoreRuntime` are a
    // native call and a @Volatile field — neither is observable, so a plain
    // getter leaves the card frozen at whatever it read first. That is how the
    // Setup card sat on "pending" while the runtime had already logged
    // `registration completed`.
    //
    // A poll is genuinely required on top of that, not just a refresh on tap:
    // granting cloud-upload consent starts registration on a background thread
    // and the SDK deliberately does not await or notify it, so there is no event
    // to hang a single refresh on.

    var attestationAvailable: Boolean by mutableStateOf(false)
        private set

    /**
     * Runtime attestation snapshot: `{status, device_id, ...}`, or null when
     * device auth is not configured or the ABI is absent.
     */
    var attestationStatus: JSONObject? by mutableStateOf(null)
        private set

    /** True once this process has completed a registration. */
    var attestationRegistered: Boolean by mutableStateOf(false)
        private set

    private var attestationPoll: Job? = null

    /** Re-read the attestation snapshot from the runtime into Compose state. */
    fun refreshAttestation() {
        if (!isInitialized) {
            attestationAvailable = false
            attestationStatus = null
            attestationRegistered = false
            return
        }
        attestationAvailable = Synheart.coreSdkDeviceAuthAvailable
        attestationStatus = runCatching { Synheart.coreDeviceAuthStatus() }.getOrNull()
        attestationRegistered = Synheart.deviceAuthUsedCoreRuntime ||
            // The in-process flag only flips on the paths this app drove. A
            // registration restored from storage, or one kicked off by a consent
            // grant, shows up in the runtime's own status instead — reporting
            // "pending" for an already-registered device is the confusion worth
            // avoiding.
            attestationStatusWord == "registered"
    }

    /**
     * Poll the snapshot while registration could still be in flight.
     *
     * Bounded: it stops once registered, and after [ATTESTATION_POLL_LIMIT]
     * ticks. An unbounded poll on a device that simply cannot attest would spin
     * for the life of the process.
     */
    private fun pollAttestation() {
        if (!attestationConfigured || attestationRegistered) return
        attestationPoll?.cancel()
        attestationPoll = viewModelScope.launch {
            repeat(ATTESTATION_POLL_LIMIT) {
                kotlinx.coroutines.delay(ATTESTATION_POLL_MS)
                refreshAttestation()
                if (attestationRegistered) return@launch
            }
        }
    }

    /**
     * The provenance claim on the registered device: `attested`, `unattested`,
     * or `unknown`.
     *
     * Worth surfacing rather than collapsing into "registered": a device
     * admitted through development mode signs every request with a real
     * hardware key and can ingest normally, but carries no provenance claim.
     * Treating it as equivalent to an attested device is the mistake this
     * distinction exists to prevent.
     */
    val attestationClaim: String
        get() = attestationStatus.field("attestation") ?: "unknown"

    /** The registered device's id, or null before registration. */
    val attestationDeviceId: String? get() = attestationStatus.field("device_id")

    /** The runtime's attestation status word, or null before the runtime loads. */
    val attestationStatusWord: String? get() = attestationStatus.field("status")

    /**
     * Force a registration attempt without waiting for a consent change.
     * Idempotent — no-ops when already registered.
     */
    fun registerDevice() {
        viewModelScope.launch {
            runCatching { Synheart.ensureDeviceAuthRegistered() }
            refreshAttestation()
            pollAttestation()
        }
    }

    /**
     * Re-attest from scratch, bypassing a locally restored `registered` state.
     * Use when the server has lost or revoked the device record.
     */
    fun reregisterDevice() {
        viewModelScope.launch {
            runCatching { Synheart.reregisterDeviceAuth() }
            refreshAttestation()
            pollAttestation()
        }
    }

    // ── Cloud ingest ───────────────────────────────────────────────────────
    //
    // The host does NOT drive uploading. The runtime owns the whole pipeline:
    // it subscribes to the engine's HSI broadcast, enqueues each window into a
    // SQLite upload queue (gated on cloud-upload consent, buffering the windows
    // that arrive during the cold-start token race), and POSTs the queue on
    // `CloudConfig.uploadIntervalMs`.
    //
    //   cloud HSI auto-enqueue: listening (engine HSI → ingest queue)
    //   ingest POST succeeded | url=…/v1/hsi/ingest status_code=200
    //
    // So `Synheart.ingestion.enqueueHsiWindows(...)` is NOT part of the normal
    // path — the runtime's own log calls it the fallback for "if engine skips
    // this channel". Calling it per window on top of the automatic bridge queues
    // every window twice.
    //
    // What is worth having is visibility, and a way to force a flush rather
    // than waiting out the interval. That is all this section does.

    var uploadedCount: Int by mutableStateOf(0)
        private set
    var uploadError: String? by mutableStateOf(null)
        private set
    var lastFlushAt: Instant? by mutableStateOf(null)
        private set
    var isFlushing: Boolean by mutableStateOf(false)
        private set

    /**
     * Windows the runtime has queued but not yet POSTed. Climbs on its own as
     * windows close, and drains on the runtime's own upload interval.
     */
    val uploadQueueLength: Int get() = Synheart.uploadQueueLength

    /**
     * When the runtime last POSTed successfully, on its own schedule.
     *
     * The counters above only see manual flushes, so with the automatic pipeline
     * working they both read 0 and a queue of 0 is ambiguous — "drained" and
     * "nothing ever arrived" look identical. This is the field that actually
     * says uploading works.
     */
    val lastIngestSuccessAt: Instant?
        get() = Synheart.lastIngestSuccessAtMs
            ?.takeIf { it > 0 }
            ?.let { Instant.ofEpochMilli(it) }

    /**
     * POST whatever is queued now, instead of waiting for the runtime's own
     * interval. Safe to call when the queue is empty.
     */
    fun flushUploads() {
        if (!uploadConfigured || isFlushing) return
        isFlushing = true
        viewModelScope.launch {
            try {
                val result = Synheart.ingestion.flushIfEligible()
                lastFlushAt = Instant.now()
                if (result.success) {
                    uploadedCount += result.uploaded
                    uploadError = null
                } else {
                    // Surfaced rather than swallowed: the common failures here
                    // are a missing cloud-upload consent and an unregistered
                    // device, and both look identical to "nothing happened" if
                    // the message is dropped.
                    uploadError = result.errorMessage ?: "flush failed"
                }
            } catch (e: Exception) {
                uploadError = e.toString()
            } finally {
                isFlushing = false
            }
        }
    }

    // ── 5. Teardown ────────────────────────────────────────────────────────

    /** Stop and release everything, returning to the pre-initialize state. */
    suspend fun shutdown() {
        stopSession()
        runCatching { Synheart.dispose() }
        isInitialized = false
        attestationPoll?.cancel()
        attestationPoll = null
        refreshAttestation()
        consentForm = null
        consentState = null
        latestState = null
        hsiWindowCount = 0
        session = null
    }

    fun shutdownAsync() {
        viewModelScope.launch { shutdown() }
    }

    /** Erase every byte the SDK holds on this device. */
    fun wipeLocalData() {
        viewModelScope.launch {
            runCatching { Synheart.wipeLocalData() }
            // The host's own snapshots are NOT runtime storage — they live in
            // this app's preferences, so Synheart.wipeLocalData() does not touch
            // them. Left behind, they would restore the wiped baselines on the
            // next launch.
            host.clearSnapshots()
            latestState = null
            hsiWindowCount = 0
            refreshConsent()
        }
    }

    override fun onCleared() {
        host.dispose()
        watchJob?.cancel()
        attestationPoll?.cancel()
        hsiJob?.cancel()
        wearJob?.cancel()
        behaviorJob?.cancel()
        super.onCleared()
    }

    private fun requireContext(): Context = checkNotNull(appContext) {
        "loadIdentity(context) must run before any SDK call"
    }
}

/**
 * Read a string field, treating a JSON null as absent.
 *
 * `optString` returns the literal text `"null"` for an explicit JSON null, so a
 * plain `optString("device_id")` rendered `device id: null` on screen — which
 * reads as a value rather than as "not registered yet".
 */
private fun JSONObject?.field(name: String): String? =
    this?.optString(name)?.takeIf { it.isNotEmpty() && it != "null" }

/**
 * Whether this example asks the SDK for invented biosignals.
 *
 * Hard-coded false, and deliberately a constant rather than a toggle: a switch
 * would let a demo produce numbers that look like measurements and land in the
 * same SRM baselines as real ones. Flip it locally only to exercise the
 * pipeline.
 */
private const val SYNTHETIC_BIOSIGNALS = false

/** Duration for the demo watch session. Short, so a test round-trips quickly. */
private const val WATCH_SESSION_SECONDS = 120

/** How often to re-read the attestation snapshot while it may still change. */
private const val ATTESTATION_POLL_MS = 1_500L

/** Ticks before giving up — a device that cannot attest never becomes registered. */
private const val ATTESTATION_POLL_LIMIT = 20

