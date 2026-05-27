// CanonicalExample.kt
// Synheart Core SDK — Full-featured example
//
// This example demonstrates the complete SDK surface:
// 1. Configuration with SynheartConfig
// 2. Consent management for all data types
// 3. HSI state streaming (onStateUpdate)
// 4. Session lifecycle (start/stop)
// 5. Sync API
// 6. Error handling
// 7. Clean shutdown on Activity destroy
//
// For a minimal example, see SimpleExample.kt.

package ai.synheart.core.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import ai.synheart.core.Synheart
import ai.synheart.core.config.SynheartConfig
import kotlinx.coroutines.launch

class CanonicalExample : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            // 1. Configure SDK
            //    In production, replace allowUnsignedCapabilities with
            //    capabilityToken + capabilitySecret from your server.
            try {
                Synheart.initialize(
                    context = this@CanonicalExample,
                    config = SynheartConfig(
                        appId = "ai.synheart.example",
                        subjectId = "example_user_123",
                        allowUnsignedCapabilities = true
                    )
                )
                println("[Synheart] SDK initialized")
            } catch (e: IllegalStateException) {
                println("[Synheart] Configuration failed: ${e.message}")
                return@launch
            }

            // 2. Grant consent for data collection types
            Synheart.grantConsent("biosignals")
            Synheart.grantConsent("behavior")
            Synheart.grantConsent("phoneContext")
            println("[Synheart] Consent granted for biosignals, behavior, phoneContext")

            // 3. Subscribe to typed HSI state updates
            launch {
                Synheart.onStateUpdate.collect { state ->
                    println("[HSI] v${state.hsiVersion} at ${state.observedAtUtc}")
                }
            }

            // 4. Start session — data collection begins
            Synheart.startSession()
            println("[Synheart] Session started")

            // 5. Sync data to the cloud
            val syncResult = Synheart.syncNow()
            println("[Synheart] Sync result: pushed=${syncResult.pushed}, pulled=${syncResult.pulled}")

            // 6. Consent can be revoked mid-session — affected features stop automatically
            // Synheart.revokeConsent("behavior")

            // 7. Runtime diagnostics via public API
            println("[Runtime] Version: ${Synheart.runtimeVersion ?: "unavailable"}")
            println("[Runtime] Baseline summary: ${Synheart.runtimeBaselineSummary ?: "n/a"}")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            // 8. Clean shutdown
            Synheart.stopSession()
            Synheart.dispose()
            println("[Synheart] SDK disposed")
        }
    }
}
