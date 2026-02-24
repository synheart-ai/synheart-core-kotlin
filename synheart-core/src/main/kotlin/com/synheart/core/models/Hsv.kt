package com.synheart.core.models

/**
 * Human State Vector - internal runtime representation of human state.
 *
 * Aligned with synheart-runtime. The HSV is the canonical internal
 * representation fusing physiology, behavior, and context modalities.
 * External consumers receive HSI JSON (via synheart-runtime), never HSV directly.
 *
 * This is the shared "state bus" that all Synheart components consume:
 * - Core produces the base HSV with physiology + behavior + context
 * - Emotion Head (via synheart-emotion SDK) populates [emotion]
 * - Focus Head (via synheart-focus SDK) populates [focus]
 * - synheart-runtime exports HSV → HSI internally
 */
data class HumanStateVector(
    val timestamp: Long,
    val meta: MetaState,

    // Physiology domain — wearable-derived readings with per-axis confidence
    val physiology: PhysiologyState = PhysiologyState.EMPTY,

    // Raw biosignals (retained for backward compatibility / feature extraction)
    val heartRate: Float? = null, // BPM
    val hrv: Float? = null, // RMSSD in ms
    val hrvSdnn: Float? = null, // SDNN in ms

    // Derived metrics
    val hsiEmbedding: List<Float> = emptyList(), // Latent representation

    // State components — emotion & focus populated by external SDKs
    val emotion: EmotionState? = null,
    val focus: FocusState? = null,
    val behavior: BehaviorState? = null,
    val context: ContextState? = null,

    // Quality and provenance
    val stateQuality: StateQuality = StateQuality.EMPTY,
    val provenance: ProvenanceInfo = ProvenanceInfo.EMPTY
)

/**
 * Metadata about the state vector
 */
data class MetaState(
    val device: DeviceInfo,
    val sessionId: String,
    val version: String = "1.0.0",
    /// SRM baseline status (EMPTY, WARMING, READY) — null if SRM not active.
    val baselineStatus: String? = null,
    /// SRM snapshot identifier — null if SRM not active.
    val srmSnapshotId: String? = null,
    /// SRM schema version — null if SRM not active.
    val srmVersion: String? = null,
    /// Total distinct calendar days across SRM strata.
    val baselineDays: Int? = null,
    /// Total accepted windows across SRM strata.
    val baselineSessions: Int? = null
)

/**
 * Device information
 */
data class DeviceInfo(
    val platform: String = "Android",
    val osVersion: String,
    val model: String? = null,
    val manufacturer: String? = null
)

