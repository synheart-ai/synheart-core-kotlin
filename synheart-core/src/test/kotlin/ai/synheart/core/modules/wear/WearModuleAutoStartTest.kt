package ai.synheart.core.modules.wear

import ai.synheart.core.modules.interfaces.CapabilityProvider
import ai.synheart.core.modules.interfaces.ConsentProvider
import ai.synheart.core.modules.interfaces.ConsentSnapshot
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `WearConfig.autoStartPlatformHealth = false` — starting the module (consent
 * with a running session) must not initialize or read the synheart-wear
 * source; `requestCollection()` (what `Synheart.startWearCollection` calls)
 * does. Without it, a host that re-grants a remembered consent at launch read
 * Health Connect on every launch.
 */
class WearModuleAutoStartTest {
    private class FakeConsent(initial: ConsentSnapshot = ConsentSnapshot.all()) : ConsentProvider {
        private val flow = MutableStateFlow<ConsentSnapshot?>(initial)
        override fun current(): ConsentSnapshot = flow.value ?: ConsentSnapshot.none()
        override fun observe(): Flow<ConsentSnapshot> = flow.asStateFlow().filterNotNull()
        override suspend fun updateConsent(newConsent: ConsentSnapshot) {
            flow.value = newConsent
        }
    }

    private class CountingSource : WearSourceHandler {
        var initialized = 0
        var collected = 0
        override val sourceType = WearSourceType.HEALTH_CONNECT
        override val isAvailable = true
        override suspend fun initialize() {
            initialized++
        }
        override val sampleFlow: Flow<WearSample>
            get() {
                collected++
                return emptyFlow()
            }
        override suspend fun dispose() {}
    }

    private fun module(source: CountingSource, autoStart: Boolean) = WearModule(
        capabilities = mockk<CapabilityProvider>(relaxed = true),
        consent = FakeConsent(),
        sources = listOf(source),
        autoStartOnConsent = autoStart,
    )

    @Test
    fun `default - starting the module initializes and reads the source`() = runTest {
        val source = CountingSource()
        val m = module(source, autoStart = true)
        m.initialize()
        m.start()
        assertEquals(1, source.initialized)
        assertEquals(1, source.collected)
    }

    @Test
    fun `opted out - starting the module does not touch the source, a request does`() = runTest {
        val source = CountingSource()
        val m = module(source, autoStart = false)
        m.initialize()
        m.start()
        assertEquals("no Health Connect init, no read", 0, source.initialized)
        assertEquals(0, source.collected)
        m.requestCollection()
        assertEquals(1, source.initialized)
        assertEquals(1, source.collected)
    }

    @Test
    fun `opted out - a request before start takes effect on start`() = runTest {
        val source = CountingSource()
        val m = module(source, autoStart = false)
        m.initialize()
        m.requestCollection()
        assertEquals(0, source.collected)
        m.start()
        assertEquals(1, source.collected)
    }

    @Test
    fun `opted out - after stop, a restart waits for a new request`() = runTest {
        val source = CountingSource()
        val m = module(source, autoStart = false)
        m.initialize()
        m.start()
        m.requestCollection()
        m.stop()
        m.start()
        assertEquals(1, source.collected)
        assertEquals("initialized once, not again", 1, source.initialized)
    }
}
