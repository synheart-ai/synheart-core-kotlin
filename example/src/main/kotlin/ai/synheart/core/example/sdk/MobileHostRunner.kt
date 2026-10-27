package ai.synheart.core.example.sdk

import ai.synheart.core.Synheart
import ai.synheart.core.config.Declared
import ai.synheart.core.config.HostDeclarations
import ai.synheart.core.config.SensingMode
import ai.synheart.core.config.SensingProfile
import ai.synheart.core.config.SensingStreams
import ai.synheart.core.example.SensingForegroundService
import ai.synheart.core.models.AccelPlacement
import ai.synheart.core.models.BehaviorEventInput
import ai.synheart.core.models.ContextEventInput
import ai.synheart.core.models.DailyMetric
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The host-side half of the mobile integration: everything the guide says a
 * mobile SDK team has to *drive*, as opposed to configure.
 *
 * [SynheartController] owns configuration, consent and the session. This owns
 * what has to keep happening once a session is live, in the order §6 requires:
 *
 * * **§6.1 — tick for the whole session.** The engine *does* run its own 1 Hz
 *   ticker from `start_session` (the guide's "there is no internal ticker" was
 *   wrong and has been corrected upstream), but it uses `tick`, not `tick_all`,
 *   so after any gap it drains one window and skips the rest. This loop calls
 *   `tick_all` so nothing is left behind. Consequences: [windowsDrained]
 *   **undercounts** — windows the engine's own loop drains never pass through
 *   it — and "frames are flowing" does not prove this loop is running.
 * * **§6.2 — keep the process alive.** A `dataSync` foreground service, bound
 *   to the session rather than started from an IME.
 * * **§6.4 — drain before the tick that closes the window.** Retroactive
 *   buffers (the typing aggregator) are flushed *first*, then the tick runs.
 * * **§6.5 — declare rest.** Evaluated once per window, one-shot per window.
 * * **§7 — session state.** Exported once per emitted window and on
 *   background, loaded *before the first tick*. The personal baselines are
 *   the runtime's to seal (0.42+).
 * * **§8 — the daily loop.** The engine closes each local day itself; this
 *   attaches the closed day's Strain once per date, and pushes wearable
 *   dailies under the local date before refreshing the reference.
 *
 * It also owns the simulated cardiac stream — the only simulated source in the
 * example, and the only signal available on a bare phone with no strap.
 *
 * ## Degradation is the normal case, not the error case
 *
 * The vendored runtime is a pinned artifact that lags this SDK, so several of
 * these calls are no-ops on any given device — `Synheart.mobileHostAbiSupport`
 * says which. Every call site handles the absent case by name, and
 * [abiSupport] is surfaced so the UI can show which parts of this file are
 * actually running.
 *
 * State is Compose `mutableStateOf` so screens subscribe by reading, matching
 * the controller.
 */
class MobileHostRunner(private val scope: CoroutineScope) {

    // ── Declarations this host makes (§2) ─────────────────────────────────

    /**
     * Whether to declare a sensing profile, device class, mask profile and CFI
     * denominator at all.
     *
     * Off by default: the sensing and mask declarations change engine output
     * for a host already in the field. `device_class` is sent as `"auto"`
     * either way — the SDK declares it when the host does not. A first-launch
     * decision to be made once and kept stable, which is why it is a toggle on
     * the Setup screen rather than something flipped at will.
     */
    var declareHostProfile: Boolean by mutableStateOf(false)

    /**
     * Force continuous sensing. On Android the foreground service below earns
     * it already, so this is mostly a demo affordance for seeing the two
     * stateful heads come back after an episodic run.
     */
    var claimContinuousSensing: Boolean by mutableStateOf(false)

    /**
     * Where the accelerometer currently sits (§4.3). [AccelPlacement.UNKNOWN]
     * withholds all four kinematic heads; only `POCKET` and `WAIST` are inside
     * the validated envelope. Dynamic — re-declared as it changes.
     */
    var placement: AccelPlacement by mutableStateOf(AccelPlacement.UNKNOWN)
        private set

