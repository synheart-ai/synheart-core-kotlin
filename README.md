# Synheart Core SDK - Kotlin

[![Version](https://img.shields.io/badge/version-0.1.0-blue.svg)](https://github.com/synheart-ai/synheart-core-kotlin)
[![Kotlin](https://img.shields.io/badge/kotlin-%3E%3D1.9.0-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](LICENSE)

**Synheart Core SDK** is the single, unified integration point for developers who want to collect HSI-compatible data, process human state on-device, generate focus/emotion signals, and integrate with Syni.

> **📦 SDK Implementations**: This is the Android/Kotlin implementation. For documentation and other platforms, see the repositories below.

## 📦 Repository Structure

The Synheart Core SDK is organized across multiple repositories:

| Repository | Purpose |
|------------|---------|
| **[synheart-core](https://github.com/synheart-ai/synheart-core)** | Main repository (source of truth for documentation) |
| **[synheart-core-dart](https://github.com/synheart-ai/synheart-core-dart)** | Flutter/Dart implementation |
| **[synheart-core-kotlin](https://github.com/synheart-ai/synheart-core-kotlin)** | Android/Kotlin implementation (this repository) |
| **[synheart-core-swift](https://github.com/synheart-ai/synheart-core-swift)** | iOS/Swift implementation |

## Overview

The Synheart Core SDK consolidates all Synheart signal channels into one SDK:

- **Wear Module** → Biosignals (HR, HRV, sleep, motion)
- **Phone Module** → Motion + context signals
- **Behavior Module** → Digital interaction patterns
- **HSV Runtime** → Signal fusion + on-device state computation (HSV is internal; HSI is export)
- **Consent Module** → User permission management
- **Capabilities Module** → Feature gating (core/extended/research)
- **Cloud Connector** → Secure HSI snapshot uploads (planned)
- **Syni Hooks** → LLM conditioning (planned)

**Key principle:**
> One SDK, many modules, unified human-state model

## Architecture

### Core Principle

> **HSI represents human state.**
>
> **Interpretation is downstream and optional.**

The Core SDK strictly separates:
- **Representation (HSI)** - State axes, indices, embeddings
- **Interpretation (Focus, Emotion)** - Optional, explicit modules
- **Application logic** - Your app

### Core Modules

1. **Capabilities Module** - Feature gating (core/extended/research)
2. **Consent Module** - User permission management
3. **Wear Module** - Biosignal collection from wearables
4. **Phone Module** - Device motion and context signals
5. **Behavior Module** - User-device interaction patterns
6. **HSV Runtime** - Signal fusion and internal state representation (HSV)
7. **Cloud Connector** - Secure HSI snapshot uploads (planned)

### Optional Interpretation Modules

- **Synheart Focus** - Focus/engagement estimation (optional, explicit enable)
- **Synheart Emotion** - Affect modeling (optional, explicit enable)

### Data Flow

```
Wear, Phone, Behavior Modules
    ↓
HSV Runtime
    ↓
HSI (State Representation)
    ↓
Optional: Focus Module → Focus Estimates
Optional: Emotion Module → Emotion Estimates
```

## Setup

### Add to your project

Add the library to your `build.gradle`:

```gradle
dependencies {
    implementation project(':synheart-core')
    // Or if published to Maven:
    // implementation 'ai.synheart:core-sdk:1.0.0'
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

The Core SDK provides HSI (Human State Interface) as the core state representation, with optional interpretation modules for Focus and Emotion:

```kotlin
import com.synheart.core.Synheart
import com.synheart.core.models.SynheartConfig
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            // Initialize the Core SDK
            Synheart.initialize(
                context = this@MainActivity,
                userId = "anon_user_123",
                config = SynheartConfig(
                    enableWear = true,
                    enablePhone = true,
                    enableBehavior = true
                )
            )

            // Subscribe to HSV updates (internal state representation)
            launch {
                Synheart.onHSVUpdate.collect { hsv ->
                    println("Arousal Index: ${hsv.affect?.arousalIndex}")
                    println("Engagement Stability: ${hsv.engagement?.engagementStability}")
                }
            }

            // Optional: Enable interpretation modules
            Synheart.enableFocus()
            launch {
                Synheart.onFocusUpdate.collect { focus ->
                    println("Focus Score: ${focus.score}")
                }
            }

            Synheart.enableEmotion()
            launch {
                Synheart.onEmotionUpdate.collect { emotion ->
                    println("Stress Index: ${emotion.stress}")
                }
            }

            // Optional: Enable cloud sync (requires consent)
            // Synheart.enableCloud()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleScope.launch {
            Synheart.stop()
        }
    }
}
```

### Module-Based Architecture

The SDK also provides a modular architecture for windowed feature collection:

```kotlin
import com.synheart.core.modules.*
import com.synheart.core.modules.consent.ConsentStorage
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

// Create HSV Runtime
val runtime = HSVRuntimeModule(collector = collector)

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
val currentState = Synheart.currentState
currentState?.emotion?.stress?.let { stressLevel ->
    // Handle stress level
}
```

### Lifecycle Integration

Synheart integrates with Android lifecycle. When using the Cloud Connector and/or background collectors, modules may continue running beyond a single Activity lifecycle depending on your integration.

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

## 📄 License

Apache 2.0 License - see [LICENSE](LICENSE) for details.

Copyright 2025 Synheart AI Inc.

## Author

Synheart AI Team
