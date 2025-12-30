package com.synheart.core.modules.cloud

import com.synheart.core.models.DeviceInfo
import com.synheart.core.models.HumanStateVector
import com.synheart.core.models.MetaState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UploadQueueTest {

    private lateinit var queue: UploadQueue

    @Before
    fun setUp() {
        // Create queue without context (for testing)
        queue = UploadQueue(context = null, maxSize = 3)
    }

    @Test
    fun `enqueue adds items up to max size`() = runTest {
        val hsv1 = createMockHSV(1)
        val hsv2 = createMockHSV(2)
        val hsv3 = createMockHSV(3)

        queue.enqueue(hsv1)
        queue.enqueue(hsv2)
        queue.enqueue(hsv3)

        assertEquals(3, queue.length)
        assertTrue(queue.hasItems)
    }

    @Test
    fun `enqueue evicts oldest when exceeding max size FIFO`() = runTest {
        val hsv1 = createMockHSV(1)
        val hsv2 = createMockHSV(2)
        val hsv3 = createMockHSV(3)
        val hsv4 = createMockHSV(4)

        queue.enqueue(hsv1)
        queue.enqueue(hsv2)
        queue.enqueue(hsv3)
        queue.enqueue(hsv4) // Should evict hsv1

        assertEquals(3, queue.length)

        val batch = queue.dequeueBatch(3)
        assertEquals(2, batch.first().timestamp) // hsv2 should be first now
        assertEquals(4, batch.last().timestamp)  // hsv4 should be last
    }

    @Test
    fun `dequeueBatch returns correct number of items`() = runTest {
        val hsv1 = createMockHSV(1)
        val hsv2 = createMockHSV(2)

        queue.enqueue(hsv1)
        queue.enqueue(hsv2)

        val batch = queue.dequeueBatch(1)
        assertEquals(1, batch.size)
        assertEquals(1, batch.first().timestamp)

        // Queue should still have both items until confirmBatch
        assertEquals(2, queue.length)
    }

    @Test
    fun `dequeueBatch returns all items when batch size exceeds queue length`() = runTest {
        val hsv1 = createMockHSV(1)
        queue.enqueue(hsv1)

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
        val hsv1 = createMockHSV(1)
        val hsv2 = createMockHSV(2)

        queue.enqueue(hsv1)
        queue.enqueue(hsv2)

        val batch = queue.dequeueBatch(1)
        queue.confirmBatch(batch)

        assertEquals(1, queue.length)

        // Next batch should be hsv2
        val nextBatch = queue.dequeueBatch(1)
        assertEquals(2, nextBatch.first().timestamp)
    }

    @Test
    fun `requeueBatch keeps items in queue`() = runTest {
        val hsv1 = createMockHSV(1)
        queue.enqueue(hsv1)

        val batch = queue.dequeueBatch(1)
        queue.requeueBatch(batch)

        // Items should still be in queue
        assertEquals(1, queue.length)

        val nextBatch = queue.dequeueBatch(1)
        assertEquals(1, nextBatch.first().timestamp)
    }

    @Test
    fun `clear removes all items`() = runTest {
        val hsv1 = createMockHSV(1)
        val hsv2 = createMockHSV(2)

        queue.enqueue(hsv1)
        queue.enqueue(hsv2)

        queue.clear()

        assertEquals(0, queue.length)
        assertFalse(queue.hasItems)
    }

    // Helper function to create mock HSV
    private fun createMockHSV(timestamp: Long): HumanStateVector {
        return HumanStateVector(
            timestamp = timestamp,
            meta = MetaState(
                sessionId = "test",
                device = DeviceInfo(
                    platform = "Android",
                    osVersion = "14"
                )
            ),
            heartRate = 70.0f,
            hsiEmbedding = List(64) { 0.0f }
        )
    }
}