    fun buildHostDeclarations(): HostDeclarations {
        if (!declareHostProfile) return HostDeclarations()
        return HostDeclarations(
            sensing = Declared.Value(
                SensingProfile(
                    // Continuous, earned by the dataSync foreground service this
                    // runner starts with the session. There is no episodic branch
                    // on Android; [claimContinuousSensing] exists for parity with
                    // hosts that default to episodic (an iOS app with no
                    // connected BLE peripheral).
                    mode = SensingMode.CONTINUOUS,
                    // A lateness budget matters even with no retroactive
                    // wearable source: the typing aggregator is one. The engine
                    // holds each completed window this long and emits it once,
                    // complete, just later — the frame's content is unchanged.
                    latenessBudgetMs = 30_000,
                    streams = OBSERVED_STREAMS,
                ),
            ),
            deviceClass = Declared.Auto,
            maskProfile = Declared.Auto,
            // 4 is the documented mobile value. It LOWERS conf_CFI for identical
            // evidence by widening the coverage denominator — that direction
            // surprises people, and it is correct.
            cfiStructuralComponents = 4,
        )
    }

    // ── Runtime capability audit (§1) ─────────────────────────────────────

    val abiSupport: Map<String, Boolean> get() = Synheart.mobileHostAbiSupport
    val unsupportedCalls: List<String>
        get() = abiSupport.filterValues { !it }.keys.sorted()

    // ── Session-scoped state ──────────────────────────────────────────────

    private var tickJob: Job? = null
    private var cardiacJob: Job? = null
    private var midnightJob: Job? = null
    private var behaviorJob: Job? = null

    private var store: HostSnapshotStore? = null

    val rest = RestWindowDetector()
    val typing = TypingMicroWindowAggregator()

    private var cardiac: SyntheticCardiacSource? = null
    private var lastCardiacTickMs = 0L

    var isRunning: Boolean by mutableStateOf(false)
        private set
    var isStreamingCardiac: Boolean by mutableStateOf(false)
        private set

    // ── Counters the UI renders ───────────────────────────────────────────

    var ticks: Int by mutableStateOf(0); private set
    var windowsDrained: Int by mutableStateOf(0); private set
    var restDeclarations: Int by mutableStateOf(0); private set
    var typingWindowsPushed: Int by mutableStateOf(0); private set
    var typingTapsPushed: Int by mutableStateOf(0); private set
    var pendingTypingTaps: Int by mutableStateOf(0); private set
    var rrPacketsPushed: Int by mutableStateOf(0); private set
    var beatsPushed: Long by mutableStateOf(0L); private set
    var sessionStateSaves: Int by mutableStateOf(0); private set
    var dailyPushes: Int by mutableStateOf(0); private set
    var contextEventsAccepted: Int by mutableStateOf(0); private set
    var contextEventsRejected: Int by mutableStateOf(0); private set
    var appForegroundPushes: Int by mutableStateOf(0); private set
    var strainScoresAttached: Int by mutableStateOf(0); private set
    var lastError: String? by mutableStateOf(null); private set

    var latestSimBpm: Double? by mutableStateOf(null); private set
    var latestSimRmssd: Double? by mutableStateOf(null); private set
    var simActivity: String by mutableStateOf("—"); private set

    /** The comparability key this session runs under (§9.5). */
    var configId: String? by mutableStateOf(null); private set

    /** True when the persisted `config_id` differs from the live one. */
    var configIdChanged: Boolean by mutableStateOf(false); private set

    /** The engine's own `motion.accel_rms`, which the rest detector's low-motion clause reads. */
    var latestAccelRms: Double? by mutableStateOf(null); private set

    /** Why the rest detector last declined, for the UI. */
    var restDeclineReason: String? by mutableStateOf(null); private set

    /** Whether the keep-alive service is up. Its failure is otherwise invisible. */
    var foregroundServiceRunning: Boolean by mutableStateOf(false); private set

    // ── Lifecycle ─────────────────────────────────────────────────────────

