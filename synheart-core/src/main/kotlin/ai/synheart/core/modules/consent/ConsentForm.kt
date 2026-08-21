package ai.synheart.core.modules.consent

import ai.synheart.core.modules.interfaces.ConsentTier
import ai.synheart.core.modules.interfaces.ConsentType
import org.json.JSONObject

/**
 * Editable consent form returned by the native runtime.
 *
 * Mirrors the flat shape the runtime emits from
 * `synheart_core_consent_get_editable_form`.
 *
 * The runtime intentionally surfaces consent at the **category level**
 * (`biosignals`, `phone_context`, `behavior`) rather than exposing per-channel
 * toggles to hosts. Channel-level truth is stored inside the runtime and
 * intersected against the cloud default profile on submit.
 */
data class ConsentForm(
    /**
     * Profile id the runtime resolved this form against. `"offline-default"`
     * when no cloud profile has been cached yet.
     */
    val profileId: String,
    /** Category-level toggle: at least one biosignals channel granted. */
    val biosignals: Boolean,
    /** Category-level toggle: at least one phone-context channel granted. */
    val phoneContext: Boolean,
    /** Category-level toggle: at least one behavior channel granted. */
    val behavior: Boolean,
    /** Processing tier (`local`, `cloud`, `research`). */
    val consentTier: ConsentTier,
    /** Top-level switch: cloud processing permitted. */
    val allowCloud: Boolean,
    /** Top-level switch: research export permitted. */
    val allowResearch: Boolean,
    /** Top-level switch: vendor sync (Whoop/Garmin/etc.) permitted. */
    val allowVendorSync: Boolean,
    /**
     * Feature toggle: on-device + cloud Syni (LLM coach) permitted.
     *
     * The runtime tracks this independently of biosignals / cloud upload;
     * consumers should still gate cloud-Syni on [allowCloud].
     */
    val syni: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("profile_id", profileId)
        put("biosignals", biosignals)
        put("phone_context", phoneContext)
        put("behavior", behavior)
        put("consent_tier", consentTier.name.lowercase())
        put("allow_cloud", allowCloud)
        put("allow_research", allowResearch)
        put("allow_vendor_sync", allowVendorSync)
        put("syni", syni)
    }

    companion object {
        fun fromJson(json: JSONObject): ConsentForm = ConsentForm(
            profileId = json.optString("profile_id"),
            biosignals = json.optBoolean("biosignals", false),
            phoneContext = json.optBoolean("phone_context", false),
            behavior = json.optBoolean("behavior", false),
            consentTier = parseConsentTier(json.optString("consent_tier")),
            allowCloud = json.optBoolean("allow_cloud", false),
            allowResearch = json.optBoolean("allow_research", false),
            allowVendorSync = json.optBoolean("allow_vendor_sync", false),
            syni = json.optBoolean("syni", false),
        )

        /**
         * Build a [ConsentForm] from a channel map — the symmetric inverse of
         * [ConsentForm.toChannelMap].
         *
         * Convenience for hosts that already track consent state as a
         * `Map<ConsentType, Boolean>` (the recommended shape for iterating
         * channels generically). Avoids spelling out every named field at the
         * call site, so adding a new channel doesn't require updating every
         * construction site.
         */
        fun fromChannelMap(
            profileId: String,
            consentTier: ConsentTier,
            channels: Map<ConsentType, Boolean>,
        ): ConsentForm {
            fun get(t: ConsentType) = channels[t] ?: false
            return ConsentForm(
                profileId = profileId,
                biosignals = get(ConsentType.BIOSIGNALS),
                phoneContext = get(ConsentType.PHONE_CONTEXT),
                behavior = get(ConsentType.BEHAVIOR),
                consentTier = consentTier,
                allowCloud = get(ConsentType.CLOUD_UPLOAD),
                allowResearch = get(ConsentType.RESEARCH),
                allowVendorSync = get(ConsentType.VENDOR_SYNC),
                syni = get(ConsentType.SYNI),
            )
        }
    }
}

/** Parse a wire tier string, defaulting to the most restrictive tier. */
fun parseConsentTier(raw: String?): ConsentTier = when (raw?.lowercase()) {
    "cloud" -> ConsentTier.CLOUD
    "research" -> ConsentTier.RESEARCH
    else -> ConsentTier.LOCAL
}
