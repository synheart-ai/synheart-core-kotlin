package ai.synheart.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `meta.synheart.state_withheld` and `meta.synheart.sensing` are what let a
 * consumer tell "withheld, with a reason" from "this build does not produce
 * that axis". Without them a host reading only `axes.*` sees an absent
 * capacity on an episodic phone and reasonably concludes the SDK is broken —
 * or worse, paints a neutral default over it, which is exactly the "Stress
 * pinned at 100 / Recovery pinned at 50" failure withholding exists to stop.
 */
class HSIStateWithholdingTest {

    private fun payload(synheart: String? = null) = """
    {
      "hsi_version": "1.3",
      "timestamp_ms": 1750000000000,
      "subject_id": "sub_test",
      "axes": { "cognitive": [ { "name": "focus", "score": 0.61, "confidence": 0.8 } ] },
      "meta": { ${if (synheart != null) "\"synheart\": $synheart" else ""} }
    }
    """.trimIndent()

    @Test
    fun `nothing withheld yields an empty map`() {
        val state = HSIState.fromJson(payload("{}"))
        assertTrue(state.stateWithheld.isEmpty())
        assertFalse(state.hasParseError)
    }

    @Test
    fun `withheld axes map to their reasons`() {
        val state = HSIState.fromJson(
            payload(
                """{ "state_withheld": { "capacity": "episodic_sensing",
                                        "mental_fatigue": "episodic_sensing" } }""",
            ),
        )
        assertEquals("episodic_sensing", state.stateWithheld["capacity"])
        assertEquals("episodic_sensing", state.stateWithheld["mental_fatigue"])
        // The two views agree: withheld axes are absent from the axes too,
        // which is what makes their union the complete member set.
        assertNull(state.hsi.capacity)
        assertNotNull(state.hsi.focus)
    }

    @Test
    fun `cold start exhaustion is a reason, not a score of zero`() {
        val state = HSIState.fromJson(
            payload("""{ "state_withheld": { "capacity": "cold_start_confidence_exhausted" } }"""),
        )
        assertEquals("cold_start_confidence_exhausted", state.stateWithheld["capacity"])
    }

    @Test
    fun `a non-string reason is skipped rather than coerced`() {
        val state = HSIState.fromJson(
            payload("""{ "state_withheld": { "capacity": "episodic_sensing", "stress": 42 } }"""),
        )
        assertEquals(mapOf("capacity" to "episodic_sensing"), state.stateWithheld)
    }

    @Test
    fun `a non-object block degrades to empty`() {
        val state = HSIState.fromJson(payload("""{ "state_withheld": "nope" }"""))
        assertTrue(state.stateWithheld.isEmpty())
        assertFalse(state.hasParseError)
    }

    @Test
    fun `sensing is null for an undeclared host`() {
        // Absent is the correct answer and it is not the same as a declared
        // default — the block is what a consumer stratifies on.
        assertNull(HSIState.fromJson(payload("{}")).sensing)
        assertNull(HSIState.fromJson(payload()).sensing)
    }

    @Test
    fun `sensing block is surfaced when declared`() {
        val state = HSIState.fromJson(
            payload(
                """{ "sensing": { "mode": "continuous", "roster_version": "sensing-roster.v1",
                                  "rest_declared": true } }""",
            ),
        )
        val sensing = state.sensing
        assertNotNull(sensing)
        assertEquals("continuous", sensing!!.getString("mode"))
        assertEquals("sensing-roster.v1", sensing.getString("roster_version"))
        assertTrue(sensing.getBoolean("rest_declared"))
    }

    @Test
    fun `a parse failure reports empty rather than guessing`() {
        val state = HSIState.fromJson("{not json")
        assertTrue(state.hasParseError)
        assertTrue(state.stateWithheld.isEmpty())
        assertNull(state.sensing)
        assertEquals("{not json", state.rawJson)
    }
}
