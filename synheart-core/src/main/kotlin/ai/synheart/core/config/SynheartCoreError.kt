package ai.synheart.core.config

/** Error codes for the Synheart SDK. */
sealed class SynheartCoreError(
    val code: String,
    message: String
) : Exception(message) {

    class NotConfigured(message: String) :
        SynheartCoreError("ERR_NOT_CONFIGURED", message)

    class InvalidMode(message: String) :
        SynheartCoreError("ERR_INVALID_MODE", message)

    class ResearchNotAllowed :
        SynheartCoreError("ERR_RESEARCH_NOT_ALLOWED", "Research mode requires privacy.allowResearch = true")

    class SessionNotFound(sessionId: String) :
        SynheartCoreError("ERR_SESSION_NOT_FOUND", "Session not found: $sessionId")

    class SessionActive :
        SynheartCoreError("ERR_SESSION_ACTIVE", "A session is already active")

    class NoActiveSession :
        SynheartCoreError("ERR_NO_ACTIVE_SESSION", "No active session")

    class StorageDisabled :
        SynheartCoreError("ERR_STORAGE_DISABLED", "Storage is disabled")

    class CryptoKeyUnavailable :
        SynheartCoreError("ERR_CRYPTO_KEY_UNAVAILABLE", "Crypto key is unavailable")

    class ModeForbidsStream(stream: String) :
        SynheartCoreError("ERR_MODE_FORBIDS_STREAM", "Current mode forbids stream: $stream")
}
