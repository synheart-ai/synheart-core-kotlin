package ai.synheart.core.config

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RawMotionResolutionTest {

    private fun config(
        mode: SynheartMode = SynheartMode.PERSONAL,
        researchBaseline: Boolean = false,
        emit: Boolean? = null,
    ) = SynheartConfig(
        appId = "com.test.app",
        subjectId = "usr_123",
        mode = mode,
        researchBaseline = researchBaseline,
        behaviorConfig = emit?.let { BehaviorConfig(emitRawMotionSamples = it) } ?: BehaviorConfig(),
    )

    @Test
    fun `off by default outside research capture`() {
        assertFalse(config().resolvedEmitRawMotionSamples)
        assertFalse(config(mode = SynheartMode.INSIGHT).resolvedEmitRawMotionSamples)
    }

    @Test
    fun `research mode turns raw motion on`() {
        assertTrue(config(mode = SynheartMode.RESEARCH).resolvedEmitRawMotionSamples)
    }

    @Test
    fun `research baseline turns raw motion on`() {
        assertTrue(config(researchBaseline = true).resolvedEmitRawMotionSamples)
    }

    @Test
    fun `an explicit true enables it in any mode`() {
        assertTrue(config(emit = true).resolvedEmitRawMotionSamples)
    }
}
