# Synheart Core SDK — Kotlin

[![Version](https://img.shields.io/badge/version-0.0.8-blue.svg)](https://github.com/synheart-ai/synheart-core-kotlin)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-7F52FF.svg)](https://kotlinlang.org)
[![License: Apache 2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

> **Source-available.** This repository is open for reading, auditing, and
> filing issues. We do **not** accept pull requests — see
> [CONTRIBUTING.md](CONTRIBUTING.md) for the rationale and how to contribute
> via issues. Security reports go through [SECURITY.md](SECURITY.md).

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

1. **Capabilities Module** — Feature gating (core/extended/research)
2. **Consent Module** — User permission management
3. **Wear Module** — Biosignal collection from wearables
4. **Phone Module** — Device motion and context signals
5. **Behavior Module** — User-device interaction patterns
6. **HSI Runtime** — Signal fusion and state computation (via the runtime native binary)
7. **Cloud Connector** — Secure HSI snapshot uploads

### Optional Modules

These ship in the same artifact and are wired through the runtime, but only become useful once you've granted the relevant consent / capabilities. Each is a thin Kotlin facade around an existing FFI surface.

| Module | Purpose | Entry point |
|---|---|---|
| **Baselines** | Reactive snapshot of the user's wearable-baseline state — `Flow<BaselinesSnapshot>` with `latestSleepScore` / `latestRecoveryScore` / `latestReadinessScore` / `reference` / 7-night recent-scores ring. | `Baselines.shared` |
| **Breathing** | 4-pillar breathing-compliance detector. RR samples from `pushRr` feed it automatically; module configures target BPM / population / window. | `BreathingModule(bridge)` |
| **Syni** | Consent-gated facade around the [`ai.synheart.syni`](https://github.com/synheart-ai/syni-kotlin) on-device agent SDK. Wraps `SyniAgent` install lifecycle + chat with a `ConsentType.SYNI` check. | `SyniModule(context, consent)` |
| **Health Connect backfill** | Cold-start SRM seeding from Health Connect's sleep + overnight HR/HRV history. Pushes `sleep_need` / `deep_sleep_min` / `rem_sleep_min` / `hrv_rmssd` / `resting_hr` per wake-day. | `HealthConnectRuntimeSink(reader, bridge)` |
| **Scoring models** | Typed input + result classes for the runtime's Sleep / Recovery / Readiness scorers, plus a self-report `SleepQuestionnaireAnswers`. | `models/{SleepScore,RecoveryScore,ReadinessScore,SleepQuestionnaire}.kt` |
| **Cloud upload models** | Typed `UploadRequest` / `UploadResponse` / `UploadErrorResponse` for the snapshot-upload protocol. Round-trips byte-equivalent JSON with Flutter + Swift siblings. | `modules/cloud/UploadModels.kt` |

Examples:

```kotlin
// Baselines — react to every score / reference update
Baselines.shared.updates.collect { snap ->
    snap.latestSleepScore?.let { render(it.score) }
    snap.latestRecoveryScore?.let { render(it.score) }
}

// Breathing — configure once, evaluate per UI frame
val breathing = BreathingModule(coreRuntime)
breathing.setTargetBpm(6.0)
breathing.setPopulation(BreathingPopulation.BEGINNER)
when (val v = breathing.evaluate()) {
    is BreathingComplianceResult.Compliant -> showCompliant(v.metrics)
    is BreathingComplianceResult.NotCompliant -> showCoaching(BreathingGuidanceCopy.copyFor(v.reason))
    is BreathingComplianceResult.Insufficient -> showWarming(v.reason)
}

// Health Connect backfill — call on first launch after consent
val reader = HealthConnectAdapter(context) // from synheart-wear-kotlin
val sink = HealthConnectRuntimeSink(reader = reader, bridge = coreRuntime)
val result = sink.backfill(daysBack = 365)
log("seeded ${result.daysIngested} days, ${result.dimensionsPushed} dimensions")

// Syni — consent-gated agent
val syni = SyniModule(context, consent = consentModule)
syni.install(persona = SyniSpecPersona.load(context, "focus.coach.v1"),
             model   = SyniModels.qwen25_15bInstructQ4)
val reply = syni.chat("how should I focus right now?")
```

### Edge ingest (watch → phone)

`EdgeIngest` is the canonical phone-side consumer of the Synheart **edge wire
contract** (watch → phone). It is the counterpart to the watch producer and
exists so apps stop re-implementing watch→phone ingest: parse, hash-verify
(`payload_hash_sha256`), HSI-version validate, dedupe by `artifact_id`, and
ACK all live here once. The core is pure-JVM (no Android / Play Services
dependency) and unit-tests under plain JUnit. The canonical message shapes are
defined by the Synheart edge wire contract.

```kotlin
import ai.synheart.core.edge.EdgeIngest
import kotlinx.coroutines.launch

// 1. Construct with a Listener (or pass a no-op and use the events stream).
val ingest = EdgeIngest(object : EdgeIngest.Listener {
    override fun onArtifact(artifact: EdgeIngest.HsiArtifact) {
        // hash-verified, non-duplicate, already recorded for ACK
        render(artifact.payloadJson)
    }
})

// 2a. Observe the reactive SharedFlow of typed events (parity with the Swift
//     `events` publisher and Dart `Stream<EdgeEvent>`).
scope.launch {
    ingest.events.collect { event ->
        when (event) {
            is EdgeIngest.EdgeEvent.HrEvent       -> { /* … */ }
            is EdgeIngest.EdgeEvent.BioEvent      -> { /* … */ }
            is EdgeIngest.EdgeEvent.ArtifactEvent -> { /* … */ }
            is EdgeIngest.EdgeEvent.SessionEventWrap -> { /* … */ }
        }
    }
}

// 2b. …or just rely on the Listener callbacks above. Both fire in lock-step.

// 3. Feed decoded bodies in (transport-agnostic), then send the artifact_ack.
ingest.onMessage(type = "hsi_artifact", rawBody = jsonString)
val ack = ingest.drainAckBody()  // { "command":"artifact_ack", "artifact_ids":[…] }
if (ack != null) sendOnCommandChannel(ack)  // → docs.synheart.ai/synheart-core/edge
```

Beyond the shared surface, Kotlin's `Listener` exposes two extra observability
hooks — `onUnsupportedHsiVersion(...)` and `onHashMismatch(...)` — that the
Swift and Dart SDKs fold into their `Outcome` return value and logging.

**Delivery hardening.** Because the watch outbox is delete-on-ACK, ingest is
hardened against two failure modes:

- **Duplicate re-ack.** A duplicate `artifact_id` (already accepted) is **not**
  re-surfaced to `onArtifact`, but it **is** re-queued for ACK. A lost ACK would
  otherwise make the watch resend forever; re-acking duplicates clears the
  outbox. The dedupe set is a bounded LRU (capacity `SEEN_LRU_CAPACITY`), so
  memory stays flat over a long-lived process.
- **Poison-pill dead-letter.** A deterministically-corrupt artifact whose
  `payload_hash_sha256` keeps mismatching is detected per `artifact_id`: after
  `POISON_PILL_THRESHOLD` (3) mismatches it is **dead-lettered** — reported via
  `onPoisonPill(artifactId, expected, actual, attempts)` and ack-to-discarded so
  it stops blocking the outbox. The first/normal mismatch still rejects without
  acking (via `onHashMismatch`).

**Opt-in transport adapter.** `EdgeIngestService` is a thin, **opt-in**
`WearableListenerService` that decodes the Wear Data Layer `path`/`type` and
feeds bodies into an `EdgeIngest` core, sending the `artifact_ack` back via
`MessageClient`. Nothing in the SDK wires it in by default — a host declares it
in its own `AndroidManifest.xml` and installs `EdgeIngestService.bindings` from
`Application.onCreate`.

Because the adapter is opt-in, the Wear Data Layer dependencies
(`com.google.android.gms:play-services-wearable` and
`org.jetbrains.kotlinx:kotlinx-coroutines-play-services`) are declared
`compileOnly` and are **not** inherited transitively. A host that uses
`EdgeIngestService` must add `play-services-wearable` to its own `build.gradle`:

```gradle
dependencies {
    implementation 'com.google.android.gms:play-services-wearable:18.2.0'
    implementation 'org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.7.3'
}
```

Consumers using only the pure `EdgeIngest` core (their own transport) need
neither dependency.

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
    // implementation 'ai.synheart:synheart-core:0.0.8'
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


### Access Current State

```kotlin
// `currentState` is the latest raw HSI JSON string emitted by the runtime.
// Parse it (or use `onStateUpdate` for the typed projection) to read axes.
val hsiJson: String? = Synheart.currentState
```

### Lifecycle Integration

Synheart integrates with Android lifecycle. When using the Cloud Connector and/or background collectors, modules may continue running beyond a single Activity lifecycle depending on your integration.



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
| `grantConsent(consentType)` | Grant a wire-string consent |
| `hasConsent(consentType)` | Check if a wire-string consent is granted |
| `revokeConsent(consentType)` | Revoke a single consent type |
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

1. Install the Synheart CLI:

```bash
# macOS / Linux
curl -fsSL https://synheart.sh/install | sh

# Windows (PowerShell)
iwr -useb https://synheart.sh/install.ps1 | iex
```

See [docs.synheart.ai/setup/install-cli](https://docs.synheart.ai/setup/install-cli) for details.

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

Production cloud ingest is device-signed and consent-gated. The `synheart local`
server ships development-only mock keys for offline iteration.

- **API Key:** `mock-dev-api-key-2026` (mock platform only)
- **Mock dev secret:** `mock-dev-hmac-secret-2026` (local testing only)

Ingested payloads are persisted as JSON files in the local server's data directory.

## 📄 License

Apache 2.0 License - see [LICENSE](LICENSE) for details.

Copyright 2025-2026 Synheart AI Inc.

## Author

Synheart AI Team

## Patent Pending Notice

This project is provided under an open-source license. Certain underlying systems, methods, and architectures described or implemented herein may be covered by one or more pending patent applications.

Nothing in this repository grants any license, express or implied, to any patents or patent applications, except as provided by the applicable open-source license.
