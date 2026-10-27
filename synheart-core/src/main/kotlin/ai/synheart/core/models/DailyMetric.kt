package ai.synheart.core.models

/**
 * The daily metrics the personal baselines accept from
 * `Synheart.srmPushWearableDaily`, by their runtime names (C ABI 3.5+). The
 * unit is part of each name: push seconds to `sleep.duration_s`, minutes to
 * `sleep.deep_min`, a `0..1` fraction to `vendor.recovery_frac`.
 *
 * Any other name is refused with `1004`, and so are the names these replaced
 * (`hrv_rmssd`, `resting_hr`, `sleep_need`, …).
 */
object DailyMetric {
    /** Total sleep for the night, in seconds. */
    const val SLEEP_DURATION_S = "sleep.duration_s"

    /** Midpoint of the sleep period, in hours since local midnight. */
    const val SLEEP_MIDPOINT_H = "sleep.midpoint_h"

    /** Overnight RMSSD, in milliseconds. */
    const val OVERNIGHT_RMSSD_MS = "cardiac.overnight_rmssd_ms"

    /** Overnight SDNN, in milliseconds. */
    const val OVERNIGHT_SDNN_MS = "cardiac.overnight_sdnn_ms"

    /** Resting heart rate, in beats per minute. */
    const val RESTING_HR_BPM = "cardiac.resting_hr_bpm"

    /** A provider's recovery score as a fraction, `0..1`. */
    const val VENDOR_RECOVERY_FRAC = "vendor.recovery_frac"

    /** Deep sleep, in minutes. */
    const val SLEEP_DEEP_MIN = "sleep.deep_min"

    /** REM sleep, in minutes. */
    const val SLEEP_REM_MIN = "sleep.rem_min"

    /** A provider's daily strain as a fraction, `0..1`. */
    const val VENDOR_STRAIN_FRAC = "vendor.strain_frac"

    /** Step count for the day. */
    const val STEPS_COUNT = "motion.steps_count"

    /** Heart-rate-reserve load for the day, in arbitrary units. */
    const val HRR_LOAD_AU = "cardiac.hrr_load_au"

    /** Standard deviation of overnight heart rate, in beats per minute. */
    const val OVERNIGHT_HR_SD_BPM = "cardiac.overnight_hr_sd_bpm"

    val ALL: Set<String> = setOf(
        SLEEP_DURATION_S,
        SLEEP_MIDPOINT_H,
        OVERNIGHT_RMSSD_MS,
        OVERNIGHT_SDNN_MS,
        RESTING_HR_BPM,
        VENDOR_RECOVERY_FRAC,
        SLEEP_DEEP_MIN,
        SLEEP_REM_MIN,
        VENDOR_STRAIN_FRAC,
        STEPS_COUNT,
        HRR_LOAD_AU,
        OVERNIGHT_HR_SD_BPM,
    )
}
