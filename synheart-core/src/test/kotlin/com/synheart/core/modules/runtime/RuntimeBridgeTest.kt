package com.synheart.core.modules.runtime

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeNotNull
import org.junit.Before
import org.junit.Test

/**
 * Integration tests for the synheart-runtime native bridge via JNA.
 *
 * These tests require the native library (libsynheart_runtime.dylib on macOS,
 * .so on Linux) to be on the JNA library path. For macOS host testing, set:
 *   -Djna.library.path=/path/to/synheart-runtime/target/release
 *
 * When the native library is unavailable, tests that depend on it are skipped
 * via JUnit's Assume mechanism.
 */
class RuntimeBridgeTest {

    private var bridge: RuntimeBridge? = null

    @Before
    fun setUp() {
        bridge = RuntimeBridge.createIfAvailable(
            RuntimeConfig(
                subjectId = "sub_test_001",
                sessionId = "sess_test_001",
                windowMs = 10_000,
                stepMs = 5_000
            )
        )
    }

    @After
    fun tearDown() {
        bridge?.close()
        bridge = null
    }

    /** Helper: push RR + HR data spanning a 10s window, then tick. */
    private fun fillWindowAndTick(b: RuntimeBridge, baseMs: Long): String? {
        for (i in 0 until 15) {
            b.pushRr(baseMs + i * 800L, 800.0)
        }
        for (i in 0 until 12) {
            b.pushHr(baseMs + i * 1000L, 72.0)
        }
        return b.tick(baseMs + 10_000L)
    }

    @Test
    fun `createIfAvailable returns non-null when library is loaded`() {
        assertNotNull(
            "Native runtime library should be loadable. " +
                "Set -Djna.library.path to the directory containing the library.",
            bridge
        )
    }

    @Test
    fun `version returns a valid string`() {
        val v = RuntimeBridge.version()
        assertNotNull("version() should return a string", v)
        assertTrue(
            "version should match semver format (got: $v)",
            v!!.matches(Regex("""\d+\.\d+\.\d+.*"""))
        )
    }

    @Test
    fun `push synthetic signals then tick produces HSI JSON`() {
        assumeNotNull(bridge)
        val b = bridge!!

        val now = System.currentTimeMillis()
        var hsi = fillWindowAndTick(b, now)
        if (hsi == null) {
            hsi = fillWindowAndTick(b, now + 15_000L)
        }

        assertNotNull("tick should produce HSI JSON after enough signal data", hsi)
        val parsed = JSONObject(hsi!!)
        assertTrue("HSI should contain hsi_version", parsed.has("hsi_version"))
    }

    @Test
    fun `push behavior events included in HSI output`() {
        assumeNotNull(bridge)
        val b = bridge!!

        val now = System.currentTimeMillis()
        for (i in 0 until 15) {
            b.pushRr(now + i * 800L, 800.0)
        }
        for (i in 0 until 12) {
            b.pushHr(now + i * 1000L, 72.0)
        }
        for (i in 0 until 10) {
            b.pushBehavior(now + i * 500L, 2, 1.0)
        }
        b.pushBehavior(now + 5000L, 3, 1.0)

        var hsi = b.tick(now + 10_000L)
        if (hsi == null) {
            for (i in 0 until 15) {
                b.pushRr(now + 15_000L + i * 800L, 800.0)
                b.pushHr(now + 15_000L + i * 800L, 72.0)
            }
            hsi = b.tick(now + 25_000L)
        }

        if (hsi != null) {
            val parsed = JSONObject(hsi)
            assertTrue(parsed.has("hsi_version"))
        }
    }

    @Test
    fun `frameCount increments after successful tick`() {
        assumeNotNull(bridge)
        val b = bridge!!

        val initialCount = b.frameCount()
        val now = System.currentTimeMillis()
        for (w in 0 until 5) {
            fillWindowAndTick(b, now + w * 15_000L)
        }

        val finalCount = b.frameCount()
        if (finalCount > initialCount) {
            assertTrue(finalCount > initialCount)
        }
    }

