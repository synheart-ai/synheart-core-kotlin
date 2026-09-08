package ai.synheart.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The host declarations, extra heads and window length must reach the config
 * JSON handed to `synheart_core_new` — and must reach it as **top-level** keys.
 *
 * The runtime reads `sensing`, `device_class`, `mask_profile` and
 * `cfi_structural_components` off the root object. Nesting them under a
 * `host_declarations` block would be silently ignored: the config would parse,
 * the handle would come back, and every declaration would be undeclared with
 * nothing to say so.
 */
class RuntimeConfigMapDeclarationsTest {

    private fun base() = SynheartConfig(appId = "com.example.app", subjectId = "usr_abc")

    @Test
    fun `a default config declares no host keys`() {
        val json = buildRuntimeConfigMap(base())
        assertFalse(json.has("sensing"))
        assertFalse(json.has("device_class"))
        assertFalse(json.has("mask_profile"))
        assertFalse(json.has("cfi_structural_components"))
        assertFalse(json.has("extra_heads"))
        assertFalse(json.has("window_ms"))
    }

    @Test
    fun `auto declarations are spread to the top level`() {
        val json = buildRuntimeConfigMap(base().copy(hostDeclarations = HostDeclarations.auto))
        assertEquals("auto", json.getString("sensing"))
        assertEquals("auto", json.getString("device_class"))
        assertEquals("auto", json.getString("mask_profile"))
        assertEquals(4, json.getInt("cfi_structural_components"))
        assertFalse("must not be nested", json.has("host_declarations"))
    }

    @Test
    fun `an explicit sensing object survives the spread intact`() {
        val json = buildRuntimeConfigMap(
            base().copy(
                hostDeclarations = HostDeclarations(
                    sensing = Declared.Value(
                        SensingProfile(
                            mode = SensingMode.CONTINUOUS,
                            latenessBudgetMs = 30_000,
                            streams = SensingStreams(cardiac = true, appFocus = false),
                        ),
                    ),
                ),
            ),
        )
        val sensing = json.getJSONObject("sensing")
        assertEquals("continuous", sensing.getString("mode"))
        assertEquals(30_000L, sensing.getLong("lateness_budget_ms"))
        assertTrue(sensing.getJSONObject("streams").getBoolean("cardiac"))
        assertFalse(sensing.getJSONObject("streams").getBoolean("app_focus"))
    }

    @Test
    fun `extra heads are emitted as a wire-name array`() {
        val json = buildRuntimeConfigMap(
            base().copy(
                extraHeads = listOf(ExtraHead.LOCOMOTION_STATE, ExtraHead.POSTURAL_STATE),
            ),
        )
        val heads = json.getJSONArray("extra_heads")
        assertEquals(2, heads.length())
        assertEquals("locomotion_state", heads.getString(0))
        assertEquals("postural_state", heads.getString(1))
    }

    @Test
    fun `window_ms is emitted only when positive`() {
        assertEquals(60_000L, buildRuntimeConfigMap(base().copy(windowMs = 60_000)).getLong("window_ms"))
        assertFalse(buildRuntimeConfigMap(base().copy(windowMs = 0)).has("window_ms"))
        assertFalse(buildRuntimeConfigMap(base().copy(windowMs = -5)).has("window_ms"))
    }
}
