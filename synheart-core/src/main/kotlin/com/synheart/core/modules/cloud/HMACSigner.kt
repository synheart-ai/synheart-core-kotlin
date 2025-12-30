package com.synheart.core.modules.cloud

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.random.Random

/**
 * HMAC-SHA256 signature generator with time-windowed nonces
 *
 * Implements the Synheart Cloud Protocol authentication scheme.
 *
 * Nonce format: `<unix_timestamp>_<random_hex>`
 * Signing string format (newline-separated):
 * ```
 * METHOD
 * PATH
 * TENANT_ID
 * TIMESTAMP
 * NONCE
 * SHA256(body_json)
 * ```
 */
class HMACSigner(private val hmacSecret: String) {

    /**
     * Generate time-windowed nonce
     *
     * Format: `<unix_timestamp>_<random_hex>`
     *
     * Example: `1704067200_a3b5c7d9e1f2`
     */
    fun generateNonce(): String {
        val timestamp = System.currentTimeMillis() / 1000
        val randomBytes = Random.nextBytes(12)
        val randomHex = randomBytes.joinToString("") { "%02x".format(it) }
        return "${timestamp}_$randomHex"
    }

    /**
     * Compute HMAC-SHA256 signature
     *
     * @param method HTTP method (e.g., "POST")
     * @param path Request path (e.g., "/v1/ingest/hsi")
     * @param tenantId Tenant identifier
     * @param timestamp Unix timestamp (seconds)
     * @param nonce Time-windowed nonce
     * @param bodyJson JSON body as string
     * @return Hex-encoded HMAC-SHA256 signature
     */
    fun computeSignature(
        method: String,
        path: String,
        tenantId: String,
        timestamp: Long,
        nonce: String,
        bodyJson: String
    ): String {
        // Compute SHA256 of body
        val bodyHash = sha256(bodyJson)

        // Construct signing string (newline-separated)
        val signingString = listOf(
            method.uppercase(),
            path,
            tenantId,
            timestamp.toString(),
            nonce,
            bodyHash
        ).joinToString("\n")

        // Compute HMAC-SHA256
        val hmac = Mac.getInstance("HmacSHA256")
        val secretKey = SecretKeySpec(hmacSecret.toByteArray(), "HmacSHA256")
        hmac.init(secretKey)

        val digest = hmac.doFinal(signingString.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Compute SHA256 hash of input string
     */
    private fun sha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(input.toByteArray())
        return hash.joinToString("") { "%02x".format(it) }
    }
}
