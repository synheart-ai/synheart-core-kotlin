package com.synheart.core.modules.interfaces

/**
 * Interface for pluggable request authentication.
 *
 * Implementations sign outgoing HTTP requests with custom auth schemes
 * (e.g., ECDSA device-identity signing from synheart-auth).
 *
 * The SDK ships with HMAC-SHA256 as the default. When an [AuthProvider]
 * is set on [CloudConfig], it takes precedence over the HMAC path.
 */
interface AuthProvider {
    /**
     * Sign an outgoing request and return headers to attach.
     *
     * The returned map is merged into the HTTP request headers.
     * Typical headers: `Authorization`, `X-Device-Signature`, etc.
     *
     * @param method HTTP method (e.g., "POST")
     * @param path Request path (e.g., "/v1/ingest/hsi")
     * @param bodyBytes Serialized request body
     * @return Header name-value pairs to attach to the request
     * @throws Exception if signing fails (e.g., keystore unavailable)
     */
    fun signRequest(method: String, path: String, bodyBytes: ByteArray): Map<String, String>

    /**
     * Called when the server returns a 401 for a request signed by this provider.
     *
     * Return `true` if the error was handled (e.g., key rotation completed)
     * and the request should be retried. Return `false` to propagate the error.
     *
     * @param statusCode HTTP status code (always 401)
     * @param responseHeaders Response headers from the server
     * @return Whether the error was handled and the request should be retried
     */
    fun onAuthError(statusCode: Int, responseHeaders: Map<String, String>): Boolean
}
