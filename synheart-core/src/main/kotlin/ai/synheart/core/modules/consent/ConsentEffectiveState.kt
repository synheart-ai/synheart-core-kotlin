package ai.synheart.core.modules.consent

import org.json.JSONObject

/**
 * Typed view of the runtime's effective consent state.
 *
 * Mirrors the JSON returned by `synheart_core_consent_effective_state` /
 * `synheart_core_current_consent` (the native runtime's
 * `consent_snapshot_summary_json` FFI export).
 *
 * This is the **read-only** shape the SDK exposes to hosts for feature gating
 * and UI hydration. Use [ConsentForm] when constructing input to
 * `consentSubmitFormTyped`.
 */
data class ConsentEffectiveState(
    /** Category-level: at least one biosignals channel is granted. */
    val biosignals: Boolean,
    /** Category-level: at least one phone-context channel is granted. */
    val phoneContext: Boolean,
    /** Category-level: at least one behavior channel is granted. */
    val behavior: Boolean,
    /** Cloud upload is permitted. */
    val cloudUpload: Boolean,
    /** Syni personalization is permitted. */
    val syni: Boolean,
    /** Vendor sync (Whoop/Garmin/etc.) is permitted. */
    val vendorSync: Boolean,
    /** Research export is permitted. */
    val research: Boolean,
    /** Last time the snapshot was updated (Unix ms, from the runtime). */
    val timestampMs: Long,
    /** Snapshot schema version as returned by the runtime. */
    val version: String,
) {
    /**
     * True if any coarse collection is granted. Useful for a quick
     * "has any consent at all" check.
     */
    val hasAnyGrant: Boolean
        get() = biosignals ||
            phoneContext ||
            behavior ||
            cloudUpload ||
            vendorSync ||
            research ||
            syni

    fun toJson(): JSONObject = JSONObject().apply {
        put("biosignals", biosignals)
        put("phone_context", phoneContext)
        put("behavior", behavior)
        put("cloud_upload", cloudUpload)
        put("syni", syni)
        put("vendor_sync", vendorSync)
        put("research", research)
        put("timestamp_ms", timestampMs)
        put("version", version)
    }

    companion object {
        fun fromJson(json: JSONObject): ConsentEffectiveState = ConsentEffectiveState(
            biosignals = json.optBoolean("biosignals", false),
            phoneContext = json.optBoolean("phone_context", false),
            behavior = json.optBoolean("behavior", false),
            cloudUpload = json.optBoolean("cloud_upload", false),
            syni = json.optBoolean("syni", false),
            vendorSync = json.optBoolean("vendor_sync", false),
            research = json.optBoolean("research", false),
            timestampMs = json.optLong("timestamp_ms", 0L),
            version = json.optString("version"),
        )
    }
}
