package ai.synheart.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the HSI 1.3 parse path against the shape the engine actually emits.
 *
 * The bug these exist for: the parser only understood a flat
 * `{ "focus": { "value": … } }` map, which the runtime has not produced since
 * 1.2. Against a real 1.3 payload every axis came back null, so
 * `onStateUpdate` and `currentHSIState` — the recommended typed API — silently
 * yielded an empty projection while `rawJson` was full of readings. Caught by
 * running the example on a physical device.
 */
class HSIStateV13ParseTest {

    /** Trimmed from an engine 0.12.0 window captured on a Galaxy A23. */
    private val v13 = """
    {
      "hsi_version": "1.3",
      "observed_at_utc": "2026-08-21T12:32:32Z",
      "producer": { "name": "synheart-engine", "version": "0.12.0" },
      "axes": {
        "cognitive": [
          { "name": "focus", "score": 0.25, "confidence": 0.8 },
          { "name": "capacity", "score": 0.5, "confidence": 0.0 },
          { "name": "cognitive_load", "score": 0.0, "confidence": 0.0 }
        ],
        "affective": [
          { "name": "valence", "score": 0.5, "confidence": 0.0 },
          { "name": "arousal", "score": 0.42, "confidence": 0.6 },
          { "name": "stress", "score": 0.1, "confidence": 0.3 }
        ],
        "physiological": [
          { "name": "sleep_score", "score": 72.0, "confidence": 0.9 }
        ],
        "digital": [
          { "name": "focus_quality", "score": 0.66, "confidence": 0.7 },
          { "name": "interruption_pressure", "score": 0.2, "confidence": 0.5 },
          { "name": "interaction_mode", "score": null, "confidence": 0.9 }
        ]
      }
    }
    """.trimIndent()

    @Test
    fun `cognitive and affective axes resolve from the 1_3 shape`() {
        val s = HSIState.fromJson(v13, subjectId = "sub")
        assertEquals(0.25, s.hsi.focus!!.value, 1e-9)
        assertEquals(0.8, s.hsi.focus!!.confidence, 1e-9)
        assertEquals(0.5, s.hsi.capacity!!.value, 1e-9)
        assertEquals(0.42, s.hsi.arousal!!.value, 1e-9)
        assertEquals(0.1, s.hsi.stress!!.value, 1e-9)
    }

    @Test
    fun `arousal is surfaced rather than valence`() {
        // Parity with the legacy four-axis model: both live under `affective`,
        // and picking the wrong one silently reports the other's number.
        val s = HSIState.fromJson(v13, subjectId = "sub")
        assertEquals(0.42, s.hsi.arousal!!.value, 1e-9)
    }

    @Test
    fun `sleep resolves from the canonical sleep_score member`() {
        val s = HSIState.fromJson(v13, subjectId = "sub")
        assertEquals(72.0, s.hsi.sleep!!.value, 1e-9)
    }

    @Test
    fun `a producer emitting sleep instead of sleep_score still resolves`() {
        val alt = """{"axes":{"physiological":[{"name":"sleep","score":55.0,"confidence":0.4}]}}"""
        assertEquals(55.0, HSIState.fromJson(alt).hsi.sleep!!.value, 1e-9)
    }

    @Test
    fun `digital axes resolve with no biosignal present`() {
        // The whole point of the digital domain: interaction alone produces
        // HSI on hardware that can supply no physiology.
        val s = HSIState.fromJson(v13, subjectId = "sub")
        assertEquals(0.66, s.hsi.focusQuality!!.value, 1e-9)
        assertEquals(0.2, s.hsi.interruptionPressure!!.value, 1e-9)
    }

    @Test
    fun `a null score is skipped rather than read as zero`() {
        // The engine emits a null score for a categorical axis or one it could
        // not compute. Coercing that to 0.0 invents a reading.
        val s = HSIState.fromJson(v13, subjectId = "sub")
        assertNull(s.hsi.interactionMode)
    }

    @Test
    fun `an axis the payload omits stays null`() {
        val sparse = """{"axes":{"cognitive":[{"name":"focus","score":0.9,"confidence":1.0}]}}"""
        val s = HSIState.fromJson(sparse)
        assertNotNull(s.hsi.focus)
        assertNull(s.hsi.arousal)
        assertNull(s.hsi.sleep)
    }

    @Test
    fun `the legacy flat shape still parses`() {
        val flat = """{"focus":{"value":0.3,"confidence":0.5},"arousal":{"value":0.7,"confidence":0.6}}"""
        val s = HSIState.fromJson(flat)
        assertEquals(0.3, s.hsi.focus!!.value, 1e-9)
        assertEquals(0.7, s.hsi.arousal!!.value, 1e-9)
    }

    @Test
    fun `subject id and timestamp survive the parse`() {
        val s = HSIState.fromJson(v13, subjectId = "sub_fallback")
        assertEquals("sub_fallback", s.subjectId)
        assertEquals(v13, s.rawJson)
    }

    @Test
    fun `malformed json degrades to empty axes rather than throwing`() {
        val s = HSIState.fromJson("not json", subjectId = "sub")
        assertNull(s.hsi.focus)
        assertEquals("not json", s.rawJson)
    }
}
