package ai.synheart.core.example.sdk

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A **simulated** cardiac source, for exercising the ingest path when no
 * wearable is attached.
 *
 * ## Read this before using it for anything
 *
 * The samples this produces are fabricated. They flow into exactly the same
 * runtime plumbing real beats do — including the SRM, which builds the
 * person's longitudinal reference ranges on this device — so a simulated run
 * leaves a mark on the baselines of whatever `subject_id` it ran under. Use a
 * throwaway subject id for it, and wipe local data afterwards. That is why
 * nothing here is wired to start on its own: the host has to ask.
 *
 * It is also tagged honestly on the way in. Every push carries a Tier-3
 * provider rather than `'ble_hrm'`, so it never claims the Tier-1 routing a
 * real chest strap earns. Claiming Tier-1 for invented data would put it into
 * the breathing detector's Tier-1 series, and withholding stops working the
 * moment a host lies about where a number came from.
 *
 * ## Cardiac and nothing else
 *
 * This is the **only** simulated source in the example, and it stays that
 * way. It models beats — RR intervals and the rate derived from them — and
 * deliberately emits no motion, no speed, no screen state, no app focus and
 * no notifications, even though the activity episodes below would make all of
 * those easy to invent alongside.
 *
 * The reason is that a fabricated non-cardiac stream is not a smaller version
 * of the same compromise, it is a worse one. A simulated heart rate is
 * visibly a stand-in for hardware the device does not have; a simulated
 * screen-state or GPS trace is indistinguishable from a real one the phone
 * could genuinely have produced, so nothing downstream — and nobody reading a
 * screenshot — can tell it apart. [activityLabel] is exposed for the UI to
 * explain a rate that just climbed 30 bpm; it is not a locomotion claim and
 * must not be pushed as one.
 *
 * ## What makes it physiologically shaped rather than random
 *
 * A `Random.nextInt(200)` heart rate is worse than no data: it has no
 * autocorrelation, so every HRV feature computed from it is noise, and it
 * wanders outside any range a living person occupies. This model instead
 * reproduces the four things that actually structure a heart-rate trace:
 *
 * 1. **A mean-reverting drift.** The instantaneous rate is pulled toward a
 *    target by an Ornstein–Uhlenbeck process, so consecutive seconds are
 *    correlated and the series never runs away. Combined with the hard clamp
 *    to [minBpm]–[maxBpm], the trace stays inside a healthy adult envelope
 *    without being pinned to one value.
 * 2. **Activity episodes.** The target itself moves between rest, light and
 *    moderate exertion, then decays back through a recovery phase. This is
 *    what stops the output being a flat line with jitter on it — HR has
 *    structure on a scale of minutes, not just beats.
 * 3. **Respiratory sinus arrhythmia.** Beat-to-beat interval is modulated by
 *    breathing at ~0.2–0.3 Hz. This is the dominant source of short-term HRV
 *    in a healthy person and the thing RMSSD is largely measuring.
 * 4. **RSA suppression under load.** The respiratory swing shrinks as rate
 *    rises, because vagal tone withdraws during exertion. Without this, a
 *    simulated 110 bpm carries resting-level HRV, which reads as a
 *    physiological impossibility to anything downstream.
 *
 * ## It generates beats, not readings
 *
 * The primitive is the RR interval, and heart rate is derived from it — the
 * same direction of causation a real sensor has. Beats are then delivered in
 * packets, because that is the shape a BLE Heart Rate Measurement notification
 * arrives in: several RR intervals under one arrival timestamp.
 */
