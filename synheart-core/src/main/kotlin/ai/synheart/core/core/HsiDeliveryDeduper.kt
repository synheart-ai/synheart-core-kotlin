package ai.synheart.core.core

import org.json.JSONObject

/**
 * Suppresses HSI windows that reach the SDK more than once.
 *
 * A completed window can arrive by two routes: the native HSI callback, and
 * the return value of a batch ingest. A window completed by a per-event push
 * (`pushWearHr`, `pushVendorHrv`) travels both, so it would otherwise be
 * delivered twice.
 *
 * Identity is `meta.ids.hsi_id` (RFC-IDENTITY-0001) — the same key the runtime
 * uses to deduplicate ingest rows.
 */
class HsiDeliveryDeduper(
    /**
     * Upper bound on remembered ids. Sized to absorb interleaving between the
     * two delivery routes, not to deduplicate across an entire session.
     */
    val capacity: Int = 100,
) {
    /**
     * A `LinkedHashSet`: iteration is insertion-ordered, so the first element
     * is the oldest entry and eviction below is FIFO.
     */
    private val seen = LinkedHashSet<String>()

    /** Ids currently remembered. Exposed for diagnostics and tests. */
    val length: Int get() = seen.size

    /**
     * Whether [hsiJson] should be delivered: true the first time a window is
     * seen, false for any repeat.
     *
     * A payload carrying no `meta.ids.hsi_id` is always delivered — it cannot
     * be identified, and dropping a window is worse than repeating one.
     */
    fun shouldDeliver(hsiJson: String): Boolean {
        val id = extractHsiId(hsiJson) ?: return true
        if (!seen.add(id)) return false
        if (seen.size > capacity) {
            val oldest = seen.iterator()
            if (oldest.hasNext()) {
                oldest.next()
                oldest.remove()
            }
        }
        return true
    }

    /**
     * Forget every id. Call on session start and teardown so an id from a
     * previous session cannot suppress the next session's first window.
     */
    fun reset() = seen.clear()

    companion object {
        /**
         * Pull `meta.ids.hsi_id` out of a raw HSI payload.
         *
         * Null when the payload is unparseable, is not an object, or predates
         * RFC-IDENTITY-0001.
         */
        fun extractHsiId(hsiJson: String): String? = try {
            JSONObject(hsiJson)
                .optJSONObject("meta")
                ?.optJSONObject("ids")
                ?.optString("hsi_id")
                ?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }
}
