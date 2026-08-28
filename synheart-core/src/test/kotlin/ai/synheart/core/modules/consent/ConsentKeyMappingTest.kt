package ai.synheart.core.modules.consent

import ai.synheart.core.Synheart
import ai.synheart.core.modules.interfaces.ConsentType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the translation between the two consent vocabularies.
 *
 * The native runtime keys consent in snake_case (`cloud_upload`); the SDK API
 * and [ai.synheart.core.modules.interfaces.ConsentSnapshot] use camelCase
 * (`cloudUpload`). Passing the caller's spelling through unchanged meant
 * neither worked in both places: `hasConsent("cloudUpload")` asked the runtime
 * about a key it does not define, and `hasConsent("cloud_upload")` missed
 * every branch of the SDK's own switch. Both returned false regardless of what
 * the user had granted.
 */
class ConsentKeyMappingTest {

    @Test
    fun `camelCase maps to the runtime's snake_case`() {
        assertEquals("cloud_upload", Synheart.runtimeConsentKey("cloudUpload"))
        assertEquals("phone_context", Synheart.runtimeConsentKey("phoneContext"))
        assertEquals("vendor_sync", Synheart.runtimeConsentKey("vendorSync"))
        assertEquals("focus_estimation", Synheart.runtimeConsentKey("focusEstimation"))
        assertEquals("emotion_estimation", Synheart.runtimeConsentKey("emotionEstimation"))
    }

    @Test
    fun `keys that are already snake_case pass through unchanged`() {
        assertEquals("cloud_upload", Synheart.runtimeConsentKey("cloud_upload"))
        assertEquals("biosignals", Synheart.runtimeConsentKey("biosignals"))
        assertEquals("research", Synheart.runtimeConsentKey("research"))
    }

    @Test
    fun `snake_case maps back to the SDK's camelCase`() {
        assertEquals("cloudUpload", Synheart.sdkConsentKey("cloud_upload"))
        assertEquals("phoneContext", Synheart.sdkConsentKey("phone_context"))
        assertEquals("vendorSync", Synheart.sdkConsentKey("vendor_sync"))
        assertEquals("cloudUpload", Synheart.sdkConsentKey("cloudUpload"))
    }

    @Test
    fun `motion stays an alias for phone context in both directions`() {
        assertEquals("phone_context", Synheart.runtimeConsentKey("motion"))
        assertEquals("phoneContext", Synheart.sdkConsentKey("motion"))
    }

    @Test
    fun `an unknown key passes through rather than being rewritten`() {
        // A consent type added in a newer runtime must still reach it.
        assertEquals("brand_new_channel", Synheart.runtimeConsentKey("brand_new_channel"))
        assertEquals("brand_new_channel", Synheart.sdkConsentKey("brand_new_channel"))
    }

    @Test
    fun `every ConsentType has both spellings and they translate to each other`() {
        for (t in ConsentType.entries) {
            assertEquals(
                "runtimeKey for $t must match the string translation of its wireKey",
                t.runtimeKey,
                Synheart.runtimeConsentKey(t.wireKey),
            )
            assertEquals(
                "wireKey for $t must be recoverable from its runtimeKey",
                t.wireKey,
                Synheart.sdkConsentKey(t.runtimeKey),
            )
        }
    }
}
