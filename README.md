# Synheart Core SDK - Kotlin

[![Version](https://img.shields.io/badge/version-1.2.0-blue.svg)](https://github.com/synheart-ai/synheart-core-kotlin)
[![Kotlin](https://img.shields.io/badge/kotlin-%3E%3D1.9.0-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](LICENSE)

**Synheart Core SDK** is the single, unified integration point for developers who want to collect HSI-compatible data, process human state on-device, and integrate with Syni. Human state inference is computed by the on-device synheart-runtime engine.

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
- **HSI Runtime** → Signal fusion + state computation (via synheart-runtime Rust engine)
- **Consent Module** → User permission management
- **Capabilities Module** → Feature gating (core/extended/research)
- **Cloud Connector** → Secure HSI snapshot uploads

**Key principle:**
> One SDK, many modules, unified human-state model

## Architecture

### Core Principle

> **All inference is computed by synheart-runtime (Rust).**
>
> **SDKs coordinate data collection and distribution.**

The Core SDK strictly separates:
- **Computation** — synheart-runtime (Rust) computes HSV
- **Collection** — Core SDK modules (Wear, Phone, Behavior, Consent, Capability)
- **Distribution** — HSI JSON export, cloud upload, raw HSV diagnostics

### Core Modules

1. **Capabilities Module** - Feature gating (core/extended/research)
2. **Consent Module** - User permission management
3. **Wear Module** - Biosignal collection from wearables
4. **Phone Module** - Device motion and context signals
5. **Behavior Module** - User-device interaction patterns
6. **HSI Runtime** - Signal fusion and state computation (via synheart-runtime)
7. **Cloud Connector** - Secure HSI snapshot uploads

### Optional Interpretation Modules

- **Synheart Focus** - Focus/engagement estimation (optional, explicit enable)
- **Synheart Emotion** - Affect modeling (optional, explicit enable)

### Data Flow

```
Wear, Phone, Behavior Modules (raw samples)
    ↓
RuntimeModule → RuntimeBridge → synheart-runtime (Rust via JNA)
    ↓                              ↓
    ↓                   session → state → HSI JSON
    ↓                              ↓
    ←──── HumanStateVector ←───────┘
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
                    allowUnsignedCapabilities = true,  // Use capabilityToken + capabilitySecret in production
                    enableWear = true,
                    enablePhone = true,
                    enableBehavior = true
                )
            )

            // Subscribe to HSI updates (raw JSON from synheart-runtime)
            launch {
                Synheart.onHSIUpdate.collect { hsiJson ->
                    println("HSI JSON: $hsiJson")
                }
            }

            // Optional: Enable interpretation modules (activate API preferred)
            Synheart.activate(SynheartFeature.FOCUS)
            launch {
                Synheart.onFocusUpdate.collect { focus ->
                    println("Focus Score: ${focus.score}")
                }
            }

            Synheart.activate(SynheartFeature.EMOTION)
            launch {
                Synheart.onEmotionUpdate.collect { emotion ->
                    println("Stress Index: ${emotion.stress}")
                }
            }

            // Optional: Enable cloud sync (requires consent)
            // Synheart.activate(SynheartFeature.CLOUD)
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
    // In production, use loadFromToken(token, secret)
    // For development only:
    loadDefaults()
}
val consent = ConsentModule(
    storage = ConsentStorage(context)
)
val wearModule = WearModule(capabilities = capabilities, consent = consent)
val phoneModule = PhoneModule(capabilities = capabilities, consent = consent)
val behaviorModule = BehaviorModule(capabilities = capabilities, consent = consent)

// Create RuntimeBridge (wraps synheart-runtime Rust engine)
val bridge = RuntimeBridge.createIfAvailable(context)

// Create Runtime Module
val runtime = RuntimeModule(
    bridge = bridge,
    wearModule = wearModule,
    behaviorModule = behaviorModule,
)

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
    runtime.hsiFlow.collect { hsiJson ->
        // Handle HSI JSON frames from synheart-runtime
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

## API Reference

### Synheart (Main Entry Point)

| Method | Description |
|--------|-------------|
| `initialize(context, userId, config, appKey)` | Initialize the SDK |
| `startSession()` | Start data collection |
| `stopSession()` | Stop data collection |
| `activate(feature)` | Enable a feature (focus, emotion, cloud, etc.) |
| `deactivate(feature)` | Disable a feature |
| `uploadNow()` | Force upload queued snapshots |
| `grantConsent(consentType)` | Grant consent for a data type |
| `revokeConsent(consentType)` | Revoke consent for a data type |
| `hasConsent(consentType)` | Check if consent is granted |
| `stop()` | Stop the session |
| `dispose()` | Release all resources |

### Properties / Flows

| Property | Type | Description |
|----------|------|-------------|
| `onHSIUpdate` | `Flow<String>` | HSI JSON frames from synheart-runtime |
| `onEmotionUpdate` | `Flow<EmotionState>` | Stream of emotion updates |
| `onFocusUpdate` | `Flow<FocusState>` | Stream of focus updates |
| `currentState` | `String?` | Latest HSI JSON frame |
| `currentConsent` | `ConsentSnapshot?` | Current consent state |

## Platform Integration

### Health Connect (via synheart-wear-kotlin)

The Wear Module collects biosignals from Health Connect via synheart-wear-kotlin:

- Heart rate monitoring
- Heart rate variability (HRV)
- Respiratory rate
- Sleep stage detection
- Motion/activity data

### SensorManager (via synheart-behavior-kotlin)

The Phone Module collects device motion via SensorManager:

- Accelerometer data
- Gyroscope data
- Device motion sensors

### Behavior Tracking (via synheart-behavior-kotlin)

The Behavior Module captures user-device interaction patterns:

- Touch events
- Scroll gestures
- App switching detection

## Error Handling

The SDK uses Kotlin exceptions for error handling:

```kotlin
try {
    Synheart.initialize(
        context = this,
        userId = "user_123",
        config = SynheartConfig(allowUnsignedCapabilities = true)
    )
    Synheart.startSession()
} catch (e: IllegalStateException) {
    when {
        e.message?.contains("already configured") == true -> {
            println("SDK already initialized")
        }
        e.message?.contains("Capability token") == true -> {
            println("Provide a valid capability token or set allowUnsignedCapabilities = true")
        }
        else -> println("Error: ${e.message}")
    }
} catch (e: Exception) {
    println("Unexpected error: $e")
}
```

### Common Exceptions

| Exception | When |
|-----------|------|
| `IllegalStateException("Synheart already configured")` | `initialize()` called twice |
| `IllegalStateException("Capability token and secret are required...")` | No token and `allowUnsignedCapabilities = false` |
| `IllegalStateException("Synheart must be initialized...")` | Method called before `initialize()` |
| `ConsentRequiredError` | Cloud operation without `cloudUpload` consent |
| `CapabilityException` | Invalid or expired capability token |

## Architecture Details

The SDK follows a pipeline architecture:

```
Raw Signals → synheart-runtime (Rust) → HSI JSON
                ↓
     session → state → HSI 1.x
                ↓
Optional: Focus/Emotion Heads → Semantic Estimates
```

See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed architecture documentation.

## Features

- **On-device state computation**: synheart-runtime (Rust) fuses signals into HSI
- **SRM baseline persistence**: Self-Reference Model snapshots automatically saved/restored across app restarts
- **Thread-safe FFI**: All native runtime calls serialized on a single-thread dispatcher
- **Emotion Head**: Predicts emotion state (stress, calm, engagement, activation, valence)
- **Focus Head**: Predicts focus state (score, cognitive load, clarity, distraction)
- **Background Processing**: Android Service for continuous signal collection
- **Kotlin Flow API**: Reactive state updates using Kotlin Coroutines
- **Module-Based Architecture**: Windowed feature collection with capability/consent management

## Testing

### Building

```bash
./gradlew build
```

### Running Tests

```bash
./gradlew test
```

### Testing with Mock Providers

The SDK ships with mock data sources for development. When no real wearable or sensor is connected, modules use mock collectors that emit synthetic data.

```kotlin
// Initialize with default capabilities (no real token needed)
Synheart.initialize(
    context = this,
    userId = "test_user",
    config = SynheartConfig(allowUnsignedCapabilities = true)
)
Synheart.startSession()

// Collect mock HSI updates
Synheart.onHSIUpdate.collect { hsiJson ->
    // Verify HSI JSON flows through pipeline
    println("HSI: $hsiJson")
}
```

## Privacy & Security

- All processing is **on-device by default**
- **No raw biosignals** are stored or transmitted
- **HSI stream is consent-gated** — `onHSIUpdate` only emits frames when `biosignals` consent is granted
- Cloud sync only for aggregated HSI (with user consent)
- **SRM baseline persistence** — Learned baselines are encrypted and persisted to `EncryptedSharedPreferences`, restored automatically on next launch
- Consent management via `ConsentModule`
- Capability-based feature access control
- Non-medical use only

## Related Repositories

This Android implementation is part of a multi-platform SDK:

- **Flutter:** `synheart-core-dart` (reference implementation)
- **iOS:** `synheart-core-swift` (Swift implementation)
- **Android:** `synheart-core-kotlin` (this repository)

All three implementations share the same modular architecture. See the Flutter repository for comprehensive documentation.

## Documentation

- **[Architecture](docs/ARCHITECTURE.md)** - Detailed architecture documentation

## 📄 License

Apache 2.0 License - see [LICENSE](LICENSE) for details.

Copyright 2025-2026 Synheart AI Inc.

## Author

Synheart AI Team

## Patent Pending Notice

This project is provided under an open-source license. Certain underlying systems, methods, and architectures described or implemented herein may be covered by one or more pending patent applications.

Nothing in this repository grants any license, express or implied, to any patents or patent applications, except as provided by the applicable open-source license.
