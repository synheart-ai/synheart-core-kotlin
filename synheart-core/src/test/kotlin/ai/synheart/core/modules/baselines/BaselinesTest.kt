// SPDX-License-Identifier: Apache-2.0

package ai.synheart.core.modules.baselines

import ai.synheart.core.models.*
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class BaselinesTest {

    private fun sleepScore(score: Int, priorNights: Int = 3): SleepScoreResult =
        SleepScoreResult(
            score = score,
            confidence = 0.8,
            path = SleepPath.STAGE,
            mode = SleepScoreMode.SHORT_HISTORY,
            components = SleepScoreBreakdown(duration = 80, quality = 75),
            adjustments = SleepScoreAdjust(debtPenalty = 0, hrAdjustment = 0),
            effectiveWeights = ComponentWeights(),
            priorNightCount = priorNights,
            pipelineVersion = "v1",
            modelId = "sleep_v1",
            constantsHash = "abc",
        )

    private fun recoveryScore(score: Int = 65): RecoveryScoreResult =
        RecoveryScoreResult(
            score = score,
            stage = RecoveryStage.SHORT_HISTORY,
            mode = RecoveryScoreMode.TRENDED,
            components = RecoveryComponents(),
            confidence = 0.8,
            modelId = "recovery_v1",
            modelVersion = "1.0",
            pipelineVersion = "v1",
        )

    private fun readinessScore(score: Int = 72): ReadinessScoreResult =
        ReadinessScoreResult(
            score = score,
            band = ReadinessBand.NORMAL,
            recoveryAnchor = 65,
            components = ReadinessComponents(recovery = 65.0),
            confidence = 0.85,
            modelId = "readiness_v1",
            modelVersion = "1.0",
            pipelineVersion = "v1",
        )

    private fun reference(status: String, recentMedian: Int? = null): WearableReferenceView {
        val dims = if (recentMedian != null) mapOf<String, Number>("recent_sleep_score_median" to recentMedian)
                   else emptyMap()
        return WearableReferenceView(status = status, dimensions = dims, recentSleepScoreMedian = recentMedian)
    }

    // ── Snapshot derived properties ─────────────────────────────────

    @Test
    fun `isEmpty true with no cached data`() {
        val snap = BaselinesSnapshot(
            reference = null, latestSleepScore = null,
            capturedAtMs = 0,
        )
        assertTrue(snap.isEmpty)
        assertEquals(0, snap.nightsLoggedCount)
        assertNull(snap.priorNightCount)
        assertNull(snap.recentMedian)
    }

    @Test
    fun `isReady mirrors reference status case-insensitively`() {
        val snap = BaselinesSnapshot(
            reference = reference("READY"),
            latestSleepScore = null, capturedAtMs = 0,
        )
        assertTrue(snap.isReady)
        assertFalse(snap.copy(reference = reference("WARMING")).isReady)
        assertFalse(snap.copy(reference = reference("STALE")).isReady)
    }

    @Test
    fun `recentMedian computed from ring when populated`() {
        val snap = BaselinesSnapshot(
            reference = reference("READY", recentMedian = 99),
            latestSleepScore = null,
            recentSleepScores = listOf(70, 80, 60),
            capturedAtMs = 0,
        )
        // Ring wins over reference median when ring is non-empty.
        assertEquals(70, snap.recentMedian)
        assertEquals(3, snap.nightsLoggedCount)
    }

    @Test
    fun `recentMedian falls back to reference when ring empty`() {
        val snap = BaselinesSnapshot(
            reference = reference("READY", recentMedian = 78),
            latestSleepScore = null,
            capturedAtMs = 0,
        )
        assertEquals(78, snap.recentMedian)
    }

    // ── Facade behavior ─────────────────────────────────────────────

    @Test
    fun `cacheSleepScore emits snapshot with latestScore and source`() = runTest {
        val b = Baselines()
        b.cacheSleepScore(sleepScore(75), source = "whoop")
        val snap = b.updates.first()
        assertEquals(75, snap.latestSleepScore?.score)
        assertEquals("whoop", b.lastSource)
        assertEquals(listOf(75), snap.recentSleepScores)
    }

    @Test
    fun `cacheSleepScore rolls scores into Path-B ring capped at 7`() = runTest {
        val b = Baselines()
        for (i in 1..10) {
            b.cacheSleepScore(sleepScore(i * 10))
        }
        val snap = b.current()
        assertEquals(7, snap.recentSleepScores.size)
        // Oldest 3 dropped; ring holds scores 40..100.
        assertEquals(listOf(40, 50, 60, 70, 80, 90, 100), snap.recentSleepScores)
    }

    @Test
    fun `cacheReference and cacheRecoveryScore land on the same snapshot`() = runTest {
        val b = Baselines()
        b.cacheReference(reference("READY"))
        b.cacheRecoveryScore(recoveryScore(60))
        val snap = b.current()
        assertEquals("READY", snap.reference?.status)
        assertEquals(60, snap.latestRecoveryScore?.score)
        assertNull(snap.latestSleepScore)
    }

    @Test
    fun `cacheReadinessScore emits and populates readiness`() = runTest {
        val b = Baselines()
        b.cacheReadinessScore(readinessScore(80))
        val snap = b.updates.first()
        assertEquals(80, snap.latestReadinessScore?.score)
        assertEquals(ReadinessBand.NORMAL, snap.latestReadinessScore?.band)
    }

    @Test
    fun `reset clears all cached state and emits empty snapshot`() = runTest {
        val b = Baselines()
        b.cacheSleepScore(sleepScore(70), source = "garmin")
        b.cacheRecoveryScore(recoveryScore())
        b.cacheReference(reference("READY"))
        b.reset()
        val snap = b.updates.first()
        assertNull(snap.latestSleepScore)
        assertNull(snap.latestRecoveryScore)
        assertNull(snap.reference)
        assertTrue(snap.recentSleepScores.isEmpty())
        assertNull(b.lastSource)
        assertTrue(snap.isEmpty)
    }

    @Test
    fun `multiple cache calls produce multiple snapshots on stream`() = runTest {
        val b = Baselines()
        val collectorDeferred = async {
            b.updates.take(3).toList()
        }
        yield() // give the collector a chance to subscribe
        b.cacheSleepScore(sleepScore(70))
        b.cacheSleepScore(sleepScore(75))
        b.cacheSleepScore(sleepScore(80))
        val collected = collectorDeferred.await()
        assertEquals(3, collected.size)
        // Last emission carries all 3 scores in the ring.
        assertEquals(listOf(70, 75, 80), collected.last().recentSleepScores)
    }
}
