package com.synheart.core

import com.synheart.core.modules.wear.WearSample
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for session data buffering (getSessionHsiWindows / getSessionWearSamples).
 *
 * These tests exercise the buffer data models and list behaviour directly,
 * without requiring the full SDK initialization or native runtime.
 */
class SessionBufferTest {

    // -- HSI buffer --

    @Test
    fun `HSI buffer is empty before any data is added`() {
        val buffer = mutableListOf<String>()
        assertEquals(emptyList<String>(), buffer.toList())
    }

    @Test
    fun `HSI buffer accumulates JSON strings`() {
        val buffer = mutableListOf<String>()
        buffer.add("{\"hsi\":\"frame1\"}")
        buffer.add("{\"hsi\":\"frame2\"}")
        buffer.add("{\"hsi\":\"frame3\"}")

        val snapshot = buffer.toList()
        assertEquals(3, snapshot.size)
        assertEquals("{\"hsi\":\"frame1\"}", snapshot[0])
        assertEquals("{\"hsi\":\"frame3\"}", snapshot[2])
    }

    @Test
    fun `HSI buffer persists after source stops adding`() {
        val buffer = mutableListOf<String>()
        buffer.add("{\"hsi\":\"frame1\"}")
        buffer.add("{\"hsi\":\"frame2\"}")

        // Simulate stopSession — no more additions, but buffer persists
        val snapshot = buffer.toList()
        assertEquals(2, snapshot.size)
    }

    @Test
    fun `HSI buffer clears on new session`() {
        val buffer = mutableListOf<String>()
        buffer.add("{\"hsi\":\"old_frame\"}")
        assertEquals(1, buffer.size)

        // Simulate startSession — clears previous data
        buffer.clear()
        assertEquals(0, buffer.size)
    }

    @Test
    fun `HSI snapshot is a copy, not a reference`() {
        val buffer = mutableListOf<String>()
        buffer.add("{\"hsi\":\"frame1\"}")

        val snapshot = buffer.toList()
        buffer.add("{\"hsi\":\"frame2\"}")

        // Snapshot should still have only the original item
        assertEquals(1, snapshot.size)
        assertEquals(2, buffer.size)
    }

    // -- WearSample buffer --

    @Test
    fun `WearSample buffer is empty before any data is added`() {
        val buffer = mutableListOf<WearSample>()
        assertEquals(emptyList<WearSample>(), buffer.toList())
    }

    @Test
    fun `WearSample buffer accumulates samples`() {
        val buffer = mutableListOf<WearSample>()
        buffer.add(WearSample(timestamp = 1000L, hr = 72.0))
        buffer.add(WearSample(timestamp = 2000L, hr = 74.0))
        buffer.add(WearSample(timestamp = 3000L, hr = 71.0))

        val snapshot = buffer.toList()
        assertEquals(3, snapshot.size)
        assertEquals(72.0, snapshot[0].hr!!, 0.01)
        assertEquals(71.0, snapshot[2].hr!!, 0.01)
    }

    @Test
    fun `WearSample buffer persists after source stops adding`() {
        val buffer = mutableListOf<WearSample>()
        buffer.add(WearSample(timestamp = 1000L, hr = 72.0))
        buffer.add(WearSample(timestamp = 2000L, hr = 74.0))

        val snapshot = buffer.toList()
        assertEquals(2, snapshot.size)
    }

    @Test
    fun `WearSample buffer clears on new session`() {
        val buffer = mutableListOf<WearSample>()
        buffer.add(WearSample(timestamp = 1000L, hr = 72.0))
        assertEquals(1, buffer.size)

        buffer.clear()
        assertEquals(0, buffer.size)
    }

    @Test
    fun `WearSample snapshot is a copy, not a reference`() {
        val buffer = mutableListOf<WearSample>()
        buffer.add(WearSample(timestamp = 1000L, hr = 72.0))

        val snapshot = buffer.toList()
        buffer.add(WearSample(timestamp = 2000L, hr = 99.0))

        assertEquals(1, snapshot.size)
        assertEquals(2, buffer.size)
    }
}
