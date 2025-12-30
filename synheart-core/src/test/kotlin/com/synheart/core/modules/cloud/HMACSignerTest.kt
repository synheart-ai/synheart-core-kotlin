package com.synheart.core.modules.cloud

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class HMACSignerTest {

    private lateinit var signer: HMACSigner

    @Before
    fun setUp() {
        signer = HMACSigner(hmacSecret = "test_secret")
    }

    @Test
    fun `generateNonce creates valid format`() {
        val nonce = signer.generateNonce()

        // Nonce format: <unix_timestamp>_<random_hex>
        val regex = Regex("""^\d+_[a-f0-9]{24}$""")
        assertTrue("Nonce should match format: timestamp_randomhex", regex.matches(nonce))

        // Verify timestamp is reasonable (within last minute)
        val parts = nonce.split("_")
        val timestamp = parts[0].toLong()
        val now = System.currentTimeMillis() / 1000
        assertTrue("Timestamp should be recent", Math.abs(now - timestamp) < 60)
    }

    @Test
    fun `generateNonce produces unique nonces`() {
        val nonce1 = signer.generateNonce()
        val nonce2 = signer.generateNonce()

        assertNotEquals("Nonces should be unique", nonce1, nonce2)
    }

    @Test
    fun `computeSignature returns valid SHA256 hex`() {
        val signature = signer.computeSignature(
            method = "POST",
            path = "/v1/ingest/hsi",
            tenantId = "test_tenant",
            timestamp = 1704067200,
            nonce = "1704067200_abc123",
            bodyJson = """{"test":"data"}"""
        )

        // SHA256 hex should be 64 characters
        assertEquals("Signature should be 64 chars (SHA256 hex)", 64, signature.length)

        // Should be valid hex
        val hexRegex = Regex("""^[a-f0-9]{64}$""")
        assertTrue("Signature should be valid hex", hexRegex.matches(signature))
    }

    @Test
    fun `computeSignature is deterministic`() {
        val signature1 = signer.computeSignature(
            method = "POST",
            path = "/v1/ingest/hsi",
            tenantId = "test_tenant",
            timestamp = 1704067200,
            nonce = "1704067200_abc123",
            bodyJson = """{"test":"data"}"""
        )

        val signature2 = signer.computeSignature(
            method = "POST",
            path = "/v1/ingest/hsi",
            tenantId = "test_tenant",
            timestamp = 1704067200,
            nonce = "1704067200_abc123",
            bodyJson = """{"test":"data"}"""
        )

        assertEquals("Same inputs should produce same signature", signature1, signature2)
    }

    @Test
    fun `computeSignature changes with different inputs`() {
        val baseSignature = signer.computeSignature(
            method = "POST",
            path = "/v1/ingest/hsi",
            tenantId = "test_tenant",
            timestamp = 1704067200,
            nonce = "1704067200_abc123",
            bodyJson = """{"test":"data"}"""
        )

        // Different tenant
        val diffTenantSig = signer.computeSignature(
            method = "POST",
            path = "/v1/ingest/hsi",
            tenantId = "different_tenant",
            timestamp = 1704067200,
            nonce = "1704067200_abc123",
            bodyJson = """{"test":"data"}"""
        )

        // Different body
        val diffBodySig = signer.computeSignature(
            method = "POST",
            path = "/v1/ingest/hsi",
            tenantId = "test_tenant",
            timestamp = 1704067200,
            nonce = "1704067200_abc123",
            bodyJson = """{"test":"different"}"""
        )

        assertNotEquals("Different tenant should change signature", baseSignature, diffTenantSig)
        assertNotEquals("Different body should change signature", baseSignature, diffBodySig)
    }
}
