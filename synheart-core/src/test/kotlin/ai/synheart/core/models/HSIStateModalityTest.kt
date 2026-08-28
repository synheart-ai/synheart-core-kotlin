package ai.synheart.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the modality / tier derivation from `meta.provenance` and
 * `meta.synheart.tiers`.
 *
 * Why it matters: the five canonical axes are physiology-derived, so on a phone
 * with no wearable they all report zero confidence while behavior and motion are
 * in fact reaching the runtime. A host that reads only the axes concludes the
 * SDK is broken. [HSIState.modalities] is what distinguishes "nothing was
 * collected" from "signal arrived, just not physiological".
 */
class HSIStateModalityTest {

    private fun payload(meta: String) = """
    {
      "hsi_version": "1.3",
      "timestamp_ms": 1750000000000,
      "subject_id": "subj_1",
      "axes": { "cognitive": [ { "name": "focus", "score": 0.4, "confidence": 0.0 } ] },
      "meta": $meta
    }
    """.trimIndent()

    @Test
    fun `digital-only window reports digital and not physiological`() {
        val state = HSIState.fromJson(
            payload(
                """
                {
                  "provenance": {
                    "sources": {
                      "host_interaction": { "signals": ["touch", "scroll"] }
                    }
                  },
                  "synheart": { "tiers": { "digital": 2 } }
                }
                """.trimIndent(),
            ),
        )

        assertTrue(state.modalities.digital)
        assertFalse(state.modalities.physiological)
        assertFalse(state.modalities.kinematic)
        assertFalse(state.modalities.isEmpty)
        assertEquals(2, state.tiers.digital)
        assertNull(state.tiers.physiological)
    }

    @Test
    fun `physiological tier takes the worst source, not the best`() {
        // A window fused from a chest strap (tier 1) and a phone camera (tier 4)
        // is only as trustworthy as its weakest input. Reporting tier 1 would
        // overstate what the reading was actually built from.
        val state = HSIState.fromJson(
            payload(
                """
                {
                  "provenance": {
                    "sources": {
                      "ble_strap": { "signals": ["rr"], "source_tier": 1 },
                      "phone_ppg": { "signals": ["ppg"], "source_tier": 4 }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        assertTrue(state.modalities.physiological)
        assertEquals(4, state.tiers.physiological)
    }

    @Test
    fun `a non-physiological source's tier is not read as physiological`() {
        val state = HSIState.fromJson(
            payload(
                """
                {
                  "provenance": {
                    "sources": {
                      "imu": { "signals": ["accel", "gyro"], "source_tier": 3 }
                    }
                  }
                }
                """.trimIndent(),
            ),
        )

        assertTrue(state.modalities.kinematic)
        assertFalse(state.modalities.physiological)
        assertNull(state.tiers.physiological)
    }

    @Test
    fun `an unknown signal name lights up no modality`() {
        // Future signals must not be bucketed by guesswork — a wrong chip is
        // worse than a missing one.
        val state = HSIState.fromJson(
            payload(
                """{ "provenance": { "sources": { "x": { "signals": ["telepathy"] } } } }""",
            ),
        )

        assertTrue(state.modalities.isEmpty)
        assertTrue(state.tiers.isEmpty)
    }

    @Test
    fun `a window with no provenance is empty rather than throwing`() {
        val state = HSIState.fromJson(payload("{}"))

        assertTrue(state.modalities.isEmpty)
        assertTrue(state.tiers.isEmpty)
        assertFalse(state.hasParseError)
    }

    @Test
    fun `a malformed payload is reported as a parse error, not as no data`() {
        // The distinction the field exists for: all-null axes from a failed
        // parse look identical to all-null axes from an engine that had no
        // basis for any of them.
        val state = HSIState.fromJson("{ not json at all ")

        assertTrue(state.hasParseError)
        assertNull(state.hsi.focus)
    }

    @Test
    fun `hasDigital is false until a digital axis resolves`() {
        assertFalse(HSIAxes().hasDigital)
        assertTrue(HSIAxes(focusQuality = HSIAxisValue(0.5, 0.9)).hasDigital)
    }
}
