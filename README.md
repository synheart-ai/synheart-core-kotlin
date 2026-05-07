# Synheart Core SDK - Kotlin

[![Version](https://img.shields.io/badge/version-0.0.4-blue.svg)](https://github.com/synheart-ai/synheart-core-kotlin)
[![Kotlin](https://img.shields.io/badge/kotlin-%3E%3D1.9.0-blue.svg)](https://kotlinlang.org)
[![License](https://img.shields.io/badge/license-Apache%202.0-green.svg)](LICENSE)

Android/Kotlin platform SDK for Synheart. This is a thin wrapper around the Synheart runtime — a native binary that owns the on-device business logic and is loaded by this SDK at startup.

Human state inference is computed on-device by the runtime.

This SDK handles platform-specific concerns only: sensor collection (Health Connect, BLE), Android Keystore key management, EncryptedSharedPreferences, Kotlin Flow reactive streams, and Jetpack integration.

## Architecture

```
Android App
    |
synheart-core-kotlin (this SDK)
    |-- Wear/Phone/Behavior modules (platform sensor collection)
    |-- CoreRuntimeBridge (loads the runtime native binary)
    |
Synheart runtime native binary (per ABI: arm64-v8a, armeabi-v7a, x86_64)
    |-- HSI computation
    |-- Storage, Crypto, Sync, Auth, Consent, Capabilities
```

## Repositories

| Repository | Purpose |
|------------|---------|
| **[synheart-core-flutter](https://github.com/synheart-ai/synheart-core-flutter)** | Flutter/Dart platform SDK |
| **[synheart-core-kotlin](https://github.com/synheart-ai/synheart-core-kotlin)** | Android/Kotlin platform SDK (this repository) |
| **[synheart-core-swift](https://github.com/synheart-ai/synheart-core-swift)** | iOS/Swift platform SDK |

## Overview

The Synheart Core SDK consolidates all Synheart signal channels into one SDK:

- **Wear Module** → Biosignals (HR, HRV, sleep, motion)
- **Phone Module** → Motion + context signals
- **Behavior Module** → Digital interaction patterns
- **HSI Runtime** → Signal fusion + state computation (via synheart-engine)
- **Consent Module** → User permission management
- **Capabilities Module** → Feature gating (core/extended/research)
- **Cloud Connector** → Secure HSI snapshot uploads

**Key principle:**
> One SDK, many modules, unified human-state model

## Architecture

### Core Principle

> **All inference is computed by synheart-engine.**
>
> **SDKs coordinate data collection and distribution.**

The Core SDK strictly separates:
- **Computation** — synheart-engine computes HSV
- **Collection** — Core SDK modules (Wear, Phone, Behavior, Consent, Capability)
- **Distribution** — HSI JSON export, cloud upload, raw HSV diagnostics

### Core Modules

1. **Capabilities Module** - Feature gating (core/extended/research)
2. **Consent Module** - User permission management
3. **Wear Module** - Biosignal collection from wearables
4. **Phone Module** - Device motion and context signals
5. **Behavior Module** - User-device interaction patterns
6. **HSI Runtime** - Signal fusion and state computation (via the runtime native binary)
7. **Cloud Connector** - Secure HSI snapshot uploads

### Data Flow

```
Wear, Phone, Behavior Modules (raw samples)
    ↓
CoreRuntimeBridge → runtime native binary
    ↓                       ↓
    ↓             session → state → HSI 1.3 JSON
    ↓                       ↓
    ←──── HSI JSON ←────────┘
    ↓
Synheart.onHSIUpdate (raw JSON) / Synheart.onStateUpdate (typed)
```

## Setup

### Add to your project

Add the library to your `build.gradle`:

```gradle
dependencies {
    implementation project(':synheart-core')
    // Or if published to Maven:
    // implementation 'ai.synheart:synheart-core:0.0.4'
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

The Core SDK exposes HSI (Human State Interface) as the canonical state representation. Apps subscribe to `onHSIUpdate` (raw JSON) or `onStateUpdate` (typed projection) — there are no separate Focus / Emotion subscriptions.

```kotlin
import ai.synheart.core.Synheart
import ai.synheart.core.config.SynheartConfig
import ai.synheart.core.config.SynheartFeature
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        lifecycleScope.launch {
            // Initialize the Core SDK
            Synheart.initialize(
                context = this@MainActivity,
                config = SynheartConfig(
                    appId = "com.example.app",
                    subjectId = "anon_user_123",
                    allowUnsignedCapabilities = true  // Use capabilityToken + capabilitySecret in production
                )
            )

            // Activate modules
            Synheart.activate(SynheartFeature.WEAR)
            Synheart.activate(SynheartFeature.BEHAVIOR)

            // Subscribe to HSI updates (raw JSON from the runtime)
            launch {
                Synheart.onHSIUpdate.collect { hsiJson ->
                    println("HSI JSON: $hsiJson")
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
import ai.synheart.core.modules.*
import ai.synheart.core.modules.consent.ConsentStorage
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

// Create the runtime bridge (loads the on-device runtime artifact)
val bridge = CoreRuntimeBridge.create(context)

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

// Subscribe to HSI updates from the runtime
lifecycleScope.launch {
    Synheart.onHSIUpdate.collect { hsiJson ->
        // Handle HSI 1.3 JSON frames
    }
}
```

### Access Current State

```kotlin
// `currentState` is the latest raw HSI JSON string emitted by the runtime.
// Parse it (or use `onStateUpdate` for the typed projection) to read axes.
val hsiJson: String? = Synheart.currentState
```

### Lifecycle Integration

Synheart integrates with Android lifecycle. When using the Cloud Connector and/or background collectors, modules may continue running beyond a single Activity lifecycle depending on your integration.

## Batch Ingest Mode

By default the runtime streams data in real time. **Batch ingest mode** buffers all events during a session and runs a single ingest call when the session stops:

```kotlin
val config = SynheartConfig(
    appId = "com.example.app",
    subjectId = "anon_user_123",
    batchIngestOnStop = true,
)
```

## Lab Ingestion

Lab session and metadata payloads are produced by the `Synheart.lab*` API and uploaded automatically when `research` consent is granted and `cloudConfig` is wired up.

```kotlin
val now = { System.currentTimeMillis() }

val sessionId = Synheart.labStart(protocolJson, now())
val windowId = Synheart.labOpenWindow(
    windowType = "baseline",
    startedAtMs = now(),
)
// ... collect data ...
Synheart.labCloseWindow(windowId, now())

val payload = Synheart.labFinalize(now())  // returns JSON; auto-enqueued for upload
```

## Data Models

The runtime emits **HSI 1.3 JSON** as its public output — apps subscribe via `Synheart.onHSIUpdate` and parse the JSON, or use `onStateUpdate` for the typed projection. Internal types (`Hsv` and friends) are not part of the public SDK API.

For the modular architecture, features are collected in time windows:

- **WearWindowFeatures**: HR, HRV, motion, sleep stage, respiration
- **PhoneWindowFeatures**: Motion level, app switch rate, screen on ratio, notification rate
- **BehaviorWindowFeatures**: Typing cadence, scroll velocity, burstiness, distraction score, focus hints

## API Reference

### Synheart (Main Entry Point)

| Method | Description |
|--------|-------------|
| `initialize(context, config, userId, autoStart)` | Initialize the SDK |
| `activate(feature)` | Enable a `SynheartFeature` (`WEAR`, `BEHAVIOR`, `PHONE_CONTEXT`, `CLOUD`) |
| `deactivate(feature)` | Disable a feature |
| `requestConsent()` | Open the cloud consent flow; returns a `ConsentToken?` |
| `hasConsent(consentType)` | Check if a wire-string consent is granted |
| `revokeConsentType(consentType)` | Revoke a single consent type |
| `revokeConsent()` | Revoke all consent types |
| `labStart` / `labOpenWindow` / `labCloseWindow` / `labFinalize` | Lab protocol APIs |
| `syncNow()` | Force a sync of pending uploads |
| `stop()` | Stop the session |
| `dispose()` | Release all resources |

### Properties / Flows

| Property | Type | Description |
|----------|------|-------------|
| `onHSIUpdate` | `Flow<String>` | HSI 1.3 JSON frames from the runtime |
| `onStateUpdate` | `Flow<HSIState>` | Typed projection of `onHSIUpdate` |
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
        e.message?.contains("already initialized") == true -> {
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
| `IllegalStateException("Synheart already initialized")` | `initialize()` called twice |
| `IllegalStateException("Capability token and secret are required...")` | No token and `allowUnsignedCapabilities = false` |
| `IllegalStateException("Synheart must be initialized...")` | Method called before `initialize()` |
| `ConsentRequiredError` | Cloud operation without `cloudUpload` consent |
| `CapabilityException` | Invalid or expired capability token |

## Architecture Details

The SDK follows a pipeline architecture:

```
Raw Signals → synheart-engine → HSI JSON
                ↓
     session → state → HSI 1.3
                ↓
Optional: Focus/Emotion Heads → Semantic Estimates
```

See [ARCHITECTURE.md](docs/ARCHITECTURE.md) for detailed architecture documentation.

## Features

- **On-device state computation**: synheart-engine fuses signals into HSI
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

- **Flutter:** `synheart-core-flutter` (reference implementation)
- **iOS:** `synheart-core-swift` (Swift implementation)
- **Android:** `synheart-core-kotlin` (this repository)

All three implementations share the same modular architecture. See the Flutter repository for comprehensive documentation.

## Local Development with `synheart local`

For offline SDK development and testing, use the **Synheart CLI** local platform server. It replicates the cloud consent and ingest APIs locally.

### Setup

1. Install the [Synheart CLI](https://github.com/synheart-ai/synheart-cli):

```bash
git clone https://github.com/synheart-ai/synheart-cli
cd synheart-cli
make build && make install
```

2. Start the local platform:

```bash
synheart local
```

This starts an HTTP server on `localhost:8083` with mock consent profiles, token issuance, and ingest endpoints.

### Connecting your Android app

Point the SDK at the local server via build config:

```kotlin
// In your Application class or DI setup
val config = SynheartConfig(
    appId = "your_app_id",
    subjectId = "user_123",
    allowUnsignedCapabilities = true,
    labIngestConfig = LabIngestConfig(
        baseUrl = "http://10.0.2.2:8083",  // Android emulator → host localhost
        apiKey = "mock-dev-api-key-2026",
    )
)
```

For a physical device on the same network:

```kotlin
baseUrl = "http://192.168.1.100:8083"  // your machine's LAN IP
```

### Available endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/apps/{id}/consent-profiles` | Fetch consent profiles |
| `POST` | `/api/v1/sdk/consent-token` | Issue consent token |
| `POST` | `/api/v1/sdk/consent-revoke` | Revoke consent |
| `POST` | `/v1/ingest/hsi` | Ingest HSI snapshots |
| `POST` | `/v1/platform/session/ingest` | Ingest session data |
| `POST` | `/v1/platform/metadata/ingest` | Ingest metadata |
| `GET` | `/status` | Server status and stats |

### Default credentials

Production cloud ingest is signed with **ECDSA P-256** via
`X-Synheart-Proof` (compact JWS) plus a `X-Consent-Token` JWT — see
[`synheart-auth`](https://github.com/synheart-ai/synheart-auth) and
RFC-AUTH-MOBILE-0001. The `synheart local` server below ships
development-only mock keys for offline iteration.

- **API Key:** `mock-dev-api-key-2026` (mock platform only)
- **Mock dev secret:** `mock-dev-hmac-secret-2026` (local testing only — production is ECDSA, not a shared secret)

Ingested payloads are persisted as JSON files in the local server's data directory.

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
