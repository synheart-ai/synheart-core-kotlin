package ai.synheart.core

import ai.synheart.core.bridge.CoreRuntimeBridge
import ai.synheart.core.config.SynheartConfig
import ai.synheart.core.config.buildRuntimeConfigMap
import ai.synheart.core.modules.behavior.RuntimeBehaviorEvent
import org.json.JSONObject

/**
 * A single, self-contained Synheart runtime instance (one native handle).
 *
 * [Synheart] is the **personal** runtime and remains the default surface for
 * the app. Use [SynheartInstance] to run a **second** runtime alongside it —
 * the canonical case being a **research** instance with its own pseudonymous
 * `subject_id`, its own [dataDir], and its own attested device identity,
 * created on study enrolment and disposed + wiped on withdrawal.
 *
 * The underlying [CoreRuntimeBridge] is already fully per-handle (every
 * operation routes through its own native handle), so two instances are
 * independent: distinct engines, Tokio runtimes, storage, and device-auth
 * identities. The one hard requirement is a **distinct [dataDir] and
 * `subject_id` per instance** — otherwise the two handles contend on the same
 * SQLite / SRM files.
 */
class SynheartInstance private constructor(
    private val bridge: CoreRuntimeBridge,
    val config: SynheartConfig,
    /**
     * The durable directory this instance's native runtime writes to
     * (`synheart_<subject>.db`, SRM snapshot, ingest queue). Must be unique
     * per instance — e.g. `<filesDir>/synheart-core/research`.
     */
    val dataDir: String,
) {

    @Volatile
    private var disposed = false

    companion object {
        /**
         * Create and wire a new runtime instance, or null when the native
         * library is unavailable or the handle could not be created.
         *
         * Wiring mirrors [Synheart.ensureRuntimeBridge]: create the handle,
         * attach storage callbacks (so consent tokens and device records
         * persist under *this* instance's store), then attach the per-handle
         * crypto callbacks so it can attest its **own** device identity.
         *
         * Baseline/SRM cloud hooks are deliberately not wired here — they
         * target a process-global hydrator on the personal facade and a
         * research instance does not want them.
         */
        fun create(config: SynheartConfig, dataDir: String): SynheartInstance? {
            config.validate()
            val bridge = CoreRuntimeBridge.create(
                buildRuntimeConfigMap(config, dataDir = dataDir).toString(),
            )
            if (bridge == null) {
                SynheartLogger.log(
                    "[SynheartInstance] core runtime bridge unavailable for " +
                        "subject=${config.subjectId} dataDir=$dataDir",
                )
                return null
            }

            // Persist consent tokens / device records under THIS instance's store.
            val storageRc = bridge.setStorageCallbacks()
            if (storageRc != 0) {
                SynheartLogger.log(
                    "[SynheartInstance] setStorageCallbacks rc=$storageRc — state may " +
                        "not persist for subject=${config.subjectId}",
                )
            }

            // Per-instance attested identity: attach crypto callbacks before any
            // registration or proof call. A distinct subject_id + device_id keeps
            // this identity separate from the personal instance's.
            if (config.deviceAuthConfig != null) {
                val cryptoRc = bridge.setSdkCryptoCallbacks()
                if (cryptoRc != 0) {
                    SynheartLogger.log(
                        "[SynheartInstance] setSdkCryptoCallbacks rc=$cryptoRc — device " +
                            "auth will fail for subject=${config.subjectId}",
                    )
                }
            }

            SynheartLogger.log(
                "[SynheartInstance] created (subject=${config.subjectId} " +
                    "org=${config.cloudConfig?.orgId.orEmpty()} dataDir=$dataDir)",
            )
            return SynheartInstance(bridge, config, dataDir)
        }
    }

    private fun json(raw: String?): JSONObject? =
        raw?.let { runCatching { JSONObject(it) }.getOrNull() }

    // ── State ────────────────────────────────────────────────────────────

    val isDisposed: Boolean get() = disposed

    /** Whether the lab C ABI is available in the loaded native build. */
    val isLabAvailable: Boolean get() = !disposed && bridge.isLabAvailable()

    /**
     * The canonical subject id the runtime resolved. Device auth derives it
     * from the `client_id` passed to [registerDevice].
     */
    val subjectId: String? get() = if (disposed) null else bridge.runtimeSubjectId()

    // ── Device auth (per-instance attested identity) ─────────────────────

    /**
     * Register (attest) this instance's device identity under [clientId]. For
     * a research instance, pass the pseudonymous research client id so the
     * derived subject is research-scoped and unlinkable to the personal one.
     */
    fun registerDevice(clientId: String): JSONObject? =
        if (disposed) null else json(bridge.registerDevice(clientId))

    fun deviceAuthStatus(): JSONObject? =
        if (disposed) null else json(bridge.deviceAuthStatus())

    /**
     * Native device/sync readiness snapshot for THIS instance — the
     * **pollable** signal for detecting a revoked or unregistered attested
     * identity (`device_revoked` / `device_registered`).
     *
     * Ingest HTTP, and therefore any 401, lives entirely inside the native
     * runtime and never surfaces here, so an app recovering an invalidated
     * research identity polls this snapshot rather than observing the 401.
     */
    fun syncReadiness(): JSONObject? = if (disposed) null else bridge.syncReadiness()

    /** Build a device-signed proof header on this instance's identity. */
    fun buildProofHeader(method: String, absoluteUrl: String): String? =
        if (disposed) null else bridge.buildProofHeader(method, absoluteUrl)

    // ── Consent ──────────────────────────────────────────────────────────

    fun consentGetEditableForm(): JSONObject? =
        if (disposed) null else json(bridge.consentGetEditableForm())

    fun consentSubmitForm(
        deviceId: String,
        platform: String,
        userId: String? = null,
        formJson: String,
    ): JSONObject? = if (disposed) {
        null
    } else {
        json(bridge.consentSubmitForm(deviceId, platform, userId, formJson))
    }

    fun consentStatus(): JSONObject? = if (disposed) null else json(bridge.consentStatus())

    // ── Research study (per-instance, attested credential) ───────────────
    //
    // These run on THIS instance's own handle: enrol / withdraw / status
    // resolve the participant and app from the instance's device-attested
    // cloud credential, with no user JWT. Use these rather than the
    // `Synheart.*ResearchStudy*` helpers, which are bound to the personal
    // singleton runtime.

    /** Preview an (access, study) code pair without redeeming it. */
    fun validateResearchStudyCodes(accessCode: String, studyCode: String): JSONObject? =
        if (disposed) null else json(bridge.validateResearchStudyCodes(accessCode, studyCode))

    /** Redeem an (access, study) code pair on this instance's credential. */
    fun enrolResearchStudy(accessCode: String, studyCode: String): JSONObject? =
        if (disposed) null else json(bridge.enrolResearchStudy(accessCode, studyCode))

    /** Read this instance's current active research-study enrolment. */
    fun researchStudyStatus(): JSONObject? =
        if (disposed) null else json(bridge.researchStudyStatus())

    /** Withdraw this instance from its active research study. */
    fun withdrawResearchStudy(): JSONObject? =
        if (disposed) null else json(bridge.withdrawResearchStudy())

    // ── Signal push (fan-in during a lab window) ─────────────────────────

    /**
     * Push an R-R interval into this instance. During an open lab window this
     * feeds the research session's wear data.
     */
    fun pushRr(tsMs: Long, rrMs: Double, provider: String = "default_sensor") {
        if (!disposed) bridge.pushRr(tsMs, rrMs, provider)
    }

    /**
     * Push a batch of R-R intervals delivered together in one sensor
     * notification. Prefer this over looping [pushRr] when a packet carries
     * several values sharing one arrival timestamp (e.g. a BLE HRM) — it keeps
     * every beat's timing.
     */
    fun pushRrBatch(
        anchorTsMs: Long,
        rrMs: DoubleArray,
        order: Int = 0,
        provider: String = "default_sensor",
    ) {
        if (!disposed) bridge.pushRrBatch(anchorTsMs, rrMs, order, provider)
    }

    fun pushHr(tsMs: Long, bpm: Double) {
        if (!disposed) bridge.pushHr(tsMs, bpm)
    }

    fun ingestBatch(batchJson: String, nowMs: Long): String? =
        if (disposed) null else bridge.ingestBatch(batchJson, nowMs)

    // ── Behavior fan-in (typing dynamics during a lab window) ────────────

    /**
     * Push a raw behavior event into this instance's engine, feeding an open
     * lab session's behavioral metrics. [eventType] is a
     * [RuntimeBehaviorEvent] code; most callers want [pushBehaviorTouch].
     */
    fun pushBehavior(tsMs: Long, eventType: Int, value: Double) {
        if (!disposed) bridge.pushBehavior(tsMs, eventType, value)
    }

    /**
     * Push a touch/keystroke event. During an open lab window this feeds the
     * research session's typing dynamics, so the finalized payload carries the
     * same behavioral metrics the personal runtime produces.
     */
    fun pushBehaviorTouch(tsMs: Long = System.currentTimeMillis()) {
        pushBehavior(tsMs, RuntimeBehaviorEvent.INPUT.code, 1.0)
    }

    // ── Session lifecycle (drives the pipeline the lab window records) ───

    /**
     * Start this instance's session. Call before [labStart] so the engine has
     * an active pipeline for the lab window to record over.
     */
    fun startSession(): JSONObject? = if (disposed) null else json(bridge.startSession())

    /** Stop this instance's session. Call after [labFinalize]. */
    fun stopSession(): Boolean = !disposed && bridge.stopSession()

    val isSessionRunning: Boolean get() = !disposed && bridge.isRunning()

    /**
     * Advance the pipeline clock so windows that should close by [nowMs] are
     * flushed — the same window-closing the personal runtime's ticker drives.
     */
    fun tick(nowMs: Long): String? = if (disposed) null else bridge.tick(nowMs)

    // ── Mobile host surface ──────────────────────────────────────────────

    /**
     * Push a typed behavior event carrying its full payload — most importantly
     * a windowed `TypingSessionData`, which the legacy int-coded path cannot
     * express at all.
     *
     * `true` accepted, `false` rejected, `null` when this instance is disposed
     * or the runtime does not export the symbol. Do not also push the raw
     * keystrokes behind a `Typing` summary — the engine counts both and every
     * rate feature roughly doubles.
     */
    fun pushBehaviorEvent(event: ai.synheart.core.models.BehaviorEventInput): Boolean? =
        if (disposed) null else bridge.pushBehaviorEvent(event.toJson().toString())

    /**
     * Whether the loaded runtime takes rich behavior events on this instance.
     * Same probe the static [Synheart.supportsRichBehaviorEvents] runs for the
     * personal runtime. Both instances load one native library, but a host
     * feeding two handles should ask the handle it is about to feed.
     */
    val supportsRichBehaviorEvents: Boolean
        get() = !disposed && bridge.supportsRichBehaviorEvents

    // ── Context fan-in (app identity + keystroke context during a lab window) ─
    //
    // The static `Synheart.pushAppForeground` / `Synheart.pushContextEvent`
    // reach the PERSONAL runtime only. A host running a second, research
    // instance had no way to give it an app identity or context evidence, so
    // every research window resolved to the `Unknown` app category (an
    // all-zero interpretation-mask row) and carried `context_label: UK` with
    // no evidence behind it. These are the per-instance equivalents.

    /**
     * Declare which application is in the foreground for THIS instance.
     *
     * Same contract as the static call: send at session start, on every
     * foreground change, and on a slow heartbeat — repeats are steady-state
     * observations, not switches. [app] is the Android package name.
     * `true` accepted, `false` rejected, `null` when this instance is disposed
     * or the runtime lacks `push_behavior_event`.
     */
    fun pushAppForeground(app: String, tsMs: Long = System.currentTimeMillis()): Boolean? =
        pushBehaviorEvent(ai.synheart.core.models.BehaviorEventInput.appForeground(tsMs, app))

    /**
     * Push one privacy-preserving context event into this instance — the only
     * source of `context.deviation.*`, and therefore of CFI. Mirrors the static
     * [Synheart.pushContextEvent]; see it for the send-both-directions rule.
     *
     * `true` accepted, `false` rejected, `null` when this instance is disposed
     * or the runtime does not export the symbol. A `false` most often means the
     * runtime was built without the `app-context` cargo feature.
     */
    fun pushContextEvent(event: ai.synheart.core.models.ContextEventInput): Boolean? =
        if (disposed) null else bridge.pushContextEvent(event.toJson().toString())

    /**
     * Raw-payload escape hatch for [pushContextEvent]; the static
     * [Synheart.pushContextEventJson] explains when to prefer the typed call.
     */
    fun pushContextEventJson(event: JSONObject): Boolean? =
        if (disposed) null else bridge.pushContextEvent(event.toString())

    /**
     * Declare the window containing [tsMs] to be a rest window. One-shot: call
     * once per rest window, not once when a break begins.
     */
    fun declareRestWindow(tsMs: Long) {
        if (!disposed) bridge.declareRestWindow(tsMs)
    }

    /** Drain every completed window as a JSON array. Prefer this to [tick] after a gap. */
    fun tickAll(nowMs: Long): String? = if (disposed) null else bridge.tickAll(nowMs)

    /** Emit every window still held by the lateness budget. */
    fun flushPending(nowMs: Long): String? = if (disposed) null else bridge.flushPending(nowMs)

    /** Export this instance's per-head session state for persistence. */
    fun exportSessionState(): String? = if (disposed) null else bridge.exportSessionState()

    /** Restore session state. Must run before this instance's first [tick]. */
    fun loadSessionState(json: String): Boolean? =
        if (disposed) null else bridge.loadSessionState(json)

    /** This instance's comparability key. Persist it beside any cached score. */
    fun configId(): String? = if (disposed) null else bridge.configId()

    // ── Lab session ──────────────────────────────────────────────────────

    /** Whether the lab-metadata C ABI is available in the loaded native build. */
    val isLabMetadataAvailable: Boolean
        get() = !disposed && "lab_ensure_metadata" !in bridge.missingSymbols

    /**
     * Cache the lab-session metadata (device / platform / user info) on this
     * instance before the first [labStart].
     */
    fun labEnsureMetadata(
        deviceId: String,
        platform: String = "android",
        osVersion: String = android.os.Build.VERSION.RELEASE ?: "",
        userInfoJson: String? = null,
        deviceExtraJson: String? = null,
    ): String? = if (disposed) {
        null
    } else {
        bridge.labEnsureMetadata(deviceId, platform, osVersion, userInfoJson, deviceExtraJson)
    }

    fun labStart(protocolJson: String, startedAtMs: Long): Boolean =
        !disposed && bridge.labStart(protocolJson, startedAtMs)

    fun labOpenWindow(
        windowType: String,
        parentId: String? = null,
        label: String? = null,
        startedAtMs: Long = System.currentTimeMillis(),
    ): String? = if (disposed) {
        null
    } else {
        bridge.labOpenWindow(parentId, windowType, label, startedAtMs)
    }

    fun labCloseWindow(windowId: String, endedAtMs: Long): Boolean =
        !disposed && bridge.labCloseWindow(windowId, endedAtMs)

    fun labSetWindowValues(windowId: String, valuesJson: String): Boolean =
        !disposed && bridge.labSetWindowValues(windowId, valuesJson)

    fun labMergeSessionExtraData(patchJson: String): Boolean =
        !disposed && bridge.labMergeExtraData(patchJson)

    /**
     * Finalize the lab session. The runtime auto-enqueues the payload to cloud
     * lab-ingest under this instance's (research) identity.
     */
    fun labFinalize(endedAtMs: Long): String? =
        if (disposed) null else bridge.labFinalize(endedAtMs)

    val isLabReenqueueAvailable: Boolean
        get() = !disposed && "reenqueue_lab_session" !in bridge.missingSymbols

    fun labReenqueueSession(sessionJson: String): Boolean =
        !disposed && bridge.reenqueueLabSession(sessionJson) == 0

    // ── Teardown (withdrawal) ────────────────────────────────────────────

    /**
     * Wipe this instance's local runtime data (SQLite, SRM, ingest queue,
     * consent + device records). Call before [dispose] on study withdrawal.
     */
    fun wipeLocalData(): Boolean = !disposed && bridge.wipeLocalData()

    /** Free the native handle. The instance is unusable afterwards. Idempotent. */
    fun dispose() {
        if (disposed) return
        disposed = true
        bridge.close()
    }
}
