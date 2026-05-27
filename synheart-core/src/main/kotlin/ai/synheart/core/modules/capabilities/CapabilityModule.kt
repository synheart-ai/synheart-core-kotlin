package ai.synheart.core.modules.capabilities

import ai.synheart.core.modules.base.BaseSynheartModule
import ai.synheart.core.modules.interfaces.CapabilityLevel
import ai.synheart.core.modules.interfaces.CapabilityProvider
import ai.synheart.core.modules.interfaces.FeatureFlag
import ai.synheart.core.modules.interfaces.Module
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages SDK capabilities based on authentication tokens.
 * Determines which features each module can use based on capability tiers.
 */
class CapabilityModule : BaseSynheartModule("capabilities"), CapabilityProvider {
    private var capabilities: SDKCapabilities? = null
    private var token: CapabilityToken? = null
    private val _capabilitiesFlow = MutableStateFlow<SDKCapabilities?>(null)

    val capabilitiesFlow: Flow<SDKCapabilities?> = _capabilitiesFlow.asStateFlow()

    fun loadFromToken(token: CapabilityToken, secret: String) {
        this.token = token
        this.capabilities = SDKCapabilities.fromToken(token)
        _capabilitiesFlow.value = capabilities
    }

    fun loadDefaults() {
        capabilities = SDKCapabilities.defaultCapabilities()
        _capabilitiesFlow.value = capabilities
    }

    override fun capability(module: Module): CapabilityLevel {
        return capabilities?.getLevel(module) ?: CapabilityLevel.NONE
    }

    override fun isEnabled(feature: FeatureFlag): Boolean {
        val caps = capabilities ?: return false
        return isFeatureEnabled(feature, caps)
    }

    override fun canAccessFeature(moduleId: String, featureId: String): Boolean {
        return true
    }

    override fun getAllCapabilities(): Map<Module, CapabilityLevel> {
        val caps = capabilities ?: return emptyMap()

        return mapOf(
            Module.BEHAVIOR to caps.behavior,
            Module.WEAR to caps.wear,
            Module.PHONE to caps.phone,
            Module.HSI to caps.hsvRuntime,
            Module.CLOUD to caps.cloud
        )
    }

    private fun isFeatureEnabled(feature: FeatureFlag, capabilities: SDKCapabilities): Boolean {
        return when (feature) {
            FeatureFlag.WEAR_DERIVED_METRICS ->
                capabilities.wear >= CapabilityLevel.CORE
            FeatureFlag.WEAR_HIGH_FREQUENCY_HRV ->
                capabilities.wear >= CapabilityLevel.EXTENDED
            FeatureFlag.WEAR_RAW_RR_INTERVALS ->
                capabilities.wear >= CapabilityLevel.RESEARCH

            FeatureFlag.PHONE_MOTION_AND_SCREEN ->
                capabilities.phone >= CapabilityLevel.CORE
            FeatureFlag.PHONE_HASHED_APP_SWITCHING ->
                capabilities.phone >= CapabilityLevel.CORE
            FeatureFlag.PHONE_DETAILED_APP_CONTEXT ->
                capabilities.phone >= CapabilityLevel.EXTENDED
            FeatureFlag.PHONE_RAW_NOTIFICATION_STRUCTURE ->
                capabilities.phone >= CapabilityLevel.EXTENDED

            FeatureFlag.BEHAVIOR_BASIC_METRICS ->
                capabilities.behavior >= CapabilityLevel.CORE
            FeatureFlag.BEHAVIOR_EXTENDED_PATTERNS ->
                capabilities.behavior >= CapabilityLevel.EXTENDED
            FeatureFlag.BEHAVIOR_FULL_TIMING_STREAM ->
                capabilities.behavior >= CapabilityLevel.RESEARCH

            FeatureFlag.HSI_EMOTION_FOCUS ->
                capabilities.hsvRuntime >= CapabilityLevel.CORE
            FeatureFlag.HSI_FULL_EMBEDDING ->
                capabilities.hsvRuntime >= CapabilityLevel.EXTENDED
            FeatureFlag.HSI_FUSION_VECTOR_ACCESS ->
                capabilities.hsvRuntime >= CapabilityLevel.RESEARCH

            FeatureFlag.CLOUD_BASIC_INGEST ->
                capabilities.cloud >= CapabilityLevel.CORE
            FeatureFlag.CLOUD_EXTENDED_ENDPOINTS ->
                capabilities.cloud >= CapabilityLevel.EXTENDED
            FeatureFlag.CLOUD_RESEARCH_ENDPOINTS ->
                capabilities.cloud >= CapabilityLevel.RESEARCH
        }
    }

    override suspend fun onInitialize() {}

    override suspend fun onStart() {}

    override suspend fun onStop() {}

    override suspend fun onDispose() {
        capabilities = null
        token = null
        _capabilitiesFlow.value = null
    }
}
