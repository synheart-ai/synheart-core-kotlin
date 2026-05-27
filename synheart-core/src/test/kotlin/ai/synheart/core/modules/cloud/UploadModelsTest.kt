// SPDX-License-Identifier: Apache-2.0

package ai.synheart.core.modules.cloud

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class UploadModelsTest {

    @Test
    fun `UploadMetadata omits org_id when null`() {
        val m = UploadMetadata(sdkVersion = "1.0", platform = "android", capabilityLevel = "free")
        val json = m.toJson()
        assertFalse(json.has("org_id"))
        assertEquals("1.0", json.getString("sdk_version"))
    }

    @Test
    fun `UploadMetadata round-trip preserves orgId`() {
        val orig = UploadMetadata("1.0", "android", "pro", "org_123")
        val rt = UploadMetadata.fromJson(orig.toJson())
        assertEquals(orig, rt)
    }

    @Test
    fun `UploadRequest preserves nested snapshot structure`() {
        val req = UploadRequest(
            userId = "usr_x",
            metadata = UploadMetadata("1.0", "android", "free"),
            snapshots = listOf(
                mapOf("artifact_id" to "abc", "nested" to mapOf("k" to 1)),
                mapOf("artifact_id" to "def"),
            ),
        )
        val rt = UploadRequest.fromJson(req.toJson())
        assertEquals("usr_x", rt.userId)
        assertEquals(2, rt.snapshots.size)
        assertEquals("abc", rt.snapshots[0]["artifact_id"])
        @Suppress("UNCHECKED_CAST")
        val nested = rt.snapshots[0]["nested"] as Map<String, Any?>
        assertEquals(1, nested["k"])
    }

    @Test
    fun `UploadResponse omits all null fields`() {
        val r = UploadResponse(batchId = "b1")
        val json = r.toJson()
        assertEquals(setOf("batch_id"), json.keys().asSequence().toSet())
    }

    @Test
    fun `UploadResponse fromJson parses snapshotIds and s3Keys arrays`() {
        val r = UploadResponse.fromJson(JSONObject("""
            {"success": true, "batch_id": "b1",
             "snapshot_ids": ["s1","s2"], "s3_keys": ["k1","k2"]}
        """.trimIndent()))
        assertEquals(true, r.success)
        assertEquals("b1", r.batchId)
        assertEquals(listOf("s1", "s2"), r.snapshotIds)
        assertEquals(listOf("k1", "k2"), r.s3Keys)
        assertNull(r.message)
    }

    @Test
    fun `UploadErrorResponse derives default code and message when error missing`() {
        val r = UploadErrorResponse.fromJson(JSONObject("{}"))
        assertEquals("unknown", r.errorCode)
        assertEquals("Unknown error", r.errorMessage)
        assertNull(r.retryAfter)
    }

    @Test
    fun `UploadErrorResponse round-trip preserves retryAfter and details`() {
        val orig = UploadErrorResponse(
            error = UploadErrorDetail(
                code = "RATE_LIMITED",
                message = "Too many requests",
                details = "10 / second",
            ),
            retryAfter = 30,
        )
        val rt = UploadErrorResponse.fromJson(orig.toJson())
        assertEquals("RATE_LIMITED", rt.errorCode)
        assertEquals("Too many requests", rt.errorMessage)
        assertEquals("10 / second", rt.error?.details)
        assertEquals(30, rt.retryAfter)
    }

    @Test
    fun `UploadErrorDetail omits details when null`() {
        val d = UploadErrorDetail(code = "X", message = "y")
        val json = d.toJson()
        assertFalse(json.has("details"))
    }
}
