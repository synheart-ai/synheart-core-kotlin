package ai.synheart.core.config

import org.json.JSONObject

/**
 * The name [SynheartNativeError] had when only sync calls reported structured
 * errors. Existing code compiles unchanged.
 */
typealias SyncNativeError = SynheartNativeError

/**
 * Thrown by the sync bridge methods when the native layer reports a failure
 * envelope. A [SynheartNativeException], so one `catch` handles every runtime
 * failure.
 */
class SyncNativeException(error: SynheartNativeError) : SynheartNativeException(error) {
    override fun toString(): String = "SyncNativeException: $error"
}

/**
 * Unwrap the native sync response envelope.
 *
 * The native sync FFI returns a consistent shape so a failure reason is never
 * lost to a bare null:
 *  * success → `{"ok": true, "data": {...}}`  → returns the `data` object
 *  * failure → `{"ok": false, "error": {...}}` → throws [SyncNativeException]
 *
 * Tolerant of two legacy inputs so a lagging vendored native lib still works:
 *  * `null` (old failure sentinel) → returns null
 *  * an object with no `ok` key (old bare payload) → returned unchanged
 *
 * Kept top-level (not a method) so it can be unit-tested without a live handle.
 */
fun unwrapSyncEnvelope(raw: JSONObject?): JSONObject? {
    if (raw == null) return null
    if (!raw.has("ok")) {
        // Legacy bare payload from an older native build — pass through.
        return raw
    }
    if (raw.optBoolean("ok", false)) {
        // Success payloads are always objects; tolerate a missing/non-object `data`.
        return raw.optJSONObject("data") ?: JSONObject()
    }
    val error = raw.optJSONObject("error")
    throw SyncNativeException(
        if (error != null) SyncNativeError.fromJson(error) else SyncNativeError.unknown(),
    )
}
