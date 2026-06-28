package ai.synheart.core.modules.consent

/**
 * Pure decision logic for cloud-consent readiness, factored out of [ai.synheart.core.Synheart]
 * so it can be unit-tested on the JVM without the native runtime or an Android
 * context.
 */
internal object CloudConsentLogic {

    /**
     * True when a loaded consent token's subject (its `user_id` claim) differs
     * from the runtime's current subject. A consent token is subject-scoped:
     * one issued for a previous subject (e.g. a different signed-in account)
     * must be reissued for the current subject before use.
     *
     * Conservative: returns false (not stale) when the current subject is
     * unknown, there is no token subject, or either side is blank — so a stable
     * or legacy token is never needlessly reissued.
     */
    fun isTokenSubjectStale(tokenUserId: String?, currentSubject: String?): Boolean {
        val subject = currentSubject?.trim()
        if (subject.isNullOrEmpty()) return false
        val tokenSub = tokenUserId?.trim()
        if (tokenSub.isNullOrEmpty()) return false
        return tokenSub != subject
    }

    /**
     * Whether a cloud consent token is already usable without reissuing it.
     * Granted + not-soon-to-expire + minted for the current subject.
     */
    fun isReadyWithoutReissue(
        status: String?,
        needsRefresh: Boolean,
        subjectStale: Boolean,
    ): Boolean = status?.trim()?.lowercase() == "granted" && !needsRefresh && !subjectStale

    /**
     * Whether a `submit_form` result represents a successfully issued token.
     * The runtime returns `synced=false, token=null` (without `error`) when the
     * cloud profile fetch or token-issue call failed; treating that as success
     * would silently drop uploads for the session.
     */
    fun submitIssuedToken(synced: Boolean, hasToken: Boolean): Boolean = synced && hasToken
}
