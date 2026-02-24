package com.synheart.core.modules.cloud

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UploadQueueTest {

    private lateinit var queue: UploadQueue

    @Before
    fun setUp() {
        queue = UploadQueue(context = null, maxSize = 3)
    }

    @Test
    fun `enqueue adds items up to max size`() = runTest {
        queue.enqueue(mockHsiJson(1))
        queue.enqueue(mockHsiJson(2))
        queue.enqueue(mockHsiJson(3))

        assertEquals(3, queue.length)
        assertTrue(queue.hasItems)
    }

    @Test
    fun `enqueue evicts oldest when exceeding max size FIFO`() = runTest {
        queue.enqueue(mockHsiJson(1))
        queue.enqueue(mockHsiJson(2))
        queue.enqueue(mockHsiJson(3))
        queue.enqueue(mockHsiJson(4)) // Should evict item 1

        assertEquals(3, queue.length)

        val batch = queue.dequeueBatch(3)
        assertTrue(batch.first().contains("\"ts\":2"))
        assertTrue(batch.last().contains("\"ts\":4"))
    }

    @Test
    fun `dequeueBatch returns correct number of items`() = runTest {
        queue.enqueue(mockHsiJson(1))
        queue.enqueue(mockHsiJson(2))

        val batch = queue.dequeueBatch(1)
        assertEquals(1, batch.size)
        assertTrue(batch.first().contains("\"ts\":1"))

        // Queue should still have both items until confirmBatch
        assertEquals(2, queue.length)
    }

    @Test
    fun `dequeueBatch returns all items when batch size exceeds queue length`() = runTest {
        queue.enqueue(mockHsiJson(1))

        val batch = queue.dequeueBatch(10)
        assertEquals(1, batch.size)
    }

    @Test
    fun `dequeueBatch returns empty list when queue is empty`() {
        val batch = queue.dequeueBatch(5)
        assertTrue(batch.isEmpty())
        assertFalse(queue.hasItems)
    }

    @Test
    fun `confirmBatch removes items from queue`() = runTest {
        queue.enqueue(mockHsiJson(1))
        queue.enqueue(mockHsiJson(2))

        val batch = queue.dequeueBatch(1)
        queue.confirmBatch(batch)

        assertEquals(1, queue.length)

        val nextBatch = queue.dequeueBatch(1)
        assertTrue(nextBatch.first().contains("\"ts\":2"))
    }

    @Test
    fun `requeueBatch keeps items in queue`() = runTest {
        queue.enqueue(mockHsiJson(1))

        val batch = queue.dequeueBatch(1)
        queue.requeueBatch(batch)

        assertEquals(1, queue.length)

        val nextBatch = queue.dequeueBatch(1)
        assertTrue(nextBatch.first().contains("\"ts\":1"))
    }

    @Test
    fun `clear removes all items`() = runTest {
        queue.enqueue(mockHsiJson(1))
        queue.enqueue(mockHsiJson(2))

        queue.clear()

        assertEquals(0, queue.length)
        assertFalse(queue.hasItems)
    }

    private fun mockHsiJson(ts: Int): String {
        return """{"hsi_version":"1.1","ts":$ts,"observed_at_utc":"2024-01-01T00:00:00Z"}"""
    }
}
