package ai.synheart.core.modules.interfaces

/// Capability levels for module features
enum class CapabilityLevel {
    /// No access
    NONE,

    /// Core features only
    CORE,

    /// Extended features
    EXTENDED,

    /// Research-level features (internal only)
    RESEARCH
}

/// Module identifiers
enum class Module {
    WEAR,
    PHONE,
    BEHAVIOR,
    HSI,
    CLOUD
}

/// Feature flags for fine-grained control
enum class FeatureFlag {
    // Wear features
    WEAR_DERIVED_METRICS,
    WEAR_HIGH_FREQUENCY_HRV,
    WEAR_RAW_RR_INTERVALS,

    // Phone features
    PHONE_MOTION_AND_SCREEN,
    PHONE_HASHED_APP_SWITCHING,
    PHONE_DETAILED_APP_CONTEXT,
    PHONE_RAW_NOTIFICATION_STRUCTURE,

    // Behavior features
    BEHAVIOR_BASIC_METRICS,
    BEHAVIOR_EXTENDED_PATTERNS,
    BEHAVIOR_FULL_TIMING_STREAM,

    // HSI features
    HSI_EMOTION_FOCUS,
    HSI_FULL_EMBEDDING,
    HSI_FUSION_VECTOR_ACCESS,

    // Cloud features
    CLOUD_BASIC_INGEST,
    CLOUD_EXTENDED_ENDPOINTS,
    CLOUD_RESEARCH_ENDPOINTS
}

/// Provider interface for capability checking
interface CapabilityProvider {
    /// Get the capability level for a specific module
    fun capability(module: Module): CapabilityLevel

    /// Check if a specific feature is enabled
    fun isEnabled(feature: FeatureFlag): Boolean

    /// Check if a module can access a specific feature
    fun canAccessFeature(moduleId: String, featureId: String): Boolean

    /// Get all capabilities as a map
    fun getAllCapabilities(): Map<Module, CapabilityLevel>
}
