// SPDX-License-Identifier: Apache-2.0
//
// Host-facing facade for typed baseline state — observable + sync
// getters. Mirrors Flutter's `Baselines` static surface in
// `lib/src/modules/baselines/baselines.dart` (live `updates` stream +
// `latestSleepScore` / `latestRecoveryScore` / `latestReadinessScore`
// / `reference` getters), without the vendor-ingest orchestration
// that lives on the Flutter SDK only.
//
// The full Flutter `Baselines.ingestVendorSleep(...)` pipeline (Whoop /
// Garmin / Apple Health / Health Connect → score → snapshot) is not
// ported here — Kotlin/Swift hosts wire those ingest paths themselves
// and call the `cache*` setters when results land. The facade owns
// only the in-memory cache + snapshot stream.

package ai.synheart.core.modules.baselines

import ai.synheart.core.models.ReadinessScoreResult
import ai.synheart.core.models.RecoveryScoreResult
import ai.synheart.core.models.SleepScoreResult
import ai.synheart.core.models.WearableReferenceView
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Process-wide observable baseline state. Singleton instance via
 * the [Baselines] companion object; direct construction is allowed
 * for tests.
 *
 * ```kotlin
 * Baselines.updates.collect { snap -> render(snap) }
 *
 * // From your vendor ingest code:
 * Baselines.cacheSleepScore(result, source = "whoop")
 * Baselines.cacheReference(refView)
 * ```
 */
class Baselines internal constructor() {

    private var _latestSleepScore: SleepScoreResult? = null
    private var _latestRecoveryScore: RecoveryScoreResult? = null
    private var _latestReadinessScore: ReadinessScoreResult? = null
    private var _latestReference: WearableReferenceView? = null
    private val _recentSleepScores: MutableList<Int> = mutableListOf()
    private var _lastSource: String? = null

    // Replay=1 so late subscribers get the current snapshot immediately.
    private val _updates = MutableSharedFlow<BaselinesSnapshot>(
        replay = 1,
        extraBufferCapacity = 16,
    )

    /** Broadcast stream of baseline snapshots; emits after every `cache*` call. */
    val updates: Flow<BaselinesSnapshot> = _updates.asSharedFlow()

    /** The most recent [SleepScoreResult], or null if none cached this session. */
    val latestSleepScore: SleepScoreResult? get() = _latestSleepScore

    /** The most recent [RecoveryScoreResult] computed alongside a sleep ingest, or null. */
    val latestRecoveryScore: RecoveryScoreResult? get() = _latestRecoveryScore

    /** The most recent [ReadinessScoreResult], or null until first compute. */
    val latestReadinessScore: ReadinessScoreResult? get() = _latestReadinessScore

    /** The most recent [WearableReferenceView], or null if the engine has not yet produced one. */
    val reference: WearableReferenceView? get() = _latestReference

    /** Provider id of the source that produced [latestSleepScore]. */
    val lastSource: String? get() = _lastSource

    /** Current snapshot of cached state (synchronous). */
    fun current(): BaselinesSnapshot = assembleSnapshot()

    // ---- Cache setters --------------------------------------------------

    /**
     * Cache a new sleep-score result and emit a snapshot.
     *
     * @param result the score result (e.g. from a runtime sleep-score pipeline)
     * @param source provider id — `whoop`, `garmin`, `apple_health`,
     *               `health_connect`, `self_report`, etc. Persisted on
     *               the facade as [lastSource].
     */
    fun cacheSleepScore(result: SleepScoreResult, source: String? = null) {
        _latestSleepScore = result
        if (source != null) _lastSource = source
        result.score?.let { rollIntoRecentRing(it) }
        emit()
    }

    /** Cache a new recovery-score result and emit a snapshot. */
    fun cacheRecoveryScore(result: RecoveryScoreResult) {
        _latestRecoveryScore = result
        emit()
    }

    /** Cache a new readiness-score result and emit a snapshot. */
    fun cacheReadinessScore(result: ReadinessScoreResult) {
        _latestReadinessScore = result
        emit()
    }

    /** Cache a new wearable-reference view and emit a snapshot. */
    fun cacheReference(reference: WearableReferenceView) {
        _latestReference = reference
        emit()
    }

    /**
     * Forget every cached value and reset the recent-scores ring.
     * Called on logout / user switch. Subscribers receive one empty
     * snapshot so UI re-renders.
     */
    fun reset() {
        _latestSleepScore = null
        _latestRecoveryScore = null
        _latestReadinessScore = null
        _latestReference = null
        _recentSleepScores.clear()
        _lastSource = null
        emit()
    }

    // ---- Internals ------------------------------------------------------

    private fun assembleSnapshot(): BaselinesSnapshot = BaselinesSnapshot(
        reference = _latestReference,
        latestSleepScore = _latestSleepScore,
        latestRecoveryScore = _latestRecoveryScore,
        latestReadinessScore = _latestReadinessScore,
        recentSleepScores = _recentSleepScores.toList(),
        capturedAtMs = System.currentTimeMillis(),
    )

    private fun emit() {
        _updates.tryEmit(assembleSnapshot())
    }

    /** Path-B ring: capped at 7 (oldest dropped when full). */
    private fun rollIntoRecentRing(score: Int) {
        _recentSleepScores.add(score)
        while (_recentSleepScores.size > 7) _recentSleepScores.removeAt(0)
    }

    companion object {
        /**
         * Process-wide instance. Wired by `Synheart` at SDK init; tests
         * can construct their own via the internal constructor.
         */
        val shared: Baselines = Baselines()
    }
}
