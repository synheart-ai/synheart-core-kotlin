package ai.synheart.core.sync

import org.json.JSONObject

/** A cross-device sync operation with its own native prerequisites. */
enum class SyncOperation {
    CREATE_SPACE,
    JOIN_SPACE,
    RECOVER_SPACE,
    GENERATE_PAIRING,
    SYNC_NOW,
    LEAVE_SPACE,
    LIST_DEVICES,
    REVOKE_DEVICE,
    DELETE_SPACE,
    CLEAR_LOCAL_SPACE,
}

/** Stable reason why a sync operation can or cannot run. */
enum class SyncReadinessCode {
    READY,
    FEATURE_NOT_ACTIVATED,
    CLOUD_CONSENT_REQUIRED,
    CAPABILITY_NOT_ALLOWED,
    NATIVE_RUNTIME_UNAVAILABLE,
    CONFIGURATION_MISSING,
    STORAGE_UNAVAILABLE,
    DEVICE_REVOKED,
    DEVICE_REGISTRATION_REQUIRED,
    ACTIVE_SPACE_REQUIRED,
    SRK_UNAVAILABLE,
}

/**
 * Operation-specific sync readiness composed from SDK authorization and the
 * native runtime readiness snapshot.
 */
data class SyncReadiness(
    val operation: SyncOperation,
    val isReady: Boolean,
    val code: SyncReadinessCode,
    /** Primary state reported by `synheart_core_sync_readiness`, when present. */
    val nativeState: String? = null,
    /** Raw snapshot retained for diagnostics and forward-compatible host UIs. */
    val nativeSnapshot: JSONObject? = null,
) {
    companion object {
        /**
         * Compose readiness without consulting collection-session state.
         *
         * Create, Join and Recover bootstrap the native sync engine
         * themselves, so they do not require an initialized engine, an active
         * space, or a loaded SRK. Pairing and Sync require both a space and an
         * SRK. Management operations need a space but no artifact-encryption
         * key material.
         */
        fun evaluate(
            operation: SyncOperation,
            activated: Boolean,
            cloudConsentGranted: Boolean,
            capabilityAllowed: Boolean,
            nativeSnapshot: JSONObject?,
        ): SyncReadiness {
            fun blocked(code: SyncReadinessCode) = SyncReadiness(
                operation = operation,
                isReady = false,
                code = code,
                nativeState = nativeSnapshot?.opt("state")?.toString(),
                nativeSnapshot = nativeSnapshot,
            )

            fun ready() = SyncReadiness(
                operation = operation,
                isReady = true,
                code = SyncReadinessCode.READY,
                nativeState = nativeSnapshot?.opt("state")?.toString(),
                nativeSnapshot = nativeSnapshot,
            )

            // Clearing local sync state is a recovery/escape hatch. It performs
            // no network request and must remain available after consent is
            // withdrawn, capability changes, registration loss, or device
            // revocation.
            if (operation == SyncOperation.CLEAR_LOCAL_SPACE) {
                if (nativeSnapshot == null) {
                    return blocked(SyncReadinessCode.NATIVE_RUNTIME_UNAVAILABLE)
                }
                if (!nativeSnapshot.optBoolean("configured", false)) {
                    return blocked(SyncReadinessCode.CONFIGURATION_MISSING)
                }
                if (!nativeSnapshot.optBoolean("storage_present", false)) {
                    return blocked(SyncReadinessCode.STORAGE_UNAVAILABLE)
                }
                return ready()
            }

            if (!activated) return blocked(SyncReadinessCode.FEATURE_NOT_ACTIVATED)
            if (!cloudConsentGranted) return blocked(SyncReadinessCode.CLOUD_CONSENT_REQUIRED)
            if (!capabilityAllowed) return blocked(SyncReadinessCode.CAPABILITY_NOT_ALLOWED)
            if (nativeSnapshot == null) {
                return blocked(SyncReadinessCode.NATIVE_RUNTIME_UNAVAILABLE)
            }
            if (!nativeSnapshot.optBoolean("configured", false)) {
                return blocked(SyncReadinessCode.CONFIGURATION_MISSING)
            }
            if (!nativeSnapshot.optBoolean("storage_present", false)) {
                return blocked(SyncReadinessCode.STORAGE_UNAVAILABLE)
            }
            if (nativeSnapshot.optBoolean("device_revoked", false)) {
                return blocked(SyncReadinessCode.DEVICE_REVOKED)
            }
            if (!nativeSnapshot.optBoolean("device_registered", false)) {
                return blocked(SyncReadinessCode.DEVICE_REGISTRATION_REQUIRED)
            }

            val requiresActiveSpace = when (operation) {
                SyncOperation.CREATE_SPACE,
                SyncOperation.JOIN_SPACE,
                SyncOperation.RECOVER_SPACE,
                -> false
                else -> true
            }
            if (requiresActiveSpace && !nativeSnapshot.optBoolean("active_space", false)) {
                return blocked(SyncReadinessCode.ACTIVE_SPACE_REQUIRED)
            }

            val requiresSrk = when (operation) {
                SyncOperation.GENERATE_PAIRING, SyncOperation.SYNC_NOW -> true
                else -> false
            }
            if (requiresSrk && !nativeSnapshot.optBoolean("srk_ready", false)) {
                return blocked(SyncReadinessCode.SRK_UNAVAILABLE)
            }

            return ready()
        }
    }
}
