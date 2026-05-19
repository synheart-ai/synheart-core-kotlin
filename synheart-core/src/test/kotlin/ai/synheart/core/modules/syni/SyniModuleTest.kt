// SPDX-License-Identifier: Apache-2.0

package ai.synheart.core.modules.syni

import ai.synheart.core.modules.interfaces.ConsentProvider
import ai.synheart.core.modules.interfaces.ConsentSnapshot
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

    // ── Gate visibility ───────────────────────────────────────────

    @Test
    fun `isGateOpen false on the default empty consent snapshot`() {
        val module = SyniModule(consent = FakeConsent())
        assertFalse(module.isGateOpen)
    }

    @Test
    fun `isGateOpen true after granting SYNI consent`() = runTest {
        val consent = FakeConsent()
        val module = SyniModule(consent = consent)
        consent.updateConsent(ConsentSnapshot.none().copyWith(syni = true))
        assertTrue(module.isGateOpen)
    }

    @Test
    fun `isGateOpen flips back to false on revoke`() = runTest {
        val consent = FakeConsent(ConsentSnapshot.none().copyWith(syni = true))
        val module = SyniModule(consent = consent)
        assertTrue(module.isGateOpen)
        consent.updateConsent(ConsentSnapshot.none())
        assertFalse(module.isGateOpen)
    }

    // ── Gate enforcement ──────────────────────────────────────────
    // These touch the underlying Syni singleton only AFTER the gate
    // check, so the consent-denied path can be exercised without a
    // real Syni runtime.

    @Test(expected = SyniConsentDeniedException::class)
    fun `availablePersonas throws when consent denied`() {
        SyniModule(consent = FakeConsent()).availablePersonas()
    }

    @Test(expected = SyniConsentDeniedException::class)
    fun `getStorageUsage throws when consent denied`() {
        SyniModule(consent = FakeConsent()).getStorageUsage()
    }

    @Test(expected = SyniConsentDeniedException::class)
    fun `getDownloadedModels throws when consent denied`() {
        SyniModule(consent = FakeConsent()).getDownloadedModels()
    }

    @Test(expected = SyniConsentDeniedException::class)
    fun `downloadModel throws when consent denied`() {
        SyniModule(consent = FakeConsent()).downloadModel(
            url = "https://example.com/model.bin",
            modelId = "test",
        )
    }

    // ── Bypass + non-gated reads ──────────────────────────────────

    @Test
    fun `unsafeSyni returns the Syni singleton (no gate)`() {
        val module = SyniModule(consent = FakeConsent())
        // Same reference twice — bypass is consistent.
        assertSame(module.unsafeSyni, module.unsafeSyni)
    }

    @Test
    fun `isInitialized does not require the gate`() {
        val module = SyniModule(consent = FakeConsent())
        // Default singleton state in a unit-test JVM (no real init) is
        // false — the important thing is that the call returns rather
        // than throwing SyniConsentDeniedException.
        assertFalse(module.isInitialized)
    }
}
