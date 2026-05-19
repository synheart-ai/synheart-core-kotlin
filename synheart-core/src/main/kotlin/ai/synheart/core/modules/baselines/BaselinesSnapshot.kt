// SPDX-License-Identifier: Apache-2.0
//
// Typed aggregate of host-visible baseline state at a point in time.
// Mirrors Flutter's `lib/src/modules/baselines/baselines_snapshot.dart`.

package ai.synheart.core.modules.baselines

import ai.synheart.core.models.ReadinessScoreResult
import ai.synheart.core.models.RecoveryScoreResult
import ai.synheart.core.models.SleepScoreResult
import ai.synheart.core.models.WearableReferenceView

/**
 * A typed aggregate of the host-visible baseline state at a point in
 * time. Produced by [Baselines] whenever a new sleep score is ingested
 * or the wearable reference refreshes. Consumers read this to render
 * a "your normal" / SRM overview without having to touch FFI or parse
 * raw JSON.
 */
data class BaselinesSnapshot(
    /**
     * Longitudinal-SRM reference view (status, per-dimension medians,
     * Path-B `recent_sleep_score_median`). Null before the first
     * reference is produced — an HSI window has to close before the
     * engine flushes the ring → reference.
     */
    val reference: WearableReferenceView?,

    /**
     * The most recently computed batch [SleepScoreResult], or null if
     * no sleep event has been ingested this session (or via persisted
     * history — the score itself is not persisted, the Path-B ring is).
     */
    val latestSleepScore: SleepScoreResult?,

    /**
     * The most recent daily Recovery Score per RFC-RECOVERY-SCORE-0001,
     * computed alongside the sleep ingest when overnight HR or HRV is
     * available in the same payload. Null when the user has only sleep
     * data (sleep-only recovery is forbidden by design) or the runtime
     * hasn't computed one yet this session.
     */
    val latestRecoveryScore: RecoveryScoreResult? = null,

    /**
     * The most recent daily Readiness Score per RFC-READINESS-SCORE-0001,
     * computed automatically each time a Recovery Score lands. Layers
     * fatigue (sleep debt + recovery slope) and history (consecutive
     * overload) on top of the Recovery anchor. Null until at least one
     * Recovery Score has been computed this session.
     */
    val latestReadinessScore: ReadinessScoreResult? = null,

    /**
     * Path-B 7-night rolling-window scores in attach order
     * (oldest → newest), read directly from the longitudinal snapshot.
     * Updates the moment a score is attached, so consumers can show
     * "N/7 nights logged" + median without waiting for an HSI window
     * to close. Empty until first ingest.
     */
    val recentSleepScores: List<Int> = emptyList(),

    /** Millisecond timestamp when this snapshot was assembled. */
    val capturedAtMs: Long,
) {
    /**
     * True when nothing has been ingested and the engine hasn't
     * produced a reference — useful for rendering an empty/warming
     * state in UI.
     */
    val isEmpty: Boolean
        get() = reference == null && latestSleepScore == null && recentSleepScores.isEmpty()

    /** True when the reference is present and reports `Stable` status. */
    val isStable: Boolean
        get() = (reference?.status ?: "").lowercase() == "stable"

    /** Number of prior nights behind the live score, when available. */
    val priorNightCount: Int?
        get() = latestSleepScore?.priorNightCount

    /**
     * Number of nights captured in the Path-B ring this session
     * (capped at 7). This is the right "nights logged" surface for
     * the user — [priorNightCount] reflects what the scorer saw, not
     * the cumulative count of attaches.
     */
    val nightsLoggedCount: Int
        get() = recentSleepScores.size

    /**
     * Median of the recent ring; available immediately after the
     * 3rd attach, regardless of HSI window state. Falls back to the
     * reference's median when the ring isn't populated locally.
     */
    val recentMedian: Int?
        get() {
            if (recentSleepScores.isEmpty()) {
                return reference?.recentSleepScoreMedian
            }
            val sorted = recentSleepScores.sorted()
            return sorted[sorted.size / 2]
        }
}