    /**
     * Prepare persistence and restore state. **Call before the first tick.**
     *
     * `load_session_state` must run before window 1, because window 1 writes
     * each head's state slot — a later restore is silently overwritten by a
     * cold window, and by then the context baseline has counted one window
     * against the wrong history. Calling this from `initialize()` rather than
     * `startSession()` is what makes that ordering unconditional.
     */
    fun restore(context: Context, subjectId: String) {
        val s = HostSnapshotStore(context, subjectId)
        store = s

        configId = Synheart.configId()
        val stored = s.readConfigId()
        configIdChanged = configId != null && stored != null && stored != configId
        configId?.let { s.writeConfigId(it) }

        // The personal baselines need nothing here: the runtime restored its
        // own sealed snapshot when it started.
        s.readSessionState()?.let { json ->
            if (Synheart.loadSessionState(json) == null) {
                // Not an error — the vendored runtime predates the symbol. The
                // heads start cold, which is the pre-0.16.0 behaviour.
                lastError = "load_session_state is absent from this runtime; the stateful " +
                    "heads start cold each launch."
            }
        }
    }

    /** Start the loops. Call right after `Synheart.startSession()` succeeds. */
    fun start(context: Context) {
        if (tickJob != null) return
        ticks = 0; windowsDrained = 0; restDeclarations = 0
        typingWindowsPushed = 0; typingTapsPushed = 0; pendingTypingTaps = 0
        rrPacketsPushed = 0; beatsPushed = 0
        sessionStateSaves = 0; dailyPushes = 0
        contextEventsAccepted = 0; contextEventsRejected = 0
        appForegroundPushes = 0; strainScoresAttached = 0
        lastError = null
        rest.reset()
        typing.reset()

        // Placement is declared explicitly, including the default. Declaring
        // UNKNOWN is not the same as never calling it: the call is what makes
        // the withholding attributable rather than merely absent.
        Synheart.setAccelPlacement(placement)

        // §8 — attach the closed day's Strain, if a day closed since the last
        // attach, and again just after each local midnight.
        attachClosedDayStrainIfNeeded()
        scheduleAfterMidnight()

        // Interaction feeds the rest detector's quiet clock.
        behaviorJob = scope.launch {
            Synheart.behaviorEventStream.collect { rest.noteInteraction(it.timestamp) }
        }

        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                onTick()
            }
        }
        isRunning = true

        // §6.2 — keep the process alive for the whole session. Never fatal: a
        // session that refused to start because the keep-alive failed would be
        // worse than one that runs and stops collecting on background.
        foregroundServiceRunning = runCatching {
            SensingForegroundService.start(context)
            true
        }.getOrElse {
            lastError = "The sensing foreground service did not start (${it.message}). The tick " +
                "loop will stop when Android stops scheduling this process."
            false
        }
    }

    /**
     * Stop the loops and persist everything. `flush_pending` matters most:
     * without it up to one lateness budget's worth of windows is stranded.
     */
    fun stop(context: Context) {
        tickJob?.cancel(); tickJob = null
        midnightJob?.cancel(); midnightJob = null
        behaviorJob?.cancel(); behaviorJob = null
        stopCardiacStream()
        isRunning = false

        runCatching { SensingForegroundService.stop(context) }
        foregroundServiceRunning = false

        // Same §6.4 ordering as every tick: the host's own buffer first.
        flushTypingWindow(force = true)
        countDrained(Synheart.flushPending(System.currentTimeMillis()))
        persistAll()
    }

    /** App went to background. Flush and persist. */
    fun onBackgrounded() {
        rest.noteScreenOff(System.currentTimeMillis())
        if (!isRunning) return
        flushTypingWindow(force = true)
        countDrained(Synheart.flushPending(System.currentTimeMillis()))
        persistAll()
    }

    fun onForegrounded() {
        rest.noteScreenOn(System.currentTimeMillis())
        // §6.4 on wake: drain first, then tick. A gap needs tick_all, not tick.
        if (isRunning) onTick()
    }

    fun dispose() {
        tickJob?.cancel(); cardiacJob?.cancel(); midnightJob?.cancel(); behaviorJob?.cancel()
    }

    // ── The tick loop (§6.1, §6.4) ────────────────────────────────────────

    private fun onTick() {
        val now = System.currentTimeMillis()
        ticks++

        // 1. Drain retroactive buffers BEFORE the tick that may close their
        //    window. Reversed, the window closes without them and the window
        //    cursor drops them with no counter to show it.
        flushTypingWindow(force = false, nowMs = now)

        // 2. Advance the clock. tick_all drains every completed window; tick
        //    polls one, so after any gap it silently skips the rest. Fall back
        //    only when the symbol is absent.
        val drained = Synheart.tickAll(now)
        if (drained == null) {
            if (!Synheart.tick(now).isNullOrEmpty()) windowsDrained++
        } else {
            countDrained(drained)
        }

        // 3. Read the engine's own motion estimate, so the rest detector's
        //    low-motion clause has a measurement rather than an assumption.
        refreshAccelRms()

        // 4. Rest, once per window, one-shot.
        rest.evaluate(now)?.let {
            Synheart.declareRestWindow(it)
            restDeclarations = rest.declaredCount
        }
        restDeclineReason = rest.lastDeclineReason
        pendingTypingTaps = typing.pendingTapCount
    }

    private fun countDrained(json: String?) {
        if (json.isNullOrEmpty()) return
        runCatching { windowsDrained += JSONArray(json).length() }
    }

    private fun refreshAccelRms() {
        val features = Synheart.lastFeatures() ?: return
        runCatching {
            val rms = JSONObject(features).optJSONObject("motion")?.opt("accel_rms") as? Number
            if (rms != null) {
                latestAccelRms = rms.toDouble()
                rest.noteMotionRms(rms.toDouble())
            }
        }
    }

    // ── Typing micro-windows (§5.3) ───────────────────────────────────────

    /**
     * Two pushes per change, on two channels, and both are needed: the 10 s
     * windowed summary → behaviour channel → `TypingFluency`; one keyboard
     * context event per change → context channel → `err_elevation`, CFI's
     * correction sub-component. Not a double count: separate buffers, separate
     * consumers. Both directions must be sent — `err_rate` is `N_corr / N_key`,
     * so deletions alone spike the error rate to its ceiling.
     */
    fun onTypingChanged(text: String) {
        val now = System.currentTimeMillis()
        rest.noteInteraction(now)
        val delta = text.length - typing.currentLength
        typing.onTextChanged(text, now)?.let { pushTypingWindow(it) }
        // A zero-length change (autocorrect swapping same-length words) is not
        // a countable keystroke in either direction; the aggregator skips it too.
        if (delta != 0) pushContextEvent(ContextEventInput.textChange(now, isDeletion = delta < 0))
        pendingTypingTaps = typing.pendingTapCount
    }

    fun resetTypingProbe() {
        typing.reset()
        pendingTypingTaps = 0
    }

    private fun flushTypingWindow(force: Boolean, nowMs: Long = System.currentTimeMillis()) {
        val completed = if (force) typing.flushNow() else typing.flushIfElapsed(nowMs)
        completed?.let { pushTypingWindow(it) }
    }

    private fun pushTypingWindow(window: TypingWindow) {
        // Stamped at the window START, matching desktop.
        val status = Synheart.pushBehaviorEvent(
            BehaviorEventInput.typing(window.windowStartMs, window.session),
        )
        if (status == null) {
            // Deliberately NOT retried through the legacy int-coded path:
            // `push_behavior` carries no payload, so the session would arrive
            // all-null, and the raw keystrokes behind this summary already took
            // that path inside the SDK. Sending both is the §5.4 double-count.
            lastError = "push_behavior_event is absent from this runtime — typing windows " +
                "cannot reach the engine. Re-vendor with `synheart install runtime`."
            return
        }
        typingWindowsPushed++
        typingTapsPushed += window.session.typingTapCount ?: 0
    }

    // ── Placement (§4.3) ──────────────────────────────────────────────────

    fun setAccelPlacement(next: AccelPlacement) {
        placement = next
        Synheart.setAccelPlacement(next)
    }

    // ── Rest, declared by hand ────────────────────────────────────────────

    /**
     * Declare the current window a rest window without waiting for the
     * composite. A demo affordance — the composite needs two minutes of
     * screen-off and nobody sits through that — not something a real host does.
     */
    fun declareRestNow() {
        Synheart.declareRestWindow(System.currentTimeMillis())
        restDeclarations++
    }

    // ── Foreground app context (§5.5) ─────────────────────────────────────

    /**
     * Declare this app as the foreground app.
     *
     * The call that gives the engine an app identity — it rides the *behaviour*
     * channel as `kind: "app_foreground"`, not the context channel. Without any
     * identity `current_app` stays `None`, `None` resolves to the `Unknown` app
     * category, and `Unknown`'s interpretation-mask row is all zeros.
     *
     * The SDK already runs a 30 s heartbeat of this for the session
     * (`BehaviorConfig.reportForegroundApp`); this is here so the effect is
     * observable on demand. It reports **itself**, which is true while the
     * person is in this app — the SDK stops on background rather than assert it
     * from there. A real host implements `ForegroundAppSource` over
     * `UsageStatsManager` (PACKAGE_USAGE_STATS) and passes it in the config.
     */
    fun declareSelfForeground(context: Context) {
        val accepted = Synheart.pushAppForeground(context.packageName)
        if (accepted == null) {
            lastError = "push_behavior_event is absent from this runtime, so app_foreground " +
                "cannot be delivered and every window is typed against the Unknown app category."
        } else {
            appForegroundPushes++
        }
    }

    // There is deliberately NO button that pushes a hand-made context event.
    // That would be fabricated non-cardiac evidence, and cardiac is the only
    // thing this example simulates. The channel is observable anyway: the SDK
    // derives LeftClick / Scroll events from real touches, and the typing probe
    // pushes a Keyboard event per real keystroke.

    private fun pushContextEvent(event: ContextEventInput) {
        when (Synheart.pushContextEvent(event)) {
            null -> lastError = "push_context_event is absent from this runtime — no context layer."
            true -> contextEventsAccepted++
            false -> {
                // Far more likely "built without the app-context cargo feature"
                // (an inert stub that always returns 1) than a bad payload —
                // the shape is pinned by ContextEventInputTest.
                contextEventsRejected++
                lastError = "push_context_event rejected the payload. The runtime was probably " +
                    "built without the `app-context` cargo feature."
            }
        }
    }

    // ── The simulated cardiac stream ──────────────────────────────────────

    /**
     * Start streaming simulated beats. One `push_rr_batch(anchor, rr[], order)`
     * per notification rather than a loop of `push_rr`: a BLE Heart Rate
     * Measurement carries several RR intervals under one arrival timestamp, and
     * pushing them individually with that shared stamp collapses every beat in
     * the packet onto one instant — or, walked backwards, has every one after
     * the first rejected by the ordering gate.
     */
    fun startCardiacStream(seed: Long? = null) {
        if (cardiacJob != null) return
        cardiac = SyntheticCardiacSource(seed = seed)
        lastCardiacTickMs = System.currentTimeMillis()
        cardiacJob = scope.launch {
            while (isActive) {
                delay(1_000)
                emitCardiac()
            }
        }
        isStreamingCardiac = true
    }

    fun stopCardiacStream() {
        cardiacJob?.cancel(); cardiacJob = null
        cardiac = null
        isStreamingCardiac = false
    }

    private fun emitCardiac() {
        val source = cardiac ?: return
        val now = System.currentTimeMillis()
        val elapsed = now - lastCardiacTickMs
        lastCardiacTickMs = now

        for (packet in source.advance(now, elapsed)) {
            Synheart.pushRrBatch(packet.anchorTsMs, packet.rrMs, order = 0, provider = SIM_PROVIDER)
            // HR alongside RR, as a real strap reports both. Goes through
            // ingest_batch, which is also what advances the pipeline clock and
            // the only path that registers a provenance source.
            Synheart.pushWearHr(packet.anchorTsMs, packet.bpm, provider = SIM_PROVIDER)

            // §4.4's push_speed is deliberately NOT called. The only speed this
            // example could supply is one the simulator invented, and cardiac
            // is the only thing allowed to be simulated. locomotion_state stays
            // on its accel-only fallback, which is its honest state.
            rrPacketsPushed++
            beatsPushed += packet.rrMs.size
            latestSimBpm = packet.bpm
            latestSimRmssd = packet.rmssdMs
            simActivity = packet.activityLabel
        }
    }

    // ── The daily loop (§8) ───────────────────────────────────────────────

    /**
     * Attach the closed day's Strain, once per date.
     *
     * The engine closes each local day itself at the first input of the next
     * (runtime 0.42+), so there is no day to roll. It keeps the closed day
     * until the next one closes, across a restart too, so the date it reports
     * is the cursor: a date already attached is not sent again.
     */
    private fun attachClosedDayStrainIfNeeded() {
        val json = Synheart.attachClosedDayStrain() ?: return
        val date = runCatching { JSONObject(json).optString("local_date") }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return
        if (store?.readLastStrainDate() == date) return
        store?.writeLastStrainDate(date)
        strainScoresAttached++
    }

    /**
     * Run the closed-day attach a few minutes after the next local midnight.
     *
     * The engine closes the day at the first input after midnight, so the
     * attach waits a little past it. A coroutine only covers an app alive
     * across midnight; a real host needs WorkManager, which this example
     * deliberately does not pull in, so a phone asleep at midnight attaches at
     * the next session start instead.
     */
    private fun scheduleAfterMidnight() {
        midnightJob?.cancel()
        midnightJob = scope.launch {
            while (isActive) {
                val now = LocalDateTime.now()
                val next = now.toLocalDate().plusDays(1).atTime(0, 5)
                val waitMs = java.time.Duration.between(now, next).toMillis()
                delay(waitMs.coerceAtLeast(1_000))
                attachClosedDayStrainIfNeeded()
            }
        }
    }

    /**
     * Push today's wearable summaries into the personal baselines, then
     * refresh the wearable reference.
     *
     * The **only** baseline path for a phone with no live wearable session.
     * Values come from the simulator rather than Health Connect, because this
     * example holds no health permission — a real host reads them from the
     * platform store and files each night under the local date the person woke
     * on. Both metrics are heart-derived, which keeps this inside the
     * cardiac-only rule. Nothing is padded to zero: a vendor reporting no
     * deep-sleep figure means the value is absent.
     */
    fun pushSimulatedDailyBaselines() {
        val source = cardiac
        val today = LocalDate.now(ZoneId.systemDefault())

        fun push(metric: String, value: Double) {
            val rc = Synheart.srmPushWearableDaily(SIM_PROVIDER, metric, today, value)
            if (rc == 0) dailyPushes++ else lastError = "srm_push_wearable_daily($metric) returned $rc"
        }

        push(DailyMetric.RESTING_HR_BPM, source?.restingBpm ?: 58.0)
        source?.rmssdMs?.let { push(DailyMetric.OVERNIGHT_RMSSD_MS, it) }

        // One refresh after the batch. The reference is built from the dates
        // before today, so today's windows are never judged against today's own
        // values; what was pushed here counts from tomorrow on.
        Synheart.srmRefreshWearableReference()
    }

    // ── Persistence (§7) ──────────────────────────────────────────────────

    /** Export session state. Once per emitted window, and on background. */
    fun persistSessionState() {
        val s = store ?: return
        val json = Synheart.exportSessionState() ?: return
        if (json.isEmpty()) return
        s.writeSessionState(json)
        sessionStateSaves++
    }

    // The personal baselines are the runtime's to seal (0.42+); session state
    // is the one snapshot left to the host.
    private fun persistAll() = persistSessionState()

    val storedSnapshotSizes: Map<String, Int>
        get() = store?.storedSizes() ?: emptyMap()

    fun clearSnapshots() {
        store?.clear()
    }

    private companion object {
        /**
         * Provider tag for every simulated push. Not `ble_hrm` — that routes
         * into the breathing detector's Tier-1 series. `sdk_wear` is Tier 3,
         * the same tier as `default_sensor`, but unlike `default_sensor` it has
         * a row in core-runtime's signal table and so actually registers a
         * provenance source. Tagged `default_sensor` the axes move and every
         * modality chip reads absent.
         */
        const val SIM_PROVIDER = "sdk_wear"

        /**
         * The streams this host **actually observes** — named explicitly
         * rather than taking the platform roster, which on Android declares
         * `app_focus`, both notification streams and `screen_state` available.
         * This host feeds none of them: no UsageStatsManager, no notification
         * access requested, nothing reading real screen state. An unnamed
         * stream is declared *unavailable*, which is a claim about this host,
         * not the platform's ceiling — `platform` already carries that.
         *
         * Flip an entry in step with the code that starts feeding it.
         */
        val OBSERVED_STREAMS = SensingStreams(
            // The wear module when a source is attached, or the simulator.
            cardiac = true,
            // AccelForwarder via emitRawMotionSamples, at 50 Hz.
            accelerometer = true,
            // The typing probe's micro-windows.
            keystrokes = true,
            pointer = false,
            appFocus = false,
            notificationArrivals = false,
            notificationResponses = false,
            screenState = false,
        )
    }
}
