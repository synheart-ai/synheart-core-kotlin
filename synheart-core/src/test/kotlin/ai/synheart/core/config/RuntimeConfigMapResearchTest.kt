package ai.synheart.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two research switches. Both are absent unless asked for, because an
 * absent key means "the runtime's default stands" and a present `false` reads
 * as a decision the host did not make.
 */
class RuntimeConfigMapResearchTest {

    private fun config(
        emitDiagnostics: Boolean = false,
        researchBaseline: Boolean = false,
    ) = SynheartConfig(
        appId = "app",
        subjectId = "subject",
        emitDiagnostics = emitDiagnostics,
        researchBaseline = researchBaseline,
    )

    @Test
    fun `neither key is emitted by default`() {
        val json = buildRuntimeConfigMap(config())
        assertFalse(json.has("emit_diagnostics"))
        assertFalse(json.has("research_baseline"))
    }

    @Test
    fun `emitDiagnostics sends the key the engine reads`() {
        // The switch that turns an export a reviewer can audit into one they
        // can only take on trust — see SynheartConfig.emitDiagnostics.
        assertTrue(buildRuntimeConfigMap(config(emitDiagnostics = true)).getBoolean("emit_diagnostics"))
    }

    @Test
    fun `researchBaseline sends d_min=1 without implying research mode`() {
        val json = buildRuntimeConfigMap(config(researchBaseline = true))
        assertTrue(json.getBoolean("research_baseline"))
        // Deliberately NOT research mode: that would also cap step_ms and open
        // a lab session a host may already be managing itself.
        assertEquals(SynheartMode.PERSONAL.value, json.getString("mode"))
    }
}
