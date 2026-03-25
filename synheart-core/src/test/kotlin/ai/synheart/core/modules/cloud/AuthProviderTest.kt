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
    fun `CloudConfig requires at least one of hmacSecret or authProvider`() {
        // Valid: hmacSecret only
        val config1 = CloudConfig(
            tenantId = "test",
            hmacSecret = "secret",
            subjectId = "user"
        )
        assertNotNull(config1)

        // Valid: authProvider only
        val config2 = CloudConfig(
            tenantId = "test",
            authProvider = MockAuthProvider(),
            subjectId = "user"
        )
        assertNotNull(config2)

        // Invalid: neither
        try {
            CloudConfig(
                tenantId = "test",
                subjectId = "user"
            )
            fail("Expected IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected
        }
    }

    @Test
    fun `CloudConfig stores authProvider and nullable hmacSecret`() {
        val provider = MockAuthProvider()
        val config = CloudConfig(
            tenantId = "test",
            authProvider = provider,
            subjectId = "user"
        )
        assertNotNull(config.authProvider)
        assertNull(config.hmacSecret)
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

    @Test
    fun `CloudConfig with both hmacSecret and authProvider is valid`() {
        val config = CloudConfig(
            tenantId = "test",
            hmacSecret = "secret",
            authProvider = MockAuthProvider(),
            subjectId = "user"
        )
        assertNotNull(config.hmacSecret)
        assertNotNull(config.authProvider)
    }
}
