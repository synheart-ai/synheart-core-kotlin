package ai.synheart.core.config

import org.json.JSONObject

/**
 * Who can resolve a [SynheartNativeError].
 *
 * If the integrating code can fix it without asking the person, the owner is
 * [APP] even when the person sees the effect.
 */
enum class NativeErrorOwner {
    /** The integrating code: call order, arguments, configuration, build. */
    APP,

    /** Only the person can act: sign in, grant consent, re-enter a code. */
    USER,

    /** Connectivity, the OS credential store, device capability. Usually wait. */
    ENVIRONMENT,

    /** The Synheart cloud refused or failed. */
    SERVICE,

    /** A defect in the native runtime. Report it with [SynheartNativeError.errorId]. */
    RUNTIME,

    /** The runtime sent no owner (older runtime) or one this SDK does not know. */
    UNKNOWN;

    companion object {
        fun parse(raw: String?): NativeErrorOwner = when (raw) {
            "app" -> APP
            "user" -> USER
            "environment" -> ENVIRONMENT
            "service" -> SERVICE
            "runtime" -> RUNTIME
            else -> UNKNOWN
        }
    }
}

/**
 * What to do about a [SynheartNativeError]. One closed-set action per failure;
 * [SynheartNativeError.recoveryCall] names the native call that performs it
 * when there is one.
 */
enum class NativeErrorRecovery {
    /** Repeat the same call, after [SynheartNativeError.retryAfterMs] if set. */
    RETRY,

    /** Retry once the device is unlocked. Never wipe data for this. */
    WAIT_FOR_DEVICE_UNLOCK,

    /** Change the call: arguments, order, or which function. */
    FIX_CALL,

    /** Change the configuration passed to `Synheart.initialize`. */
    FIX_CONFIGURATION,

    /** Ask the person to re-enter what they typed. */
    REENTER_INPUT,

    /** Re-authenticate the account. */
    SIGN_IN,

    /** Run device registration. */
    REGISTER_DEVICE,

    /** Refresh the device attestation. */
    REATTEST_DEVICE,

    /** Sign out, then register again. */
    SIGN_OUT_THEN_REGISTER,

    /** Ask for the consent named in [SynheartNativeError.reason], then retry. */
    GRANT_CONSENT,

    /** Start pairing again on the other device. */
    GENERATE_NEW_PAIRING_CODE,

    /** Re-read sync readiness, then join or create a space. */
    REJOIN_SPACE,

    /** Start a session, then retry. */
    START_SESSION_FIRST,

    /** Stop the active session, then retry. */
    END_SESSION_FIRST,

    /** The feature is compiled out of this runtime build. */
    USE_SUPPORTED_BUILD,

    /** Report it, with [SynheartNativeError.errorId] and the diagnostic log. */
    REPORT_BUG,

    /** Nothing changes the outcome; show [SynheartNativeError.message]. */
    NONE,

    /** The runtime sent no recovery (older runtime) or one this SDK does not know. */
    UNKNOWN;

    companion object {
        fun parse(raw: String?): NativeErrorRecovery = when (raw) {
            "retry" -> RETRY
            "wait_for_device_unlock" -> WAIT_FOR_DEVICE_UNLOCK
            "fix_call" -> FIX_CALL
            "fix_configuration" -> FIX_CONFIGURATION
            "reenter_input" -> REENTER_INPUT
            "sign_in" -> SIGN_IN
            "register_device" -> REGISTER_DEVICE
            "reattest_device" -> REATTEST_DEVICE
            "sign_out_then_register" -> SIGN_OUT_THEN_REGISTER
            "grant_consent" -> GRANT_CONSENT
            "generate_new_pairing_code" -> GENERATE_NEW_PAIRING_CODE
            "rejoin_space" -> REJOIN_SPACE
            "start_session_first" -> START_SESSION_FIRST
            "end_session_first" -> END_SESSION_FIRST
            "use_supported_build" -> USE_SUPPORTED_BUILD
            "report_bug" -> REPORT_BUG
            "none" -> NONE
            else -> UNKNOWN
        }
    }
}

/**
 * A structured failure reported by the native runtime.
 *
 * Every runtime failure has this shape, whether it arrived inside a sync
 * envelope (`{"ok": false, "error": {...}}`) or was read back after a call that
 * returned null, `false` or a non-zero code. Branch on [code] and
 * [recoveryKind]; show [message]; log [hint] and [errorId]. Internal detail
 * (server bodies, paths, tokens) never reaches here: it stays in the native
 * diagnostic log, on the line tagged with the same [errorId].
 *
 * Every field beyond [code], [message] and [retryable] is optional in both
 * directions: an older runtime never sends it, and a newer SDK must not
 * assume it.
 */
