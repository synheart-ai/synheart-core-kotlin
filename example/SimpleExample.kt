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
import kotlinx.coroutines.launch

class SimpleExample : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            // Initialize with wearable data collection
            Synheart.initialize(
                context = this@SimpleExample,
                config = SynheartConfig(
                    appId = "com.synheart.example",
                    subjectId = "user_123",
                    allowUnsignedCapabilities = true
                )
            )

            // Grant consent for biosignal collection
            Synheart.grantConsent("biosignals")

            // Subscribe to typed state updates
            launch {
                Synheart.onStateUpdate.collect { state ->
                    println("[State] $state")
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
