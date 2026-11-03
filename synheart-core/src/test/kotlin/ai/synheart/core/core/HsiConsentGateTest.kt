package ai.synheart.core.core

import ai.synheart.core.modules.consent.ConsentEffectiveState
import ai.synheart.core.modules.interfaces.ConsentSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HsiConsentGateTest {

    private fun effective(
        biosignals: Boolean = false,
        behavior: Boolean = false,
        phoneContext: Boolean = false,
        cloudUpload: Boolean = false,
        syni: Boolean = false,
        vendorSync: Boolean = false,
        research: Boolean = false,
    ) = ConsentEffectiveState(
        biosignals = biosignals,
        phoneContext = phoneContext,
        behavior = behavior,
        cloudUpload = cloudUpload,
        syni = syni,
        vendorSync = vendorSync,
        research = research,
        timestampMs = 0,
        version = "1.0.0",
    )

    private fun local(
        biosignals: Boolean = false,
        behavior: Boolean = false,
        phoneContext: Boolean = false,
        cloudUpload: Boolean = false,
        research: Boolean = false,
    ) = ConsentSnapshot(
        biosignals = biosignals,
        phoneContext = phoneContext,
        behavior = behavior,
        cloudUpload = cloudUpload,
        focusEstimation = false,
        emotionEstimation = false,
        syni = false,
        research = research,
    )

    @Test
    fun `any collection category in the runtime state delivers`() {
        assertTrue(HsiConsentGate.allows(effective(biosignals = true), local()))
        assertTrue(HsiConsentGate.allows(effective(behavior = true), local()))
        assertTrue(HsiConsentGate.allows(effective(phoneContext = true), local()))
        assertTrue(HsiConsentGate.allows(effective(research = true), local()))
    }

    @Test
    fun `cloud upload, syni and vendor sync alone do not deliver`() {
        // None of these collect an input, so no axis could come from them.
        val e = effective(cloudUpload = true, syni = true, vendorSync = true)
        assertFalse(HsiConsentGate.allows(e, local(cloudUpload = true)))
    }

    @Test
    fun `behavior only delivers - the digital-only app`() {
        assertTrue(HsiConsentGate.allows(null, local(behavior = true)))
    }

    @Test
    fun `a stale SDK snapshot does not refuse what the runtime granted`() {
        assertTrue(HsiConsentGate.allows(effective(biosignals = true), local()))
    }

    @Test
    fun `nothing granted refuses`() {
        assertFalse(HsiConsentGate.allows(effective(), local()))
        assertFalse(HsiConsentGate.allows(null, null))
    }
}
