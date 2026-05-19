// SPDX-License-Identifier: Apache-2.0
//
// Smoke tests for HealthConnectRuntimeSink. Pure-Kotlin — uses a fake
// HealthHistoryReader and records pushDaily/triggerRecompute calls, so
// no native runtime / Health Connect SDK is required.
//
// NOTE: depends on `ai.synheart.wear.backfill.*` (HealthHistoryReader,
// SleepNightSummary, OvernightPhysiologySummary). Will only compile
// once synheart-core/build.gradle is pointing at a synheart-wear
// release that ships the `ai.synheart.wear.backfill` package.

package ai.synheart.core.backfill

import ai.synheart.wear.backfill.HealthHistoryReader
import ai.synheart.wear.backfill.OvernightPhysiologySummary
import ai.synheart.wear.backfill.SleepNightSummary
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HealthConnectRuntimeSinkTest {

    private val zone = ZoneId.of("UTC")

    // Day used by the "full data" fixture: 2026-01-15 (UTC).
    private val day = LocalDate.of(2026, 1, 15)

    private data class PushCall(
        val dimension: String,
        val dayIndex: Int,
        val value: Double,
        val confidence: Double,
        val fidelity: Int,
    )

    private class Recorder {
        val pushes = mutableListOf<PushCall>()
        var recomputeCount = 0

        val pushDaily: PushDailyCallback = { dim, day, v, c, f ->
            pushes.add(PushCall(dim, day, v, c, f))
        }
        val triggerRecompute: TriggerRecomputeCallback = { recomputeCount++ }
    }

    private class FakeReader(
        private val available: Boolean = true,
        private val sleep: Map<LocalDate, SleepNightSummary> = emptyMap(),
        private val overnight: Map<LocalDate, OvernightPhysiologySummary> = emptyMap(),
    ) : HealthHistoryReader {
        override suspend fun isAvailable(): Boolean = available

        override suspend fun fetchSleepNights(
            start: Instant, end: Instant, zone: ZoneId,
        ): Map<LocalDate, SleepNightSummary> = sleep

        override suspend fun fetchOvernightPhysiology(
            start: Instant, end: Instant, zone: ZoneId,
        ): Map<LocalDate, OvernightPhysiologySummary> = overnight
    }

    private fun sink(
        reader: HealthHistoryReader,
        recorder: Recorder = Recorder(),
    ): Pair<HealthConnectRuntimeSink, Recorder> {
        val s = HealthConnectRuntimeSink(
            reader = reader,
            bridge = null,
            pushDaily = recorder.pushDaily,
            triggerRecompute = recorder.triggerRecompute,
            zone = zone,
        )
        return s to recorder
    }

    // ── Skip paths ────────────────────────────────────────────────────

    @Test
    fun `non-positive daysBack short-circuits`() = runTest {
        val (s, r) = sink(FakeReader())
        val result = s.backfill(daysBack = 0)

        assertTrue(result.skipped)
        assertEquals("daysBack must be positive", result.skipReason)
        assertEquals(0, result.dimensionsPushed)
        assertEquals(0, result.daysIngested)
        assertTrue(r.pushes.isEmpty())
        assertEquals(0, r.recomputeCount)
    }

    @Test
    fun `reader unavailable short-circuits`() = runTest {
        val (s, r) = sink(FakeReader(available = false))
        val result = s.backfill(daysBack = 7)

        assertTrue(result.skipped)
        assertNotNull(result.skipReason)
        assertTrue(r.pushes.isEmpty())
        assertEquals(0, r.recomputeCount)
    }

    @Test
    fun `empty reader returns zero-dimensions and no recompute`() = runTest {
        val (s, r) = sink(FakeReader())
        val result = s.backfill(daysBack = 30)

        assertFalse(result.skipped)
        assertNull(result.skipReason)
        assertEquals(0, result.dimensionsPushed)
        assertEquals(0, result.daysIngested)
        assertTrue(r.pushes.isEmpty())
        assertEquals(0, r.recomputeCount)
    }

    // ── Push semantics ────────────────────────────────────────────────

    @Test
    fun `full sleep plus overnight day pushes all 5 dimensions`() = runTest {
        val reader = FakeReader(
            sleep = mapOf(day to SleepNightSummary(
                totalAsleepMinutes = 420.0,
                deepMinutes = 90.0,
                remMinutes = 110.0,
            )),
            overnight = mapOf(day to OvernightPhysiologySummary(
                hrvRmssdMs = 55.0,
                restingHrBpm = 58.0,
            )),
        )
        val (s, r) = sink(reader)
        val result = s.backfill(daysBack = 30)

        assertEquals(5, result.dimensionsPushed)
        assertEquals(1, result.daysIngested)
        assertEquals(1, r.recomputeCount)

        val dims = r.pushes.associateBy { it.dimension }
        assertEquals(setOf("sleep_need", "deep_sleep_min", "rem_sleep_min", "hrv_rmssd", "resting_hr"), dims.keys)

        // sleep_need is in seconds — 420 min * 60.
        assertEquals(420.0 * 60.0, dims.getValue("sleep_need").value, 0.0001)
        assertEquals(90.0, dims.getValue("deep_sleep_min").value, 0.0001)
        assertEquals(110.0, dims.getValue("rem_sleep_min").value, 0.0001)
        assertEquals(55.0, dims.getValue("hrv_rmssd").value, 0.0001)
        assertEquals(58.0, dims.getValue("resting_hr").value, 0.0001)

        // All pushes share confidence=0.85, fidelity=1, same day_index.
        val expectedDayIndex = day.toEpochDay().toInt()
        for (p in r.pushes) {
            assertEquals(0.85, p.confidence, 0.0001)
            assertEquals(1, p.fidelity)
            assertEquals(expectedDayIndex, p.dayIndex)
        }
    }

    @Test
    fun `zero or null stage minutes are skipped`() = runTest {
        val reader = FakeReader(
            sleep = mapOf(day to SleepNightSummary(
                totalAsleepMinutes = 300.0,
                deepMinutes = null,
                remMinutes = 0.0,
            )),
        )
        val (s, r) = sink(reader)
        val result = s.backfill(daysBack = 30)

        // Only sleep_need pushed.
        assertEquals(1, result.dimensionsPushed)
        assertEquals(1, result.daysIngested)
        assertEquals(setOf("sleep_need"), r.pushes.map { it.dimension }.toSet())
    }

    @Test
    fun `sleep with zero asleep minutes pushes nothing for that day`() = runTest {
        val reader = FakeReader(
            sleep = mapOf(day to SleepNightSummary(0.0, 90.0, 110.0)),
        )
        val (s, r) = sink(reader)
        val result = s.backfill(daysBack = 30)

        // Stage pushes are gated on totalAsleepMinutes > 0.
        assertEquals(0, result.dimensionsPushed)
        assertEquals(0, result.daysIngested)
        assertEquals(0, r.recomputeCount)
    }

    @Test
    fun `overnight only day still counts as ingested`() = runTest {
        val reader = FakeReader(
            overnight = mapOf(day to OvernightPhysiologySummary(
                hrvRmssdMs = 50.0,
                restingHrBpm = null,
            )),
        )
        val (s, r) = sink(reader)
        val result = s.backfill(daysBack = 30)

        assertEquals(1, result.dimensionsPushed)
        assertEquals(1, result.daysIngested)
        assertEquals(1, r.recomputeCount)
        assertEquals(setOf("hrv_rmssd"), r.pushes.map { it.dimension }.toSet())
    }

    @Test
    fun `multiple days are aggregated independently`() = runTest {
        val d1 = LocalDate.of(2026, 1, 10)
        val d2 = LocalDate.of(2026, 1, 11)
        val reader = FakeReader(
            sleep = mapOf(
                d1 to SleepNightSummary(400.0, 80.0, 100.0),
                d2 to SleepNightSummary(360.0, null, null),
            ),
            overnight = mapOf(
                d1 to OvernightPhysiologySummary(52.0, 60.0),
            ),
        )
        val (s, r) = sink(reader)
        val result = s.backfill(daysBack = 30)

        // d1: sleep_need + deep + rem + hrv + resting = 5
        // d2: sleep_need = 1
        assertEquals(6, result.dimensionsPushed)
        assertEquals(2, result.daysIngested)
        assertEquals(1, r.recomputeCount)

        val byDay = r.pushes.groupBy { it.dayIndex }
        assertEquals(5, byDay[d1.toEpochDay().toInt()]!!.size)
        assertEquals(1, byDay[d2.toEpochDay().toInt()]!!.size)
    }
}
