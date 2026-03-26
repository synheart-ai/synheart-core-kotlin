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

/**
 * Device role determines which modules the SDK enables.
 *
 * - [PHONE]: Full pipeline — all modules, cloud upload, session management.
 *   The phone is the source of truth for data persistence and cloud sync.
 *
 * - [WATCH]: Edge pipeline — wear + runtime only. No behavior tracking,
 *   no cloud upload, no phone context. Sessions are captured locally and
 *   relayed to the phone via the companion channel.
 */
enum class DeviceRole {
    PHONE,
    WATCH;

    /** Features available on this device role. */
    val supportedFeatures: Set<SynheartFeature>
        get() = when (this) {
            PHONE -> SynheartFeature.entries.toSet()
            WATCH -> setOf(SynheartFeature.WEAR)
        }

    /** Whether this role supports cloud upload (only phone). */
    val supportsCloud: Boolean get() = this == PHONE

    /** Whether this role supports behavior tracking (only phone). */
    val supportsBehavior: Boolean get() = this == PHONE

    /** Whether this role supports phone context (only phone). */
    val supportsPhoneContext: Boolean get() = this == PHONE

    /** Whether this role manages its own session persistence (only phone). */
    val managesStorage: Boolean get() = this == PHONE
}
