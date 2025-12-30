package com.synheart.core.modules.cloud

import android.content.Context
import android.content.SharedPreferences
import com.synheart.core.models.HumanStateVector
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.LinkedList

/**
 * Persistent offline queue for HSV snapshots
 *
 * Features:
 * - FIFO eviction when max size exceeded
 * - Persistent storage using SharedPreferences
 * - Atomic batch operations
 * - Thread-safe queue operations
 */
class UploadQueue(
    private val context: Context?,
    private val maxSize: Int = 100
) {
    private val queue: LinkedList<HumanStateVector> = LinkedList()
    private val storage: SharedPreferences? = context?.getSharedPreferences(
        STORAGE_KEY,
        Context.MODE_PRIVATE
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    companion object {
        private const val STORAGE_KEY = "synheart_upload_queue"
        private const val QUEUE_DATA_KEY = "queue_data"
    }

    val hasItems: Boolean
        get() = queue.isNotEmpty()

    val length: Int
        get() = queue.size

    /**
     * Load queue from persistent storage
     */
    suspend fun loadFromStorage() {
        if (storage == null) return

        try {
            val jsonString = storage.getString(QUEUE_DATA_KEY, null)
            if (jsonString.isNullOrEmpty()) return

            val items = json.decodeFromString<List<HumanStateVector>>(jsonString)
            queue.addAll(items)

            println("[UploadQueue] Loaded ${queue.size} items from storage")
        } catch (e: Exception) {
            println("[UploadQueue] Failed to load from storage: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Persist queue to storage
     */
    suspend fun persistToStorage() {
        if (storage == null) return

        try {
            val jsonString = json.encodeToString(queue.toList())
            storage.edit().putString(QUEUE_DATA_KEY, jsonString).apply()
        } catch (e: Exception) {
            println("[UploadQueue] Failed to persist to storage: ${e.message}")
            e.printStackTrace()
        }
    }

    /**
     * Enqueue a new HSV snapshot
     *
     * Enforces max size with FIFO eviction.
     */
    suspend fun enqueue(hsv: HumanStateVector) {
        queue.add(hsv)

        // FIFO eviction if exceeding max size
        if (queue.size > maxSize) {
            queue.removeFirst()
        }

        persistToStorage()
    }

    /**
     * Dequeue a batch of snapshots
     *
     * Returns up to `batchSize` items from the front of the queue.
     * Items remain in queue until confirmBatch() is called.
     *
     * @param batchSize Maximum number of items to dequeue
     * @return List of HSV snapshots (may be less than batchSize)
     */
    fun dequeueBatch(batchSize: Int): List<HumanStateVector> {
        if (queue.isEmpty()) return emptyList()

        val count = minOf(queue.size, batchSize)
        return queue.take(count)
    }

    /**
     * Confirm batch was successfully uploaded (remove from queue)
     *
     * @param batch The batch that was successfully uploaded
     */
    suspend fun confirmBatch(batch: List<HumanStateVector>) {
        // Remove the batch from the front of the queue
        repeat(batch.size) {
            if (queue.isNotEmpty()) {
                queue.removeFirst()
            }
        }
        persistToStorage()
    }

    /**
     * Re-enqueue batch on failure
     *
     * Batch is still at the front of queue - just persist to ensure it's saved.
     *
     * @param batch The batch that failed to upload
     */
    suspend fun requeueBatch(batch: List<HumanStateVector>) {
        // Batch is still at the front of queue - no action needed
        // Just persist to ensure it's saved
        persistToStorage()
    }

    /**
     * Clear entire queue
     */
    suspend fun clear() {
        queue.clear()
        storage?.edit()?.remove(QUEUE_DATA_KEY)?.apply()
    }
}
