package ai.synheart.core.modules.consent

import ai.synheart.core.SynheartLogger

/**
 * Callback type for presenting consent UI.
 *
 * Apps can provide their own UI implementation. The callback receives
 * available consent profiles and should return the selected profile,
 * or null if user declined.
 */
typealias ConsentUIProvider = suspend (availableProfiles: List<ConsentProfile>) -> ConsentProfile?

/**
 * Manager for consent UI hooks.
 *
 * Provides a flexible way for apps to implement their own consent UI
 * while the SDK handles the backend integration.
 */
class ConsentUIManager(
    /** Custom UI provider (set by app) */
    var customUIProvider: ConsentUIProvider? = null
) {
    /**
     * Present consent flow to user.
     *
     * If [customUIProvider] is set, it will be called. Otherwise,
     * returns null (app must handle UI separately).
     */
    suspend fun presentConsentFlow(profiles: List<ConsentProfile>): ConsentProfile? {
        if (profiles.isEmpty()) {
            SynheartLogger.log("[ConsentUI] No consent profiles available")
            return null
        }

        val provider = customUIProvider
        if (provider != null) {
            return try {
                val selected = provider(profiles)
                if (selected != null) {
                    SynheartLogger.log("[ConsentUI] User selected profile: ${selected.id}")
                } else {
                    SynheartLogger.log("[ConsentUI] User declined consent")
                }
                selected
            } catch (e: Exception) {
                SynheartLogger.log("[ConsentUI] Error in custom UI provider: $e")
                null
            }
        }

        // No custom UI provider - app must handle UI separately
        SynheartLogger.log("[ConsentUI] No custom UI provider set. App must implement consent UI.")
        return null
    }

    /**
     * Get default profile from list (if available).
     */
    fun getDefaultProfile(profiles: List<ConsentProfile>): ConsentProfile? {
        return profiles.firstOrNull { it.isDefault }
    }
}
