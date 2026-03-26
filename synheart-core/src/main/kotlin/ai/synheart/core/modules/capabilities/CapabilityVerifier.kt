package ai.synheart.core.modules.capabilities

import android.util.Base64
import org.json.JSONObject
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/// Verifies capability tokens
class CapabilityVerifier {
    /// Verify the HMAC signature of a capability token
    fun verifySignature(token: CapabilityToken, secret: String): Boolean {
        val message = buildSignatureMessage(token)
        val keySpec = SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256")

        val mac = Mac.getInstance("HmacSHA256")
        mac.init(keySpec)
        val hmacBytes = mac.doFinal(message.toByteArray(Charsets.UTF_8))
        val expectedSignature = Base64.encodeToString(hmacBytes, Base64.NO_WRAP)

        return expectedSignature == token.signature
    }

    /// Check if token is expired
    fun isExpired(token: CapabilityToken): Boolean {
        return token.isExpired
    }

    /// Verify token validity (signature + expiration)
    fun isValid(token: CapabilityToken, secret: String): Boolean {
        return !isExpired(token) && verifySignature(token, secret)
    }

    /// Parse capabilities from token
    fun parse(token: CapabilityToken): SDKCapabilities {
        if (isExpired(token)) {
            throw CapabilityException("Capability token is expired")
        }

        return SDKCapabilities.fromToken(token)
    }

    /// Build the message for HMAC signature
    private fun buildSignatureMessage(token: CapabilityToken): String {
        // Message format: orgId:projectId:environment:capabilities:issuedAt:expiresAt
        val capabilitiesJson = JSONObject(token.capabilities).toString()
        val issuedAtMs = token.issuedAt.toEpochMilli()
        val expiresAtMs = token.expiresAt.toEpochMilli()

        return "${token.orgId}:${token.projectId}:${token.environment}:$capabilitiesJson:$issuedAtMs:$expiresAtMs"
    }
}

/// Exception thrown when capability verification fails
class CapabilityException(
    message: String,
    val code: String? = null
) : Exception("CapabilityException: $message")
