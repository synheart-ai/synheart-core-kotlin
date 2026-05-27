// SPDX-License-Identifier: Apache-2.0
//
// Smoke tests for the scoring models. Asserts JSON round-trips and
// edge cases that matter for cross-language wire compatibility
// (null stages, unknown enum wires, default values).

package ai.synheart.core.models

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ScoringModelsTest {

    // ── SleepScore ──────────────────────────────────────────────────

    @Test
    fun `SleepPath fromWire falls back to PROXY on unknown`() {
        assertEquals(SleepPath.STAGE, SleepPath.fromWire("stage"))
        assertEquals(SleepPath.PROXY, SleepPath.fromWire("garbage"))
        assertEquals(SleepPath.PROXY, SleepPath.fromWire(null))
    }

    @Test
    fun `SleepScoreReason fromWire returns null on missing`() {
        assertNull(SleepScoreReason.fromWire(null))
        assertNull(SleepScoreReason.fromWire("garbage"))
        assertEquals(SleepScoreReason.NO_SLEEP_DATA,
                     SleepScoreReason.fromWire("no_sleep_data"))
    }

    @Test
    fun `AggregatedTotals omits null deep and rem from JSON`() {
        val totals = AggregatedTotals(
            totalSleepMinutes = 420.0,
            deepSleepMinutes = null,
            remSleepMinutes = null,
            awakeMinutes = 30.0,
        )
        val json = totals.toJson()
        assertFalse(json.has("deep_sleep_minutes"))
        assertFalse(json.has("rem_sleep_minutes"))
        assertEquals(420.0, json.getDouble("total_sleep_minutes"), 0.0001)
    }

    @Test
    fun `NightInput Aggregated round-trips kind tag`() {
        val night = NightInput.Aggregated(
            sessionStartMs = 1000,
            sessionEndMs = 2000,
            totals = AggregatedTotals(totalSleepMinutes = 400.0, awakeMinutes = 20.0),
        )
        val json = night.toJson()
        assertEquals("aggregated", json.getString("kind"))
        assertEquals(1000L, json.getLong("session_start_ms"))
    }

    @Test
    fun `SleepScoreResult fromJson tolerates missing fields`() {
        val r = SleepScoreResult.fromJson(JSONObject("""{"score": 72, "path": "stage"}"""))
        assertEquals(72, r.score)
        assertEquals(SleepPath.STAGE, r.path)
        assertEquals(SleepScoreMode.COLD_START, r.mode) // default
        assertNull(r.reason)
        assertEquals(0.0, r.confidence, 0.0001)
        assertEquals(0, r.priorNightCount)
    }

    @Test
    fun `WearableReferenceView reads recent_sleep_score_median from dimensions`() {
        val view = WearableReferenceView.fromJsonString(
            """{"status":"Stable","dimensions":{"recent_sleep_score_median":78,"hrv_rmssd_ms":42.5}}"""
        )
        assertEquals("Stable", view.status)
        assertEquals(78, view.recentSleepScoreMedian)
        assertEquals(42.5, view.dimensions["hrv_rmssd_ms"]?.toDouble())
    }

    // ── SleepQuestionnaire ──────────────────────────────────────────

    @Test
    fun `SleepQuestionnaireAnswers computes asleep minutes from latency and awakenings`() {
        val bedtime = Instant.parse("2026-01-15T22:00:00Z")
        val wakeTime = Instant.parse("2026-01-16T06:00:00Z") // 480 min TIB
        val q = SleepQuestionnaireAnswers(
            bedtime = bedtime,
            wakeTime = wakeTime,
            sleepLatencyMinutes = 20,
            awakenings = 4,
        )
        assertEquals(480.0, q.timeInBedMinutes, 0.0001)
        assertEquals(40.0, q.awakeMinutes, 0.0001) // 20 + 4*5
        assertEquals(440.0, q.totalSleepMinutes, 0.0001)
    }

    @Test
    fun `SleepQuestionnaireAnswers toIngestPayload includes optional fields when set`() {
        val q = SleepQuestionnaireAnswers(
            bedtime = Instant.ofEpochMilli(1_000_000),
            wakeTime = Instant.ofEpochMilli(2_000_000),
            subjectiveQuality = 4,
            feltRested = SleepFeltRested.YES,
        )
        val payload = q.toIngestPayload()
        assertEquals(4, payload["subjective_quality"])
        assertEquals("yes", payload["felt_rested"])
        val self = payload["self_report_data"] as Map<*, *>
        assertEquals(1_000_000L, self["session_start_ms"])
        assertEquals(2_000_000L, self["session_end_ms"])
    }

    // ── RecoveryScore ───────────────────────────────────────────────

    @Test
    fun `OvernightPhysiology hasSignal is true when HRV present, false when only SDNN`() {
        assertTrue(OvernightPhysiology(hrvRmssdMs = 42.5).hasSignal)
        assertTrue(OvernightPhysiology(overnightHrBpm = 58.0).hasSignal)
        assertFalse(OvernightPhysiology(hrvSdnnMs = 50.0, hrStdBpm = 5.0).hasSignal)
    }

    @Test
    fun `RecoveryScoreResult parses explanation factors and skips unknowns`() {
        val r = RecoveryScoreResult.fromJsonString("""
            {
              "score": 65,
              "stage": "short_history",
              "mode": "trended",
              "confidence": 0.8,
              "explanation": ["hrv_above_baseline", "garbage_factor", "strong_sleep_quality"]
            }
        """.trimIndent())
        assertEquals(65, r.score)
        assertEquals(RecoveryStage.SHORT_HISTORY, r.stage)
        assertEquals(RecoveryScoreMode.TRENDED, r.mode)
        assertEquals(listOf(
            RecoveryFactor.HRV_ABOVE_BASELINE,
            RecoveryFactor.STRONG_SLEEP_QUALITY,
        ), r.explanation)
    }

    // ── ReadinessScore ──────────────────────────────────────────────

    @Test
    fun `ReadinessBand fromWire falls back to REST on unknown`() {
        assertEquals(ReadinessBand.PUSH, ReadinessBand.fromWire("push"))
        assertEquals(ReadinessBand.REST, ReadinessBand.fromWire("garbage"))
        assertEquals("Light", ReadinessBand.LIGHT.label)
    }

    @Test
    fun `ReadinessScoreInput fromRecovery builds minimal input`() {
        val input = ReadinessScoreInput.fromRecovery(72)
        val json = input.toJson()
        assertEquals(72, json.getInt("recovery_score"))
        assertEquals(JSONObject.NULL, json.opt("acute_workload"))
        assertEquals(JSONObject.NULL, json.opt("fatigue"))
    }

    @Test
    fun `ReadinessScoreResult round-trips`() {
        val s = """
            {
              "score": 78, "band": "normal", "recovery_anchor": 70,
              "confidence": 0.85,
              "components": {"recovery": 70.0, "acute_load": 1.0, "fatigue": -3.0},
              "explanation": ["acute_load_optimal"],
              "model_id": "readiness_v1",
              "model_version": "1.0.0",
              "pipeline_version": "p1"
            }
        """.trimIndent()
        val r = ReadinessScoreResult.fromJsonString(s)
        assertEquals(78, r.score)
        assertEquals(ReadinessBand.NORMAL, r.band)
        assertEquals(70, r.recoveryAnchor)
        assertEquals(0.85, r.confidence, 0.0001)
        assertEquals(70.0, r.components.recovery, 0.0001)
        assertEquals(1.0, r.components.acuteLoad)
        assertNull(r.components.history)
        assertEquals(listOf(ReadinessFactor.ACUTE_LOAD_OPTIMAL), r.explanation)
        assertEquals("readiness_v1", r.modelId)
    }
}
