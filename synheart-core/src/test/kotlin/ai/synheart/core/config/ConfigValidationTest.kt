package ai.synheart.core.config

import org.junit.Assert.*
import org.junit.Test

class ConfigValidationTest {

    @Test
    fun `valid config passes`() {
        val config = SynheartConfig(
            appId = "com.test.app",
            subjectId = "usr_123",
            mode = SynheartMode.PERSONAL
        )
        config.validate() // should not throw
    }

    @Test(expected = SynheartCoreError.ResearchNotAllowed::class)
    fun `research mode requires allowResearch`() {
        SynheartConfig(
            appId = "com.test.app",
            subjectId = "usr_123",
            mode = SynheartMode.RESEARCH,
            privacy = PrivacyConfig(allowResearch = false)
        ).validate()
    }

    @Test
    fun `research mode with allowResearch passes`() {
        SynheartConfig(
            appId = "com.test.app",
            subjectId = "usr_123",
            mode = SynheartMode.RESEARCH,
            privacy = PrivacyConfig(allowResearch = true)
        ).validate()
    }

    @Test
    fun `empty appId fails`() {
        try {
            SynheartConfig(
                appId = "",
                subjectId = "usr_123",
                mode = SynheartMode.PERSONAL
            ).validate()
            fail("Expected SynheartCoreError.NotConfigured")
        } catch (e: SynheartCoreError.NotConfigured) {
            assertTrue(e.message!!.contains("appId"))
        }
    }

    @Test
    fun `empty subjectId fails`() {
        try {
            SynheartConfig(
                appId = "com.test.app",
                subjectId = "",
                mode = SynheartMode.PERSONAL
            ).validate()
            fail("Expected SynheartCoreError.NotConfigured")
        } catch (e: SynheartCoreError.NotConfigured) {
            assertTrue(e.message!!.contains("subjectId"))
        }
    }

    @Test(expected = SynheartCoreError.InvalidMode::class)
    fun `pipe in subjectId fails`() {
        SynheartConfig(
            appId = "com.test.app",
            subjectId = "usr|bad",
            mode = SynheartMode.PERSONAL
        ).validate()
    }
}
