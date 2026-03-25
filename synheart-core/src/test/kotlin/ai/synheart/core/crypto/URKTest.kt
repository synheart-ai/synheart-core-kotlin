package ai.synheart.core.crypto

import org.junit.Assert.*
import org.junit.Test

class URKTest {

    @Test
    fun `generates 32-byte key`() {
        val urk = URK.generate()
        assertEquals(32, urk.bytes.size)
    }

    @Test
    fun `fromBytes validates length`() {
        try {
            URK.fromBytes(ByteArray(16))
            fail("Expected exception for wrong key length")
        } catch (e: IllegalArgumentException) {
            // expected
        }

        val urk = URK.fromBytes(ByteArray(32))
        assertEquals(32, urk.bytes.size)
    }

    @Test
    fun `generates unique keys`() {
        val a = URK.generate()
        val b = URK.generate()
        assertFalse(a.bytes.contentEquals(b.bytes))
    }

    @Test
    fun `derives BEK deterministically`() {
        val bek1 = URK.deriveBEK("test_secret", "usr_123")
        val bek2 = URK.deriveBEK("test_secret", "usr_123")
        assertArrayEquals(bek1, bek2)
        assertEquals(32, bek1.size)
    }

    @Test
    fun `different subjects produce different BEKs`() {
        val bek1 = URK.deriveBEK("test_secret", "usr_123")
        val bek2 = URK.deriveBEK("test_secret", "usr_456")
        assertFalse(bek1.contentEquals(bek2))
    }

    @Test
    fun `derives artifact key deterministically`() {
        val urk = ByteArray(32) { it.toByte() }
        val key1 = URK.deriveArtifactKey(urk, "art_123")
        val key2 = URK.deriveArtifactKey(urk, "art_123")
        assertArrayEquals(key1, key2)
        assertEquals(32, key1.size)
    }

    @Test
    fun `different artifact IDs produce different keys`() {
        val urk = ByteArray(32) { it.toByte() }
        val key1 = URK.deriveArtifactKey(urk, "art_123")
        val key2 = URK.deriveArtifactKey(urk, "art_456")
        assertFalse(key1.contentEquals(key2))
    }

    @Test
    fun `encrypts and decrypts bundle round-trip`() {
        val urk = URK.generate()
        val bundle = URK.encryptBundle(urk.bytes, "my_secret", "usr_abc")

        assertEquals("1", bundle["urk_bundle_version"])
        assertTrue(bundle["ciphertext_b64"]!!.isNotEmpty())
        assertTrue(bundle["nonce_b64"]!!.isNotEmpty())
        assertEquals("synheart-urk-bundle:v1", bundle["kdf_info"])

        val decrypted = URK.decryptBundle(bundle, "my_secret", "usr_abc")
        assertArrayEquals(urk.bytes, decrypted.bytes)
    }

    @Test
    fun `wrong secret fails to decrypt bundle`() {
        val urk = URK.generate()
        val bundle = URK.encryptBundle(urk.bytes, "correct_secret", "usr_abc")

        try {
            URK.decryptBundle(bundle, "wrong_secret", "usr_abc")
            fail("Expected exception for wrong secret")
        } catch (_: Exception) {
            // expected — AES-GCM auth tag verification fails
        }
    }
}