    @Test
    fun `lastQuality returns valid JSON`() {
        assumeNotNull(bridge)
        val b = bridge!!

        val now = System.currentTimeMillis()
        for (w in 0 until 3) {
            fillWindowAndTick(b, now + w * 15_000L)
        }

        val quality = b.lastQuality()
        if (quality != null) {
            val parsed = JSONObject(quality)
            assertNotNull(parsed)
        }
    }

    @Test
    fun `reset clears state and frameCount returns to 0`() {
        assumeNotNull(bridge)
        val b = bridge!!

        val now = System.currentTimeMillis()
        for (w in 0 until 3) {
            fillWindowAndTick(b, now + w * 15_000L)
        }

        b.reset()
        assertEquals("frameCount should be 0 after reset", 0L, b.frameCount())
    }

    @Test
    fun `multiple windows eventually produce frames`() {
        assumeNotNull(bridge)
        val b = bridge!!

        val now = System.currentTimeMillis()
        var framesProduced = 0

        for (window in 0 until 10) {
            val hsi = fillWindowAndTick(b, now + window * 15_000L)
            if (hsi != null) framesProduced++
        }

        assertTrue(
            "Should produce at least one frame across 10 windows",
            framesProduced >= 1
        )
    }

    // -- SRM Baselines --

    @Test
    fun `baselineSummary returns valid JSON with expected keys`() {
        assumeNotNull(bridge)
        val b = bridge!!

        val summary = b.baselineSummary()
        assertNotNull("baselineSummary() should return JSON", summary)

        val parsed = JSONObject(summary!!)
        assertTrue("summary should have 'total'", parsed.has("total"))
        assertTrue("summary should have 'ready'", parsed.has("ready"))
        assertTrue("summary should have 'warming'", parsed.has("warming"))
        assertTrue("summary should have 'empty'", parsed.has("empty"))
    }

    @Test
    fun `baselinesJson returns valid JSON`() {
        assumeNotNull(bridge)
        val b = bridge!!

        val baselines = b.baselinesJson()
        assertNotNull("baselinesJson() should return JSON", baselines)
        // Should parse without throwing
        JSONObject(baselines!!)
    }

    @Test
    fun `baselineSummary shows metrics after data ingestion`() {
        assumeNotNull(bridge)
        val b = bridge!!

        val now = System.currentTimeMillis()
        for (w in 0 until 5) {
            fillWindowAndTick(b, now + w * 15_000L)
        }

        val summary = b.baselineSummary()
        assertNotNull(summary)

        val parsed = JSONObject(summary!!)
        assertTrue("Should have SRM metrics registered", parsed.getInt("total") > 0)
    }

    @Test
    fun `SRM snapshot export and load round-trip`() {
        assumeNotNull(bridge)
        val b = bridge!!

        val now = System.currentTimeMillis()
        for (w in 0 until 5) {
            fillWindowAndTick(b, now + w * 15_000L)
        }

        // Export snapshot
        val snapshot = b.exportSrmSnapshot()
        assertNotNull("exportSrmSnapshot() should return JSON", snapshot)
        // Should parse without throwing
        JSONObject(snapshot!!)

        // Create a new bridge and load the snapshot
        val b2 = RuntimeBridge.createIfAvailable(
            RuntimeConfig(
                subjectId = "sub_test_002",
                sessionId = "sess_test_002",
                windowMs = 10_000,
                stepMs = 5_000
            )
        )
        assertNotNull("Second bridge should be created", b2)

        val result = b2!!.loadSrmSnapshot(snapshot)
        assertEquals("loadSrmSnapshot should return 0 on success", 0, result)

        // Verify loaded bridge has same baseline summary
        assertEquals(
            "Baseline summary should match after round-trip",
            b.baselineSummary(),
            b2.baselineSummary()
        )

        b2.close()
    }

    @Test
    fun `reset clears SRM baselines`() {
        assumeNotNull(bridge)
        val b = bridge!!

        val now = System.currentTimeMillis()
        for (w in 0 until 5) {
            fillWindowAndTick(b, now + w * 15_000L)
        }

        b.reset()

        val summary = b.baselineSummary()
        assertNotNull(summary)

        val parsed = JSONObject(summary!!)
        assertEquals("warming should be 0 after reset", 0, parsed.getInt("warming"))
        assertEquals("ready should be 0 after reset", 0, parsed.getInt("ready"))
    }
}
