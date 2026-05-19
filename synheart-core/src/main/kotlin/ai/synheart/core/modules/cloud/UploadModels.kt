// SPDX-License-Identifier: Apache-2.0
//
// Typed Kotlin models for the snapshot-upload request / response pair.
// Mirrors the Flutter reference at `lib/src/modules/cloud/upload_models.dart`
// and the Swift sibling at `SynheartCore/Modules/Cloud/UploadModels.swift`.
// snake_case wire keys for cross-language byte-equivalent JSON.

package ai.synheart.core.modules.cloud

import org.json.JSONArray
import org.json.JSONObject

/**
 * Per-batch metadata sent alongside snapshots — describes the SDK
 * version / platform / capability tier the snapshots were collected
 * under so the backend can apply the right schema validation.
 */
data class UploadMetadata(
    val sdkVersion: String,
    val platform: String,
    val capabilityLevel: String,
    val orgId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("sdk_version", sdkVersion)
        put("platform", platform)
        put("capability_level", capabilityLevel)
        if (orgId != null) put("org_id", orgId)
    }

    companion object {
        fun fromJson(json: JSONObject): UploadMetadata = UploadMetadata(
            sdkVersion = json.optString("sdk_version", ""),
            platform = json.optString("platform", ""),
            capabilityLevel = json.optString("capability_level", ""),
            orgId = if (json.has("org_id") && !json.isNull("org_id"))
                json.optString("org_id", null) else null,
        )
    }
}

/**
 * One batch upload request.
 *
 * `snapshots` is intentionally `List<Map<String, Any?>>` — snapshot
 * shapes evolve faster than the SDK and the backend validates against
 * its own schema. Hosts that need typed snapshots build them through
 * the artifact / HSI APIs and serialise to maps for this call.
 */
data class UploadRequest(
    val userId: String,
    val metadata: UploadMetadata,
    val snapshots: List<Map<String, Any?>>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("user_id", userId)
        put("metadata", metadata.toJson())
        put("snapshots", JSONArray(snapshots.map { JSONObject(it) }))
    }

    companion object {
        fun fromJson(json: JSONObject): UploadRequest {
            val arr = json.optJSONArray("snapshots") ?: JSONArray()
            val snapshots = mutableListOf<Map<String, Any?>>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                snapshots.add(jsonObjectToMap(obj))
            }
            return UploadRequest(
                userId = json.optString("user_id", ""),
                metadata = UploadMetadata.fromJson(
                    json.optJSONObject("metadata") ?: JSONObject()
                ),
                snapshots = snapshots,
            )
        }
    }
}

/** Successful upload response from the cloud connector. */
data class UploadResponse(
    val success: Boolean? = null,
    val batchId: String? = null,
    val snapshotIds: List<String>? = null,
    val s3Keys: List<String>? = null,
    val message: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        if (success != null) put("success", success)
        if (batchId != null) put("batch_id", batchId)
        if (snapshotIds != null) put("snapshot_ids", JSONArray(snapshotIds))
        if (s3Keys != null) put("s3_keys", JSONArray(s3Keys))
        if (message != null) put("message", message)
    }

    companion object {
        fun fromJson(json: JSONObject): UploadResponse = UploadResponse(
            success = if (json.has("success") && !json.isNull("success"))
                json.optBoolean("success") else null,
            batchId = if (json.has("batch_id") && !json.isNull("batch_id"))
                json.optString("batch_id", null) else null,
            snapshotIds = json.optJSONArray("snapshot_ids")?.let { arr ->
                List(arr.length()) { arr.optString(it, "") }
            },
            s3Keys = json.optJSONArray("s3_keys")?.let { arr ->
                List(arr.length()) { arr.optString(it, "") }
            },
            message = if (json.has("message") && !json.isNull("message"))
                json.optString("message", null) else null,
        )
    }
}

/** Backend error envelope (HTTP 4xx / 5xx body). */
data class UploadErrorResponse(
    val error: UploadErrorDetail? = null,
    val retryAfter: Int? = null,
) {
    val errorCode: String get() = error?.code ?: "unknown"
    val errorMessage: String get() = error?.message ?: "Unknown error"

    fun toJson(): JSONObject = JSONObject().apply {
        if (error != null) put("error", error.toJson())
        if (retryAfter != null) put("retry_after", retryAfter)
    }

    companion object {
        fun fromJson(json: JSONObject): UploadErrorResponse {
            val errObj = json.optJSONObject("error")
            return UploadErrorResponse(
                error = errObj?.let { UploadErrorDetail.fromJson(it) },
                retryAfter = if (json.has("retry_after") && !json.isNull("retry_after"))
                    json.optInt("retry_after") else null,
            )
        }
    }
}

/** Discrete error payload inside [UploadErrorResponse.error]. */
data class UploadErrorDetail(
    val code: String,
    val message: String,
    val details: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("code", code)
        put("message", message)
        if (details != null) put("details", details)
    }

    companion object {
        fun fromJson(json: JSONObject): UploadErrorDetail = UploadErrorDetail(
            code = json.optString("code", ""),
            message = json.optString("message", ""),
            details = if (json.has("details") && !json.isNull("details"))
                json.optString("details", null) else null,
        )
    }
}

// Shallow-map conversion that preserves nested JSONObjects/Arrays as
// nested Maps/Lists — needed because snapshots come through as
// arbitrary nested JSON the backend defines.
private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> {
    val out = mutableMapOf<String, Any?>()
    val keys = obj.keys()
    while (keys.hasNext()) {
        val k = keys.next()
        out[k] = unwrap(obj.opt(k))
    }
    return out
}

private fun jsonArrayToList(arr: JSONArray): List<Any?> {
    val out = mutableListOf<Any?>()
    for (i in 0 until arr.length()) out.add(unwrap(arr.opt(i)))
    return out
}

private fun unwrap(v: Any?): Any? = when (v) {
    JSONObject.NULL, null -> null
    is JSONObject -> jsonObjectToMap(v)
    is JSONArray -> jsonArrayToList(v)
    else -> v
}
