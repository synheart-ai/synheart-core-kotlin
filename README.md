# Synheart Core SDK — Kotlin

[![Version](https://img.shields.io/badge/version-0.3.0-blue.svg)](https://github.com/synheart-ai/synheart-core-kotlin)
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
| **Cloud upload models** | Typed `UploadRequest` / `UploadResponse` / `UploadErrorResponse` for the snapshot-upload protocol. Round-trips byte-equivalent JSON with the sibling platform SDKs. | `modules/cloud/UploadModels.kt` |

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

## Documentation

| Document | What it covers |
|---|---|
| **[doc/INTEGRATION.md](doc/INTEGRATION.md)** | The ordered path from an empty project to a device that uploads. Start here. |
| [example/](example) | A runnable app that walks the same sequence |
| [example/SETUP.md](example/SETUP.md) | Native runtime, credentials, attestation on an emulator |
| [CHANGELOG.md](CHANGELOG.md) | Release notes, including breaking changes |
| This README | API reference |

## Setup

### Add to your project

```gradle
dependencies {
    implementation 'ai.synheart:synheart-core:0.3.0'
}
```

Or, when working inside this repo / a composite build:

```gradle
dependencies {
    implementation project(':synheart-core')
}
```

### Requirements

| | |
|---|---|
| **`minSdk`** | **26** — see the note below |
| `compileSdk` | 35 |
| Java / Kotlin JVM target | 17 |
| Core library desugaring | **required** |

The module itself declares `minSdk 24`, but it depends on
`ai.synheart:syni`, whose manifest declares `minSdkVersion 26`. A library never
runs the full manifest merge, so the conflict only surfaces when an **app**
builds against it:

```
uses-sdk:minSdkVersion 24 cannot be smaller than version 26 declared in
library [ai.synheart:syni:0.0.3]
```

Set your app to `minSdk 26`. Desugaring is required transitively by this SDK
and by `synheart-wear`:

```gradle
android {
    compileOptions {
        coreLibraryDesugaringEnabled true
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}

dependencies {
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.0.4'
}
```

### Native runtime

The SDK computes state in a native runtime loaded over JNA. It is **not**
bundled in the published AAR — provision it and point your app at it, or every
FFI call degrades to "native library not loaded" with no build-time error. See
[Installing the native runtime](#installing-the-native-runtime).

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

            // Activate modules — this is one of four required authorities,
            // not a switch on its own. See "What makes a feature run" below.
            Synheart.activate(SynheartFeature.WEAR)
            Synheart.activate(SynheartFeature.BEHAVIOR)

            // Consent. Without it the modules stay inert.
            Synheart.grantConsent("biosignals")
            Synheart.grantConsent("behavior")

            // Subscribe to HSI updates (raw JSON from the runtime)
            launch {
                Synheart.onHSIUpdate.collect { hsiJson ->
                    println("HSI JSON: $hsiJson")
                }
            }

            // Start the session. Nothing is collected until this runs —
            // activation and consent alone produce no data.
            Synheart.startSession()

            // Optional: Enable cloud sync (requires consent + an org id)
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

### What makes a feature run

A feature collects data only when **all four** of these hold:

```
operational = activated && consented && capability-allowed && session-running
```

| Authority | Set by | Read with |
|---|---|---|
| Activated | `Synheart.activate(feature)` | `isActivated(feature)` |
| Consented | `grantConsent(type)` | `hasConsent(type)` |
| Capability allowed | the capability token (or `allowUnsignedCapabilities`) | — |
| Session running | `Synheart.startSession()` | `isSessionRunning` |

Miss any one and the feature stays silent — there is no error, because none of
these is a failure. `Synheart.isFeatureOperational(feature)` collapses all four
into a single answer; check it first when data is not arriving.


### Access Current State

```kotlin
// `currentState` is the latest raw HSI JSON string emitted by the runtime.
// Parse it (or use `onStateUpdate` for the typed projection) to read axes.
val hsiJson: String? = Synheart.currentState
```

### Lifecycle Integration

Synheart integrates with Android lifecycle. When using the Cloud Connector and/or background collectors, modules may continue running beyond a single Activity lifecycle depending on your integration.



## Data Models

The runtime emits **HSI 1.3 JSON** as its public output — apps subscribe via `Synheart.onHSIUpdate` and parse the JSON, or use `onStateUpdate` for the typed projection. Internal types (`HumanStateVector` and friends, in `models/Hsv.kt`) are not part of the public SDK API.

For the modular architecture, features are collected in time windows:

- **WearWindowFeatures**: HR, HRV, motion, sleep stage, respiration
- **PhoneWindowFeatures**: Motion level, app switch rate, screen on ratio, notification rate
- **BehaviorWindowFeatures**: Typing cadence, scroll velocity, burstiness, distraction score, focus hints

## API Reference

`Synheart` is a Kotlin `object` — the process-wide personal runtime. To run a
second runtime alongside it (a research instance with its own subject id, data
directory, and attested device identity), use [`SynheartInstance`](#secondary-runtime-instances).

### Lifecycle

| Method | Description |
|--------|-------------|
| `initialize(context, config, userId, autoStart)` | Initialize the SDK |
| `isInitialized` | True once `initialize` has completed |
| `startSession()` / `stopSession()` | Open / close a collection session |
| `isSessionRunning` | True while a session is in flight |
| `currentSession` | The active `SessionHandle`, if any |
| `stop()` | Stop the session |
| `dispose()` | Release all resources |
| `ensureRuntimeBridge(config, dataDir)` | Bring the runtime up before a full `initialize` |

### Features and consent

| Method | Description |
|--------|-------------|
| `activate(feature)` / `deactivate(feature)` | Enable / disable a `SynheartFeature` |
| `isActivated(feature)` / `activatedFeatures()` | Read developer activation |
| `isFeatureOperational(feature)` | Activated **and** consented **and** capability-allowed **and** running |
| `grantConsent(type)` / `revokeConsent(type)` | Grant / revoke one wire-string channel |
| `hasConsent(type)` | Whether a channel is **enforceable** — see the note below |
| `consentEffectiveStateTyped()` | What the user actually chose (typed) |
| `consentGetEditableFormTyped()` / `consentSubmitFormTyped(form)` | Read / submit the consent form |
| `consentStatus()` / `consentNeedsTokenRefresh()` / `consentClearStored()` | Cloud consent token state |
| `consentChanges` | `Flow<ConsentSnapshot>` of every change |
| `requestConsent()` / `setConsentUIProvider(p)` | Present the host's consent UI |
| `needsConsent()` / `denyConsent()` | Whether to ask; record an explicit decline |

> `hasConsent` and `consentEffectiveStateTyped` answer different questions. Once
> a cloud consent client is configured, the runtime reports **every** channel as
> denied until the consent service issues a token — whatever the user chose. Use
> `hasConsent` to gate an action that must not proceed without cloud
> confirmation (an upload); use `consentEffectiveStateTyped` to render UI.

### Data collection

| Method | Description |
|--------|-------------|
| `startWearCollection()` / `stopWearCollection()` | Biosignals |
| `startBehaviorCollection()` / `stopBehaviorCollection()` | Interaction patterns |
| `startPhoneCollection()` / `stopPhoneCollection()` | Motion / device context |
| `isWearCollecting` / `isBehaviorCollecting` / `isPhoneCollecting` | Per-collector state |
| `startBehaviorSession()` / `stopBehaviorSession(id)` | A behavior-only episode → `BehaviorSessionResults` |
| `checkNotificationListenerEnabled()` / `openNotificationListenerSettings()` | Notification access |

### Pushing signals

| Method | Description |
|--------|-------------|
| `pushRr(tsMs, rrMs, provider)` | One R-R interval |
| `pushRrBatch(anchorTsMs, rrMs, order, provider)` | A burst from one notification — **preferred** over looping `pushRr`, it keeps inter-beat timing |
| `pushWearHr(tsMs, bpm)` / `pushAccel(...)` | Heart rate / accelerometer |
| `pushVendorHrv(...)` / `pushVendorVitals(...)` | Vendor-computed metrics |
| `pushBehaviorTouch()` / `pushBehaviorNotificationReceived()` | Interaction alone produces an HSI digital axis |
| `ingestBatch(batchJson, nowMs)` | A pre-built batch |
| `tick(nowMs)` / `ensureRuntimePipeline()` | Drive the pipeline manually |

### Personalization

| Method | Description |
|--------|-------------|
| `setTaskType(t)` / `currentTaskType()` | `TaskType` — mid-workout `MOVEMENT` dampens cognitive confidence |
| `setFocusKind(k)` / `currentFocusKind()` | `FocusKind` cognitive-load grading |
| `pushWorkoutEvent(...)` / `currentWorkoutKind()` | Completed workout windows |
| `personalizationContextJson()` | Full context incl. the explanation trace |
| `srmPushWearableDaily(...)` / `srmTriggerWearableRecompute(...)` | Historical import into baselines |
| `epochDayFor(tsMs)` | Timestamp → the epoch-day index the SRM keys on |

### Scores and baselines

| Method | Description |
|--------|-------------|
| `computeSleepScore(input)` / `attachSleepScore(result)` | Batch sleep scorer |
| `computeRecoveryScore(input)` / `attachRecoveryScoreToday(score)` | Daily recovery |
| `computeReadinessScore(input)` | Recovery + load + fatigue + history |
| `wearableReference()` | What "normal" currently means for this user |
| `baselineSnapshots` | Typed per-kind snapshot facade (`BaselineSnapshots`) |
| `longitudinalSnapshotJson()` / `loadLongitudinalSnapshot(json)` | Multi-day snapshot transfer |
| `baselineExportOffline(pass)` / `baselineImportOffline(pass, blob)` | Encrypted offline transfer |

### Cloud, sync and history

| Method | Description |
|--------|-------------|
| `cloudSyncStatus` | One `CloudSyncStatus` pill for host UI |
| `ingestion` | Queue + upload orchestration (`SynheartIngestion`) |
| `uploadQueueLength` / `lastIngestSuccessAtMs` | Queue depth (diagnostic) / last success |
| `listHsiHistory(...)` / `hsiHistoryCount()` / `clearHsiHistory()` | On-device HSI mirror |
| `fetchCloudHsiWindows(fromMs, toMs)` | The cloud archive (>30 days, cross-device) |
| `syncNow()` | One sync cycle → `SyncResult` |
| `checkSyncReadiness(op)` | Why an operation can or cannot run |
| `syncCreateSpace()` / `syncJoinSpace(...)` / `syncGeneratePairing()` | Space bootstrap |
| `syncListDevices()` / `syncRevokeDevice(id)` / `syncLeaveSpace()` / `syncDeleteSpace()` | Membership |
| `syncClearLocalSpace()` | Local escape hatch — stays available after revocation |

> Every `sync*` call throws `SyncNativeException` when the runtime returns a
> failure envelope, so the reason survives instead of collapsing to a bare null.
> Branch on `reason` (`unsupported`, `misconfigured`, `policy`, …), not just
> `retryable`.

### Realtime event stream

| Method | Description |
|--------|-------------|
| `startEventStream(config)` / `stopEventStream()` | The shared streaming connection |
| `startVendorSync(config)` / `stopVendorSync()` | …plus auto-routing of vendor events |
| `vendorSyncState` | `connecting` / `connected` / `disconnected` / `reconnecting` |
| `rawRamenEvents` | `Flow<RuntimeStreamEvent>` with the ping-vs-stream `DeliveryHint` |
| `vendorEvents` | `Flow<CanonicalWearableEvent>` after normalization + storage |
| `onDataDeletionUpdate` | `Flow<DataDeletionEvent>` — no polling needed |

### Data management and erasure

| Method | Description |
|--------|-------------|
| `listSessions()` / `getSessionSummary(id)` / `getHSIWindows(id)` | Stored session data |
| `sweepOrphanSessions()` | Close sessions a crash left `active` — call once on app start |
| `getStorageUsage()` / `setRetentionDays(days)` | Storage |
| `deleteLocalSession(id)` / `deleteLocalData()` / `deleteModuleData(m)` | Local erasure |
| `requestDataDeletion(...)` / `dataDeletionStatus(id)` / `listDataDeletions()` | GDPR Article 17 |
| `requestAccountDeletion()` / `cancelAccountDeletion()` | Account lifecycle |

### Device auth and identity

| Method | Description |
|--------|-------------|
| `subjectId` | The canonical subject, preferring the runtime's own resolution |
| `rebindSubjectId(id)` | Re-point consent + cloud without a full reinit |
| `ensureDeviceAuthRegistered()` / `reregisterDeviceAuth()` | Hardware-backed registration |
| `coreDeviceAuthStatus()` / `coreSdkDeviceAuthAvailable` | Registration state |
| `buildProofHeader(method, url)` | Sign a request the host makes itself |

### Diagnostics

| Method | Description |
|--------|-------------|
| `runtimeVersion` / `buildInfo()` | The **native runtime's** version / build metadata |
| `runtimeCompatibility` | Version gate result: the loaded runtime against `RuntimeCompat.WRITTEN_AGAINST` (0.31.1) and `MINIMUM` (0.20.0); `initialize` refuses below the minimum |
| `SYNHEART_CORE_VERSION` | This Kotlin SDK's own version |
| `runtimeDiagnostics()` | Runtime state, annotated with `missingSymbols` |
| `initRuntimeLogging(filter, onLine)` | Install the runtime's `tracing` subscriber |
| `initRuntimeLoggingBuffered(filter)` / `drainRuntimeLogs()` | Pull-based logging |
| `isHsiDeliveryBuffered` / `droppedHsiFrames` | Whether HSI is polled from the runtime's ring (runtime ≥ 0.31.1) and how many frames it evicted |

> `runtimeDiagnostics()["missingSymbols"]` lists the symbols the loaded native
> library turned out not to export. Check it before concluding a feature is
> merely disabled — a lagging vendored runtime degrades to null/false rather
> than crashing.

### Properties / Flows

| Property | Type | Description |
|----------|------|-------------|
| `onHSIUpdate` | `Flow<String>` | HSI 1.3 JSON frames from the runtime |
| `onStateUpdate` | `Flow<HSIState>` | Typed projection of `onHSIUpdate` |
| `currentHSIState` | `HSIState?` | Latest typed state |
| `currentState` | `String?` | Latest HSI JSON frame |
| `currentConsent` | `ConsentSnapshot?` | Current consent state |
| `wearSampleStream` | `Flow<WearSample>` | Raw wear samples |
| `behaviorEventStream` | `Flow<BehaviorEvent>` | Recorded interaction events |
| `breathing` | `BreathingModule?` | Breathing-compliance facade |
| `syni` | `SyniModule?` | Adaptive agent, after `configureSyni()` |

### Configuring the platform origin

The SDK ships **no built-in API host**. A host baked into the library becomes
the destination for any build that forgot to name one — including forks and
self-hosted deployments. Set it explicitly, first non-empty wins:

```kotlin
ApiEndpoints.baseUrlOverride = BuildConfig.SYNHEART_BASE_URL
```

…or via the `synheart.baseUrl` system property or the `SYNHEART_BASE_URL`
environment variable. With none set, no origin is passed to the native runtime
and the runtime applies its own default — fine for a local-only build, which
makes no network calls at all. Check `apiBaseUrlConfigured(config)` before
enabling anything cloud-bound.

### Secondary runtime instances

`SynheartInstance` runs a second, fully independent native handle — its own
engine, storage, and attested device identity. The canonical case is a research
instance created on study enrolment and wiped on withdrawal:

```kotlin
val research = SynheartInstance.create(
    config = SynheartConfig(
        appId = "com.example.app",
        subjectId = "research_pseudonym_123",
        deviceAuthConfig = DeviceAuthConfig(authBaseUrl = authOrigin),
    ),
    dataDir = File(context.filesDir, "synheart-core/research").absolutePath,
)
research?.registerDevice("research_pseudonym_123")
```

Each instance **must** get a distinct `subjectId` and `dataDir` — otherwise the
two handles contend on the same SQLite and SRM files.

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

The SDK raises typed exceptions. `SynheartCoreError` carries a stable
machine-readable [`code`](#error-codes) alongside its message.

```kotlin
import ai.synheart.core.config.SynheartCoreError
import ai.synheart.core.config.SyncNativeException

try {
    Synheart.initialize(
        context = this,
        config = SynheartConfig(
            // Both are required — `validate()` rejects an empty value before
            // the runtime is touched.
            appId = "com.example.app",
            subjectId = "anon_user_123",
            allowUnsignedCapabilities = true,
        ),
    )
    Synheart.startSession()
} catch (e: SynheartCoreError) {
    when (e.code) {
        "ERR_NOT_CONFIGURED" -> println("Missing appId / subjectId: ${e.message}")
        "ERR_RESEARCH_NOT_ALLOWED" -> println("Research mode needs privacy.allowResearch")
        else -> println("${e.code}: ${e.message}")
    }
} catch (e: IllegalStateException) {
    // e.g. no capability token and allowUnsignedCapabilities = false
    println("Not startable: ${e.message}")
}
```

`initialize()` is a **no-op** when it has already succeeded — it returns
without throwing, so there is nothing to catch for a double call. Guard with
`Synheart.isInitialized` if you need to know.

### Error codes

`SynheartCoreError` is a sealed type; branch on `code` rather than the message.

| Code | When |
|------|------|
| `ERR_NOT_CONFIGURED` | `appId` or `subjectId` empty, or an operation ran before `initialize()` |
| `ERR_INVALID_MODE` | Mode invalid for the operation (e.g. a `\|` in `subjectId`) |
| `ERR_RESEARCH_NOT_ALLOWED` | Research mode without `privacy.allowResearch` |
| `ERR_SESSION_NOT_FOUND` | No session with that id |
| `ERR_SESSION_ACTIVE` | A session is already running |
| `ERR_NO_ACTIVE_SESSION` | Operation needs a session |
| `ERR_STORAGE_DISABLED` | Storage is off in the configuration |
| `ERR_SYNC_DISABLED` | Sync is not enabled |
| `ERR_CRYPTO_KEY_UNAVAILABLE` | Encryption key unavailable |
| `ERR_MODE_FORBIDS_STREAM` | The mode does not allow that stream |

### Cloud exceptions

All extend `CloudConnectorException`:

| Exception | When |
|-----------|------|
| `ConsentRequiredError` | Cloud operation without `cloudUpload` consent |
| `CapabilityRequiredError` | Required capability not granted |
| `TokenExpiredError` | Consent token expired |
| `InvalidSignatureError` | HMAC signature validation failed |
| `RateLimitExceededError` | Rate limited; carries `retryAfter` |
| `SchemaValidationError` | Payload failed HSI schema validation |
| `NetworkError` | Transport failure |

### Sync failures

Sync calls throw `SyncNativeException` when the runtime returns a failure
envelope, so the reason survives instead of collapsing to a bare null:

```kotlin
try {
    Synheart.syncNow()
} catch (e: SyncNativeException) {
    when {
        // No attestation material and never will be on this hardware —
        // degrade to local-only and stop asking, including across relaunches.
        e.isUnsupported -> disableCloudPermanently()
        // Setup is wrong (Play Console, project number, callbacks). A human
        // has to fix it; retrying cannot.
        e.isMisconfigured -> reportToDeveloper(e)
        e.retryable -> scheduleRetry(e.retryAfterMs)
        else -> showError(e.message)
    }
}
```

Branch on `reason` / the `is*` helpers rather than `retryable` alone —
`retryable` says whether to try again, not whether it can ever work.

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

`WearModule` falls back to `MockWearSourceHandler` when no sources are
supplied, so a development build emits synthetic biosignals with no wearable
attached.

```kotlin
// appId and subjectId are required — `validate()` rejects an empty value
// before the runtime is touched.
Synheart.initialize(
    context = this,
    config = SynheartConfig(
        appId = "com.example.app",
        subjectId = "test_user",
        allowUnsignedCapabilities = true,
    ),
)

// Activation + consent + a session: all three are needed before anything
// is collected. See "What makes a feature run".
Synheart.activate(SynheartFeature.WEAR)
Synheart.grantConsent("biosignals")
Synheart.startSession()

Synheart.onHSIUpdate.collect { hsiJson ->
    println("HSI: $hsiJson")
}
```

Host-side unit tests do not need the native runtime — the bridge reports
itself unavailable and the suite passes without it. That also means the FFI
paths go untested locally unless you drop a desktop build of the runtime into
`native/`.

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

- **Android:** `synheart-core-kotlin` (this repository)
- **iOS:** `synheart-core-swift`

The platform SDKs share the same modular architecture and wire format.

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

### Supplying credentials

Download the credentials file from platform.synheart.ai after creating the app
— the four ids are issued together:

```json
{ "org_id": "org_…", "tenant_id": "ten_…", "project_id": "prj_…", "app_id": "app_…" }
```

All four are identifiers, not secrets; none is an API key. They are
organization-specific, so a populated file stays out of git.

Drop the download straight in — the template uses the same key names:

```bash
cp ~/Downloads/app-credentials*.json example/env/synheart.credentials.json
```

Then add the two fields the download does not carry:

```jsonc
{
  "org_id": "org_…",          // used — gates cloud ingest
  "app_id": "app_…_and_…",    // used — the ingest scope resolves from this
  "tenant_id": "ten_…",       // not used by this SDK
  "project_id": "prj_…",      // not used by this SDK

  "base_url": "https://api.synheart.ai",
  "package_name": "ai.synheart.core.example"
}
```

`example/env/*.json` is gitignored apart from the template, so a populated file
stays on your machine. The build reads it into `BuildConfig` — the Kotlin
read at configure time. A missing or partial file is
fine: every field falls back to empty and the SDK runs local-only, so a fresh
clone builds without anyone's organization ids.

Three notes:

- **`org_id` is load-bearing.** The runtime rejects a configuration that enables
  cloud ingest without one — `synheart_core_new` returns a null handle rather
  than a targeted error. With no `org_id` the example passes no `CloudConfig`
  at all, keeping ingest gated off instead of failing to initialize.
- **`tenant_id` and `project_id` are not used by this SDK.** The runtime config
  carries `app_id` and `org_id` and nothing else. They are in the template so a
  credential file can be transcribed whole.
- **The app id and the package name are different things.** `app_id` is the
  platform-issued `app_…` identifier an ingest scope resolves from;
  `package_name` is the real installed bundle id that Play Integrity verifies
  against. Passing the `app_…` value as the package name fails attestation.

### Installing the native runtime

The SDK loads a native runtime (`libsynheart_core_runtime.so`) over JNA. It is
**not** committed to this repo and **not** bundled in the published AAR —
provision it with the CLI:

```bash
synheart install runtime
```

That writes the binaries to `<your-app-module>/synheart/vendor/runtime/android/jniLibs/<abi>/`.
AGP only picks up `src/main/jniLibs` by default, so point your app module at
the vendored path:

```groovy
android {
    sourceSets {
        main {
            jniLibs.srcDirs = [
                project.findProperty('synheart.runtime.android.jniLibs')
                    ?: new File(projectDir, 'synheart/vendor/runtime/android/jniLibs')
                        .absolutePath
            ]
        }
    }
}
```

See `example/build.gradle` for a working copy. Without this the APK builds
fine but ships no runtime, and every FFI call degrades to
"native library not loaded" at runtime — check
`Synheart.runtimeDiagnostics()` if state never arrives.

Host-side unit tests read a **desktop** build of the runtime from `native/` at
the repo root (override with `-Djna.library.path=…`). The suite is written to
pass without it — the bridge reports itself unavailable — so an absent
`native/` means the FFI paths simply go untested locally, not that anything is
broken.

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
