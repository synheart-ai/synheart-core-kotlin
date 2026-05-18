// SPDX-License-Identifier: Apache-2.0

package ai.synheart.core.modules.syni

import ai.synheart.core.modules.interfaces.ConsentProvider
import ai.synheart.core.modules.interfaces.ConsentSnapshot
import ai.synheart.syni.SyniInstallState
import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class SyniModuleTest {

    /** In-memory ConsentProvider for gating tests; no DB / no Synheart. */
    private class FakeConsent(initial: ConsentSnapshot = ConsentSnapshot.none()) : ConsentProvider {
        private val flow = MutableStateFlow<ConsentSnapshot?>(initial)

        override fun current(): ConsentSnapshot = flow.value ?: ConsentSnapshot.none()
        override fun observe(): Flow<ConsentSnapshot> = flow.asStateFlow().filterNotNull()
        override suspend fun updateConsent(newConsent: ConsentSnapshot) {
            flow.value = newConsent
        }
    }

    private fun moduleWith(consent: ConsentProvider): SyniModule =
        SyniModule(context = mockk(relaxed = true), consent = consent)

    // ── Gate visibility ───────────────────────────────────────────

    @Test
    fun `isGateOpen false on the default empty consent snapshot`() {
        assertFalse(moduleWith(FakeConsent()).isGateOpen)
    }

    @Test
    fun `isGateOpen true after granting SYNI consent`() = runTest {
        val consent = FakeConsent()
        val module = moduleWith(consent)
        consent.updateConsent(ConsentSnapshot.none().copyWith(syni = true))
        assertTrue(module.isGateOpen)
    }

    @Test
    fun `isGateOpen flips back to false on revoke`() = runTest {
        val consent = FakeConsent(ConsentSnapshot.none().copyWith(syni = true))
        val module = moduleWith(consent)
        assertTrue(module.isGateOpen)
        consent.updateConsent(ConsentSnapshot.none())
        assertFalse(module.isGateOpen)
    }

    // ── Reactive state is not gated ───────────────────────────────

    @Test
    fun `currentState observable without consent`() {
        val module = moduleWith(FakeConsent())
        // Fresh agent starts as NotInstalled; the important thing is
        // that the read returns rather than throwing the consent error.
        assertEquals(SyniInstallState.NotInstalled, module.currentState)
        assertFalse(module.isInstalled)
        assertFalse(module.hasCloud)
    }

    @Test
    fun `installState flow observable without consent`() {
        val module = moduleWith(FakeConsent())
        assertNotNull(module.installState)
    }

    // ── Gate enforcement on lifecycle ─────────────────────────────

    @Test(expected = SyniConsentDeniedException::class)
    fun `chatStream throws when consent denied`() {
        moduleWith(FakeConsent()).chatStream("hi")
    }

    @Test
    fun `chat throws SyniConsentDeniedException when consent denied`() = runTest {
        val module = moduleWith(FakeConsent())
        try {
            module.chat("hi")
            fail("expected SyniConsentDeniedException")
        } catch (e: SyniConsentDeniedException) {
            // expected
        }
    }

    @Test
    fun `install throws SyniConsentDeniedException when consent denied`() = runTest {
        val module = moduleWith(FakeConsent())
        try {
            module.install(persona = mockk(), model = mockk())
            fail("expected SyniConsentDeniedException")
        } catch (e: SyniConsentDeniedException) {
            // expected
        }
    }

    // ── Bypass ────────────────────────────────────────────────────

    @Test
    fun `unsafeAgent returns the same SyniAgent reference twice`() {
        val module = moduleWith(FakeConsent())
        assertSame(module.unsafeAgent, module.unsafeAgent)
    }
}
