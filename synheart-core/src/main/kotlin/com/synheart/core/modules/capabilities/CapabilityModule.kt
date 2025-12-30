package com.synheart.core.modules.capabilities

import com.synheart.core.modules.base.BaseSynheartModule
import com.synheart.core.modules.interfaces.CapabilityLevel
import com.synheart.core.modules.interfaces.CapabilityProvider
import com.synheart.core.modules.interfaces.FeatureFlag
import com.synheart.core.modules.interfaces.Module
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/// Capabilities Module
///
/// Manages SDK capabilities based on authentication tokens.
/// Determines which features each module can use based on capability tiers.
class CapabilityModule : BaseSynheartModule("capabilities"), CapabilityProvider {
    private var capabilities: SDKCapabilities? = null
    private var token: CapabilityToken? = null
    private val verifier = CapabilityVerifier()
    private val _capabilitiesFlow = MutableStateFlow<SDKCapabilities?>(null)

    /// Stream of capability updates
    val capabilitiesFlow: Flow<SDKCapabilities?> = _capabilitiesFlow.asStateFlow()

    /// Load capabilities from token
    fun loadFromToken(token: CapabilityToken, secret: String) {
        if (!verifier.isValid(token, secret)) {
            throw CapabilityException("Invalid capability token")
        }

        this.token = token
        this.capabilities = verifier.parse(token)
        _capabilitiesFlow.value = capabilities
    }

    /// Load default capabilities (for development/testing)
    fun loadDefaults() {
        capabilities = SDKCapabilities.defaultCapabilities()
        _capabilitiesFlow.value = capabilities
    }

    // MARK: - CapabilityProvider

    override fun capability(module: Module): CapabilityLevel {
        return capabilities?.getLevel(module) ?: CapabilityLevel.NONE
    }

    override fun isEnabled(feature: FeatureFlag): Boolean {
        val caps = capabilities ?: return false
        return isFeatureEnabled(feature, caps)
    }

    override fun canAccessFeature(moduleId: String, featureId: String): Boolean {
        // TODO: Implement fine-grained feature access control
        return true
    }

    override fun getAllCapabilities(): Map<Module, CapabilityLevel> {
        val caps = capabilities ?: return emptyMap()

        return mapOf(
            Module.BEHAVIOR to caps.behavior,
            Module.WEAR to caps.wear,
            Module.PHONE to caps.phone,
            Module.HSV_RUNTIME to caps.hsvRuntime,
            Module.CLOUD to caps.cloud
        )
    }

    /// Check if a feature is enabled based on capability levels
    private fun isFeatureEnabled(feature: FeatureFlag, capabilities: SDKCapabilities): Boolean {
        return when (feature) {
            // Wear features
            FeatureFlag.WEAR_DERIVED_METRICS ->
                capabilities.wear >= CapabilityLevel.CORE
            FeatureFlag.WEAR_HIGH_FREQUENCY_HRV ->
                capabilities.wear >= CapabilityLevel.EXTENDED
            FeatureFlag.WEAR_RAW_RR_INTERVALS ->
                capabilities.wear >= CapabilityLevel.RESEARCH

            // Phone features
            FeatureFlag.PHONE_MOTION_AND_SCREEN ->
                capabilities.phone >= CapabilityLevel.CORE
            FeatureFlag.PHONE_HASHED_APP_SWITCHING ->
                capabilities.phone >= CapabilityLevel.CORE
            FeatureFlag.PHONE_DETAILED_APP_CONTEXT ->
                capabilities.phone >= CapabilityLevel.EXTENDED
            FeatureFlag.PHONE_RAW_NOTIFICATION_STRUCTURE ->
                capabilities.phone >= CapabilityLevel.EXTENDED

            // Behavior features
            FeatureFlag.BEHAVIOR_BASIC_METRICS ->
                capabilities.behavior >= CapabilityLevel.CORE
            FeatureFlag.BEHAVIOR_EXTENDED_PATTERNS ->
                capabilities.behavior >= CapabilityLevel.EXTENDED
            FeatureFlag.BEHAVIOR_FULL_TIMING_STREAM ->
                capabilities.behavior >= CapabilityLevel.RESEARCH

            // HSV Runtime features
            FeatureFlag.HSV_RUNTIME_EMOTION_FOCUS ->
                capabilities.hsvRuntime >= CapabilityLevel.CORE
            FeatureFlag.HSV_RUNTIME_FULL_EMBEDDING ->
                capabilities.hsvRuntime >= CapabilityLevel.EXTENDED
            FeatureFlag.HSV_RUNTIME_FUSION_VECTOR_ACCESS ->
                capabilities.hsvRuntime >= CapabilityLevel.RESEARCH

            // Cloud features
            FeatureFlag.CLOUD_BASIC_INGEST ->
                capabilities.cloud >= CapabilityLevel.CORE
            FeatureFlag.CLOUD_EXTENDED_ENDPOINTS ->
                capabilities.cloud >= CapabilityLevel.EXTENDED
            FeatureFlag.CLOUD_RESEARCH_ENDPOINTS ->
                capabilities.cloud >= CapabilityLevel.RESEARCH
        }
    }

    // MARK: - Module Lifecycle

    override suspend fun onInitialize() {
        // Nothing to initialize
    }

    override suspend fun onStart() {
        // Nothing to start
    }

    override suspend fun onStop() {
        // Nothing to stop
    }

    override suspend fun onDispose() {
        capabilities = null
        token = null
        _capabilitiesFlow.value = null
    }
}
