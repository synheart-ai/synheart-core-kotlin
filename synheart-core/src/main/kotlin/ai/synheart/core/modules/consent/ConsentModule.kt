package ai.synheart.core.modules.consent

import android.content.Context
import ai.synheart.core.SynheartLogger
import ai.synheart.core.config.ConsentConfig
import ai.synheart.core.modules.base.BaseSynheartModule
import ai.synheart.core.modules.interfaces.ConsentProvider
import ai.synheart.core.modules.interfaces.ConsentSnapshot
import ai.synheart.core.modules.interfaces.ConsentType
import ai.synheart.core.bridge.CoreRuntimeBridge
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import java.util.UUID

/**
 * Consent Module — thin wrapper delegating core logic to Rust via CoreRuntimeBridge.
 *
 * Platform-specific concerns (EncryptedSharedPreferences, device ID, Flow publishers) stay here.
 */
class ConsentModule(
    private val context: Context? = null,
    private val consentConfig: ConsentConfig? = null,
    private val bridge: CoreRuntimeBridge? = null
) : BaseSynheartModule("consent"), ConsentProvider {

    private val _consentFlow = MutableStateFlow<ConsentSnapshot?>(null)
    private var currentConsent: ConsentSnapshot? = null
    private val listeners = mutableListOf<(ConsentSnapshot) -> Unit>()

    private val deviceIdPrefs = context?.getSharedPreferences(
        "synheart_device_id_prefs", Context.MODE_PRIVATE
    )

    override fun current(): ConsentSnapshot {
        return currentConsent ?: throw IllegalStateException("Consent module not initialized")
    }

    override fun observe(): Flow<ConsentSnapshot> {
        return _consentFlow.asStateFlow().filterNotNull()
    }

    fun updateConsent(newConsent: ConsentSnapshot) {
        currentConsent = newConsent
        _consentFlow.value = newConsent
        notifyListeners(newConsent)
    }

    fun addListener(listener: (ConsentSnapshot) -> Unit) {
        listeners.add(listener)
    }

    fun clearListeners() {
        listeners.clear()
    }

    fun grantAll() {
        updateConsent(ConsentSnapshot.all())
    }

    fun revokeAll() {
        updateConsent(ConsentSnapshot.none())
    }

    fun updateConsentType(type: ConsentType, granted: Boolean) {
        if (granted) {
            bridge?.grantConsent(type.name.lowercase())
        } else {
            bridge?.revokeConsent(type.name.lowercase())
        }
        val current = currentConsent ?: return
        val updated = current.copyWith(type, granted)
        updateConsent(updated)
    }

    fun denyConsent() {
        updateConsent(ConsentSnapshot.none())
        SynheartLogger.log("[ConsentModule] Consent explicitly denied by user")
    }

    fun getOrGenerateDeviceId(): String {
        val prefs = deviceIdPrefs ?: return UUID.randomUUID().toString()
        val existing = prefs.getString("device_id", null)
        if (!existing.isNullOrEmpty()) return existing

        val deviceId = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", deviceId).apply()
        SynheartLogger.log("[ConsentModule] Generated new device ID: $deviceId")
        return deviceId
    }

    private fun notifyListeners(consent: ConsentSnapshot) {
        listeners.forEach { it(consent) }
    }

    override suspend fun onInitialize() {
        val defaultConsent = ConsentSnapshot.none()
        currentConsent = defaultConsent
        _consentFlow.value = defaultConsent
    }

    override suspend fun onStart() {}
    override suspend fun onStop() {}

    override suspend fun onDispose() {
        _consentFlow.value = null
        listeners.clear()
        currentConsent = null
    }
}
