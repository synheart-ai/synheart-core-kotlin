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
    fun `ReadinessBand reads the engine's names and falls back to the score`() {
        assertEquals(ReadinessBand.REST, ReadinessBand.fromWire("low", 90))
        assertEquals(ReadinessBand.LIGHT, ReadinessBand.fromWire("reduced", 90))
        assertEquals(ReadinessBand.PUSH, ReadinessBand.fromWire("high", 10))
        // Missing or unknown: the band the score falls in, never a default.
        assertEquals(ReadinessBand.REST, ReadinessBand.fromWire(null, 39))
        assertEquals(ReadinessBand.LIGHT, ReadinessBand.fromWire("garbage", 40))
        assertEquals(ReadinessBand.NORMAL, ReadinessBand.fromWire(null, 79))
        assertEquals(ReadinessBand.PUSH, ReadinessBand.fromWire(null, 80))
        assertEquals("Light", ReadinessBand.LIGHT.label)
    }

    @Test
    fun `ReadinessScoreInput writes the engine's input shape`() {
        val json = ReadinessScoreInput(
            recoveryScore = 72,
            sleepScore = 68,
            alignment = OvernightAlignment(58.0, 55.0, 42.0, 45.0),
            sleepColdStart = true,
            baselineMaturity = 0.6,
        ).toJson()
        assertEquals(72, json.getInt("recovery_score"))
        assertEquals(68, json.getInt("sleep_score"))
        assertEquals(58.0, json.getJSONObject("alignment").getDouble("hr_sleep_bpm"), 0.0)
        assertEquals(45.0, json.getJSONObject("alignment").getDouble("rmssd_base_ms"), 0.0)
        assertFalse(json.getBoolean("recovery_cold_start"))
        assertTrue(json.getBoolean("sleep_cold_start"))
        assertEquals(0.6, json.getDouble("baseline_maturity"), 0.0)
        assertFalse(json.has("baseline_days"))
    }

    @Test
    fun `ReadinessScoreInput clamps scores and maturity`() {
        val json = ReadinessScoreInput(
            recoveryScore = 140,
            sleepScore = -5,
            baselineMaturity = Double.NaN,
        ).toJson()
        assertEquals(100, json.getInt("recovery_score"))
        assertEquals(0, json.getInt("sleep_score"))
        assertEquals(JSONObject.NULL, json.get("alignment"))
        assertEquals(0.0, json.getDouble("baseline_maturity"), 0.0)
        assertEquals(
            1.0,
            ReadinessScoreInput(1, 1, baselineMaturity = 3.0).toJson().getDouble("baseline_maturity"),
            0.0,
        )
    }

    @Test
    fun `ReadinessScoreResult round-trips`() {
        val s = """
            {
              "score": 74, "band": "normal", "recovery_anchor": 72, "sleep_anchor": 68,
              "confidence": 0.85,
              "components": {"recovery": 72.0, "sleep": 68.0, "baseline_alignment": 82.67,
                             "hr_alignment": null, "hrv_alignment": 90.0},
              "explanation": ["hrv_below_baseline", "garbage_factor", "scores_only"],
              "model_id": "readiness_score",
              "model_version": "3.0.0",
              "pipeline_version": "readiness_score/3.0.0"
            }
        """.trimIndent()
        val r = ReadinessScoreResult.fromJsonString(s)
        assertEquals(74, r.score)
        assertEquals(ReadinessBand.NORMAL, r.band)
        assertEquals(72, r.recoveryAnchor)
        assertEquals(68, r.sleepAnchor)
        assertEquals(68.0, r.components.sleep, 0.0001)
        assertEquals(82.67, r.components.baselineAlignment!!, 0.0001)
        assertNull(r.components.hrAlignment)
        assertEquals(
            listOf(ReadinessFactor.HRV_BELOW_BASELINE, ReadinessFactor.SCORES_ONLY),
            r.explanation,
        )
        assertEquals("3.0.0", r.modelVersion)
        // And back: toJson is the engine's shape.
        assertEquals(r, ReadinessScoreResult.fromJson(r.toJson()))
    }

    // ── Daily baselines (C ABI 4.2) ─────────────────────────────────

    @Test
    fun `BaselineRefs fromJson needs all four numbers`() {
        val full = JSONObject("""
            {"hrv_rmssd_ms": 48.0, "hrv_confidence": 0.7, "resting_hr_bpm": 56.0, "rhr_confidence": 0.4}
        """.trimIndent())
        val refs = BaselineRefs.fromJson(full)!!
        assertEquals(48.0, refs.hrvRmssdMs, 0.0)
        assertEquals(0.4, refs.maturity, 0.0)
        for (key in listOf("hrv_rmssd_ms", "hrv_confidence", "resting_hr_bpm", "rhr_confidence")) {
            assertNull(key, BaselineRefs.fromJson(JSONObject(full.toString()).apply { remove(key) }))
            assertNull(key, BaselineRefs.fromJson(JSONObject(full.toString()).put(key, JSONObject.NULL)))
        }
    }

    @Test
    fun `BaselineRefs maturity is the lower confidence, clamped`() {
        assertEquals(0.3, BaselineRefs(1.0, 0.3, 1.0, 0.9).maturity, 0.0)
        assertEquals(1.0, BaselineRefs(1.0, 1.4, 1.0, 2.0).maturity, 0.0)
        assertEquals(0.0, BaselineRefs(1.0, -0.2, 1.0, 0.5).maturity, 0.0)
        assertEquals(0.0, BaselineRefs(1.0, Double.NaN, 1.0, 0.5).maturity, 0.0)
    }

    @Test
    fun `SleepBaselines keeps each reference independent`() {
        val b = SleepBaselines.fromJson(JSONObject("""
            {"midpoint_h": {"value": 0.0, "confidence": 0.5},
             "overnight_hr_bpm": null,
             "deep_min": {"value": 70.0},
             "rem_min": {"value": 95.0, "confidence": 0.8}}
        """.trimIndent()))
        // 0.0 is midnight: a value, not missing.
        assertEquals(DailyRef(0.0, 0.5), b.midpointH)
        assertNull(b.overnightHrBpm)
        assertNull(b.deepMin)
        assertEquals(DailyRef(95.0, 0.8), b.remMin)
        assertEquals(SleepBaselines(), SleepBaselines.fromJson(JSONObject()))
    }

    @Test
    fun `SleepScoreInput writes the midpoint and the baselines`() {
        val night = NightRaw(20261030, NightInput.VendorScore(80))
        val bare = SleepScoreInput(tonight = night).toJson()
        assertEquals(JSONObject.NULL, bare.get("tonight_midpoint_h"))
        assertFalse(bare.has("baselines"))

        val full = SleepScoreInput(
            tonight = night,
            tonightMidpointH = 3.25,
            baselines = SleepBaselines(midpointH = DailyRef(3.0, 0.6)),
        ).toJson()
        assertEquals(3.25, full.getDouble("tonight_midpoint_h"), 0.0)
        val baselines = full.getJSONObject("baselines")
        assertEquals(3.0, baselines.getJSONObject("midpoint_h").getDouble("value"), 0.0)
        assertEquals(JSONObject.NULL, baselines.get("rem_min"))
    }
}
