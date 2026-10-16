package ai.synheart.core.config

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SynheartNativeErrorTest {

    @Test
    fun `parses every field of a structured runtime error`() {
        val e = SynheartNativeError.fromJson(
            JSONObject(
                """
                {"code":"CONSENT_REQUIRED","message":"Consent needed.","retryable":false,
                 "reason":"biosignals","owner":"user","recovery":"grant_consent",
                 "recovery_call":"synheart_core_grant_consent","hint":"grant it first",
                 "op":"synheart_core_start_session","error_id":"0a1b2c3d",
                 "detail":{"argument":"type_name"},
                 "report":{"runtime_version":"0.38.0","abi":"2.0"}}
                """.trimIndent(),
            ),
        )
        assertEquals("CONSENT_REQUIRED", e.code)
        assertEquals(NativeErrorOwner.USER, e.ownerKind)
        assertEquals(NativeErrorRecovery.GRANT_CONSENT, e.recoveryKind)
        assertEquals("synheart_core_grant_consent", e.recoveryCall)
        assertEquals("synheart_core_start_session", e.op)
        assertEquals("0a1b2c3d", e.errorId)
        assertEquals("type_name", e.argument)
        assertEquals("2.0", e.report?.get("abi"))
        assertFalse(e.isRuntimeBug)
    }

    @Test
    fun `an older runtime's minimal error parses with unknown owner and recovery`() {
        val e = SynheartNativeError.fromJson(JSONObject("""{"code":"NETWORK","message":"Offline.","retryable":true}"""))
        assertTrue(e.retryable)
        assertEquals(NativeErrorOwner.UNKNOWN, e.ownerKind)
        assertEquals(NativeErrorRecovery.UNKNOWN, e.recoveryKind)
        assertNull(e.argument)
    }

    @Test
    fun `a malformed payload falls back to a safe shape`() {
        val e = SynheartNativeError.fromJson(JSONObject("""{"code":7,"retry_after_ms":1500.0}"""))
        assertEquals("UNKNOWN", e.code)
        assertEquals("An unexpected error occurred.", e.message)
        assertEquals(1500L, e.retryAfterMs)
    }

    @Test
    fun `sync failures are structured runtime failures`() {
        val thrown = runCatching {
            unwrapSyncEnvelope(JSONObject("""{"ok":false,"error":{"code":"NETWORK","message":"m"}}"""))
        }.exceptionOrNull()
        assertTrue(thrown is SyncNativeException)
        assertTrue(thrown is SynheartNativeException)
        assertEquals("NETWORK", (thrown as SynheartNativeException).code)
    }
}
