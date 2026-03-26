package ai.synheart.core.modules.consent

import android.content.Context
import ai.synheart.core.SynheartLogger
import ai.synheart.core.config.ConsentConfig
import ai.synheart.core.modules.base.BaseSynheartModule
import ai.synheart.core.modules.interfaces.ConsentProvider
import ai.synheart.core.modules.interfaces.ConsentSnapshot
import ai.synheart.core.modules.interfaces.ConsentType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * Consent Module
 *
 * Single source of truth for user consent on the device.
 * Gates collection and export of biosignals, behavior, phone context,
 * cloud upload, and Syni personalization.
 *
 * Supports both local consent (on-device only) and cloud consent service
 * integration (with JWT tokens for cloud uploads).
 */
class ConsentModule(
    private val storage: ConsentStorage,
    private val consentConfig: ConsentConfig? = null,
    context: Context? = null
) : BaseSynheartModule("consent"), ConsentProvider {

    private val _consentFlow = MutableStateFlow<ConsentSnapshot?>(null)
    private var currentConsent: ConsentSnapshot? = null

    /** Callbacks for when consent changes */
    private val listeners = mutableListOf<(ConsentSnapshot) -> Unit>()

    // Cloud consent service integration (optional)
    private var apiClient: ConsentAPIClient? = null
    private var tokenStorage: ConsentTokenStorage? = null
    private var currentToken: ConsentToken? = null
    private var tokenRefreshJob: Job? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    // Device ID storage key
    private val deviceIdPrefs = context?.getSharedPreferences("synheart_device_id_prefs", Context.MODE_PRIVATE)

    init {
        if (consentConfig?.isConfigured == true && context != null) {
            tokenStorage = ConsentTokenStorage(context)
            apiClient = ConsentAPIClient(
                baseUrl = consentConfig.consentServiceUrl,
                appId = consentConfig.appId!!,
                appApiKey = consentConfig.appApiKey!!
            )
        }
    }

    // MARK: - ConsentProvider

    override fun current(): ConsentSnapshot {
        return currentConsent ?: error("Consent module not initialized")
    }

    override fun observe(): Flow<ConsentSnapshot> {
        return _consentFlow.filterNotNull()
    }

    override suspend fun updateConsent(newConsent: ConsentSnapshot) {
        val oldConsent = currentConsent
        currentConsent = newConsent

        // Persist to storage
        storage.save(newConsent)

        // Emit to stream
        _consentFlow.value = newConsent

        // Notify listeners
        notifyListeners(newConsent)

        // Check for consent revocations and log
        if (oldConsent != null) {
            logConsentChanges(oldConsent, newConsent)
        }
    }

    // MARK: - Public API

    /** Register a listener for consent changes */
    fun addListener(listener: (ConsentSnapshot) -> Unit) {
        listeners.add(listener)
    }

    /** Remove a listener */
    fun removeListener(listener: (ConsentSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    /** Load consent from storage or use defaults */
    suspend fun loadConsent() {
        val stored = storage.load()

        if (stored != null) {
            currentConsent = stored
            _consentFlow.value = stored
            SynheartLogger.log(
                "[ConsentModule] Loaded consent from storage: biosignals=${stored.biosignals}, " +
                    "behavior=${stored.behavior}, phoneContext=${stored.phoneContext}, " +
                    "cloudUpload=${stored.cloudUpload}"
            )
        } else {
            // No stored consent, use defaults (all denied for safety)
            val defaultConsent = ConsentSnapshot.none()
            currentConsent = defaultConsent
            _consentFlow.value = defaultConsent
            SynheartLogger.log(
                "[ConsentModule] No stored consent, using defaults (all denied - explicit consent required)"
            )
        }
    }

    /** Grant all consents */
    suspend fun grantAll() {
        updateConsent(ConsentSnapshot.all())
    }

    /** Revoke all consents */
    suspend fun revokeAll() {
        updateConsent(ConsentSnapshot.none())
    }

    /** Update a specific consent type */
    suspend fun updateConsentType(type: ConsentType, granted: Boolean) {
        val current = currentConsent ?: error("Consent module not initialized")

        val updated = current.copyWith(
            biosignals = if (type == ConsentType.BIOSIGNALS) granted else current.biosignals,
            phoneContext = if (type == ConsentType.PHONE_CONTEXT) granted else current.phoneContext,
            behavior = if (type == ConsentType.BEHAVIOR) granted else current.behavior,
            cloudUpload = if (type == ConsentType.CLOUD_UPLOAD) granted else current.cloudUpload,
            focusEstimation = if (type == ConsentType.FOCUS_ESTIMATION) granted else current.focusEstimation,
            emotionEstimation = if (type == ConsentType.EMOTION_ESTIMATION) granted else current.emotionEstimation,
            syni = if (type == ConsentType.SYNI) granted else current.syni,
            timestamp = Instant.now()
        )

        updateConsent(updated)
    }

    // MARK: - Cloud Consent Service Integration

    /**
     * Get available consent profiles from cloud service.
     *
     * Returns cached profiles if available and not expired, otherwise fetches from API.
     *
     * @throws IllegalStateException if consent service is not configured
     */
    suspend fun getAvailableProfiles(): List<ConsentProfile> {
        SynheartLogger.log("[ConsentModule] getAvailableProfiles() called")

        val client = apiClient ?: error(
            "Consent service not configured. Provide ConsentConfig with appId and appApiKey."
        )

        SynheartLogger.log(
            "[ConsentModule] API client configured: baseUrl=${consentConfig?.consentServiceUrl}, appId=${consentConfig?.appId}"
        )

        // Try to load from cache first
        SynheartLogger.log("[ConsentModule] Checking for cached profiles...")
        val cached = tokenStorage?.loadCachedProfiles()
        if (cached != null && cached.isNotEmpty()) {
            SynheartLogger.log("[ConsentModule] Using cached profiles (count: ${cached.size})")
            return cached
        }

        SynheartLogger.log("[ConsentModule] No valid cached profiles, fetching from API...")

        // Fetch from API
        try {
            val profiles = client.getAvailableProfiles()
            SynheartLogger.log("[ConsentModule] Successfully fetched ${profiles.size} profiles from API")

            // Cache the profiles
            tokenStorage?.cacheProfiles(profiles)
            SynheartLogger.log("[ConsentModule] Profiles cached successfully")

            return profiles
        } catch (e: Exception) {
            SynheartLogger.log("[ConsentModule] ERROR fetching profiles: $e")
            throw e
        }
    }

    /**
     * Request consent by issuing a token for the selected profile.
     *
     * This should be called after the user has selected a consent profile.
     *
     * @throws IllegalStateException if consent service is not configured
     */
    suspend fun requestConsent(profile: ConsentProfile): ConsentToken {
        val client = apiClient ?: error(
            "Consent service not configured. Provide ConsentConfig with appId and appApiKey."
        )
        val config = consentConfig ?: error("ConsentConfig is null")

        // Get or generate persistent device ID
        val deviceId = config.deviceId ?: getOrGenerateDeviceId()

        try {
            // Issue token from consent service
            val token = client.issueToken(
                deviceId = deviceId,
                consentProfileId = profile.id,
                platform = config.platform,
                userId = config.userId,
                region = config.region
            )

            // Store token
            tokenStorage?.saveToken(token)
            currentToken = token

            // Update local consent snapshot based on profile
            updateConsentFromProfile(profile)

            // Start token refresh timer
            startTokenRefreshTimer()

            SynheartLogger.log("[ConsentModule] Consent token issued for profile: ${profile.id}")
            return token
        } catch (e: Exception) {
            SynheartLogger.log("[ConsentModule] Error requesting consent: $e")
            throw e
        }
    }

    /**
     * Request consent token directly by profile id (without fetching profiles first).
     * Useful when integrator already knows the consent_profile_id.
     */
    suspend fun requestConsentByProfileId(
        profileId: String,
        ipAddress: String? = null,
        userAgent: String? = null
    ): ConsentToken {
        val client = apiClient ?: error(
            "Consent service not configured. Provide ConsentConfig with appId and appApiKey."
        )
        val config = consentConfig ?: error("ConsentConfig is null")

        val deviceId = config.deviceId ?: getOrGenerateDeviceId()

        val token = client.issueToken(
            deviceId = deviceId,
            consentProfileId = profileId,
            platform = config.platform,
            userId = config.userId,
            region = config.region,
            ipAddress = ipAddress,
            userAgent = userAgent
        )

        tokenStorage?.saveToken(token)
        currentToken = token
        startTokenRefreshTimer()
        SynheartLogger.log("[ConsentModule] Consent token issued directly for profile: $profileId")
        return token
    }

    /**
     * Check current consent status.
     */
    fun checkConsentStatus(): ConsentStatus {
        if (currentToken == null) {
            loadTokenFromStorage()
            if (currentToken == null) {
                return ConsentStatus.PENDING
            }
        }

        if (currentToken!!.isExpired) {
            return ConsentStatus.EXPIRED
        }

        return ConsentStatus.GRANTED
    }

    /**
     * Get current valid consent token.
     */
    fun getCurrentToken(): ConsentToken? {
        if (currentToken == null) {
            loadTokenFromStorage()
        }

        if (currentToken != null && currentToken!!.isValid) {
            return currentToken
        }

        return null
    }

    /**
     * Revoke consent (clears token and notifies cloud).
     */
    suspend fun revokeConsent() {
        if (currentToken != null && apiClient != null && consentConfig != null) {
            try {
                val deviceId = consentConfig.deviceId ?: getOrGenerateDeviceId()
                apiClient!!.revokeConsent(
                    deviceId = deviceId,
                    profileId = currentToken!!.profileId
                )
            } catch (e: Exception) {
                SynheartLogger.log("[ConsentModule] Error notifying cloud of revocation: $e")
                // Continue with local revocation even if cloud notification fails
            }
        }

        // Clear token locally
        tokenStorage?.deleteToken()
        currentToken = null
        tokenRefreshJob?.cancel()
        tokenRefreshJob = null

        // Update consent snapshot to deny cloud upload
        if (currentConsent != null) {
            updateConsent(
                currentConsent!!.copyWith(
                    cloudUpload = false,
                    timestamp = Instant.now()
                )
            )
        } else {
            updateConsent(ConsentSnapshot.none())
        }

        SynheartLogger.log("[ConsentModule] Consent revoked")
    }

    /**
     * Mark consent as explicitly denied by user.
     *
     * This should be called when user declines consent in the UI,
     * to distinguish from "never asked" (pending) state.
     */
    suspend fun denyConsent() {
        // Clear any existing token
        tokenStorage?.deleteToken()
        currentToken = null
        tokenRefreshJob?.cancel()
        tokenRefreshJob = null

        // Update consent snapshot to mark as denied
        updateConsent(ConsentSnapshot.none())

        SynheartLogger.log("[ConsentModule] Consent explicitly denied by user")
    }

    /**
     * Refresh consent token if it's about to expire.
     */
    suspend fun refreshTokenIfNeeded(): ConsentToken? {
        if (currentToken == null || apiClient == null || consentConfig == null) {
            return null
        }

        // Only refresh if token expires soon
        if (!currentToken!!.expiresSoon()) {
            return currentToken
        }

        return try {
            val profileId = currentToken!!.profileId
            val profiles = getAvailableProfiles()
            val profile = profiles.firstOrNull { it.id == profileId }
                ?: error("Profile $profileId not found")

            requestConsent(profile)
        } catch (e: Exception) {
            SynheartLogger.log("[ConsentModule] Error refreshing token: $e")
            null
        }
    }

    // MARK: - Private Methods

    /** Update local consent snapshot from profile */
    private suspend fun updateConsentFromProfile(profile: ConsentProfile) {
        val snapshot = ConsentSnapshot(
            biosignals = profile.channels.biosignals.vitals || profile.channels.biosignals.sleep,
            behavior = profile.channels.behavior.enabled,
            phoneContext = profile.channels.phoneContext.motion || profile.channels.phoneContext.screenState,
            cloudUpload = profile.cloudEnabled,
            syni = false,
            focusEstimation = false,
            emotionEstimation = false,
            timestamp = Instant.now()
        )
        updateConsent(snapshot)
    }

    /** Load token from storage */
    private fun loadTokenFromStorage() {
        if (tokenStorage != null) {
            val token = tokenStorage!!.loadToken()
            if (token != null && token.isValid) {
                currentToken = token
                startTokenRefreshTimer()
            } else if (token != null && token.isExpired) {
                tokenStorage!!.deleteToken()
                currentToken = null
            }
        }
    }

    /**
     * Start token refresh timer.
     *
     * Checks 5 minutes before expiry, then every minute if close to expiry.
     */
    private fun startTokenRefreshTimer() {
        tokenRefreshJob?.cancel()

        val token = currentToken ?: return

        val now = Instant.now()
        val expiresAt = token.expiresAt
        val timeUntilExpirySeconds = expiresAt.epochSecond - now.epochSecond
        val refreshThresholdSeconds = 300L // 5 minutes

        val checkIntervalMs: Long = if (timeUntilExpirySeconds <= refreshThresholdSeconds) {
            // Close to expiry - check every minute
            60_000L
        } else {
            // Far from expiry - check 5 minutes before expiry
            val timeUntilRefreshSeconds = timeUntilExpirySeconds - refreshThresholdSeconds
            // Cap at 1 hour max interval
            minOf(timeUntilRefreshSeconds * 1000, 3_600_000L)
        }

        SynheartLogger.log("[ConsentModule] Token refresh timer: checking in ${checkIntervalMs / 60_000} minutes")

        tokenRefreshJob = scope.launch {
            delay(checkIntervalMs)
            val refreshed = refreshTokenIfNeeded()
            if (refreshed != null && refreshed != currentToken) {
                currentToken = refreshed
                startTokenRefreshTimer()
            } else if (currentToken?.isExpired == true) {
                SynheartLogger.log("[ConsentModule] Token expired and refresh failed")
                tokenRefreshJob?.cancel()
                tokenRefreshJob = null
            } else {
                startTokenRefreshTimer()
            }
        }
    }

    /**
     * Get or generate persistent device ID (UUID v4 format).
     */
    private fun getOrGenerateDeviceId(): String {
        val existingId = deviceIdPrefs?.getString(DEVICE_ID_KEY, null)
        if (!existingId.isNullOrEmpty()) {
            return existingId
        }

        val deviceId = UUID.randomUUID().toString()
        deviceIdPrefs?.edit()?.putString(DEVICE_ID_KEY, deviceId)?.apply()

        SynheartLogger.log("[ConsentModule] Generated new device ID: $deviceId")
        return deviceId
    }

    /** Notify all registered listeners */
    private fun notifyListeners(consent: ConsentSnapshot) {
        listeners.forEach { listener ->
            try {
                listener(consent)
            } catch (e: Exception) {
                SynheartLogger.log("[ConsentModule] Error notifying consent listener: $e")
            }
        }
    }

    /** Log consent changes for debugging */
    private fun logConsentChanges(oldConsent: ConsentSnapshot, newConsent: ConsentSnapshot) {
        val fields = listOf(
            Triple("biosignals", oldConsent.biosignals, newConsent.biosignals),
            Triple("behavior", oldConsent.behavior, newConsent.behavior),
            Triple("phoneContext", oldConsent.phoneContext, newConsent.phoneContext),
            Triple("focusEstimation", oldConsent.focusEstimation, newConsent.focusEstimation),
            Triple("emotionEstimation", oldConsent.emotionEstimation, newConsent.emotionEstimation),
            Triple("cloudUpload", oldConsent.cloudUpload, newConsent.cloudUpload),
            Triple("syni", oldConsent.syni, newConsent.syni),
        )
        for ((name, oldVal, newVal) in fields) {
            if (oldVal != newVal) {
                SynheartLogger.log("Consent changed: $name ${if (newVal) "granted" else "revoked"}")
            }
        }
    }

    // MARK: - Module Lifecycle

    override suspend fun onInitialize() {
        loadConsent()

        // Load token from storage if consent service is configured
        if (tokenStorage != null) {
            loadTokenFromStorage()
        }
    }

    override suspend fun onStart() {
        // Start token refresh timer if we have a token
        if (currentToken != null) {
            startTokenRefreshTimer()
        }
    }

    override suspend fun onStop() {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = null
    }

    override suspend fun onDispose() {
        tokenRefreshJob?.cancel()
        tokenRefreshJob = null
        apiClient?.dispose()
        _consentFlow.value = null
        listeners.clear()
        currentConsent = null
        currentToken = null
    }

    companion object {
        private const val DEVICE_ID_KEY = "synheart_device_id"
    }
}
