// SPDX-License-Identifier: Apache-2.0
//
// Pure-Kotlin unit tests for the SynheartPriority in-memory fallback.
// The native FFI path is exercised by the runtime's own Rust tests.

package ai.synheart.core.priority

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.fail
import org.junit.Test

class SynheartPriorityTest {

    private fun newPriority() = SynheartPriority(native = null)

    // ── Wire names ─────────────────────────────────────────────────────

    @Test
    fun `wire names are pinned`() {
        // Persisted in the runtime SQLite schema. Renaming requires
        // a migration.
        assertEquals("heart_rate", PriorityMetric.HEART_RATE.wireName)
        assertEquals("hrv", PriorityMetric.HRV.wireName)
        assertEquals("steps", PriorityMetric.STEPS.wireName)
        assertEquals("sleep", PriorityMetric.SLEEP.wireName)
        assertEquals("calories", PriorityMetric.CALORIES.wireName)
        assertEquals("spo2", PriorityMetric.SPO2.wireName)
        assertEquals("temperature", PriorityMetric.TEMPERATURE.wireName)
        assertEquals("stress", PriorityMetric.STRESS.wireName)
    }

    @Test
    fun `fromWire round-trips every variant`() {
        for (m in PriorityMetric.values()) {
            assertEquals(m, PriorityMetric.fromWire(m.wireName))
        }
    }

    @Test
    fun `fromWire returns null for unknown`() {
        assertNull(PriorityMetric.fromWire("garbage"))
        assertNull(PriorityMetric.fromWire(null))
    }

    // ── In-memory store ────────────────────────────────────────────────

    @Test
    fun `reports not using native store`() {
        assertFalse(newPriority().isUsingNativeStore)
    }

    @Test
    fun `unknown provider returns UNRANKED`() {
        val p = newPriority()
        assertEquals(PRIORITY_UNRANKED, p.effectiveRank(PriorityMetric.HEART_RATE, "apple_watch"))
    }

    @Test
    fun `set then read provider rank`() {
        val p = newPriority()
        p.setProviderPriority("apple_watch", 10)
        assertEquals(10, p.effectiveRank(PriorityMetric.HEART_RATE, "apple_watch"))
    }

    @Test
    fun `metric override beats global rank`() {
        val p = newPriority()
        p.setProviderPriority("oura_ring", 40)
        assertEquals(40, p.effectiveRank(PriorityMetric.HEART_RATE, "oura_ring"))
        p.setMetricOverride(PriorityMetric.SLEEP, "oura_ring", 5)
        assertEquals(5, p.effectiveRank(PriorityMetric.SLEEP, "oura_ring"))
        // Other metrics keep using the global rank.
        assertEquals(40, p.effectiveRank(PriorityMetric.HEART_RATE, "oura_ring"))
    }

    @Test
    fun `clearing override falls back to global`() {
        val p = newPriority()
        p.setProviderPriority("oura_ring", 40)
        p.setMetricOverride(PriorityMetric.SLEEP, "oura_ring", 5)
        p.setMetricOverride(PriorityMetric.SLEEP, "oura_ring", null)
        assertEquals(40, p.effectiveRank(PriorityMetric.SLEEP, "oura_ring"))
    }

    @Test
    fun `empty provider name throws`() {
        val p = newPriority()
        try {
            p.setProviderPriority("", 1)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
        try {
            p.setMetricOverride(PriorityMetric.HRV, "", 1)
            fail("expected IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // ok
        }
    }

    // ── Resolve ────────────────────────────────────────────────────────

    @Test
    fun `resolve empty input returns null`() {
        assertNull(newPriority().resolve(PriorityMetric.HEART_RATE, emptyMap()))
    }

    @Test
    fun `resolve all-zero counts returns null`() {
        val p = newPriority()
        assertNull(p.resolve(
            PriorityMetric.HEART_RATE,
            mapOf("apple_watch" to 0, "oura_ring" to 0),
        ))
    }

    @Test
    fun `resolve lowest rank wins`() {
        val p = newPriority()
        p.setProviderPriority("apple_watch", 10)
        p.setProviderPriority("oura_ring", 40)
        p.setProviderPriority("phone", 90)

        val r = p.resolve(PriorityMetric.HEART_RATE, mapOf(
            "apple_watch" to 100,
            "oura_ring" to 100,
            "phone" to 100,
        ))
        assertNotNull(r)
        assertEquals("apple_watch", r!!.winner)
        assertEquals(10, r.rank)
        assertEquals(2, r.alsoRan.size)
        assertEquals("oura_ring", r.alsoRan[0].provider)
        assertEquals("phone", r.alsoRan[1].provider)
    }

    @Test
    fun `resolve override changes winner`() {
        val p = newPriority()
        p.setProviderPriority("apple_watch", 10)
        p.setProviderPriority("oura_ring", 40)
        p.setMetricOverride(PriorityMetric.SLEEP, "oura_ring", 5)

        val r = p.resolve(PriorityMetric.SLEEP, mapOf(
            "apple_watch" to 100,
            "oura_ring" to 100,
        ))
        assertEquals("oura_ring", r?.winner)
        assertEquals(5, r?.rank)
    }

    @Test
    fun `resolve tie-break by sample count then alpha`() {
        val p = newPriority()
        p.setProviderPriority("alpha", 10)
        p.setProviderPriority("beta", 10)
        p.setProviderPriority("charlie", 10)

        val byCount = p.resolve(PriorityMetric.STEPS, mapOf(
            "alpha" to 50, "beta" to 200, "charlie" to 50,
        ))
        assertEquals("beta", byCount?.winner)

        val byAlpha = p.resolve(PriorityMetric.STEPS, mapOf(
            "alpha" to 100, "beta" to 100,
        ))
        assertEquals("alpha", byAlpha?.winner)
    }

    @Test
    fun `unknown provider loses to known`() {
        val p = newPriority()
        p.setProviderPriority("apple_watch", 10)
        val r = p.resolve(PriorityMetric.HEART_RATE, mapOf(
            "apple_watch" to 10,
            "ghost_tracker" to 10,
        ))
        assertEquals("apple_watch", r?.winner)
    }

    @Test
    fun `unknown only still resolves with UNRANKED rank`() {
        val p = newPriority()
        val r = p.resolve(PriorityMetric.HEART_RATE, mapOf("ghost_tracker" to 5))
        assertEquals("ghost_tracker", r?.winner)
        assertEquals(PRIORITY_UNRANKED, r?.rank)
    }

}
