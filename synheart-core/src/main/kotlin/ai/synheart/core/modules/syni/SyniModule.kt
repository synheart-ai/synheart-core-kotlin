// SPDX-License-Identifier: Apache-2.0
//
// Synheart-side gate around the Syni on-device agent SDK.
//
// Mirrors the Flutter shape: `SyniAgent` instance + install lifecycle
// state machine + typed `chat()` / `chatStream()` returning
// `SyniChatResponse` and `SyniChatEvent` respectively. The underlying
// `ai.synheart.syni.SyniAgent` is wrapped with a `ConsentType.SYNI`
// gate so consumers can't accidentally bypass the consent check.
//
// API note: this matches the post-alignment Syni SDK
// (`ai.synheart.syni:syni-sdk:0.0.2+`). The earlier `Syni` singleton
// shape is gone.

package ai.synheart.core.modules.syni

import ai.synheart.core.modules.interfaces.ConsentProvider
import ai.synheart.core.modules.interfaces.ConsentType
import ai.synheart.syni.SyniAgent
import ai.synheart.syni.SyniChatEvent
import ai.synheart.syni.SyniChatResponse
import ai.synheart.syni.SyniCloudConfig
import ai.synheart.syni.SyniExecutionMode
import ai.synheart.syni.SyniInstallState
import ai.synheart.syni.SyniInstaller
import ai.synheart.syni.SyniModelSpec
import ai.synheart.syni.SyniPersona
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Thrown when a Syni call is attempted without the user having granted SYNI consent. */
class SyniConsentDeniedException :
    Exception("Syni access requires explicit user consent (ConsentType.SYNI).")

/**
 * Synheart-side facade around the `SyniAgent` on-device agent SDK.
 *
 * ```kotlin
 * val module = SyniModule(context, consent = Synheart.consentModule!!)
 * module.install(persona, model)
 * val resp = module.chat("hi")
 * ```
 *
 * Every method that touches the agent first checks consent; throws
 * [SyniConsentDeniedException] when denied. The reactive
 * [installState] / [currentState] / [hasCloud] / [isInstalled] reads
 * are not gated — they're cheap state observations consumers may
 * legitimately need before deciding to ask for consent.
 */
class SyniModule(
    context: Context,
    private val consent: ConsentProvider,
    installer: SyniInstaller? = null,
    cloudConfig: SyniCloudConfig? = null,
) {

    /**
     * Direct access to the underlying agent. Bypasses the consent
     * gate — use only when you've performed the check yourself (e.g.
     * during initialization, before consent has been granted, in
     * internal tooling).
     */
    val unsafeAgent: SyniAgent = SyniAgent(
        context = context,
        installer = installer,
        cloudConfig = cloudConfig,
    )

    /** True if the user has granted SYNI consent on the current snapshot. */
    val isGateOpen: Boolean
        get() = try {
            consent.current().allows(ConsentType.SYNI)
        } catch (_: Exception) {
            false
        }

    // ---- Reactive state (not gated — observation only) -----------------

    val installState: StateFlow<SyniInstallState> get() = unsafeAgent.installState
    val currentState: SyniInstallState get() = unsafeAgent.currentState
    val isInstalled: Boolean get() = unsafeAgent.isInstalled
    val hasCloud: Boolean get() = unsafeAgent.hasCloud

    // ---- Lifecycle ------------------------------------------------------

    /** Install [persona] with [model]. Gated; suspend. */
    suspend fun install(persona: SyniPersona, model: SyniModelSpec) {
        requireGate()
        unsafeAgent.install(persona, model)
    }

    /**
     * Restore an existing install if the on-disk state matches
     * [persona] + [model]. Gated; suspend. Returns true on successful
     * restore, false if a fresh [install] is required.
     */
    suspend fun restoreInstallIfReady(persona: SyniPersona, model: SyniModelSpec): Boolean {
        requireGate()
        return unsafeAgent.restoreInstallIfReady(persona, model)
    }

    /** Uninstall the current persona + model. Gated; suspend. */
    suspend fun uninstall() {
        requireGate()
        unsafeAgent.uninstall()
    }

    /** Release resources held by the underlying runtime. Not gated. */
    suspend fun dispose() {
        unsafeAgent.dispose()
    }

    // ---- Chat -----------------------------------------------------------

    /**
     * Single-turn chat. Returns the assembled [SyniChatResponse].
     * Gated; suspend.
     */
    suspend fun chat(
        message: String,
        hsiContext: Map<String, Any?>? = null,
        seed: Long = 0L,
        mode: SyniExecutionMode = SyniExecutionMode.LOCAL_FIRST,
    ): SyniChatResponse {
        requireGate()
        return unsafeAgent.chat(message, hsiContext, seed, mode)
    }

    /**
     * Streaming chat. Emits [SyniChatEvent] (Delta / Final) until the
     * response is complete. Gate is checked at subscription time;
     * revoking consent mid-stream does not cancel the in-flight
     * generation.
     */
    fun chatStream(
        message: String,
        hsiContext: Map<String, Any?>? = null,
        seed: Long = 0L,
        mode: SyniExecutionMode = SyniExecutionMode.LOCAL_FIRST,
    ): Flow<SyniChatEvent> {
        requireGate()
        return unsafeAgent.chatStream(message, hsiContext, seed, mode)
    }

    private fun requireGate() {
        if (!isGateOpen) throw SyniConsentDeniedException()
    }
}
