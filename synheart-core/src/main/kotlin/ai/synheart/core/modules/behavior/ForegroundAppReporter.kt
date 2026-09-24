package ai.synheart.core.modules.behavior

import ai.synheart.core.SynheartLogger
import ai.synheart.core.models.BehaviorEventInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Where the foreground application identifier comes from.
 *
 * Implement this to plug a real platform source in. The default
 * ([SelfForegroundAppSource]) reports the host's own application id, which is
 * honest and useful for an app the person is actively using, but it cannot see
 * what is in front when the person leaves.
 *
 * | Platform | Real source | Status |
 * |---|---|---|
 * | Android | `UsageStatsManager.queryEvents` (`PACKAGE_USAGE_STATS`, a Settings-granted permission) | not built — implement this interface |
 *
 * A source that cannot answer right now returns `null`, and the reporter sends
 * nothing rather than re-asserting a stale app.
 */
fun interface ForegroundAppSource {
    /** The application id in front, or `null` when unknown. Android package name. */
    fun currentForegroundApp(): String?
}

/**
 * Reports the host's own application id.
 *
 * Not a placeholder — for a foreground-only mobile session this is the *true*
 * answer, and it is the difference between the engine having an app identity
 * and having none. With no identity at all the runtime's `current_app` stays
 * `None`, which resolves to the `Unknown` app category, whose
 * interpretation-mask row is all zeros: every behavioural evidence term reads
 * `0` for a person who was working the whole time.
 *
 * Its limit is real: while the person is in another app this keeps naming
 * *your* app, which is wrong rather than merely incomplete. The reporter is
 * therefore lifecycle-gated and stops while the app is backgrounded.
 */
class SelfForegroundAppSource(private val appId: String) : ForegroundAppSource {
    override fun currentForegroundApp(): String? = appId.takeIf { it.isNotEmpty() }
}

/**
 * Pushes `app_foreground` resolves for the life of a session.
 *
 * ## Why a heartbeat and not just an edge
 *
 * `app_switch` fires on a *transition*. A mobile session where the person
 * opens one app and stays in it — the common case — produces no transition at
 * all, so an edge-only host never gives the engine an app identity. The
 * runtime has a separate entry point for exactly this (`note_app_resolved`),
 * and it is explicitly safe to call repeatedly: an unchanged app is a
 * steady-state observation and deliberately does **not** bump the switch
 * count, because switch count is itself a fragmentation feature. A resolve
 * that reveals a *different* app does count as a switch — the engine infers
 * the transition the host's edge detector missed.
 *
 * ## Cadence
 *
 * [DEFAULT_INTERVAL_MS] is deliberately shorter than the default 60 s HSI
 * window, so every window contains at least one resolve.
 */
class ForegroundAppReporter(
    private val source: ForegroundAppSource,
    /**
     * The rich-event sink. Returns the runtime's acceptance, or `null` when the
     * loaded runtime does not export `push_behavior_event` — in which case
     * there is no path for `app_foreground` at all, since the legacy int-coded
     * call carries no payload and so cannot name an app.
     */
    private val push: (BehaviorEventInput) -> Boolean?,
    private val scope: CoroutineScope,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) {
    private var job: Job? = null
    private var abiUnavailableLogged = false

    /** The last id actually delivered, for diagnostics. */
    var lastReportedApp: String? = null
        private set

    /** Resolves delivered this session. */
    var reportCount: Int = 0
        private set

    val isRunning: Boolean get() = job != null

    /**
     * Resolve immediately, then keep resolving on the heartbeat. Idempotent.
     * The immediate resolve matters: window 1 is otherwise typed against no
     * app at all, and a window is never re-emitted.
     */
    fun start() {
        if (job != null) return
        report()
        job = scope.launch {
            while (isActive) {
                delay(intervalMs)
                report()
            }
        }
    }

    /**
     * Stop resolving. Call on backgrounding as well as on session end —
     * continuing to assert the host's own id while the person is in another
     * app attributes that app's window to this one.
     */
    fun stop() {
        job?.cancel()
        job = null
    }

    /** Resolve once, now. Safe on top of the heartbeat. */
    fun report() {
        val app = source.currentForegroundApp()?.takeIf { it.isNotEmpty() } ?: return
        val accepted = push(BehaviorEventInput.appForeground(System.currentTimeMillis(), app))
        if (accepted == null) {
            if (!abiUnavailableLogged) {
                abiUnavailableLogged = true
                SynheartLogger.log(
                    "[ForegroundAppReporter] push_behavior_event is absent from this runtime, so " +
                        "app_foreground cannot be delivered. Every window is typed against the " +
                        "Unknown app category, whose interpretation-mask row is all zeros — CFI, " +
                        "Stress B, Mental Fatigue B and Focus deviation terms will read 0.",
                )
            }
            return
        }
        lastReportedApp = app
        reportCount++
    }

    companion object {
        /** One resolve per half-window, so no HSI window goes without an identity. */
        const val DEFAULT_INTERVAL_MS: Long = 30_000
    }
}
