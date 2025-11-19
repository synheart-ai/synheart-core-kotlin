# Synheart Core SDK - Android

**Synheart Core SDK** is the single, unified integration point for developers who want to collect HSI-compatible data, process human state on-device, generate focus/emotion signals, and integrate with Syni.

## Overview

The Synheart Core SDK consolidates all Synheart signal channels into one SDK:

- **Wear Module** → Biosignals (HR, HRV, sleep, motion)
- **Phone Module** → Motion + context signals
- **Behavior Module** → Digital interaction patterns
- **HSI Runtime** → Signal fusion + state computation
- **Consent Module** → User permission management
- **Capabilities Module** → Feature gating (core/extended/research)
- **Cloud Connector** → Secure HSI snapshot uploads (planned)
- **Syni Hooks** → LLM conditioning (planned)

**Key principle:**
> One SDK, many modules, unified human-state model

## Architecture

The Core SDK consists of **7 core modules** working together:

1. **Capabilities Module** - Feature gating (core/extended/research)
2. **Consent Module** - User permission management
3. **Wear Module** - Biosignal collection from wearables
4. **Phone Module** - Device motion and context signals
5. **Behavior Module** - User-device interaction patterns
6. **HSI Runtime** - Signal fusion and state computation (produces Human State Vector)
7. **Cloud Connector** - Secure HSI snapshot uploads (planned)

The **HSI Runtime** module:
- Ingests signals from Wear, Phone, and Behavior modules
- Fuses them into a unified **Human State Vector (HSV)**
- Feeds higher-level models (Emotion Engine, Focus Engine)
- Powers Syni's LLM layer for human-aware AI

## Setup

### Add to your project

Add the library to your `build.gradle`:

```gradle
dependencies {
    implementation project(':hsi')
    // Or if published to Maven:
    // implementation 'com.synheart:hsi:1.0.0'
}
```

### Permissions

Add required permissions to your `AndroidManifest.xml`:

```xml
<!-- Health data permissions (if using Health Connect) -->
<uses-permission android:name="android.permission.health.READ_HEART_RATE" />
<uses-permission android:name="android.permission.health.READ_STEPS" />

<!-- Foreground service permission -->
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
```

## Usage

### Basic Usage

```kotlin
import com.synheart.hsi.HSI
import com.synheart.hsi.models.HumanStateVector
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Configure Core SDK with your app key
        HSI.configure("your-app-key")
        
        // Start HSI
        HSI.start(this)
        
        // Observe state updates
        lifecycleScope.launch {
            HSI.stateFlow.collect { hsv ->
                hsv?.let { updateUI(it) }
            }
        }
    }
    
    private fun updateUI(hsv: HumanStateVector) {
        // Access emotion state
        hsv.emotion?.let { emotion ->
            val stress = emotion.stress
            val engagement = emotion.engagement
            // Update UI...
        }
        
        // Access focus state
        hsv.focus?.let { focus ->
            val score = focus.score
            val cognitiveLoad = focus.cognitiveLoad
            // Update UI...
        }
        
        // Access behavior
        hsv.behavior?.let { behavior ->
            val typingSpeed = behavior.typingSpeed
            val engagementLevel = behavior.engagementLevel
            // Update UI...
        }
    }
    
    override fun onDestroy() {
        super.onDestroy()
        // Stop HSI when done
        HSI.stop()
    }
}
```

### Module-Based Architecture

The SDK also provides a modular architecture for windowed feature collection:

```kotlin
import com.synheart.hsi.modules.*
import com.synheart.hsi.modules.consent.ConsentStorage
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Initialize modules
val capabilities = CapabilityModule().apply {
    loadDefaults() // Or loadFromToken(token, secret) for production
}
val consent = ConsentModule(
    storage = ConsentStorage(context)
)
val wearModule = WearModule(capabilities = capabilities, consent = consent)
val phoneModule = PhoneModule(capabilities = capabilities, consent = consent)
val behaviorModule = BehaviorModule(capabilities = capabilities, consent = consent)

// Create channel collector
val collector = ChannelCollector(
    wear = wearModule,
    phone = phoneModule,
    behavior = behaviorModule
)

// Create HSI Runtime
val runtime = HSIRuntimeModule(collector = collector)

// Initialize and start modules
lifecycleScope.launch {
    // Initialize all modules (must be done before starting)
    capabilities.initialize()
    consent.initialize() // This loads consent from storage
    wearModule.initialize()
    phoneModule.initialize()
    behaviorModule.initialize()
    runtime.initialize()
    
    // Optionally grant consents (after initialization)
    consent.grantAll() // Or updateConsentType() for granular control
    
    // Start all modules
    capabilities.start()
    consent.start()
    wearModule.start()
    phoneModule.start()
    behaviorModule.start()
    runtime.start()
}

// Subscribe to final HSV
lifecycleScope.launch {
    runtime.finalHsvFlow.collect { hsv ->
        // Handle state updates
    }
}
```

