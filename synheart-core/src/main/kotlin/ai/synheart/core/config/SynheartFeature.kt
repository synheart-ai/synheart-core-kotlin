package ai.synheart.core.config

/**
 * RFC-0005 Section 6: Synheart feature identifiers for the four-authority activation model.
 *
 * A feature is **operational** when all four conditions hold:
 * ```
 * FeatureOperational = Activation AND Consent AND Capability AND SessionActive
 * ```
 */
enum class SynheartFeature(val requiredConsent: String) {
    /** Biosignal collection (HR, HRV) via wearable */
    WEAR("biosignals"),

    /** User interaction tracking (taps, keystrokes, gestures) */
    BEHAVIOR("behavior"),

    /** Device motion, screen state, and app context */
    PHONE_CONTEXT("motion"),

    /** Cloud upload connector */
    CLOUD("cloudUpload"),

    /** Syni hooks integration */
    SYNI("syni")
}
