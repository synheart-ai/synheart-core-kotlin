package ai.synheart.core.modules.cloud

import android.content.Context
import android.content.SharedPreferences
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.LinkedList
import ai.synheart.core.SynheartLogger

/**
 * Persistent offline queue for HSI JSON snapshots.
 *
 * Stores raw HSI JSON strings produced by synheart-runtime.
 *
 * Features:
 * - FIFO eviction when max size exceeded
 * - Persistent storage using SharedPreferences
 * - Atomic batch operations
 */
class UploadQueue(
    private val context: Context?,
    private val maxSize: Int = 100
) {
    private val queue: LinkedList<String> = LinkedList()
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

    /** Load queue from persistent storage. */
    suspend fun loadFromStorage() {
        if (storage == null) return

        try {
            val jsonString = storage.getString(QUEUE_DATA_KEY, null)
            if (jsonString.isNullOrEmpty()) return

            val items = json.decodeFromString<List<String>>(jsonString)
            queue.addAll(items)

            SynheartLogger.log("[UploadQueue] Loaded ${queue.size} items from storage")
        } catch (e: Exception) {
            SynheartLogger.log("[UploadQueue] Failed to load from storage: ${e.message}")
        }
    }

    /** Persist queue to storage. */
    suspend fun persistToStorage() {
        if (storage == null) return

        try {
            val jsonString = json.encodeToString(queue.toList())
            storage.edit().putString(QUEUE_DATA_KEY, jsonString).apply()
        } catch (e: Exception) {
            SynheartLogger.log("[UploadQueue] Failed to persist to storage: ${e.message}")
        }
    }

    /** Enqueue a new HSI JSON string. Enforces max size with FIFO eviction. */
    suspend fun enqueue(hsiJson: String) {
        queue.add(hsiJson)
        if (queue.size > maxSize) {
            queue.removeAt(0)
        }
        persistToStorage()
    }

    /**
     * Dequeue a batch of HSI JSON strings.
     * Items remain in queue until [confirmBatch] is called.
     */
    fun dequeueBatch(batchSize: Int): List<String> {
        if (queue.isEmpty()) return emptyList()
        return queue.take(minOf(queue.size, batchSize))
    }

    /** Confirm batch was successfully uploaded (remove from queue). */
    suspend fun confirmBatch(batch: List<String>) {
        repeat(batch.size) {
            if (queue.isNotEmpty()) {
                queue.removeAt(0)
            }
        }
        persistToStorage()
    }

    /** Re-enqueue batch on failure (items are still at front, just persist). */
    suspend fun requeueBatch(batch: List<String>) {
        persistToStorage()
    }

    /** Clear entire queue. */
    suspend fun clear() {
        queue.clear()
        storage?.edit()?.remove(QUEUE_DATA_KEY)?.apply()
    }
}
