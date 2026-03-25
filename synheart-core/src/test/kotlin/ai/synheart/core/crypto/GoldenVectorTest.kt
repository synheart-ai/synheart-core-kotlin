package ai.synheart.core.crypto

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Golden vector tests for cross-platform key derivation consistency.
 *
 * These expected hex values were computed from the Kotlin HKDF implementation
 * and are shared with the Swift/iOS test suite via encryption_vectors.json
 * in synheart-core/test/vectors/.
 */
class GoldenVectorTest {

    // -- Inline vectors (mirrors encryption_vectors.json) --------------------

    private val ARTIFACT_URK_HEX =
        "0101010101010101010101010101010101010101010101010101010101010101"
    private val ARTIFACT_ID = "test_artifact_001"
    private val EXPECTED_ARTIFACT_KEY_HEX =
        "38f4cae76ca1a4a75f8300552747adf23a4043685ff3548e98bd523897f062f4"

    private val BEK_SESSION_SECRET = "test_session_secret"
    private val BEK_SUBJECT_ID = "usr_test123"
    private val EXPECTED_BEK_HEX =
        "02ab906478234d2d1db18ab9fefd1d2f9c3a2548700b3c0853c685f8cba20e9b"

    // -- Helpers -------------------------------------------------------------

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it) }

    private fun hexToBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // -- Tests ---------------------------------------------------------------

    @Test
    fun `artifact key derivation matches golden vector`() {
        val urk = hexToBytes(ARTIFACT_URK_HEX)
        val derived = URK.deriveArtifactKey(urk, ARTIFACT_ID)
        assertEquals(
            "Artifact key derivation mismatch — cross-platform HKDF inconsistency",
            EXPECTED_ARTIFACT_KEY_HEX,
            derived.toHex()
        )
    }

    @Test
    fun `BEK derivation matches golden vector`() {
        val derived = URK.deriveBEK(BEK_SESSION_SECRET, BEK_SUBJECT_ID)
        assertEquals(
            "BEK derivation mismatch — cross-platform HKDF inconsistency",
            EXPECTED_BEK_HEX,
            derived.toHex()
        )
    }
}
