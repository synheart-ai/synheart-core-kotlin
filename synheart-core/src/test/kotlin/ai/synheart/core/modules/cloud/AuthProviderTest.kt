package ai.synheart.core.modules.cloud

import ai.synheart.core.config.CloudConfig
import ai.synheart.core.modules.interfaces.AuthProvider
import org.junit.Assert.*
import org.junit.Test

/** Mock AuthProvider for testing */
class MockAuthProvider(
    private val headers: Map<String, String> = mapOf("Authorization" to "ECDSA test-sig-123"),
    private val onAuthErrorResult: Boolean = false
) : AuthProvider {
    var onAuthErrorCalled = false
    var signRequestCallCount = 0

    override fun signRequest(method: String, path: String, bodyBytes: ByteArray): Map<String, String> {
        signRequestCallCount++
        return headers
    }

    override fun onAuthError(statusCode: Int, responseHeaders: Map<String, String>): Boolean {
        onAuthErrorCalled = true
        return onAuthErrorResult
    }
}

class AuthProviderTest {

    @Test
    fun `CloudConfig accepts no authProvider (runtime injects ECDSA path)`() {
        val config = CloudConfig(subjectId = "user")
        assertNotNull(config)
        assertNull(config.authProvider)
    }

    @Test
    fun `CloudConfig stores authProvider when set`() {
        val provider = MockAuthProvider()
        val config = CloudConfig(
            authProvider = provider,
            subjectId = "user"
        )
        assertNotNull(config.authProvider)
    }

    @Test
    fun `MockAuthProvider signRequest returns correct headers`() {
        val provider = MockAuthProvider(headers = mapOf(
            "Authorization" to "ECDSA sig-abc",
            "X-Device-Id" to "dev-123"
        ))

        val headers = provider.signRequest("POST", "/v1/ingest/hsi", "{}".toByteArray())

        assertEquals("ECDSA sig-abc", headers["Authorization"])
        assertEquals("dev-123", headers["X-Device-Id"])
        assertEquals(1, provider.signRequestCallCount)
    }

    @Test
    fun `MockAuthProvider onAuthError tracks call and returns configured result`() {
        val provider = MockAuthProvider(onAuthErrorResult = true)

        val handled = provider.onAuthError(401, emptyMap())

        assertTrue(handled)
        assertTrue(provider.onAuthErrorCalled)
    }

}
