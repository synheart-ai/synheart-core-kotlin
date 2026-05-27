package ai.synheart.core.modules.cloud

import ai.synheart.core.SynheartLogger
import ai.synheart.core.modules.interfaces.AuthProvider

/**
 * [AuthProvider] backed by device-identity signing.
 *
 * Wraps a device auth implementation to produce the signed header set
 * (X-App-ID, X-Device-ID, X-Synheart-Signature, etc.) for every
 * outgoing request to cloud and lab ingest services.
 *
 * This is the Kotlin equivalent of the Dart `DeviceAuthProvider` which
 * wraps `SynheartAuth.signRequest`.
 */
class DeviceAuthProvider(
    private val appId: String,
    private val deviceSigner: DeviceSigner? = null
) : AuthProvider {

    /**
     * Sign an outgoing request and return headers to attach.
     *
     * Delegates to [DeviceSigner.signRequest] when available, otherwise
     * produces a minimal header set with just the app ID.
     */
    override fun signRequest(method: String, path: String, bodyBytes: ByteArray): Map<String, String> {
        if (deviceSigner != null) {
            return deviceSigner.signRequest(
                appId = appId,
                method = method,
                path = path,
                bodyBytes = bodyBytes
            )
        }

        // Fallback: minimal headers when no device signer is available
        return mapOf(
            "X-App-ID" to appId,
            "Content-Type" to "application/json"
        )
    }

    /**
     * Called when the server returns a 401 for a request signed by this provider.
     *
     * Handles:
     * - Clock skew correction (via x-server-timestamp header)
     * - Key invalidation (via x-synheart-error header) with automatic rotation
     */
    override fun onAuthError(statusCode: Int, responseHeaders: Map<String, String>): Boolean {
        // Handle clock skew -- server sends its timestamp so we can correct drift.
        val serverTs = responseHeaders["x-server-timestamp"]
        if (serverTs != null) {
            val ts = serverTs.toDoubleOrNull()
            if (ts != null && deviceSigner != null) {
                try {
                    deviceSigner.correctClockSkew(ts)
                    SynheartLogger.log("[DeviceAuth] Clock skew corrected, retrying")
                    return true
                } catch (e: Exception) {
                    SynheartLogger.log("[DeviceAuth] Clock skew correction failed: $e")
                }
            }
        }

        // Handle key invalidation -- rotate and retry.
        val errorCode = responseHeaders["x-synheart-error"]
        if (errorCode == "KEY_INVALIDATED" && deviceSigner != null) {
            try {
                val success = deviceSigner.rotateKey(appId)
                if (success) {
                    SynheartLogger.log("[DeviceAuth] Key rotated, retrying")
                    return true
                }
            } catch (e: Exception) {
                SynheartLogger.log("[DeviceAuth] Key rotation failed: $e")
            }
        }

        return false
    }
}

/**
 * Interface for device-level cryptographic signing.
 *
 * Implementations should wrap platform-specific device auth libraries
 * (e.g., synheart-auth-android) to provide ECDSA or similar signing.
 */
interface DeviceSigner {
    /**
     * Sign a request and return the header map to attach.
     *
     * @param appId Application identifier
     * @param method HTTP method (e.g., "POST")
     * @param path Request path (e.g., "/ingest/v1/hsi")
     * @param bodyBytes Serialized request body
     * @return Header name-value pairs (e.g., X-App-ID, X-Device-ID, X-Synheart-Signature)
     */
    fun signRequest(appId: String, method: String, path: String, bodyBytes: ByteArray): Map<String, String>

    /**
     * Correct local clock skew using the server's timestamp.
     *
     * @param serverTimestamp Server timestamp as epoch seconds (double)
     */
    fun correctClockSkew(serverTimestamp: Double)

    /**
     * Rotate the device signing key.
     *
     * @param appId Application identifier
     * @return true if rotation succeeded
     */
    fun rotateKey(appId: String): Boolean
}
