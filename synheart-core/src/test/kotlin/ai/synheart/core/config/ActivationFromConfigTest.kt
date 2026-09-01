package ai.synheart.core.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the config-declared activation rule.
 *
 * Wear, phone and behavior used to be activated unconditionally, ignoring the
 * config entirely — so a host could not run one collector without the others,
 * and `deviceRole`, documented as controlling which modules are enabled, was
 * read by nothing. Declaring a module config now activates that feature, which
 * is the rule the sibling platform SDKs have always used.
 */
class ActivationFromConfigTest {

    private fun activated(config: SynheartConfig): Set<SynheartFeature> =
        ActivationManager().apply { activateFromConfig(config) }.activatedFeatures()

    @Test
    fun `a config declaring nothing activates nothing`() {
        // The behavioural change. Previously this activated three collectors on
        // a host that asked for none.
        assertTrue(activated(SynheartConfig(appId = "a", subjectId = "s")).isEmpty())
    }

    @Test
    fun `each module config activates only its own feature`() {
        val behaviorOnly = activated(
            SynheartConfig(appId = "a", subjectId = "s", behaviorConfig = BehaviorConfig()),
        )
        assertEquals(setOf(SynheartFeature.BEHAVIOR), behaviorOnly)

        val wearOnly = activated(
            SynheartConfig(appId = "a", subjectId = "s", wearConfig = WearConfig()),
        )
        assertEquals(setOf(SynheartFeature.WEAR), wearOnly)

        val phoneOnly = activated(
            SynheartConfig(appId = "a", subjectId = "s", phoneConfig = PhoneConfig()),
        )
        assertEquals(setOf(SynheartFeature.PHONE_CONTEXT), phoneOnly)
    }

    @Test
    fun `cloud activates from cloudConfig, independently of the collectors`() {
        val features = activated(
            SynheartConfig(
                appId = "a",
                subjectId = "s",
                cloudConfig = CloudConfig(subjectId = "s", orgId = "org_x"),
            ),
        )
        assertEquals(setOf(SynheartFeature.CLOUD), features)
    }

    @Test
    fun `all four declared activates all four`() {
        val features = activated(
            SynheartConfig(
                appId = "a",
                subjectId = "s",
                wearConfig = WearConfig(),
                phoneConfig = PhoneConfig(),
                behaviorConfig = BehaviorConfig(),
                cloudConfig = CloudConfig(subjectId = "s", orgId = "org_x"),
            ),
        )
        assertEquals(
            setOf(
                SynheartFeature.WEAR,
                SynheartFeature.PHONE_CONTEXT,
                SynheartFeature.BEHAVIOR,
                SynheartFeature.CLOUD,
            ),
            features,
        )
    }

    @Test
    fun `a watch role cannot activate phone or behavior even when declared`() {
        // DeviceRole was previously read by nothing at all. A watch build that
        // passed a behavior config would otherwise silently start collecting
        // interaction it has no business collecting.
        val features = activated(
            SynheartConfig(
                appId = "a",
                subjectId = "s",
                deviceRole = DeviceRole.WATCH,
                wearConfig = WearConfig(),
                phoneConfig = PhoneConfig(),
                behaviorConfig = BehaviorConfig(),
            ),
        )
        assertEquals(setOf(SynheartFeature.WEAR), features)
        assertFalse(SynheartFeature.BEHAVIOR in features)
    }
}
