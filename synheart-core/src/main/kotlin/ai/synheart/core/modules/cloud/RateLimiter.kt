package ai.synheart.core.modules.cloud

import ai.synheart.core.modules.interfaces.CapabilityProvider
import ai.synheart.core.modules.interfaces.CapabilityLevel
import ai.synheart.core.modules.interfaces.Module

/**
 * Client-side rate limiter for cloud uploads
 *
 * Rate limits are per window type:
 * - micro: 30 seconds
 * - short: 2 minutes
 * - medium: 10 minutes
 * - long: 1 hour
 *
 * Batch sizes vary by capability level:
 * - core: 10
 * - extended: 50
 * - research: 200
 */
class RateLimiter(private val capabilityProvider: CapabilityProvider) {

    private val lastUpload = mutableMapOf<String, Long>()

    companion object {
        // Upload frequency per window type (from CLOUD_PROTOCOL.md)
        private val UPLOAD_INTERVALS = mapOf(
            "micro" to 30_000L,      // 30 seconds
            "short" to 120_000L,     // 2 minutes
            "medium" to 600_000L,    // 10 minutes
            "long" to 3_600_000L     // 1 hour
        )
    }

    /**
     * Get batch size based on capability level
     */
    val batchSize: Int
        get() {
            val level = capabilityProvider.capability(Module.CLOUD)
            return when (level) {
                CapabilityLevel.CORE -> 10
                CapabilityLevel.EXTENDED -> 50
                CapabilityLevel.RESEARCH -> 200
                else -> 10
            }
        }

    /**
     * Check if upload is allowed for this window type
     *
     * @param windowType Window type (micro, short, medium, long)
     * @return true if upload is allowed, false if rate limited
     */
    fun canUpload(windowType: String): Boolean {
        val interval = UPLOAD_INTERVALS[windowType] ?: return true

        val lastUploadTime = lastUpload[windowType] ?: return true

        val now = System.currentTimeMillis()
        val elapsed = now - lastUploadTime

        return elapsed >= interval
    }

    /**
     * Record an upload for rate limiting
     *
     * @param windowType Window type that was uploaded
     * @param batchSize Number of items in the batch (for logging/metrics)
     */
    fun recordUpload(windowType: String, batchSize: Int) {
        lastUpload[windowType] = System.currentTimeMillis()
    }
}
