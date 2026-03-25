package ai.synheart.core.crypto

import org.junit.Assert.*
import org.junit.Test

class ArtifactCryptoTest {

    private fun makeTestSMK(): SMK {
        val bytes = ByteArray(32) { it.toByte() }
        return SMK.fromBytes(bytes)
    }

    @Test
    fun `encrypt decrypt round trip`() {
        val smk = makeTestSMK()
        val plaintext = """{"type":"hsi_window","value":42}""".toByteArray(Charsets.UTF_8)

        val encrypted = ArtifactCrypto.encrypt(smk, plaintext)
        assertEquals("aes256gcm", encrypted.encAlg)
        assertTrue(encrypted.sha256.isNotEmpty())
        assertTrue(encrypted.ciphertext.isNotEmpty())

        val decrypted = ArtifactCrypto.decrypt(smk, encrypted.ciphertext)
        assertArrayEquals(plaintext, decrypted)
    }

    @Test(expected = Exception::class)
    fun `wrong key fails decryption`() {
        val smk1 = makeTestSMK()
        val smk2 = SMK.fromBytes(ByteArray(32) { 0xFF.toByte() })

        val encrypted = ArtifactCrypto.encrypt(smk1, "test".toByteArray())
        ArtifactCrypto.decrypt(smk2, encrypted.ciphertext)
    }

    @Test
    fun `SHA-256 consistent for same input`() {
        val smk = makeTestSMK()
        val plaintext = """{"stable":true}""".toByteArray()

        val enc1 = ArtifactCrypto.encrypt(smk, plaintext)
        val enc2 = ArtifactCrypto.encrypt(smk, plaintext)

        // Same plaintext → same SHA-256
        assertEquals(enc1.sha256, enc2.sha256)

        // Different random nonce → different ciphertext
        assertFalse(enc1.ciphertext.contentEquals(enc2.ciphertext))
    }

    @Test(expected = Exception::class)
    fun `truncated ciphertext fails`() {
        val smk = makeTestSMK()
        ArtifactCrypto.decrypt(smk, byteArrayOf(0, 1, 2))
    }
}
