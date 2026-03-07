package com.synheart.core.auth

import org.junit.Assert.*
import org.junit.Test

class AuthModuleTest {

    @Test
    fun `AuthStatus unauthenticated has correct defaults`() {
        val status = AuthStatus.UNAUTHENTICATED
        assertFalse(status.authenticated)
        assertNull(status.subjectId)
        assertNull(status.provider)
        assertFalse(status.syncReady)
    }

    @Test
    fun `AuthResult holds all fields`() {
        val result = AuthResult(
            subjectId = "usr_123",
            accessToken = "at_abc",
            refreshToken = "rt_xyz",
            sessionSecret = "ss_secret",
            syncReady = false
        )
        assertEquals("usr_123", result.subjectId)
        assertEquals("at_abc", result.accessToken)
        assertEquals("rt_xyz", result.refreshToken)
        assertEquals("ss_secret", result.sessionSecret)
        assertFalse(result.syncReady)
    }

    @Test
    fun `AuthStatus copy updates syncReady`() {
        val status = AuthStatus(
            authenticated = true,
            subjectId = "usr_123",
            provider = "synheart",
            syncReady = false
        )
        val updated = status.copy(syncReady = true)
        assertTrue(updated.syncReady)
        assertEquals("usr_123", updated.subjectId)
        assertTrue(updated.authenticated)
    }

    @Test
    fun `AuthResult with null optional fields`() {
        val result = AuthResult(subjectId = "usr_anon", syncReady = false)
        assertEquals("usr_anon", result.subjectId)
        assertNull(result.accessToken)
        assertNull(result.refreshToken)
        assertNull(result.sessionSecret)
    }
}
