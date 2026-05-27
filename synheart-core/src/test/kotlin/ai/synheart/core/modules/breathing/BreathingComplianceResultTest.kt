// SPDX-License-Identifier: Apache-2.0

package ai.synheart.core.modules.breathing

import org.junit.Assert.*
import org.junit.Test

class BreathingComplianceResultTest {

    // ── BreathingMetrics ───────────────────────────────────────────

    @Test
    fun `BreathingMetrics defaults to zero on missing fields`() {
        val m = BreathingMetrics.fromJson(org.json.JSONObject("""{}"""))
        assertEquals(0.0, m.peakHz, 0.0001)
        assertEquals(0, m.criteriaMet)
    }

    @Test
    fun `BreathingMetrics fromJson reads all fields`() {
        val m = BreathingMetrics.fromJson(org.json.JSONObject("""
            {"peak_hz":0.1,"peak_bpm":6.0,"coherence":0.85,
             "rsa_bpm":8.2,"relative_power":0.6,"criteria_met":3,"confidence":0.78}
        """.trimIndent()))
        assertEquals(0.1, m.peakHz, 0.0001)
        assertEquals(6.0, m.peakBpm, 0.0001)
        assertEquals(0.85, m.coherence, 0.0001)
        assertEquals(8.2, m.rsaBpm, 0.0001)
        assertEquals(0.6, m.relativePower, 0.0001)
        assertEquals(3, m.criteriaMet)
        assertEquals(0.78, m.confidence, 0.0001)
    }

    // ── NonComplianceReason ────────────────────────────────────────

    @Test
    fun `NonComplianceReason parses all four variants`() {
        val freq = NonComplianceReason.fromJson(org.json.JSONObject(
            """{"type":"WrongFrequency","detected_bpm":9.0,"target_bpm":6.0}"""
        )) as NonComplianceReason.WrongFrequency
        assertEquals(9.0, freq.detectedBpm, 0.0001)
        assertEquals(6.0, freq.targetBpm, 0.0001)

        val shallow = NonComplianceReason.fromJson(org.json.JSONObject(
            """{"type":"ShallowBreathing","rsa_bpm":2.1}"""
        )) as NonComplianceReason.ShallowBreathing
        assertEquals(2.1, shallow.rsaBpm, 0.0001)

        val irreg = NonComplianceReason.fromJson(org.json.JSONObject(
            """{"type":"IrregularPattern","coherence":0.2}"""
        )) as NonComplianceReason.IrregularPattern
        assertEquals(0.2, irreg.coherence, 0.0001)

        val none = NonComplianceReason.fromJson(org.json.JSONObject(
            """{"type":"NoBreathingSignature"}"""
        ))
        assertSame(NonComplianceReason.NoBreathingSignature, none)
    }

    @Test
    fun `NonComplianceReason falls back to NoBreathingSignature on unknown type`() {
        val r = NonComplianceReason.fromJson(org.json.JSONObject("""{"type":"garbage"}"""))
        assertSame(NonComplianceReason.NoBreathingSignature, r)
    }

    // ── InsufficientReason ─────────────────────────────────────────

    @Test
    fun `InsufficientReason parses all four variants`() {
        val nb = InsufficientReason.fromJson(org.json.JSONObject(
            """{"type":"NotEnoughBeats","have":12,"need":50}"""
        )) as InsufficientReason.NotEnoughBeats
        assertEquals(12, nb.have)
        assertEquals(50, nb.need)

        val ws = InsufficientReason.fromJson(org.json.JSONObject(
            """{"type":"WindowTooShort","have_secs":20,"need_secs":30}"""
        )) as InsufficientReason.WindowTooShort
        assertEquals(20, ws.haveSecs)
        assertEquals(30, ws.needSecs)

        val vot = InsufficientReason.fromJson(org.json.JSONObject(
            """{"type":"VendorOnlyTier","device":"WHOOP 4.0"}"""
        )) as InsufficientReason.VendorOnlyTier
        assertEquals("WHOOP 4.0", vot.device)

        val ea = InsufficientReason.fromJson(org.json.JSONObject(
            """{"type":"ExcessiveArtifacts","rejected_pct":42.5}"""
        )) as InsufficientReason.ExcessiveArtifacts
        assertEquals(42.5, ea.rejectedPct, 0.0001)
    }

