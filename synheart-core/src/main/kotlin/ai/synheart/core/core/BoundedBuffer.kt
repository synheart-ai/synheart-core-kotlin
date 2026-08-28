package ai.synheart.core.core

import java.util.ArrayDeque

/**
 * A fixed-capacity FIFO buffer that evicts its oldest entry once full.
 *
 * Backs the in-memory session history behind `Synheart.getSessionHsiWindows()`
 * and `Synheart.getSessionWearSamples()`, bounding what a long-running session
 * can retain.
 *
 * Eviction is silent by design: the durable record lives in the native
 * runtime's storage and is read back through `Synheart.getHSIWindows()`.
 *
 * Not thread-safe on its own; callers synchronize (the SDK guards it behind
 * the session lock).
 */
class BoundedBuffer<T>(
    /**
     * Maximum entries retained; adding beyond this evicts from the front.
     *
     * Zero or less disables the buffer rather than unbounding it, so a
     * misconfigured cap can never restore unbounded growth.
     */
    val capacity: Int,
) {
    private val items = ArrayDeque<T>()

    /** Append [value], evicting the oldest entries if that exceeds [capacity]. */
    fun add(value: T) {
        if (capacity <= 0) return
        items.addLast(value)
        while (items.size > capacity) {
            items.removeFirst()
        }
    }

    fun clear() = items.clear()

    val length: Int get() = items.size

    fun isEmpty(): Boolean = items.isEmpty()

    fun isNotEmpty(): Boolean = items.isNotEmpty()

    /** True once [capacity] is reached and further [add]s start evicting. */
    val isSaturated: Boolean get() = items.size >= capacity

    /**
     * An immutable oldest-first snapshot. Safe to hand to callers — later
     * [add]s do not mutate a snapshot already returned.
     */
    fun snapshot(): List<T> = items.toList()
}
