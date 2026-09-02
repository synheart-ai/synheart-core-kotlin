package ai.synheart.core.config

/**
 * Internal manager that tracks which features the developer has activated.
 *
 * Part of the four-authority activation model:
 * ```
 * FeatureOperational = Activation AND Consent AND Capability AND SessionActive
 * ```
 *
 * This class manages the **Activation** authority — the developer's explicit
 * intent to use a feature.
 */
internal class ActivationManager {
    private val activated: MutableSet<SynheartFeature> = mutableSetOf()

    /** Activate a feature (add to set). */
    fun activate(feature: SynheartFeature) {
        activated.add(feature)
    }

    /** Deactivate a feature (remove from set). */
    fun deactivate(feature: SynheartFeature) {
        activated.remove(feature)
    }

    /** Check if a feature is activated. */
    fun isActivated(feature: SynheartFeature): Boolean {
        return activated.contains(feature)
    }

    /** Return a copy of all activated features. */
    fun activatedFeatures(): Set<SynheartFeature> {
        return activated.toSet()
    }

    /**
     * Bulk-activate features based on SynheartConfig.
     *
     * Activates all data-collection features by default.
     * Cloud is activated when a cloud config is provided.
     */
    /**
     * Activate the features the config declares.
     *
     * Declaring a module config activates that feature; omitting it leaves the
     * module inert. This matches the sibling platform SDKs.
     *
     * Previously wear, phone and behavior were activated unconditionally,
     * ignoring the config. That gave a host no way to run one collector without
     * the others, and meant `deviceRole` — documented as controlling which
     * modules are enabled — was read by nothing at all.
     *
     * The result is intersected with [DeviceRole.supportedFeatures], so a watch
     * build cannot silently activate phone-context or behavior collection just
     * because a config object was passed.
     */
    fun activateFromConfig(config: SynheartConfig) {
        val declared = buildSet {
            if (config.wearConfig != null) add(SynheartFeature.WEAR)
            if (config.phoneConfig != null) add(SynheartFeature.PHONE_CONTEXT)
            if (config.behaviorConfig != null) add(SynheartFeature.BEHAVIOR)
            if (config.cloudConfig != null) add(SynheartFeature.CLOUD)
        }
        activated.addAll(declared intersect config.deviceRole.supportedFeatures)
    }
}
