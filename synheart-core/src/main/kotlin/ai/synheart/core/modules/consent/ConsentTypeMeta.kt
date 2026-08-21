package ai.synheart.core.modules.consent

import ai.synheart.core.modules.interfaces.ConsentType

/**
 * Canonical metadata for [ConsentType]. Use these to iterate the SDK's
 * complete set of consent channels instead of hardcoding strings or
 * per-field `when`s — adding a new channel then only requires extending the
 * [ConsentType] enum and the accessors below (plus the underlying state/form
 * fields), not every consumer of consent state.
 */

/**
 * String identifier used by `Synheart.hasConsent` / `grantConsent` /
 * `revokeConsent`, and the camelCase form of consent keys hosts pass into
 * the SDK.
 */
val ConsentType.wireKey: String
    get() = when (this) {
        ConsentType.BIOSIGNALS -> "biosignals"
        ConsentType.BEHAVIOR -> "behavior"
        ConsentType.PHONE_CONTEXT -> "phoneContext"
        ConsentType.CLOUD_UPLOAD -> "cloudUpload"
        ConsentType.FOCUS_ESTIMATION -> "focusEstimation"
        ConsentType.EMOTION_ESTIMATION -> "emotionEstimation"
        ConsentType.SYNI -> "syni"
        ConsentType.VENDOR_SYNC -> "vendorSync"
        ConsentType.RESEARCH -> "research"
    }

/**
 * Default human-readable label. Hosts typically override per-app; this is the
 * safe fallback so a freshly-added channel has *some* label.
 */
val ConsentType.displayName: String
    get() = when (this) {
        ConsentType.BIOSIGNALS -> "Wearable sensors"
        ConsentType.BEHAVIOR -> "Behavior"
        ConsentType.PHONE_CONTEXT -> "Motion & phone context"
        ConsentType.CLOUD_UPLOAD -> "Cloud upload"
        ConsentType.FOCUS_ESTIMATION -> "Focus estimation"
        ConsentType.EMOTION_ESTIMATION -> "Emotion estimation"
        ConsentType.SYNI -> "Syni AI Coach"
        ConsentType.VENDOR_SYNC -> "Integrations"
        ConsentType.RESEARCH -> "Research participation"
    }

/**
 * The snake_case identifier the **native runtime** keys consent on.
 *
 * Distinct from [wireKey], which is the camelCase spelling the SDK's own API
 * and `ConsentSnapshot` use. Crossing the FFI boundary with the camelCase form
 * leaves the runtime unaware of a grant the SDK believes it made.
 */
val ConsentType.runtimeKey: String
    get() = when (this) {
        ConsentType.BIOSIGNALS -> "biosignals"
        ConsentType.BEHAVIOR -> "behavior"
        ConsentType.PHONE_CONTEXT -> "phone_context"
        ConsentType.CLOUD_UPLOAD -> "cloud_upload"
        ConsentType.FOCUS_ESTIMATION -> "focus_estimation"
        ConsentType.EMOTION_ESTIMATION -> "emotion_estimation"
        ConsentType.SYNI -> "syni"
        ConsentType.VENDOR_SYNC -> "vendor_sync"
        ConsentType.RESEARCH -> "research"
    }

/** Read the typed flag for this channel from a [ConsentEffectiveState]. */
fun ConsentType.valueOn(state: ConsentEffectiveState): Boolean = when (this) {
    ConsentType.BIOSIGNALS -> state.biosignals
    ConsentType.BEHAVIOR -> state.behavior
    ConsentType.PHONE_CONTEXT -> state.phoneContext
    ConsentType.CLOUD_UPLOAD -> state.cloudUpload
    ConsentType.SYNI -> state.syni
    ConsentType.VENDOR_SYNC -> state.vendorSync
    ConsentType.RESEARCH -> state.research
    // The runtime's effective state summarises interpretation channels under
    // the categories that feed them; it carries no dedicated flag for either.
    ConsentType.FOCUS_ESTIMATION, ConsentType.EMOTION_ESTIMATION -> false
}

/**
 * Read the typed flag for this channel from a [ConsentForm].
 *
 * `CLOUD_UPLOAD` / `VENDOR_SYNC` / `RESEARCH` map to the form's historical
 * `allowCloud` / `allowVendorSync` / `allowResearch` fields; the asymmetric
 * naming is preserved on the form for backward compatibility.
 */
fun ConsentType.valueOnForm(form: ConsentForm): Boolean = when (this) {
    ConsentType.BIOSIGNALS -> form.biosignals
    ConsentType.BEHAVIOR -> form.behavior
    ConsentType.PHONE_CONTEXT -> form.phoneContext
    ConsentType.CLOUD_UPLOAD -> form.allowCloud
    ConsentType.SYNI -> form.syni
    ConsentType.VENDOR_SYNC -> form.allowVendorSync
    ConsentType.RESEARCH -> form.allowResearch
    // Not represented on the category-level form — see `valueOn`.
    ConsentType.FOCUS_ESTIMATION, ConsentType.EMOTION_ESTIMATION -> false
}

/**
 * Snapshot every channel of a [ConsentEffectiveState] into a map — the
 * canonical iteration shape across SDK consumers.
 */
fun ConsentEffectiveState.toChannelMap(): Map<ConsentType, Boolean> =
    ConsentType.entries.associateWith { it.valueOn(this) }

/** Snapshot a [ConsentForm] into a channel map. */
fun ConsentForm.toChannelMap(): Map<ConsentType, Boolean> =
    ConsentType.entries.associateWith { it.valueOnForm(this) }
