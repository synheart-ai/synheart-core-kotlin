// SimpleExample.kt
// Synheart Core SDK — Minimal example
//
// The absolute minimum to get HSI data flowing.
// For a full-featured example, see CanonicalExample.kt.

package com.synheart.core.example

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.synheart.core.Synheart
import com.synheart.core.config.SynheartConfig
import com.synheart.core.modules.runtime.RuntimeBridge
import kotlinx.coroutines.launch

class SimpleExample : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            // Initialize with wearable data collection
            Synheart.initialize(
                context = this@SimpleExample,
                userId = "user_123",
                config = SynheartConfig(
                    allowUnsignedCapabilities = true,
                    enableWear = true
                )
            )

            // Grant consent for biosignal collection
            Synheart.grantConsent("biosignals")

            // Log runtime diagnostics
            val runtimeVersion = RuntimeBridge.version()
            println("[Runtime] Version: ${runtimeVersion ?: "unavailable"}")
            println("[Runtime] Native library loaded: ${runtimeVersion != null}")

            // Subscribe to HSI updates
            var firstHsiReceived = false
            launch {
                Synheart.onHSIUpdate.collect { hsi ->
                    if (!firstHsiReceived) {
                        firstHsiReceived = true
                        println("[Runtime] First HSI frame received")
                    }
                    println("Arousal: ${hsi.affect?.arousalIndex}")
                    println("Valence: ${hsi.affect?.valenceIndex}")
                }
            }

            // Start session — data collection begins
            Synheart.startSession()
            println("Session started")
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            Synheart.stopSession()
            Synheart.dispose()
        }
    }
}