data class SynheartNativeError(
    /**
     * Stable machine-readable code, e.g. `CONSENT_REQUIRED`, `NETWORK`.
     * Treat an unrecognised value as `UNKNOWN`.
     */
    val code: String,
    /** End-user-safe message. Show it; never parse it. */
    val message: String,
    /**
     * Whether retrying the same call unchanged may succeed.
     *
     * Runtimes before 0.20.0 inverted this in both directions: a permanent
     * rejection reported `true`, so auto-retrying hosts looped on a 403, and a
     * recoverable attestation blip reported `false`. Both are correct from
     * 0.20.0 on (the SDK's minimum), so honouring this field is the right
     * behaviour.
     */
    val retryable: Boolean = false,
    /**
     * Closed-set token narrowing the cause, or null when there is none.
     *
     * On attestation and registration failures: `transient`, `timeout`,
     * `quota`, `unsupported`, `misconfigured`, `server_transient`, `policy`,
     * `unknown`. On `INVALID_ARGUMENT`: `null`, `not_utf8`, `blank`,
     * `malformed`, `out_of_range`, `unrecognized`. On `CONSENT_REQUIRED`: the
     * consent type to grant, e.g. `biosignals`.
     */
    val reason: String? = null,
    /** Suggested backoff before retrying. Null unless [retryable]. */
    val retryAfterMs: Long? = null,
    /**
     * Diagnostics — `phase`, `http_status`, `server_code`, `argument`. Log it;
     * branch on [argument] if you need the parameter, never on the map shape.
     */
    val detail: Map<String, Any?>? = null,
    /** Raw `owner` token; see [ownerKind]. */
    val owner: String? = null,
    /** Raw `recovery` token; see [recoveryKind]. */
    val recovery: String? = null,
    /**
     * The exact native function that performs [recovery], when one does, e.g.
     * `synheart_core_grant_consent`. Agents should read this, not [hint].
     */
    val recoveryCall: String? = null,
    /**
     * One developer-facing sentence on the cause. Log it; it may be reworded in
     * any runtime release, so never branch on it.
     */
    val hint: String? = null,
    /** The native entry point that failed, e.g. `synheart_core_start_session`. */
    val op: String? = null,
    /**
     * Eight hex digits matching the native diagnostic log line for this
     * failure. Put it in bug reports and "report a problem" UI.
     */
    val errorId: String? = null,
    /**
     * `runtime_version` and `abi`, present only when the runtime itself is at
     * fault ([ownerKind] is [NativeErrorOwner.RUNTIME]).
     */
    val report: Map<String, Any?>? = null,
) {
    /** Who can resolve this failure. */
    val ownerKind: NativeErrorOwner get() = NativeErrorOwner.parse(owner)

    /** What to do about it. */
    val recoveryKind: NativeErrorRecovery get() = NativeErrorRecovery.parse(recovery)

    /** On `INVALID_ARGUMENT`: the rejected parameter's name, e.g. `batch_json`. */
    val argument: String? get() = detail?.get("argument") as? String

    /** A defect in the runtime, not in the integrating code. Report it. */
    val isRuntimeBug: Boolean get() = ownerKind == NativeErrorOwner.RUNTIME

    /**
     * The device can never attest (no Play Services, a de-Googled ROM, an
     * emulator). Degrade to local-only and stop asking, including across
     * relaunches.
     */
    val isUnsupported: Boolean get() = reason == "unsupported"

    /** The attestation setup is wrong; a developer has to fix it. */
    val isMisconfigured: Boolean get() = reason == "misconfigured"

    /** The server refused this device permanently. */
    val isPolicyRefusal: Boolean get() = reason == "policy"

    override fun toString(): String {
        // What lands in crash logs: enough to act on without the native log.
        val buf = StringBuilder("SynheartNativeError($code): $message")
        if (reason != null) buf.append(" [reason: $reason]")
        argument?.let { buf.append(" [argument: $it]") }
        if (recovery != null) buf.append(" [recovery: $recovery]")
        if (recoveryCall != null) buf.append(" [call: $recoveryCall]")
        if (op != null) buf.append(" [op: $op]")
        if (errorId != null) buf.append(" [error_id: $errorId]")
        buf.append(" (retryable: $retryable")
        if (retryAfterMs != null) buf.append(", retryAfterMs: $retryAfterMs")
        buf.append(")")
        return buf.toString()
    }

    companion object {
        /** Fallback used when a failure is known but its payload is missing or malformed. */
        fun unknown(): SynheartNativeError = SynheartNativeError(
            code = "UNKNOWN",
            message = "An unexpected error occurred.",
        )

        /**
         * Parse a native error object. Missing or mistyped fields fall back to a
         * safe shape, so a malformed payload never throws here.
         */
        fun fromJson(error: JSONObject): SynheartNativeError {
            fun str(key: String): String? = (error.opt(key) as? String)?.takeIf { it.isNotEmpty() }
            fun map(key: String): Map<String, Any?>? = error.optJSONObject(key)?.let { obj ->
                obj.keys().asSequence().associateWith { obj.opt(it) }
            }
            return SynheartNativeError(
                code = str("code") ?: "UNKNOWN",
                message = str("message") ?: "An unexpected error occurred.",
                retryable = error.opt("retryable") as? Boolean ?: false,
                reason = str("reason"),
                // A JSON number may decode as double across the FFI boundary.
                retryAfterMs = (error.opt("retry_after_ms") as? Number)?.toLong(),
                detail = map("detail"),
                owner = str("owner"),
                recovery = str("recovery"),
                recoveryCall = str("recovery_call"),
                hint = str("hint"),
                op = str("op"),
                errorId = str("error_id"),
                report = map("report"),
            )
        }
    }
}

/**
 * Thrown when the native runtime reports a failure. Carries the structured
 * [error] so the host can render cause-specific copy and decide whether to
 * retry, instead of a generic failure message.
 */
open class SynheartNativeException(val error: SynheartNativeError) : Exception(error.message) {
    val code: String get() = error.code
    val retryable: Boolean get() = error.retryable
    val reason: String? get() = error.reason
    val retryAfterMs: Long? get() = error.retryAfterMs
    val detail: Map<String, Any?>? get() = error.detail
    val owner: NativeErrorOwner get() = error.ownerKind
    val recovery: NativeErrorRecovery get() = error.recoveryKind
    val recoveryCall: String? get() = error.recoveryCall
    val errorId: String? get() = error.errorId

    val isUnsupported: Boolean get() = error.isUnsupported
    val isMisconfigured: Boolean get() = error.isMisconfigured
    val isPolicyRefusal: Boolean get() = error.isPolicyRefusal

    override fun toString(): String = "SynheartNativeException: $error"
}
