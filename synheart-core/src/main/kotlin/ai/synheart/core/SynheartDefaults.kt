package ai.synheart.core

/**
 * Default constants used across the Synheart SDK.
 *
 * Centralizes magic numbers for runtime configuration, biosignal validation,
 * and physiological range boundaries.
 */
object SynheartDefaults {
    /** Default runtime window duration in milliseconds (60 seconds). */
    const val RUNTIME_WINDOW_MS: Long = 60_000

    /** Default runtime step interval in milliseconds (5 seconds). */
    const val RUNTIME_STEP_MS: Long = 5_000

    /** Default runtime tick interval in milliseconds (5 seconds). */
    const val RUNTIME_TICK_INTERVAL_MS: Long = 5_000

    /** Maximum daily steps used for motion normalization (0–1 range). */
    const val MAX_STEPS_FOR_MOTION: Double = 10_000.0

    /** Minimum valid heart rate in BPM. */
    const val HR_MIN_BPM: Double = 40.0

    /** Maximum valid heart rate in BPM. */
    const val HR_MAX_BPM: Double = 180.0

    /** Minimum valid RR interval in milliseconds. */
    const val RR_MIN_MS: Double = 300.0

    /** Maximum valid RR interval in milliseconds. */
    const val RR_MAX_MS: Double = 2_000.0

    /** Milliseconds per minute, used for HR ↔ RR conversion. */
    const val MS_PER_MINUTE: Double = 60_000.0
}
