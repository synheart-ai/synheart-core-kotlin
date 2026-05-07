package ai.synheart.core.models

/**
 * Human State Vector - INTERNAL ONLY
 *
 * The HSV is an intermediate representation computed by synheart-engine.
 * It contains per-head inference results (emotion, focus, capacity, etc.)
 * with confidence and metadata.
 *
 * This is NOT part of the public API - consumers use HSI instead.
 * Used internally for quality assessment, diagnostics, and SRM baselines.
 */
data class HumanStateVector(
    val timestamp: Long,
    val meta: Map<String, Any> = emptyMap(),
    val physiology: PhysiologyState = PhysiologyState.EMPTY,
    val heartRate: Float? = null,
    val hrvRmssd: Float? = null,
    val hrvSdnn: Float? = null,
    val hsiEmbedding: List<Float> = emptyList(),
    val behavior: BehaviorState? = null,
    val context: ContextState? = null,
    val stateQuality: StateQuality = StateQuality.EMPTY,
    val provenance: ProvenanceInfo = ProvenanceInfo.EMPTY
)
