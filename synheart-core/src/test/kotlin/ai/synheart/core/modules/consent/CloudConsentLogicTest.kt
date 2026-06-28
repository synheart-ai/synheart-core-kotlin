package ai.synheart.core.modules.consent

import org.junit.Assert.*
import org.junit.Test

/**
 * JVM unit tests for [CloudConsentLogic] — the pure cloud-consent decision
 * logic. No native runtime or Android context required.
 */
class CloudConsentLogicTest {

    // ---- isTokenSubjectStale ---------------------------------------------- //

    @Test
    fun `same subject is not stale`() {
        assertFalse(CloudConsentLogic.isTokenSubjectStale("sub_a", "sub_a"))
    }

    @Test
    fun `different subject is stale`() {
        // The account-re-key case: token minted for sub_a, runtime now on sub_b.
        assertTrue(CloudConsentLogic.isTokenSubjectStale("sub_a", "sub_b"))
    }

    @Test
    fun `unknown current subject is never stale`() {
        assertFalse(CloudConsentLogic.isTokenSubjectStale("sub_a", null))
        assertFalse(CloudConsentLogic.isTokenSubjectStale("sub_a", ""))
        assertFalse(CloudConsentLogic.isTokenSubjectStale("sub_a", "   "))
    }

    @Test
    fun `token without a subject claim is never stale`() {
        assertFalse(CloudConsentLogic.isTokenSubjectStale(null, "sub_a"))
        assertFalse(CloudConsentLogic.isTokenSubjectStale("", "sub_a"))
    }

    @Test
    fun `subjects are compared trimmed`() {
        assertFalse(CloudConsentLogic.isTokenSubjectStale(" sub_a ", "sub_a"))
    }

    // ---- isReadyWithoutReissue -------------------------------------------- //

    @Test
    fun `granted fresh matching subject is ready`() {
        assertTrue(
            CloudConsentLogic.isReadyWithoutReissue(
                status = "granted",
                needsRefresh = false,
                subjectStale = false,
            ),
        )
    }

    @Test
    fun `granted but stale subject is not ready`() {
        assertFalse(
            CloudConsentLogic.isReadyWithoutReissue("granted", needsRefresh = false, subjectStale = true),
        )
    }

    @Test
    fun `granted but needs refresh is not ready`() {
        assertFalse(
            CloudConsentLogic.isReadyWithoutReissue("GRANTED", needsRefresh = true, subjectStale = false),
        )
    }

    @Test
    fun `pending is not ready`() {
        assertFalse(
            CloudConsentLogic.isReadyWithoutReissue("pending", needsRefresh = false, subjectStale = false),
        )
        assertFalse(
            CloudConsentLogic.isReadyWithoutReissue(null, needsRefresh = false, subjectStale = false),
        )
    }

    // ---- submitIssuedToken ------------------------------------------------ //

    @Test
    fun `submit issued only when synced and token present`() {
        assertTrue(CloudConsentLogic.submitIssuedToken(synced = true, hasToken = true))
        assertFalse(CloudConsentLogic.submitIssuedToken(synced = true, hasToken = false))
        assertFalse(CloudConsentLogic.submitIssuedToken(synced = false, hasToken = true))
        assertFalse(CloudConsentLogic.submitIssuedToken(synced = false, hasToken = false))
    }
}