    // ── BreathingComplianceResult ──────────────────────────────────

    @Test
    fun `verdict Compliant parses metrics and is compliant`() {
        val r = BreathingComplianceResult.fromJsonString("""
            {"verdict":"Compliant","metrics":{"peak_bpm":6.0,"criteria_met":4,"confidence":0.9}}
        """.trimIndent())
        assertTrue(r is BreathingComplianceResult.Compliant)
        assertTrue(r.isCompliant)
        assertEquals(6.0, r.metrics?.peakBpm)
        assertEquals(4, r.metrics?.criteriaMet)
    }

    @Test
    fun `verdict NotCompliant carries metrics plus reason`() {
        val r = BreathingComplianceResult.fromJsonString("""
            {"verdict":"NotCompliant",
             "metrics":{"peak_bpm":9.0,"criteria_met":1,"confidence":0.4},
             "reason":{"type":"WrongFrequency","detected_bpm":9.0,"target_bpm":6.0}}
        """.trimIndent()) as BreathingComplianceResult.NotCompliant
        assertFalse(r.isCompliant)
        assertEquals(9.0, r.metrics.peakBpm, 0.0001)
        val wf = r.reason as NonComplianceReason.WrongFrequency
        assertEquals(9.0, wf.detectedBpm, 0.0001)
        assertEquals(6.0, wf.targetBpm, 0.0001)
    }

    @Test
    fun `verdict Insufficient has null metrics and carries reason`() {
        val r = BreathingComplianceResult.fromJsonString("""
            {"verdict":"Insufficient",
             "reason":{"type":"NotEnoughBeats","have":10,"need":50}}
        """.trimIndent()) as BreathingComplianceResult.Insufficient
        assertNull(r.metrics)
        assertFalse(r.isCompliant)
        val nb = r.reason as InsufficientReason.NotEnoughBeats
        assertEquals(10, nb.have)
    }

    @Test
    fun `unknown verdict falls back to Insufficient`() {
        val r = BreathingComplianceResult.fromJsonString("""
            {"verdict":"Mystery","reason":{"type":"NotEnoughBeats","have":0,"need":50}}
        """.trimIndent())
        assertTrue(r is BreathingComplianceResult.Insufficient)
    }

    // ── BreathingGuidanceCopy ──────────────────────────────────────

    @Test
    fun `WrongFrequency copy switches on direction`() {
        val tooFast = NonComplianceReason.WrongFrequency(detectedBpm = 10.0, targetBpm = 6.0)
        val tooSlow = NonComplianceReason.WrongFrequency(detectedBpm = 4.0, targetBpm = 6.0)
        assertTrue(BreathingGuidanceCopy.copyFor(tooFast).contains("Slow down"))
        assertTrue(BreathingGuidanceCopy.copyFor(tooSlow).contains("Speed up"))
    }

    @Test
    fun `localizer override is global`() {
        val original = BreathingGuidanceCopy.localize
        try {
            BreathingGuidanceCopy.localize = { "X" }
            assertEquals("X", BreathingGuidanceCopy.copyFor(
                NonComplianceReason.IrregularPattern(coherence = 0.0)
            ))
        } finally {
            BreathingGuidanceCopy.localize = original
        }
    }

    // ── BreathingPopulation ────────────────────────────────────────

    @Test
    fun `BreathingPopulation ordinals match native enum`() {
        assertEquals(0, BreathingPopulation.BEGINNER.ordinal)
        assertEquals(1, BreathingPopulation.EXPERIENCED.ordinal)
        assertEquals(2, BreathingPopulation.CLINICAL.ordinal)
    }
}