### Access Current State

```kotlin
val currentState = HSI.currentState
currentState?.emotion?.stress?.let { stressLevel ->
    // Handle stress level
}
```

### Lifecycle Integration

HSI integrates with Android lifecycle. The service will continue running in the background even when your activity is paused.

## Data Models

### HumanStateVector

The main data structure containing all state information:

```kotlin
data class HumanStateVector(
    val timestamp: Long,
    val meta: MetaState,
    val heartRate: Float?,
    val hrv: Float?,
    val hrvSdnn: Float?,
    val hsiEmbedding: List<Float>,
    val emotion: EmotionState?,
    val focus: FocusState?,
    val behavior: BehaviorState?,
    val context: ContextState?
)
```

### EmotionState

```kotlin
data class EmotionState(
    val stress: Float,      // 0.0 - 1.0
    val calm: Float,         // 0.0 - 1.0
    val engagement: Float,  // 0.0 - 1.0
    val activation: Float,  // 0.0 - 1.0
    val valence: Float      // -1.0 to 1.0
)
```

### FocusState

```kotlin
data class FocusState(
    val score: Float,           // 0.0 - 1.0
    val cognitiveLoad: Float,   // 0.0 - 1.0
    val clarity: Float,          // 0.0 - 1.0
    val distraction: Float       // 0.0 - 1.0
)
```

### Window Features

For the modular architecture, features are collected in time windows:

- **WearWindowFeatures**: HR, HRV, motion, sleep stage, respiration
- **PhoneWindowFeatures**: Motion level, app switch rate, screen on ratio, notification rate
- **BehaviorWindowFeatures**: Typing cadence, scroll velocity, burstiness, distraction score, focus hints

## Platform Integration

### Health Connect Integration (Planned)

The Wear Module will integrate with Android Health Connect for biosignal collection:

- Heart rate monitoring
- Heart rate variability (HRV)
- Respiratory rate
- Sleep stage detection
- Motion/activity data

### SensorManager Integration (Planned)

The Phone Module will integrate with SensorManager for device motion:

- Accelerometer data
- Gyroscope data
- Device motion sensors

### MotionEvent Integration (Planned)

The Behavior Module will integrate with MotionEvent for interaction tracking:

- Touch events
- Scroll gestures
- App switching detection

## Architecture Details

The SDK follows a pipeline architecture:

```
Raw Signals → Ingestion Service → Signal Processor → Fusion Engine → Base HSV
                                                                    ↓
Final HSV ← Focus Head ← Emotion Head ←──────────────────────────────┘
```

See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed architecture documentation.

## Features

- **Signal Processing**: Synchronization, normalization, noise reduction
- **Fusion Engine**: Combines biosignals, behavior, and context into unified state
- **Emotion Head**: Predicts emotion state (stress, calm, engagement, activation, valence)
- **Focus Head**: Predicts focus state (score, cognitive load, clarity, distraction)
- **Background Processing**: Android Service for continuous signal collection
- **Kotlin Flow API**: Reactive state updates using Kotlin Coroutines
- **Module-Based Architecture**: Windowed feature collection with capability/consent management

## Development

### Building

```bash
./gradlew build
```

### Testing

```bash
./gradlew test
```

## Privacy & Security

- All processing is **on-device by default**
- **No raw biosignals** are stored or transmitted
- Cloud sync (when implemented) will only sync aggregated HSV with user consent
- Consent management via `ConsentModule`
- Capability-based feature access control
- Non-medical use only

## Related Repositories

This Android implementation is part of a multi-platform SDK:

- **Flutter:** `synheart-core-flutter` (reference implementation)
- **iOS:** `synheart-core-ios` (Swift implementation)
- **Android:** `synheart-core-android` (this repository)

All three implementations share the same modular architecture. See the Flutter repository for comprehensive documentation.

## Documentation

- **[Architecture](docs/ARCHITECTURE.md)** - Detailed architecture documentation
- **[Module Specifications](docs/MODULE_SPECS.md)** - Module API documentation
- **[Native Implementation Status](../synheart-core-flutter/docs/NATIVE_IMPLEMENTATION_STATUS.md)** - Cross-platform status

## Next Steps

1. **Connect to actual SDKs**: Integrate with Health Connect and SensorManager
2. **Implement fusion model**: Replace placeholder embedding with actual Tiny Transformer or CNN-LSTM
3. **Integrate model packages**: Connect synheart_emotion and synheart_focus modules
4. **Cloud sync**: Implement cloud sync functionality (optional, with consent)

## License

[Add your license here]

## Author

Israel Goytom
