package ai.synheart.core.config

import org.json.JSONObject

/**
 * A structured failure returned by a native sync operation.
 *
 * The native runtime reports sync failures as a consistent envelope
 * (`{"ok": false, "error": {"code", "message", "retryable"}}`) instead of a
 * bare null, so the host keeps the exact failure reason. [code] is a stable,
 * machine-readable string (e.g. `DEVICE_REGISTRATION_REQUIRED`, `NETWORK`);
 * [message] is a safe, human-readable string suitable for display; sensitive
 * internal detail stays in the native diagnostic logs and never reaches here.
 */
data class SyncNativeError(
    /** Stable machine-readable code, e.g. `DEVICE_REGISTRATION_REQUIRED`. */
    val code: String,
    /** Safe, user-facing message from the native layer. */
    val message: String,
    /**
     * Whether retrying the same call unchanged may succeed (transient failure).
     *
     * Runtimes before 0.20.0 inverted this in both directions: a permanent
     * rejection reported `true`, so auto-retrying hosts looped on a 403, and a
     * recoverable attestation blip reported `false`, so hosts gave up on
     * something that would have worked seconds later. Both are correct from
     * 0.20.0 on, so honouring this field is now the right behaviour.
     */
    val retryable: Boolean = false,
    /**
     * Closed-set token narrowing the cause, or null when the runtime gave none
     * (including any runtime older than 0.20.0 — absence is normal, not an
     * error).
     *
     * `transient` · `timeout` · `quota` · `unsupported` · `misconfigured` ·
     * `server_transient` · `policy` · `unknown`.
     *
     * This is the field worth branching on. [code] says a registration failed;
     * `reason` says whether to retry, degrade to local-only permanently, or
     * wake a developer — `unsupported` means stop asking even across
     * relaunches, while `misconfigured` means a human has to fix something.
     */
    val reason: String? = null,
    /** Suggested backoff before retrying. Null unless [retryable]. */
    val retryAfterMs: Long? = null,
    /**
     * Diagnostics only — `phase`, `http_status`, `server_code`. Log it; never
     * branch on it. Its shape is not part of the contract.
     */
    val detail: Map<String, Any?>? = null,
) {
    /**
     * Registration failed because the device could produce no attestation
     * material, and it never will on this hardware — no Play Services, a
     * de-Googled ROM, an emulator.
     *
     * Hosts should degrade to local-only and stop asking, including across
     * relaunches.
     */
    val isUnsupported: Boolean get() = reason == "unsupported"

    /**
     * The attestation setup itself is wrong — not linked in Play Console,
     * wrong cloud project number, callbacks unwired. Retrying cannot fix it;
     * surface it to a developer.
     */
    val isMisconfigured: Boolean get() = reason == "misconfigured"

    /**
     * The server refused this device permanently. Distinct from
     * [isUnsupported]: the device could attest, the server declined.
     */
    val isPolicyRefusal: Boolean get() = reason == "policy"

    override fun toString(): String {
        // `reason` is included deliberately: this string is what lands in crash
        // logs, and without it every attestation failure reads identically.
        val buf = StringBuilder("SyncNativeError($code): $message")
        if (reason != null) buf.append(" [reason: $reason]")
        buf.append(" (retryable: $retryable")
        if (retryAfterMs != null) buf.append(", retryAfterMs: $retryAfterMs")
        buf.append(")")
        return buf.toString()
    }

    companion object {
        /** Fallback used when a failure envelope is present but malformed. */
        fun unknown(): SyncNativeError = SyncNativeError(
            code = "UNKNOWN",
            message = "An unexpected error occurred.",
        )

        /**
         * Parse the `error` object of a native failure envelope. Missing fields
         * fall back to a safe [unknown] shape so a malformed payload never
         * throws here.
         *
         * `reason`, `retry_after_ms` and `detail` are optional in both
         * directions: the runtime omits them rather than sending null, and an
         * older runtime never sends them at all. A new SDK against an old
         * runtime and an old SDK against a new one both work.
         */
        fun fromJson(error: JSONObject): SyncNativeError {
            val code = error.optString("code").takeIf { it.isNotEmpty() } ?: "UNKNOWN"
            val message = error.optString("message").takeIf { it.isNotEmpty() }
                ?: "An unexpected error occurred."
            val reason = error.optString("reason").takeIf { it.isNotEmpty() }
            // Tolerate a JSON number that decoded as double — the envelope
            // crosses an FFI JSON boundary, so an integral value is not
            // guaranteed to arrive as an integer.
            val retryAfterMs = if (error.has("retry_after_ms")) {
                when (val v = error.opt("retry_after_ms")) {
                    is Number -> v.toLong()
                    else -> null
                }
            } else {
                null
            }
            val detail = error.optJSONObject("detail")?.let { obj ->
                obj.keys().asSequence().associateWith { obj.opt(it) }
            }
            return SyncNativeError(
                code = code,
                message = message,
                retryable = error.optBoolean("retryable", false),
                reason = reason,
                retryAfterMs = retryAfterMs,
                detail = detail,
            )
        }
    }
}

/**
 * Thrown by the sync bridge methods when the native layer reports a failure
 * envelope. Carries the structured [error] so the host can render
 * cause-specific copy (and decide whether to offer a retry) instead of a
 * generic "sync engine not ready" message.
 */
class SyncNativeException(val error: SyncNativeError) : Exception(error.message) {
    val code: String get() = error.code
    val retryable: Boolean get() = error.retryable
    val reason: String? get() = error.reason
    val retryAfterMs: Long? get() = error.retryAfterMs
    val detail: Map<String, Any?>? get() = error.detail

    val isUnsupported: Boolean get() = error.isUnsupported
    val isMisconfigured: Boolean get() = error.isMisconfigured
    val isPolicyRefusal: Boolean get() = error.isPolicyRefusal

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
