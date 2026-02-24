// CanonicalExample.kt
// Synheart Core SDK — Full-featured example
//
// This example demonstrates the complete SDK surface:
// 1. Initialization with full module configuration
// 2. Consent management for all data types
// 3. HSI streaming (core state representation)
// 4. Activating optional features (Focus, Emotion)
// 5. Feature activation/deactivation
// 6. Error handling
// 7. Clean shutdown on Activity destroy
//
// For a minimal example, see SimpleExample.kt.

package com.synheart.core.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.synheart.core.Synheart
import com.synheart.core.config.SynheartConfig
import com.synheart.core.config.SynheartFeature
import com.synheart.core.modules.runtime.RuntimeBridge
import kotlinx.coroutines.launch

class CanonicalExample : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            // 1. Initialize SDK with all modules enabled
            //    In production, replace allowUnsignedCapabilities with
            //    capabilityToken + capabilitySecret from your server.
            try {
                Synheart.initialize(
                    context = this@CanonicalExample,
                    userId = "example_user_123",
                    config = SynheartConfig(
                        allowUnsignedCapabilities = true,
                        enableWear = true,
                        enablePhone = true,
                        enableBehavior = true
                    )
                )
                println("[Synheart] SDK initialized")
            } catch (e: IllegalStateException) {
                println("[Synheart] Initialization failed: ${e.message}")
                return@launch
            }

            // 2. Grant consent for all data collection types
            Synheart.grantConsent("biosignals")
            Synheart.grantConsent("behavior")
            Synheart.grantConsent("phoneContext")
            println("[Synheart] Consent granted for biosignals, behavior, phoneContext")

            // 3. Runtime diagnostics
            val runtimeVersion = RuntimeBridge.version()
            println("[Runtime] Version: ${runtimeVersion ?: "unavailable"}")
            println("[Runtime] Native library loaded: ${runtimeVersion != null}")

            // 4. Subscribe to HSI updates (core state representation)
            launch {
                Synheart.onHSIUpdate.collect { hsi ->
                    println("[HSI] v${hsi.hsiVersion} at ${hsi.observedAtUtc}")
                }
            }

            // 5. Activate optional features (four-authority model)
            //    Features become operational when: Activated AND Consent AND Capability AND SessionActive
            Synheart.activate(SynheartFeature.FOCUS)
            launch {
                Synheart.onFocusUpdate.collect { focus ->
                    println("[Focus] Score: ${focus.score}")
                }
            }

            Synheart.activate(SynheartFeature.EMOTION)
            launch {
                Synheart.onEmotionUpdate.collect { emotion ->
                    println("[Emotion] Stress: ${emotion.stress}")
                }
            }

            // 6. Start session — data collection begins, activated features become operational
            Synheart.startSession()
            println("[Synheart] Session started")
            println("[Synheart] Active features: ${Synheart.activatedFeatures()}")

            // 7. Features can be deactivated mid-session
            Synheart.deactivate(SynheartFeature.EMOTION)
            println("[Synheart] Emotion deactivated")

            // 8. Consent can be revoked mid-session — affected features stop automatically
            // Synheart.revokeConsent("behavior")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            // 9. Clean shutdown
            Synheart.stopSession()
            Synheart.dispose()
            println("[Synheart] SDK disposed")
        }
    }
}
