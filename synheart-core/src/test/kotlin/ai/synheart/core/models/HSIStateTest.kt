package ai.synheart.core.models

import org.junit.Assert.*
import org.junit.Test

class HSIStateTest {

    @Test
    fun `parses nested HSI JSON`() {
        val json = """{"subject_id":"usr_123","timestamp_ms":1700000000000,"hsi":{"focus":{"value":0.8,"confidence":0.9},"arousal":{"value":0.5,"confidence":0.7},"capacity":{"value":0.6,"confidence":0.8},"sleep":{"value":0.3,"confidence":0.95}}}"""
        val state = HSIState.fromJson(json)
        assertEquals("usr_123", state.subjectId)
        assertEquals(1700000000000L, state.timestampMs)
        assertEquals(0.8, state.hsi.focus!!.value, 0.001)
        assertEquals(0.9, state.hsi.focus!!.confidence, 0.001)
        assertEquals(0.5, state.hsi.arousal!!.value, 0.001)
        assertEquals(0.6, state.hsi.capacity!!.value, 0.001)
        assertEquals(0.3, state.hsi.sleep!!.value, 0.001)
        assertEquals(json, state.rawJson)
    }

    @Test
    fun `parses flat axes JSON`() {
        val json = """{"focus":{"value":0.7,"confidence":0.8},"arousal":{"value":0.4,"confidence":0.6}}"""
        val state = HSIState.fromJson(json, subjectId = "usr_ext")
        assertEquals("usr_ext", state.subjectId)
        assertEquals(0.7, state.hsi.focus!!.value, 0.001)
        assertEquals(0.4, state.hsi.arousal!!.value, 0.001)
        assertNull(state.hsi.capacity)
        assertNull(state.hsi.sleep)
    }

    @Test
    fun `handles malformed JSON gracefully`() {
        val state = HSIState.fromJson("not-json", subjectId = "usr_x")
        assertEquals("usr_x", state.subjectId)
        assertEquals("not-json", state.rawJson)
        assertNull(state.hsi.focus)
    }

    @Test
    fun `uses observed_at_ms as fallback timestamp`() {
        val json = """{"observed_at_ms":1234567890000,"focus":{"value":0.5,"confidence":0.5}}"""
        val state = HSIState.fromJson(json)
        assertEquals(1234567890000L, state.timestampMs)
    }
}
