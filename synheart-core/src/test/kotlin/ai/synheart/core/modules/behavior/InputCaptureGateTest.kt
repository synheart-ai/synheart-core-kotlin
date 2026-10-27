package ai.synheart.core.modules.behavior

import org.junit.Assert.assertEquals
import org.junit.Test

class InputCaptureGateTest {

    private val reports = mutableListOf<Boolean>()
    private val gate = InputCaptureGate { reports += it }

    @Test
    fun `running only while tracking and in the foreground`() {
        gate.setTracking(true)
        assertEquals(emptyList<Boolean>(), reports)
        gate.setForeground(true)
        assertEquals(listOf(true), reports)
        gate.setForeground(false)
        assertEquals(listOf(true, false), reports)
        gate.setForeground(true)
        gate.setTracking(false)
        assertEquals(listOf(true, false, true, false), reports)
    }

    @Test
    fun `reports only changes, and nothing before the first running`() {
        // The runtime's default is already "not running".
        gate.setForeground(false)
        gate.setTracking(false)
        gate.setForeground(true)
        assertEquals(emptyList<Boolean>(), reports)
        gate.setTracking(true)
        gate.setTracking(true)
        gate.setForeground(true)
        assertEquals(listOf(true), reports)
    }

    @Test
    fun `reset forgets without reporting`() {
        gate.setTracking(true)
        gate.setForeground(true)
        gate.reset()
        assertEquals(listOf(true), reports)
        assertEquals(null, gate.lastReported)
    }
}
