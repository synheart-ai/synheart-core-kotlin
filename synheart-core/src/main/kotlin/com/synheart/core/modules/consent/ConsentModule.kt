package com.synheart.core.modules.consent

import com.synheart.core.modules.base.BaseSynheartModule
import com.synheart.core.modules.interfaces.ConsentProvider
import com.synheart.core.modules.interfaces.ConsentSnapshot
import com.synheart.core.modules.interfaces.ConsentType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import java.time.Instant

/// Consent Module
///
/// Single source of truth for user consent on the device.
/// Gates collection and export of biosignals, behavior, motion/context,
/// cloud upload, and Syni personalization.
class ConsentModule(
    private val storage: ConsentStorage
) : BaseSynheartModule("consent"), ConsentProvider {
    private val _consentFlow = MutableStateFlow<ConsentSnapshot?>(null)
    private var currentConsent: ConsentSnapshot? = null

    /// Callbacks for when consent changes
    private val listeners = mutableListOf<(ConsentSnapshot) -> Unit>()

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

    /// Register a listener for consent changes
    fun addListener(listener: (ConsentSnapshot) -> Unit) {
        listeners.add(listener)
    }

    /// Remove a listener
    fun removeListener(listener: (ConsentSnapshot) -> Unit) {
        listeners.remove(listener)
    }

    /// Load consent from storage or use defaults
    suspend fun loadConsent() {
        val stored = storage.load()

        if (stored != null) {
            currentConsent = stored
            _consentFlow.value = stored
        } else {
            // No stored consent, use defaults (all denied for safety)
            val defaultConsent = ConsentSnapshot.none()
            currentConsent = defaultConsent
            _consentFlow.value = defaultConsent
        }
    }

    /// Grant all consents
    suspend fun grantAll() {
        updateConsent(ConsentSnapshot.all())
    }

    /// Revoke all consents
    suspend fun revokeAll() {
        updateConsent(ConsentSnapshot.none())
    }

    /// Update a specific consent type
    suspend fun updateConsentType(type: ConsentType, granted: Boolean) {
        val current = currentConsent ?: error("Consent module not initialized")

        val updated = current.copyWith(
            biosignals = if (type == ConsentType.BIOSIGNALS) granted else current.biosignals,
            behavior = if (type == ConsentType.BEHAVIOR) granted else current.behavior,
            motion = if (type == ConsentType.MOTION) granted else current.motion,
            cloudUpload = if (type == ConsentType.CLOUD_UPLOAD) granted else current.cloudUpload,
            syni = if (type == ConsentType.SYNI) granted else current.syni,
            timestamp = Instant.now()
        )

        updateConsent(updated)
    }

    // MARK: - Private Methods

    /// Notify all registered listeners
    private fun notifyListeners(consent: ConsentSnapshot) {
        listeners.forEach { listener ->
            try {
                listener(consent)
            } catch (e: Exception) {
                android.util.Log.e("ConsentModule", "Error notifying consent listener", e)
            }
        }
    }

    /// Log consent changes for debugging
    private fun logConsentChanges(oldConsent: ConsentSnapshot, newConsent: ConsentSnapshot) {
        if (oldConsent.biosignals != newConsent.biosignals) {
            android.util.Log.d(
                "ConsentModule",
                "Consent changed: biosignals ${if (newConsent.biosignals) "granted" else "revoked"}"
            )
        }
        if (oldConsent.behavior != newConsent.behavior) {
            android.util.Log.d(
                "ConsentModule",
                "Consent changed: behavior ${if (newConsent.behavior) "granted" else "revoked"}"
            )
        }
        if (oldConsent.motion != newConsent.motion) {
            android.util.Log.d(
                "ConsentModule",
                "Consent changed: motion ${if (newConsent.motion) "granted" else "revoked"}"
            )
        }
        if (oldConsent.cloudUpload != newConsent.cloudUpload) {
            android.util.Log.d(
                "ConsentModule",
                "Consent changed: cloudUpload ${if (newConsent.cloudUpload) "granted" else "revoked"}"
            )
        }
        if (oldConsent.syni != newConsent.syni) {
            android.util.Log.d(
                "ConsentModule",
                "Consent changed: syni ${if (newConsent.syni) "granted" else "revoked"}"
            )
        }
    }

    // MARK: - Module Lifecycle

    override suspend fun onInitialize() {
        loadConsent()
    }

    override suspend fun onStart() {
        // Nothing to start
    }

    override suspend fun onStop() {
        // Nothing to stop
    }

    override suspend fun onDispose() {
        _consentFlow.value = null
        listeners.clear()
        currentConsent = null
    }
}