class SyntheticCardiacSource(
    seed: Long? = null,
    /**
     * The person's resting rate. Episode targets are expressed relative to it,
     * so changing this shifts the whole trace rather than only its floor.
     */
    val restingBpm: Double = 58.0,
    /**
     * Hard envelope. The OU process is already mean-reverting, so these are a
     * backstop against a long tail rather than the mechanism keeping the trace
     * sane — but they are the guarantee that no consumer ever sees a 0 or a 200.
     */
    val minBpm: Double = 48.0,
    val maxBpm: Double = 132.0,
) {
    private val rng: Random = if (seed == null) Random.Default else Random(seed)

    private enum class Episode { REST, LIGHT, MODERATE, RECOVERY }

    // ── Rate state ────────────────────────────────────────────────────────

    /** Where the OU process is being pulled. Moved by the episode machine. */
    private var targetBpm = restingBpm + 4.0

    /** The current rate. This is what a beat's interval is computed from. */
    private var instantBpm = targetBpm

    /**
     * Phase of the respiratory oscillator, in radians. Advanced by real elapsed
     * time so the breathing rhythm is continuous across packets.
     */
    private var breathPhase = 0.0

    private var episode = Episode.REST
    private var episodeRemainingMs = restEpisodeDurationMs()

    /**
     * Fractional beat accumulator: how much of the next RR interval has already
     * elapsed. Carrying this across calls is what keeps beat timing honest when
     * [advance] is called on a cadence that does not divide evenly into an RR.
     */
    private var beatDebtMs = 0.0

    private val pendingRr = ArrayList<Double>()

    /** Wall-clock stamp of the most recent beat — a packet's anchor. */
    private var lastBeatTsMs = 0L

    /** Trailing intervals for the derived bpm a real monitor reports. */
    private val recentRr = ArrayDeque<Double>()

    /** Beats emitted since construction. */
    var beatCount: Long = 0
        private set

    /**
     * The current activity episode, as a label a UI can show. Worth surfacing:
     * it explains a rate that just climbed 30 bpm, which otherwise looks like
     * the generator misbehaving.
     */
    val activityLabel: String
        get() = when (episode) {
            Episode.REST -> "rest"
            Episode.LIGHT -> "light activity"
            Episode.MODERATE -> "moderate activity"
            Episode.RECOVERY -> "recovery"
        }

    /**
     * The rate a monitor would currently display, averaged over recent beats.
     * Null until enough beats exist — deliberately not a placeholder.
     */
    val displayBpm: Double?
        get() = if (recentRr.isEmpty()) null else 60_000.0 / recentRr.average()

    /**
     * RMSSD over the trailing beats, in ms — the same statistic the engine's
     * HRV features are built on. A healthy adult at rest sits roughly in the
     * 20–70 ms band and drops sharply under exertion.
     */
    val rmssdMs: Double?
        get() {
            if (recentRr.size < 3) return null
            var sumSq = 0.0
            val list = recentRr.toList()
            for (i in 1 until list.size) {
                val d = list[i] - list[i - 1]
                sumSq += d * d
            }
            return sqrt(sumSq / (list.size - 1))
        }

    /**
     * Advance the model by [elapsedMs] of wall clock ending at [nowMs], and
     * return whichever complete packets that produced.
     *
     * Usually zero or one packet. More than one only when the caller was
     * starved long enough to owe several — which is the case worth getting
     * right, because collapsing a backlog of beats onto one timestamp is
     * exactly what `push_rr_batch` exists to prevent.
     */
    fun advance(nowMs: Long, elapsedMs: Long): List<CardiacPacket> {
        if (elapsedMs <= 0) return emptyList()
        if (lastBeatTsMs == 0L) lastBeatTsMs = nowMs - elapsedMs

        advanceEpisode(elapsedMs)
        advanceRate(elapsedMs)

        breathPhase += 2 * PI * BREATH_HZ * (elapsedMs / 1000.0)
        if (breathPhase > 2 * PI) breathPhase -= 2 * PI

        generateBeats(nowMs, elapsedMs)
        return drainPackets(nowMs)
    }

    // ── Episode machine ───────────────────────────────────────────────────

    private fun advanceEpisode(elapsedMs: Long) {
        episodeRemainingMs -= elapsedMs
        if (episodeRemainingMs > 0) return

        when (episode) {
            Episode.REST -> {
                // Most rest periods stay restful; occasionally one escalates.
                // Weighted so a demo session spends most of its time near
                // resting rate, which is where a phone-in-pocket day actually is.
                if (rng.nextDouble() < 0.55) {
                    episode = Episode.LIGHT
                    targetBpm = restingBpm + 18 + rng.nextDouble() * 10
                    episodeRemainingMs = 45_000L + rng.nextLong(75_000L)
                } else {
                    targetBpm = restingBpm + 2 + rng.nextDouble() * 8
                    episodeRemainingMs = restEpisodeDurationMs()
                }
            }
            Episode.LIGHT -> {
                if (rng.nextDouble() < 0.4) {
                    episode = Episode.MODERATE
                    targetBpm = restingBpm + 42 + rng.nextDouble() * 14
                    episodeRemainingMs = 40_000L + rng.nextLong(60_000L)
                } else {
                    episode = Episode.RECOVERY
                    targetBpm = restingBpm + 8
                    episodeRemainingMs = 50_000L + rng.nextLong(40_000L)
                }
            }
            Episode.MODERATE -> {
                episode = Episode.RECOVERY
                // Recovery undershoots toward resting rather than snapping back:
                // post-exertion HR decays, it does not step.
                targetBpm = restingBpm + 6
                episodeRemainingMs = 70_000L + rng.nextLong(60_000L)
            }
            Episode.RECOVERY -> {
                episode = Episode.REST
                targetBpm = restingBpm + 3 + rng.nextDouble() * 6
                episodeRemainingMs = restEpisodeDurationMs()
            }
        }
    }

    private fun restEpisodeDurationMs(): Long = 90_000L + rng.nextLong(120_000L)

    /**
     * Ornstein–Uhlenbeck step toward [targetBpm]. Scaled by `dt` so the
     * trajectory is the same whether the caller ticks at 1 Hz or 4 Hz — a
     * generator whose output depends on its own call cadence would make the
     * UI's numbers an artifact of the timer interval.
     */
    private fun advanceRate(elapsedMs: Long) {
        val theta = 0.08
        val sigma = 1.4
        val dt = elapsedMs / 1000.0
        val drift = theta * (targetBpm - instantBpm) * dt
        val diffusion = sigma * sqrt(dt) * gaussian()
        instantBpm = (instantBpm + drift + diffusion).coerceIn(minBpm, maxBpm)
    }

    // ── Beat generation ───────────────────────────────────────────────────

    private fun generateBeats(nowMs: Long, elapsedMs: Long) {
        var remaining = elapsedMs.toDouble() + beatDebtMs

        // Bounded so a pathological gap cannot mint thousands of beats and blow
        // past the ingest gate. A real monitor would have missed those beats
        // too, and the runtime records the dropout honestly.
        var guard = 0
        while (remaining > 0 && guard++ < MAX_BEATS_PER_ADVANCE) {
            val rr = nextRrMs()
            if (remaining < rr) break
            remaining -= rr
            lastBeatTsMs = min(nowMs, lastBeatTsMs + rr.toLong())
            pendingRr.add(rr)
            recentRr.addLast(rr)
            // ~30 beats: long enough for a stable RMSSD, short enough to track
            // the episode the person is actually in.
            if (recentRr.size > 30) recentRr.removeFirst()
            beatCount++
        }
        beatDebtMs = remaining
    }

    /**
     * One RR interval in ms, from the current rate plus respiratory modulation
     * plus beat-level noise.
     */
    private fun nextRrMs(): Double {
        val baseRr = 60_000.0 / instantBpm

        // RSA amplitude as a fraction of the interval, tapering to near zero as
        // rate climbs. At rest this is the dominant HRV term; at 120 bpm it is
        // almost gone, which is what makes the simulated RMSSD collapse under
        // load the way a real one does.
        val exertion = ((instantBpm - restingBpm) / 55.0).coerceIn(0.0, 1.0)
        val rsaFraction = 0.055 * (1.0 - exertion) + 0.004
        val rsa = baseRr * rsaFraction * sin(breathPhase)

        // Small non-respiratory beat-to-beat noise, kept well below the RSA
        // term so HRV remains structured rather than white.
        val jitter = gaussian() * 6.0

        return (baseRr + rsa + jitter).coerceIn(60_000.0 / maxBpm, 60_000.0 / minBpm)
    }

    /**
     * Group pending beats into notification-shaped packets. A BLE HRM notifies
     * about once a second and carries however many beats fell in that second.
     */
    private fun drainPackets(nowMs: Long): List<CardiacPacket> {
        if (pendingRr.isEmpty()) return emptyList()
        val packet = CardiacPacket(
            // The anchor is the arrival time of the notification, i.e. its last
            // beat. `push_rr_batch` walks backwards from here to reconstruct the
            // earlier beats' timestamps.
            anchorTsMs = if (lastBeatTsMs == 0L) nowMs else lastBeatTsMs,
            rrMs = pendingRr.toDoubleArray(),
            bpm = displayBpm ?: (60_000.0 / instantBpm),
            rmssdMs = rmssdMs,
            activityLabel = activityLabel,
        )
        pendingRr.clear()
        return listOf(packet)
    }

    /** Box–Muller, standard normal. */
    private fun gaussian(): Double {
        val u1 = 1.0 - rng.nextDouble() // never exactly 0 → ln is finite
        val u2 = rng.nextDouble()
        return sqrt(-2.0 * ln(u1)) * cos(2 * PI * u2)
    }

    private companion object {
        /** ~13 breaths/min, inside the normal adult band. */
        const val BREATH_HZ = 0.22
        const val MAX_BEATS_PER_ADVANCE = 512
    }
}

/**
 * One sensor notification's worth of beats, shaped after a BLE Heart Rate
 * Measurement: several RR intervals sharing a single arrival timestamp, plus
 * the rate the monitor reports.
 */
data class CardiacPacket(
    /** Arrival time of the notification — the timestamp of its **last** beat. */
    val anchorTsMs: Long,
    /** Intervals in ms, oldest first (`order = 0`). */
    val rrMs: DoubleArray,
    /** Rate averaged over recent beats, as a monitor would display it. */
    val bpm: Double,
    /** RMSSD over the trailing window, or null before enough beats exist. */
    val rmssdMs: Double?,
    /** Which activity episode produced this packet. */
    val activityLabel: String,
)
