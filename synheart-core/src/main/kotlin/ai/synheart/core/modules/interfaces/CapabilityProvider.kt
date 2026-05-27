package ai.synheart.core.modules.interfaces

enum class CapabilityLevel {
    NONE,
    CORE,
    EXTENDED,
    RESEARCH
}

enum class Module {
    WEAR,
    PHONE,
    BEHAVIOR,
    HSI,
    CLOUD
}

enum class FeatureFlag {
    WEAR_DERIVED_METRICS,
    WEAR_HIGH_FREQUENCY_HRV,
    WEAR_RAW_RR_INTERVALS,

    PHONE_MOTION_AND_SCREEN,
    PHONE_HASHED_APP_SWITCHING,
    PHONE_DETAILED_APP_CONTEXT,
    PHONE_RAW_NOTIFICATION_STRUCTURE,

    BEHAVIOR_BASIC_METRICS,
    BEHAVIOR_EXTENDED_PATTERNS,
    BEHAVIOR_FULL_TIMING_STREAM,

    HSI_EMOTION_FOCUS,
    HSI_FULL_EMBEDDING,
    HSI_FUSION_VECTOR_ACCESS,

    CLOUD_BASIC_INGEST,
    CLOUD_EXTENDED_ENDPOINTS,
    CLOUD_RESEARCH_ENDPOINTS
}

/** Provider interface for capability checking. */
interface CapabilityProvider {
    fun capability(module: Module): CapabilityLevel
    fun isEnabled(feature: FeatureFlag): Boolean
    fun canAccessFeature(moduleId: String, featureId: String): Boolean
    fun getAllCapabilities(): Map<Module, CapabilityLevel>
}
