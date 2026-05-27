// SPDX-License-Identifier: Apache-2.0
//
// Pure-Kotlin unit tests for SynheartResilience.
// Native FFI path is exercised by the runtime's own internal tests.

package ai.synheart.core.resilience

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SynheartResilienceTest {

    // ── Type round-trips ───────────────────────────────────────────────

    @Test
    fun `HrvSample json shape uses snake_case keys`() {
        val s = HrvSample(tsMs = 100, rmssdMs = 42.5)
        assertEquals(100L, s.toJsonObject()["ts_ms"])
        assertEquals(42.5, s.toJsonObject()["rmssd_ms"])
    }

    @Test
    fun `SleepWindow json shape uses snake_case keys`() {
        val w = SleepWindow(startMs = 1000, endMs = 2000)
        assertEquals(1000L, w.toJsonObject()["start_ms"])
        assertEquals(2000L, w.toJsonObject()["end_ms"])
    }

    @Test
    fun `ResilienceConfig defaults match runtime defaults`() {
        val c = ResilienceConfig()
        assertEquals(7, c.lookbackDays)
        assertEquals(5, c.minDaysRequired)
        assertEquals(20, c.minRrSamples)
        assertEquals(7.0, c.cvCeilingPct, 0.0001)
        assertEquals(40.0, c.cvFloorPct, 0.0001)
    }

    @Test
    fun `ResilienceConfig json shape uses snake_case keys`() {
        val obj = ResilienceConfig().toJsonObject()
        assertEquals(7, obj["lookback_days"])
        assertEquals(5, obj["min_days_required"])
        assertEquals(20, obj["min_rr_samples"])
        assertEquals(7.0, obj["cv_ceiling_pct"])
        assertEquals(40.0, obj["cv_floor_pct"])
    }

    // ── Reason mapping ─────────────────────────────────────────────────

    @Test
    fun `reason fromWire maps every variant`() {
        assertEquals(ResilienceReason.INSUFFICIENT_DAYS,
            ResilienceReason.fromWire("InsufficientDays"))
        assertEquals(ResilienceReason.NO_SLEEP_WINDOWS,
            ResilienceReason.fromWire("NoSleepWindows"))
        assertEquals(ResilienceReason.INSUFFICIENT_SAMPLES,
            ResilienceReason.fromWire("InsufficientSamples"))
        assertEquals(ResilienceReason.NO_VALID_SAMPLES,
            ResilienceReason.fromWire("NoValidSamples"))
        assertEquals(ResilienceReason.ZERO_MEAN_HRV,
            ResilienceReason.fromWire("ZeroMeanHrv"))
    }

    @Test
    fun `reason fromWire returns null for unknown`() {
        assertNull(ResilienceReason.fromWire(null))
        assertNull(ResilienceReason.fromWire("garbage"))
    }

    // ── Wrapper unavailable path ───────────────────────────────────────

    @Test
    fun `reports not available when native is null`() {
        val r = SynheartResilience(native = null)
        assertFalse(r.isAvailable)
    }

    @Test
    fun `compute throws RuntimeUnavailable when native is null`() {
        val r = SynheartResilience(native = null)
        try {
            r.compute(samples = emptyList(), sleepWindows = emptyList())
            fail("expected ResilienceError.RuntimeUnavailable")
        } catch (e: ResilienceError.RuntimeUnavailable) {
            // ok
        }
    }
}
