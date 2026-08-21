package ai.synheart.core.sync

import ai.synheart.core.config.SyncNativeException
import ai.synheart.core.config.unwrapSyncEnvelope
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SyncEnvelopeTest {

    @Test
    fun `null passes through as null (legacy failure sentinel)`() {
        assertNull(unwrapSyncEnvelope(null))
    }

    @Test
    fun `legacy bare payload without an ok key is returned unchanged`() {
        val legacy = JSONObject()
            .put("sync_space_id", "space_123")
            .put("recovery_key", "abc")
        assertSame(legacy, unwrapSyncEnvelope(legacy))
    }

    @Test
    fun `success envelope returns the data object`() {
        val env = JSONObject()
            .put("ok", true)
            .put(
                "data",
                JSONObject().put("sync_space_id", "space_123").put("recovery_key", "abc"),
            )
        val data = unwrapSyncEnvelope(env)
        assertNotNull(data)
        assertEquals("space_123", data!!.getString("sync_space_id"))
        assertEquals("abc", data.getString("recovery_key"))
    }

    @Test
    fun `success envelope with missing data yields an empty object`() {
        val data = unwrapSyncEnvelope(JSONObject().put("ok", true))
        assertNotNull(data)
        assertEquals(0, data!!.length())
    }

    @Test
    fun `failure envelope throws a typed exception`() {
        val env = JSONObject().put("ok", false).put(
            "error",
            JSONObject()
                .put("code", "DEVICE_REGISTRATION_REQUIRED")
                .put("message", "Device registration is required.")
                .put("retryable", false),
        )
        try {
            unwrapSyncEnvelope(env)
            fail("expected SyncNativeException")
        } catch (e: SyncNativeException) {
            assertEquals("DEVICE_REGISTRATION_REQUIRED", e.code)
            assertEquals("Device registration is required.", e.message)
            assertFalse(e.retryable)
        }
    }

    @Test
    fun `retryable failure is preserved`() {
        val env = JSONObject().put("ok", false).put(
            "error",
            JSONObject()
                .put("code", "NETWORK")
                .put("message", "Network error.")
                .put("retryable", true),
        )
        try {
            unwrapSyncEnvelope(env)
            fail("expected SyncNativeException")
        } catch (e: SyncNativeException) {
            assertTrue(e.retryable)
        }
    }

    @Test
    fun `malformed failure envelope falls back to UNKNOWN`() {
        try {
            unwrapSyncEnvelope(JSONObject().put("ok", false))
            fail("expected SyncNativeException")
        } catch (e: SyncNativeException) {
            assertEquals("UNKNOWN", e.code)
        }
    }

    @Test
    fun `reason retry_after_ms and detail survive the round trip`() {
        val env = JSONObject().put("ok", false).put(
            "error",
            JSONObject()
                .put("code", "ATTESTATION_FAILED")
                .put("message", "Attestation unavailable.")
                .put("retryable", true)
                .put("reason", "unsupported")
                .put("retry_after_ms", 5000)
                .put("detail", JSONObject().put("phase", "register")),
        )
        try {
            unwrapSyncEnvelope(env)
            fail("expected SyncNativeException")
        } catch (e: SyncNativeException) {
            assertEquals("unsupported", e.reason)
            assertEquals(5000L, e.retryAfterMs)
            assertTrue(e.isUnsupported)
            assertFalse(e.isMisconfigured)
            assertEquals("register", e.detail?.get("phase"))
        }
    }

    @Test
    fun `a retry_after_ms that arrives as a double is still read`() {
        // The envelope crosses an FFI JSON boundary, so an integral value is
        // not guaranteed to arrive as an integer.
        val env = JSONObject().put("ok", false).put(
            "error",
            JSONObject().put("code", "NETWORK").put("retry_after_ms", 1500.0),
        )
        try {
            unwrapSyncEnvelope(env)
            fail("expected SyncNativeException")
        } catch (e: SyncNativeException) {
            assertEquals(1500L, e.retryAfterMs)
        }
    }

    @Test
    fun `readiness snapshot keys stay top-level after unwrap`() {
        val env = JSONObject().put("ok", true).put(
            "data",
            JSONObject()
                .put("state", "DEVICE_REGISTRATION_REQUIRED")
                .put("configured", true)
                .put("storage_present", true)
                .put("device_registered", false),
        )
        val data = unwrapSyncEnvelope(env)!!
        assertEquals("DEVICE_REGISTRATION_REQUIRED", data.getString("state"))
        assertFalse(data.getBoolean("device_registered"))
    }
}

class SyncResultTest {

    @Test
    fun `reads the runtime counts`() {
        val r = SyncResult.fromRuntimeResponse(JSONObject().put("pushed", 3).put("pulled", 7))
        assertEquals(3, r.pushed)
        assertEquals(7, r.pulled)
    }

    @Test
    fun `tolerates counts that arrive as doubles`() {
        val r = SyncResult.fromRuntimeResponse(JSONObject().put("pushed", 2.0).put("pulled", 0.0))
        assertEquals(2, r.pushed)
        assertEquals(0, r.pulled)
    }

    @Test(expected = IllegalStateException::class)
    fun `a null response is an error, not an empty sync`() {
        // Reporting "0 pushed, 0 pulled" here would read as a successful no-op.
        SyncResult.fromRuntimeResponse(null)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `a response missing the counts is an error`() {
        SyncResult.fromRuntimeResponse(JSONObject().put("pushed", 1))
    }
}
