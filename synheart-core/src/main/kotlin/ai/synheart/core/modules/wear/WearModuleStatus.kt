package ai.synheart.core.modules.wear

/**
 * Status events emitted by [WearModule] to surface initialization, permission,
 * and streaming outcomes that would otherwise be silent.
 *
 * Callers that don't subscribe to the status stream are unaffected — this is
 * purely additive.
 */
enum class WearModuleStatusType {
    /** A wear source initialized successfully. */
    SOURCE_INITIALIZED,

    /** A wear source failed to initialize (non-permission error). */
    SOURCE_INIT_FAILED,

    /** Permissions were denied or missing for a wear source. */
    PERMISSION_DENIED,

    /** An error occurred on the sample stream from a wear source. */
    STREAMING_ERROR,

    /** Data collection started successfully. */
    DATA_COLLECTION_STARTED,

    /** Data collection stopped (consent revoked or explicit stop). */
    DATA_COLLECTION_STOPPED,

    /** The consent stream encountered an error. */
    CONSENT_STREAM_ERROR,
}

data class WearModuleStatus(
    val type: WearModuleStatusType,
    /** Optional source identifier (e.g. `"healthConnect"`, `"mock"`). */
    val source: String? = null,
    /** The error, if this is a failure status. */
    val error: Throwable? = null,
    /** Human-readable message for logging / debugging. */
    val message: String? = null,
) {
    val isError: Boolean
        get() = type == WearModuleStatusType.SOURCE_INIT_FAILED ||
            type == WearModuleStatusType.PERMISSION_DENIED ||
            type == WearModuleStatusType.STREAMING_ERROR ||
            type == WearModuleStatusType.CONSENT_STREAM_ERROR

    override fun toString(): String = buildString {
        append("WearModuleStatus(").append(type)
        source?.let { append(", source: ").append(it) }
        message?.let { append(", ").append(it) }
        error?.let { append(", error: ").append(it) }
        append(")")
    }
}
