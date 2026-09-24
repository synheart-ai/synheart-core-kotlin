package ai.synheart.core.example.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * These assert the properties that make the simulated trace usable rather
 * than merely non-constant. A generator that passes "not always the same
 * number" while producing white noise across a 40 bpm span is useless to
 * every HRV feature downstream, so the interesting assertions are about
 * range, autocorrelation and the RSA/exertion relationship — not about any
 * individual sample. Seeded, so a failure is reproducible.
 */
class SyntheticCardiacSourceTest {

    private fun run(source: SyntheticCardiacSource, minutes: Int, startMs: Long = 1_700_000_000_000L): List<CardiacPacket> {
        val out = ArrayList<CardiacPacket>()
        var now = startMs
        repeat(minutes * 60) {
            now += 1000
            out += source.advance(now, 1000)
        }
        return out
    }

    @Test
    fun `heart rate never leaves a healthy envelope over half an hour`() {
        val packets = run(SyntheticCardiacSource(seed = 7), minutes = 30)
        assertTrue(packets.isNotEmpty())
        for (p in packets) assertTrue("bpm ${p.bpm} is not a person", p.bpm in 45.0..135.0)
    }

    @Test
    fun `every RR interval is a plausible beat interval`() {
        val rr = run(SyntheticCardiacSource(seed = 11), minutes = 20).flatMap { it.rrMs.toList() }
        assertTrue(rr.isNotEmpty())
        for (v in rr) assertTrue("rr $v", v in 440.0..1300.0)
    }

    @Test
    fun `envelope holds across several seeds`() {
        for (seed in listOf(1L, 2L, 3L, 42L, 99L)) {
            val rates = run(SyntheticCardiacSource(seed = seed), minutes = 10).map { it.bpm }
            assertTrue("seed $seed", rates.all { it > 45 && it < 135 })
        }
    }

    @Test
    fun `rate actually moves`() {
        val rates = run(SyntheticCardiacSource(seed = 3), minutes = 30).map { it.bpm }
        // A pinned trace is the failure this whole file exists to avoid.
        assertTrue("span ${rates.max() - rates.min()}", rates.max() - rates.min() > 12)
    }

    @Test
    fun `consecutive rates are correlated, not independent draws`() {
        val rates = run(SyntheticCardiacSource(seed = 5), minutes = 25).map { it.bpm }
        var stepSum = 0.0
        for (i in 1 until rates.size) stepSum += abs(rates[i] - rates[i - 1])
        val meanStep = stepSum / (rates.size - 1)
        val span = rates.max() - rates.min()
        // Independent uniform draws would give a step comparable to the span;
        // a mean-reverting walk gives one far smaller.
        assertTrue("meanStep $meanStep vs span $span", meanStep < span / 4)
        assertTrue(meanStep > 0.0)
    }

    @Test
    fun `visits more than one activity episode`() {
        val labels = run(SyntheticCardiacSource(seed = 13), minutes = 40).map { it.activityLabel }.toSet()
        assertTrue(labels.size > 1)
    }

    @Test
    fun `RMSSD lands in a physiological band`() {
        val rmssds = run(SyntheticCardiacSource(seed = 17), minutes = 30).mapNotNull { it.rmssdMs }
        assertTrue(rmssds.isNotEmpty())
        val mean = rmssds.average()
        // Neither ~0 (no variability) nor absurd (hundreds of ms).
        assertTrue("mean rmssd $mean", mean in 8.0..120.0)
    }

    @Test
    fun `variability falls as rate rises`() {
        // RSA is suppressed by vagal withdrawal. Without this a simulated
        // 110 bpm carries resting-level HRV — impossible physiology.
        val samples = run(SyntheticCardiacSource(seed = 23), minutes = 60).filter { it.rmssdMs != null }
        val sorted = samples.map { it.bpm }.sorted()
        val lowCut = sorted[(sorted.size * 0.25).toInt()]
        val highCut = sorted[(sorted.size * 0.75).toInt()]
        val restMean = samples.filter { it.bpm <= lowCut }.map { it.rmssdMs!! }.average()
        val activeMean = samples.filter { it.bpm >= highCut }.map { it.rmssdMs!! }.average()
        assertTrue("active $activeMean should be < rest $restMean", activeMean < restMean)
    }

    @Test
    fun `packets carry one to a few intervals under one anchor`() {
        for (p in run(SyntheticCardiacSource(seed = 29), minutes = 5)) {
            assertTrue(p.rrMs.isNotEmpty())
            assertTrue(p.rrMs.size <= 4)
        }
    }

    @Test
    fun `anchors advance monotonically`() {
        // The rr channel rejects a backwards timestamp; a non-monotonic anchor
        // would have every packet after it silently dropped.
        val anchors = run(SyntheticCardiacSource(seed = 31), minutes = 15).map { it.anchorTsMs }
        for (i in 1 until anchors.size) assertTrue(anchors[i] >= anchors[i - 1])
    }

    @Test
    fun `beat rate roughly matches the reported rate`() {
        val packets = run(SyntheticCardiacSource(seed = 37), minutes = 20)
        val beatsPerMinute = packets.sumOf { it.rrMs.size } / 20.0
        val reported = packets.map { it.bpm }.average()
        assertTrue(abs(beatsPerMinute - reported) < 6.0)
    }

    @Test
    fun `a starved caller owes beats rather than losing the clock`() {
        val beats = SyntheticCardiacSource(seed = 41).advance(1_700_000_030_000L, 30_000).sumOf { it.rrMs.size }
        assertTrue(beats > 20)
    }

    @Test
    fun `the beat generator is bounded against an absurd gap`() {
        val beats = SyntheticCardiacSource(seed = 43).advance(1_700_086_400_000L, 86_400_000).sumOf { it.rrMs.size }
        assertTrue(beats <= 512)
    }

    @Test
    fun `zero or negative advance produces nothing`() {
        val s = SyntheticCardiacSource(seed = 53)
        assertTrue(s.advance(1_700_000_000_000L, 0).isEmpty())
        assertTrue(s.advance(1_700_000_000_000L, -1000).isEmpty())
    }

    @Test
    fun `same seed same trace`() {
        val a = run(SyntheticCardiacSource(seed = 61), minutes = 5).map { it.bpm }
        val b = run(SyntheticCardiacSource(seed = 61), minutes = 5).map { it.bpm }
        assertEquals(a, b)
    }
}
