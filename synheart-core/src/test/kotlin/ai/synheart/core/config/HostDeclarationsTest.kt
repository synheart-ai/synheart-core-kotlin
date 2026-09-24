package ai.synheart.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the wire shape of the four host declarations.
 *
 * The property that matters most is the one that is easy to lose in a
 * refactor: **an undeclared field emits no key at all.** Undeclared and
 * declared-as-default are different states to the runtime — declaring
 * `device_class` folds into the SRM `config_hash` and invalidates every
 * persisted baseline — so a serializer that "helpfully" filled in a default
 * would reset every host's baselines on its next launch.
 */
class HostDeclarationsTest {

    @Test
    fun `declaring nothing emits nothing`() {
        val json = HostDeclarations().toJson()
        assertEquals(0, json.length())
        assertTrue(HostDeclarations().isEmpty)
    }

    @Test
    fun `auto declares all four as the string auto`() {
        val json = HostDeclarations.auto.toJson()
        assertEquals("auto", json.getString("sensing"))
        assertEquals("auto", json.getString("device_class"))
        assertEquals("auto", json.getString("mask_profile"))
        assertEquals(4, json.getInt("cfi_structural_components"))
        assertFalse(HostDeclarations.auto.isEmpty)
    }

    @Test
    fun `an explicit sensing profile serializes as an object with the mode`() {
        val json = HostDeclarations(
            sensing = Declared.Value(
                SensingProfile(
                    mode = SensingMode.CONTINUOUS,
                    latenessBudgetMs = 30_000,
                    streams = SensingStreams(cardiac = true, pointer = false),
                ),
            ),
        ).toJson()

        val sensing = json.getJSONObject("sensing")
        assertEquals("continuous", sensing.getString("mode"))
        assertEquals(30_000L, sensing.getLong("lateness_budget_ms"))
        val streams = sensing.getJSONObject("streams")
        assertTrue(streams.getBoolean("cardiac"))
        assertFalse(streams.getBoolean("pointer"))
        // Not named → not emitted. The runtime reads an unnamed stream as
        // unavailable, and that must be the host's decision, not the
        // serializer's.
        assertFalse(streams.has("accelerometer"))
        assertFalse(streams.has("screen_state"))

        // Only sensing was declared.
        assertFalse(json.has("device_class"))
        assertFalse(json.has("mask_profile"))
        assertFalse(json.has("cfi_structural_components"))
    }

    @Test
    fun `stream roster keys are the engine's snake_case names`() {
        val json = SensingStreams(
            appFocus = true,
            notificationArrivals = true,
            notificationResponses = false,
            screenState = true,
        ).toJson()
        assertTrue(json.getBoolean("app_focus"))
        assertTrue(json.getBoolean("notification_arrivals"))
        assertFalse(json.getBoolean("notification_responses"))
        assertTrue(json.getBoolean("screen_state"))
    }

    @Test
    fun `lateness budget and streams are omitted when null`() {
        val sensing = SensingProfile(mode = SensingMode.EPISODIC).toJson()
        assertEquals("episodic", sensing.getString("mode"))
        assertFalse(sensing.has("lateness_budget_ms"))
        assertFalse(sensing.has("streams"))
    }

    @Test
    fun `explicit device class and mask profile use their wire names`() {
        val json = HostDeclarations(
            deviceClass = Declared.Value(DeviceClass.TABLET),
            maskProfile = Declared.Value(MaskProfile.MOBILE),
        ).toJson()
        assertEquals("tablet", json.getString("device_class"))
        assertEquals("mobile", json.getString("mask_profile"))
    }

    @Test
    fun `extra heads carry their wire names`() {
        assertEquals("movement_regularity", ExtraHead.MOVEMENT_REGULARITY.wire)
        assertEquals("postural_state", ExtraHead.POSTURAL_STATE.wire)
        assertEquals("activity_state", ExtraHead.ACTIVITY_STATE.wire)
        assertEquals("locomotion_state", ExtraHead.LOCOMOTION_STATE.wire)
    }
}
