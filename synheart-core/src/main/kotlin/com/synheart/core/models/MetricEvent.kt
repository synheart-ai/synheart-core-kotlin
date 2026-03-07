package com.synheart.core.models

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** An app-level metric event recorded during a session (RFC-CORE-0007 §4). */
data class MetricEvent(
    val name: String,
    val timestampMs: Long,
    val value: Any,
    val tags: Map<String, String>? = null
)

/** Result of a storage usage query. */
data class StorageUsage(
    val totalBytes: Long,
    val bySessionBytes: Map<String, Long>
)

/** Result of an account deletion request. */
data class DeletionRequestResult(
    val status: String,
    val message: String
)

/** Optional filter for listing sessions. */
data class SessionRange(
    val startMs: Long? = null,
    val endMs: Long? = null,
    val mode: String? = null
)

/** Optional filter for querying HSI windows. */
data class WindowRange(
    val startMs: Long? = null,
    val endMs: Long? = null,
    val limit: Int? = null
)
