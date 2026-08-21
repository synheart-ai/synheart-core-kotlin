package ai.synheart.core.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BoundedBufferTest {

    @Test
    fun `appends in order until capacity`() {
        val b = BoundedBuffer<Int>(3)
        b.add(1); b.add(2)
        assertEquals(listOf(1, 2), b.snapshot())
        assertEquals(2, b.length)
        assertFalse(b.isSaturated)
    }

    @Test
    fun `evicts the oldest entry once full`() {
        val b = BoundedBuffer<Int>(3)
        (1..5).forEach { b.add(it) }
        assertEquals(listOf(3, 4, 5), b.snapshot())
        assertEquals(3, b.length)
        assertTrue(b.isSaturated)
    }

    @Test
    fun `a non-positive capacity disables the buffer rather than unbounding it`() {
        // A misconfigured cap must never restore unbounded growth.
        for (cap in listOf(0, -1)) {
            val b = BoundedBuffer<Int>(cap)
            (1..100).forEach { b.add(it) }
            assertEquals(0, b.length)
            assertTrue(b.isEmpty())
        }
    }

    @Test
    fun `a snapshot is not mutated by later adds`() {
        val b = BoundedBuffer<Int>(2)
        b.add(1)
        val snap = b.snapshot()
        b.add(2); b.add(3)
        assertEquals(listOf(1), snap)
        assertEquals(listOf(2, 3), b.snapshot())
    }

    @Test
    fun `clear empties the buffer`() {
        val b = BoundedBuffer<Int>(3)
        b.add(1); b.add(2)
        b.clear()
        assertEquals(0, b.length)
        assertTrue(b.isEmpty())
        assertFalse(b.isNotEmpty())
    }
}
