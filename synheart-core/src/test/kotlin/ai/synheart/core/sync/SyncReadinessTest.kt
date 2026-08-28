package ai.synheart.core.sync

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyncReadinessTest {

    private fun snapshot(
        state: String = "READY",
        configured: Boolean = true,
        storagePresent: Boolean = true,
        deviceRegistered: Boolean = true,
        deviceRevoked: Boolean = false,
        engineReady: Boolean = true,
        activeSpace: Boolean = true,
        srkReady: Boolean = true,
    ): JSONObject = JSONObject()
        .put("state", state)
        .put("configured", configured)
        .put("storage_present", storagePresent)
        .put("device_registered", deviceRegistered)
        .put("device_revoked", deviceRevoked)
        .put("engine_ready", engineReady)
        .put("active_space", activeSpace)
        .put("srk_ready", srkReady)

    private fun evaluate(
        operation: SyncOperation,
        activated: Boolean = true,
        consent: Boolean = true,
        capability: Boolean = true,
        native: JSONObject? = null,
    ) = SyncReadiness.evaluate(
        operation = operation,
        activated = activated,
        cloudConsentGranted = consent,
        capabilityAllowed = capability,
        nativeSnapshot = native ?: snapshot(),
    )

    // ── bootstrap operations ─────────────────────────────────────────────

    @Test
    fun `create space does not require a running collection session`() {
        // Collection-session state is deliberately not an input. A fresh app
        // can create a space while the native snapshot reports every bootstrap
        // signal as absent.
        val result = evaluate(
            SyncOperation.CREATE_SPACE,
            native = snapshot(
                state = "ENGINE_NOT_INITIALIZED",
                engineReady = false,
                activeSpace = false,
                srkReady = false,
            ),
        )
        assertTrue(result.isReady)
        assertEquals(SyncReadinessCode.READY, result.code)
        assertEquals("ENGINE_NOT_INITIALIZED", result.nativeState)
    }

    @Test
    fun `create space ignores no-active-space and missing-SRK gates`() {
        val result = evaluate(
            SyncOperation.CREATE_SPACE,
            native = snapshot(state = "NO_ACTIVE_SPACE", activeSpace = false, srkReady = false),
        )
        assertTrue(result.isReady)
    }

    @Test
    fun `join and recover also bootstrap the engine themselves`() {
        for (op in listOf(SyncOperation.JOIN_SPACE, SyncOperation.RECOVER_SPACE)) {
            val result = evaluate(op, native = snapshot(activeSpace = false, srkReady = false))
            assertTrue("$op should be ready", result.isReady)
        }
    }

    // ── common native prerequisites ──────────────────────────────────────

    @Test
    fun `configuration is required`() {
        val result = evaluate(SyncOperation.CREATE_SPACE, native = snapshot(configured = false))
        assertEquals(SyncReadinessCode.CONFIGURATION_MISSING, result.code)
        assertFalse(result.isReady)
    }

    @Test
    fun `storage is required`() {
        val result = evaluate(SyncOperation.CREATE_SPACE, native = snapshot(storagePresent = false))
        assertEquals(SyncReadinessCode.STORAGE_UNAVAILABLE, result.code)
    }

    @Test
    fun `a revoked device blocks everything except clearing local state`() {
        val result = evaluate(SyncOperation.SYNC_NOW, native = snapshot(deviceRevoked = true))
        assertEquals(SyncReadinessCode.DEVICE_REVOKED, result.code)
    }

    @Test
    fun `registration is required`() {
        val result = evaluate(SyncOperation.SYNC_NOW, native = snapshot(deviceRegistered = false))
        assertEquals(SyncReadinessCode.DEVICE_REGISTRATION_REQUIRED, result.code)
    }

    @Test
    fun `an absent native snapshot blocks with a runtime-unavailable code`() {
        val result = SyncReadiness.evaluate(
            operation = SyncOperation.SYNC_NOW,
            activated = true,
            cloudConsentGranted = true,
            capabilityAllowed = true,
            nativeSnapshot = null,
        )
        assertEquals(SyncReadinessCode.NATIVE_RUNTIME_UNAVAILABLE, result.code)
    }

    // ── SDK-side authorization ───────────────────────────────────────────

    @Test
    fun `activation consent and capability are each required`() {
        assertEquals(
            SyncReadinessCode.FEATURE_NOT_ACTIVATED,
            evaluate(SyncOperation.SYNC_NOW, activated = false).code,
        )
        assertEquals(
            SyncReadinessCode.CLOUD_CONSENT_REQUIRED,
            evaluate(SyncOperation.SYNC_NOW, consent = false).code,
        )
        assertEquals(
            SyncReadinessCode.CAPABILITY_NOT_ALLOWED,
            evaluate(SyncOperation.SYNC_NOW, capability = false).code,
        )
    }

    // ── per-operation gates ──────────────────────────────────────────────

    @Test
    fun `management operations require an active space but not an SRK`() {
        val ops = listOf(
            SyncOperation.LEAVE_SPACE,
            SyncOperation.LIST_DEVICES,
            SyncOperation.REVOKE_DEVICE,
            SyncOperation.DELETE_SPACE,
        )
        for (op in ops) {
            assertEquals(
                "$op should need a space",
                SyncReadinessCode.ACTIVE_SPACE_REQUIRED,
                evaluate(op, native = snapshot(activeSpace = false)).code,
            )
            assertTrue(
                "$op should not need an SRK",
                evaluate(op, native = snapshot(srkReady = false)).isReady,
            )
        }
    }

    @Test
    fun `pairing and sync require key material`() {
        for (op in listOf(SyncOperation.GENERATE_PAIRING, SyncOperation.SYNC_NOW)) {
            assertEquals(
                "$op should need an SRK",
                SyncReadinessCode.SRK_UNAVAILABLE,
                evaluate(op, native = snapshot(srkReady = false)).code,
            )
        }
    }

    // ── the escape hatch ─────────────────────────────────────────────────

    @Test
    fun `clearing local space survives withdrawn consent and a revoked device`() {
        // A recovery hatch: it performs no network request, so it must stay
        // available after consent withdrawal, capability change, registration
        // loss, or device revocation.
        val result = evaluate(
            SyncOperation.CLEAR_LOCAL_SPACE,
            activated = false,
            consent = false,
            capability = false,
            native = snapshot(deviceRevoked = true, deviceRegistered = false, activeSpace = false),
        )
        assertTrue(result.isReady)
        assertEquals(SyncReadinessCode.READY, result.code)
    }

    @Test
    fun `clearing local space still needs configuration and storage`() {
        assertEquals(
            SyncReadinessCode.CONFIGURATION_MISSING,
            evaluate(SyncOperation.CLEAR_LOCAL_SPACE, native = snapshot(configured = false)).code,
        )
        assertEquals(
            SyncReadinessCode.STORAGE_UNAVAILABLE,
            evaluate(
                SyncOperation.CLEAR_LOCAL_SPACE,
                native = snapshot(storagePresent = false),
            ).code,
        )
    }
}
