package ai.synheart.core.bridge

import ai.synheart.core.models.DailyMetric
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/**
 * Pins what the SDK sends across C ABI 4.0 (runtime 0.42) that no native call
 * can check for it: the per-sample UTC offset and the daily metric names.
 * JNA does not check native signatures, so these are the contract.
 */
class Abi4ContractTest {

    @Test
    fun `utcOffsetMinAt is the offset in force at the sample, not now`() {
        val saved = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Europe/Berlin"))
            // 2026-01-15 and 2026-07-15, noon UTC: CET then CEST.
            assertEquals(60, CoreRuntimeBridge.utcOffsetMinAt(1_768_478_400_000L))
            assertEquals(120, CoreRuntimeBridge.utcOffsetMinAt(1_784_116_800_000L))

            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
            assertEquals(330, CoreRuntimeBridge.utcOffsetMinAt(1_768_478_400_000L))
        } finally {
            TimeZone.setDefault(saved)
        }
    }

    @Test
    fun `utcOffsetMinAt stays within the runtime bound of 840`() {
        val m = CoreRuntimeBridge.utcOffsetMinAt(System.currentTimeMillis())
        assertTrue(m in -840..840)
    }

    @Test
    fun `DailyMetric names exactly the twelve metrics runtime 0_42 accepts`() {
        // The runtime refuses any other name with 1004, including the names
        // these replaced, so this set is the contract.
        assertEquals(
            setOf(
                "sleep.duration_s",
                "sleep.midpoint_h",
                "cardiac.overnight_rmssd_ms",
                "cardiac.overnight_sdnn_ms",
                "cardiac.resting_hr_bpm",
                "vendor.recovery_frac",
                "sleep.deep_min",
                "sleep.rem_min",
                "vendor.strain_frac",
                "motion.steps_count",
                "cardiac.hrr_load_au",
                "cardiac.overnight_hr_sd_bpm",
            ),
            DailyMetric.ALL,
        )
    }
}
