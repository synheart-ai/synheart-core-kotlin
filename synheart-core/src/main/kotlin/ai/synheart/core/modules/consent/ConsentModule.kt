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
 * Consent Module — thin wrapper delegating core logic to the native runtime via CoreRuntimeBridge.
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

    override suspend fun updateConsent(newConsent: ConsentSnapshot) {
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

    suspend fun grantAll() {
        updateConsent(ConsentSnapshot.all())
    }

    suspend fun revokeAll() {
        updateConsent(ConsentSnapshot.none())
    }

    suspend fun updateConsentType(type: ConsentType, granted: Boolean) {
        if (granted) {
            bridge?.grantConsent(type.runtimeKey)
        } else {
            bridge?.revokeConsent(type.runtimeKey)
        }
        val current = currentConsent ?: return
        val updated = when (type) {
            ConsentType.BIOSIGNALS -> current.copyWith(biosignals = granted)
            ConsentType.PHONE_CONTEXT -> current.copyWith(phoneContext = granted)
            ConsentType.BEHAVIOR -> current.copyWith(behavior = granted)
            ConsentType.CLOUD_UPLOAD -> current.copyWith(cloudUpload = granted)
            ConsentType.FOCUS_ESTIMATION -> current.copyWith(focusEstimation = granted)
            ConsentType.EMOTION_ESTIMATION -> current.copyWith(emotionEstimation = granted)
            ConsentType.SYNI -> current.copyWith(syni = granted)
            ConsentType.VENDOR_SYNC -> current.copyWith(vendorSync = granted)
            ConsentType.RESEARCH -> current.copyWith(research = granted)
        }
        updateConsent(updated)
    }

    suspend fun denyConsent() {
        // `explicitlyDenied` is what separates "user said no" from "never
        // asked" — without it a host re-prompts someone who already declined.
        updateConsent(ConsentSnapshot.none().copyWith(explicitlyDenied = true))
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

    /**
     * Consent profiles this app offers, newest resolution first.
     *
     * Sourced from the runtime's editable form, which already reflects the
     * cloud default profile when one has been cached. Empty when the runtime
     * is absent or has no profile yet — a host with no profiles should fall
     * back to its own copy rather than showing an empty picker.
     */
    fun availableProfiles(): List<ConsentProfile> {
        val raw = bridge?.consentGetEditableForm() ?: return emptyList()
        val form = runCatching { ConsentForm.fromJson(org.json.JSONObject(raw)) }.getOrNull()
            ?: return emptyList()
        return listOf(
            ConsentProfile(
                id = form.profileId,
                name = form.profileId,
                description = "Consent profile resolved by the Synheart runtime.",
                channels = ConsentChannels(
                    biosignals = BiosignalsConsent(
                        vitals = form.biosignals,
                        sleep = form.biosignals,
                    ),
                    phoneContext = PhoneContextConsent(
                        deviceMotion = form.phoneContext,
                        deviceContext = form.phoneContext,
                    ),
                    behavior = BehaviorConsent(digitalActivity = form.behavior),
                ),
                cloudEnabled = form.allowCloud,
                vendorSyncEnabled = form.allowVendorSync,
                isDefault = true,
            ),
        )
    }

    /**
     * Apply a profile the user selected, replacing the current snapshot.
     *
     * Channel-level truth comes from the profile; the category booleans are
     * derived from it so a host reading either shape sees the same decision.
     */
    suspend fun applyProfile(profile: ConsentProfile) {
        val ch = profile.channels
        val biosignals = ch.biosignals.vitals || ch.biosignals.sleep ||
            ch.biosignals.cardioAdvanced || ch.biosignals.neuromuscular ||
            ch.biosignals.wearableMotion
        val phoneContext = ch.phoneContext.deviceMotion || ch.phoneContext.deviceContext ||
            ch.phoneContext.systemState
        val behavior = ch.behavior.digitalActivity || ch.behavior.notificationPatterns ||
            ch.behavior.appContext

        updateConsent(
            ConsentSnapshot(
                biosignals = biosignals,
                phoneContext = phoneContext,
                behavior = behavior,
                cloudUpload = profile.cloudEnabled,
                focusEstimation = ch.interpretation.focusEstimation,
                emotionEstimation = ch.interpretation.emotionEstimation,
                syni = ch.interpretation.focusEstimation || ch.interpretation.emotionEstimation,
                vendorSync = profile.vendorSyncEnabled,
                channels = ch,
            ),
        )

        // Mirror the decision into the runtime so its consent gate agrees with
        // the SDK's snapshot; without this the two disagree until the next mint.
        bridge?.let { b ->
            ConsentType.entries.forEach { type ->
                val granted = currentConsent?.allows(type) ?: false
                // The runtime keys consent in snake_case; `wireKey` is the
                // camelCase spelling the SDK and hosts use.
                val key = type.runtimeKey
                if (granted) b.grantConsent(key) else b.revokeConsent(key)
            }
        }
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
