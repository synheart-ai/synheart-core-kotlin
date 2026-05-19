// SPDX-License-Identifier: Apache-2.0
//
// Synheart-side gate around the Syni on-device agent SDK.
//
// Unlike Flutter's `package:syni`, the native Syni Android SDK
// (`com.syni:syni-sdk`) is a separate orchestration layer with its
// own types (Syni singleton, SyniRequest, SyniResponse) — not a port
// of Flutter's `SyniAgent`. This module owns the Synheart-specific
// concern — consent gating — and delegates everything else (engine
// routing, model download, persona registry, request / response
// semantics) to the underlying `com.syni.sdk.Syni` singleton.
//
// Why a module at all when Syni is already a singleton?
// - The Synheart facade hands a single `SyniModule` to consumers.
//   The module wraps each call with a consent check so apps don't
//   accidentally bypass the gate by calling `com.syni.sdk.Syni`
//   directly.
// - Consent revocation flips the gate immediately; in-flight calls
//   started before the change still complete (no cancellation).
// - Hosts that want to bypass the gate (e.g. an internal admin tool)
//   can still reach the Syni singleton through `module.unsafeSyni`.

package ai.synheart.core.modules.syni

import ai.synheart.core.modules.interfaces.ConsentProvider
import ai.synheart.core.modules.interfaces.ConsentType
import android.content.Context
import com.syni.sdk.Syni
import com.syni.sdk.core.Persona
import com.syni.sdk.core.SyniConfig
import com.syni.sdk.core.SyniRequest
import com.syni.sdk.core.SyniResponse
import com.syni.sdk.core.SyniResult
import com.syni.sdk.model.DownloadProgress
import com.syni.sdk.model.ModelInfo
import kotlinx.coroutines.flow.Flow

/** Thrown when a Syni call is attempted without the user having granted SYNI consent. */
class SyniConsentDeniedException :
    Exception("Syni access requires explicit user consent (ConsentType.SYNI).")

/**
 * Synheart-side facade around the `Syni` on-device agent SDK.
 *
 * ```kotlin
 * val module = SyniModule(consent = Synheart.consentModule!!)
 * module.initialize(context, SyniConfig())
 * val response = module.generateAsync(SyniRequest(...))
 * ```
 *
 * Every call routing through this module first checks consent. The
 * gate is consent-only today; capability-token gating is the caller's
 * responsibility (use `Synheart.isFeatureOperational`).
 */
class SyniModule(
    private val consent: ConsentProvider,
) {

    /**
     * Direct access to the underlying singleton. Bypasses the consent
     * gate — use only when you've performed the check yourself (e.g.
     * during initialization, before consent has been granted, in
     * internal tooling).
     */
    val unsafeSyni: Syni get() = Syni

    /** True if the user has granted SYNI consent on the current snapshot. */
    val isGateOpen: Boolean
        get() = try {
            consent.current().allows(ConsentType.SYNI)
        } catch (_: Exception) {
            false
        }

    /** Mirror of [Syni.isInitialized]. Does not require the gate to be open. */
    val isInitialized: Boolean get() = Syni.isInitialized

    /**
     * Initialize the underlying Syni SDK with [config]. Requires SYNI
     * consent to be granted; throws [SyniConsentDeniedException] otherwise.
     *
     * Idempotent in the same sense `Syni.initialize` is — calling twice
     * with a different config throws via the underlying SDK, not here.
     */
    fun initialize(context: Context, config: SyniConfig = SyniConfig()) {
        requireGate()
        Syni.initialize(context, config)
    }

    /** True if Syni is initialized AND ready (model loaded). Suspend; gated. */
    suspend fun isReady(): Boolean {
        requireGate()
        return Syni.isReady()
    }

    /** Generate a response for [request]. Gated; suspend. */
    suspend fun generate(request: SyniRequest): SyniResult<SyniResponse> {
        requireGate()
        return Syni.generate(request)
    }

    /** Convenience: generate for a persona + plain text. Gated; suspend. */
    suspend fun generate(personaId: String, text: String): SyniResult<SyniResponse> {
        requireGate()
        return Syni.generate(personaId, text)
    }

    /** Throws on error instead of returning a [SyniResult]. Gated; suspend. */
    suspend fun generateAsync(request: SyniRequest): SyniResponse {
        requireGate()
        return Syni.generateAsync(request)
    }

    /** Download a model. Gated; returns a progress flow. */
    fun downloadModel(
        url: String,
        modelId: String,
        expectedChecksum: String? = null,
        wifiOnly: Boolean = false,
    ): Flow<DownloadProgress> {
        requireGate()
        return Syni.downloadModel(url, modelId, expectedChecksum, wifiOnly)
    }

    /** List downloaded models. Gated. */
    fun getDownloadedModels(): List<ModelInfo> {
        requireGate()
        return Syni.getDownloadedModels()
    }

    /** Delete a model. Gated; suspend. */
    suspend fun deleteModel(modelId: String): Boolean {
        requireGate()
        return Syni.deleteModel(modelId)
    }

    /** Storage used by downloaded models, in bytes. Gated. */
    fun getStorageUsage(): Long {
        requireGate()
        return Syni.getStorageUsage()
    }

    /** IDs of registered personas. Gated. */
    fun availablePersonas(): Set<String> {
        requireGate()
        return Syni.availablePersonas()
    }

    /** Look up a registered persona. Gated. */
    fun getPersona(personaId: String): Persona {
        requireGate()
        return Syni.getPersona(personaId)
    }

    /** Register a new persona at runtime. Gated. */
    fun registerPersona(persona: Persona) {
        requireGate()
        Syni.registerPersona(persona)
    }

    /**
     * Shut down the underlying Syni SDK. Not gated — letting consent
     * revocation drive a shutdown is the expected path.
     */
    suspend fun shutdown() {
        Syni.shutdown()
    }

    private fun requireGate() {
        if (!isGateOpen) throw SyniConsentDeniedException()
    }
}
