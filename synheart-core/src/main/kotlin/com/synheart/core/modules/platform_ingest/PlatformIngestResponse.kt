package com.synheart.core.modules.platform_ingest

/**
 * Response from platform ingestion API calls.
 */
data class PlatformIngestResponse(
    val success: Boolean,
    val statusCode: Int,
    val body: Map<String, Any?>? = null,
    val errorMessage: String? = null
) {
    override fun toString(): String {
        val base = "PlatformIngestResponse(success=$success, statusCode=$statusCode"
        return if (errorMessage != null) "$base, error=$errorMessage)" else "$base)"
    }
}
