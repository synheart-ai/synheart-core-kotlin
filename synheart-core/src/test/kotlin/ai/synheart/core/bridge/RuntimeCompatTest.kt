package ai.synheart.core.bridge

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeCompatTest {

    @Test
    fun `compare orders dotted numeric versions`() {
        assertTrue(RuntimeCompat.compare("0.31.1", "0.31.0") > 0)
        assertTrue(RuntimeCompat.compare("0.30.1", "0.31.0") < 0)
        assertEquals(0, RuntimeCompat.compare("0.31.1", "0.31.1"))
        assertTrue(RuntimeCompat.compare("1.0.0", "0.99.99") > 0)
    }

    @Test
    fun `compare treats a missing component as zero and ignores suffixes`() {
        assertEquals(0, RuntimeCompat.compare("0.31", "0.31.0"))
        assertEquals(0, RuntimeCompat.compare("0.31.1-rc1", "0.31.1"))
        assertTrue(RuntimeCompat.compare("0.31.10", "0.31.9") > 0)
    }

    @Test
    fun `check is OK at or above the written-against version`() {
        val r = RuntimeCompat.check(JSONObject().put("core_runtime", RuntimeCompat.WRITTEN_AGAINST))
        assertEquals(RuntimeCompatStatus.OK, r.status)
        assertTrue(r.isAcceptable)
    }

    @Test
    fun `check is OLDER between minimum and written-against, still acceptable`() {
        val r = RuntimeCompat.check(JSONObject().put("core_runtime", "0.42.0"))
        assertEquals(RuntimeCompatStatus.OLDER, r.status)
        assertTrue(r.isAcceptable)
        assertTrue(r.message.contains("0.42.0"))
    }

    @Test
    fun `check refuses TOO_OLD below the minimum`() {
        val r = RuntimeCompat.check(JSONObject().put("core_runtime", "0.41.0"))
        assertEquals(RuntimeCompatStatus.TOO_OLD, r.status)
        assertFalse(r.isAcceptable)
    }

    @Test
    fun `check is UNKNOWN without a version, still acceptable`() {
        assertEquals(RuntimeCompatStatus.UNKNOWN, RuntimeCompat.check(null).status)
        assertTrue(RuntimeCompat.check(JSONObject()).isAcceptable)
    }

    @Test
    fun `check refuses a runtime reporting an ABI outside the accepted range`() {
        // 2.x and 3.x are refused too: ABI 4.0 re-signed every sample push.
        for (abi in listOf("0.9", "2.1", "3.5", "5.0")) {
            val r = RuntimeCompat.check(JSONObject().put("core_runtime", "0.42.1").put("abi", abi))
            assertEquals(abi, RuntimeCompatStatus.INCOMPATIBLE_ABI, r.status)
            assertFalse(r.isAcceptable)
            assertTrue(r.message.contains(RuntimeCompat.INSTALL_COMMAND))
        }
    }

    @Test
    fun `check accepts ABI 4`() {
        for (abi in listOf("4.0", "4.3")) {
            val r = RuntimeCompat.check(JSONObject().put("core_runtime", "0.42.1").put("abi", abi))
            assertTrue(abi, r.isAcceptable)
            assertEquals(abi, r.abi)
        }
    }

    @Test
    fun `a malformed ABI falls back to the version checks`() {
        val r = RuntimeCompat.check(JSONObject().put("core_runtime", "0.42.1").put("abi", "two"))
        assertEquals(RuntimeCompatStatus.OK, r.status)
        assertEquals(null, r.abi)
    }

    @Test
    fun `install command pins the written-against release`() {
        assertEquals(
            "synheart install runtime --version ${RuntimeCompat.WRITTEN_AGAINST}",
            RuntimeCompat.INSTALL_COMMAND,
        )
    }

    @Test
    fun `incompatible exception carries the versions and the fix`() {
        val compat = RuntimeCompat.check(JSONObject().put("core_runtime", "0.41.0").put("abi", "2.1"))
        val e = SynheartRuntimeException.incompatible(compat)
        assertEquals(SynheartRuntimeErrorKind.INCOMPATIBLE, e.kind)
        assertEquals("0.41.0", e.runtimeVersion)
        assertEquals("2.1", e.runtimeAbi)
        assertEquals(RuntimeCompat.INSTALL_COMMAND, e.installCommand)
        assertEquals(RuntimeCompat.WRITTEN_AGAINST, e.recommendedVersion)
    }

    @Test
    fun `loader messages classify into a runtime error kind`() {
        assertEquals(
            SynheartRuntimeErrorKind.NOT_INSTALLED,
            SynheartRuntimeException.classifyLoadFailure(
                "Unable to load library 'synheart_core_runtime': Native library not found",
            ),
        )
        assertEquals(
            SynheartRuntimeErrorKind.WRONG_ARCHITECTURE,
            SynheartRuntimeException.classifyLoadFailure("dlopen failed: \"x.so\" is 32-bit instead of 64-bit"),
        )
        assertEquals(
            SynheartRuntimeErrorKind.LOAD_FAILED,
            SynheartRuntimeException.classifyLoadFailure("dlopen failed: bad ELF magic"),
        )
    }
}
