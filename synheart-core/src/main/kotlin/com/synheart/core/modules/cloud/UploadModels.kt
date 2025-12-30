package com.synheart.core.modules.cloud

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName
import kotlinx.serialization.json.JsonObject

/**
 * Subject information for upload payload
 */
@Serializable
data class Subject(
    @SerialName("subject_type")
    val subjectType: String,

    @SerialName("subject_id")
    val subjectId: String
)

/**
 * Upload request payload
 *
 * Contains subject info and HSI 1.0 snapshots.
 */
@Serializable
data class UploadRequest(
    val subject: Subject,
    val snapshots: List<JsonObject>
)

/**
 * Successful upload response
 */
@Serializable
data class UploadResponse(
    val status: String,

    @SerialName("snapshot_id")
    val snapshotId: String? = null,

    val timestamp: Long
)

/**
 * Error response from upload endpoint
 */
@Serializable
data class UploadErrorResponse(
    val status: String,
    val code: String,
    val message: String,

    @SerialName("retry_after")
    val retryAfter: Int? = null  // For 429 responses
)
