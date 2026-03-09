package com.synheart.core.config

/**
 * Internal manager that tracks which features the developer has activated.
 *
 * Part of the four-authority activation model (RFC-0005 Section 6):
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
    fun activateFromConfig(config: SynheartConfig) {
        activated.add(SynheartFeature.WEAR)
        activated.add(SynheartFeature.PHONE_CONTEXT)
        activated.add(SynheartFeature.BEHAVIOR)
        if (config.cloudConfig != null) activated.add(SynheartFeature.CLOUD)
    }
}
