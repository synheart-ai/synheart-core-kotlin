package ai.synheart.core.example.sdk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Enforces the rule that **cardiac is the only simulated source**.
 *
 * Reads source files rather than exercising behaviour, which is unusual enough
 * to justify. The rule is not expressible as an assertion on output: the
 * failure mode is a *new* fabricated stream being pushed, and nothing
 * observable distinguishes an invented GPS trace or screen state from a real
 * one — that indistinguishability is precisely why the rule exists. So the
 * guard sits where the decision is made: at the call sites. If a future change
 * genuinely needs one of these, wire a real platform source and update this
 * test in the same commit — deliberately, not by accident.
 */
class OnlyCardiacIsSimulatedTest {

    private fun read(path: String): String {
        val f = File(path)
        assertTrue("$path moved — update this guard rather than deleting it", f.exists())
        return f.readText()
    }

    /** Strip comment lines so a prohibition *discussed* is not mistaken for one violated. */
    private fun code(source: String) =
        source.lines().filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }.joinToString("\n")

    private val runner = "src/main/kotlin/ai/synheart/core/example/sdk/MobileHostRunner.kt"
    private val controller = "src/main/kotlin/ai/synheart/core/example/sdk/SynheartController.kt"
    private val simulator = "src/main/kotlin/ai/synheart/core/example/sdk/SyntheticCardiacSource.kt"

    @Test
    fun `the host runner pushes no fabricated non-cardiac signal`() {
        val src = code(read(runner))
        assertFalse("push_speed can only be fed an invented speed here. Wire a real location source first.", src.contains("pushSpeed("))
        // The context channel IS used — for real keystrokes from the typing probe
        // (ContextEventInput.textChange) and real touches via the SDK. What is
        // banned is constructing a context event from nothing.
        assertFalse("a hand-made shortcut event is fabricated context evidence", src.contains("ShortcutType."))
        assertFalse(
            "the runner must not construct pointer or shortcut context events — those come from real gestures via the SDK",
            src.contains("ContextEventInput.mouse(") || src.contains("ContextEventInput.shortcut("),
        )
    }

    @Test
    fun `the example declares no phoneConfig`() {
        assertFalse(
            "PhoneModule fabricates motion, screen state, app focus and notifications. Replace its collectors before enabling it.",
            code(read(controller)).contains("phoneConfig ="),
        )
    }

    @Test
    fun `the cardiac simulator exposes no non-cardiac field`() {
        val src = code(read(simulator))
        for (banned in listOf("speedMps", "screenState", "appFocus")) {
            assertFalse("$banned is not cardiac and must not be simulated", src.contains(banned))
        }
    }

    @Test
    fun `the SDK's synthetic wear generator stays off`() {
        assertTrue(code(read(controller)).contains("allowSyntheticBiosignals = SYNTHETIC_BIOSIGNALS"))
        assertTrue(code(read(controller)).contains("private const val SYNTHETIC_BIOSIGNALS = false"))
    }
}
