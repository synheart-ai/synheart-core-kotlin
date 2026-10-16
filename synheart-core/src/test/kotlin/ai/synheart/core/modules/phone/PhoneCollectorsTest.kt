package ai.synheart.core.modules.phone

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.delay
import org.junit.Assert.assertTrue
import org.junit.Test

/** The collectors have no platform source, so they must emit nothing. */
class PhoneCollectorsTest {

    private fun <T> emitted(flow: Flow<T>, start: suspend () -> Unit, stop: suspend () -> Unit): List<T> =
        runBlocking {
            val seen = mutableListOf<T>()
            val job = launch { flow.toList(seen) }
            start()
            delay(300)
            stop()
            job.cancel()
            seen
        }

    @Test
    fun `motion collector emits nothing`() {
        val c = MotionCollector()
        assertTrue(emitted(c.motionFlow, c::start, c::stop).isEmpty())
    }

    @Test
    fun `screen state tracker emits nothing, not even an initial state`() {
        val c = ScreenStateTracker()
        assertTrue(emitted(c.screenFlow, c::start, c::stop).isEmpty())
    }

    @Test
    fun `app focus tracker emits nothing`() {
        val c = AppFocusTracker()
        assertTrue(emitted(c.appSwitchFlow, c::start, c::stop).isEmpty())
    }

    @Test
    fun `notification tracker emits nothing`() {
        val c = NotificationTracker()
        assertTrue(emitted(c.notificationFlow, c::start, c::stop).isEmpty())
    }
}
