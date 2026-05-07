# Changelog

All notable changes to this package will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Removed (BREAKING) — 2026-05-05
- **`CloudConfig.tenantId`** — dead field. The cloud resolves `(org_id, tenant_id, project_id)` from `app_id` server-side; the SDK never sent it on the wire. Drop the `tenantId =` argument from your `CloudConfig(...)` calls.
- **`CloudConfig.hmacSecret`** — dead field. Request signing is performed by the runtime's hardware-backed ECDSA key, not by HMAC.
- **`require(hmacSecret != null || authProvider != null)`** init precondition — removed alongside `hmacSecret`. `authProvider` is now optional; pass it only when you need to override the runtime's default signer.
- **`InvalidTenantError`** exception class — never raised on the SDK→ingest path.

### Changed (docs) — 2026-05-05
- README: removed fictional `SynheartFeature.FOCUS` / `.EMOTION`, `Synheart.onFocusUpdate` / `onEmotionUpdate`, `RuntimeBridge.createIfAvailable()`, `RuntimeModule(...)`, `runtime.hsiFlow`, `Synheart.currentState?.emotion?.stress`, `synheart.ingestSession()` / `ingestMetadata()`, `LabPayloadBuilder.buildSession(...)`, `HumanStateVector` / `EmotionState` / `FocusState` data-class examples, `uploadNow()` API entry.
- README: corrected import — `ai.synheart.core.config.SynheartConfig` (not `models.SynheartConfig`).
- README: corrected install snippet — `ai.synheart:synheart-core:1.2.0` (not `ai.synheart:core-sdk:1.0.0`).
- README: version badge `1.3.0` → `1.2.0`.
- README: low-level / internal-language wording ("JNA", "C ABI") removed from user-facing copy; runtime native binary referenced abstractly.
- `Synheart.kt` KDoc cleaned: removed `enableFocus()` / `enableEmotion()` / `onFocusUpdate` / `onEmotionUpdate` examples that referenced symbols not on the object.

### Added — Loot #3 + Loot #4 (open-wearables recon, 2026-05-01)

Two new public Kotlin APIs in `ai.synheart.core.priority` and
`ai.synheart.core.resilience`, both routing through new JNA decls
in `bridge.CoreRuntimeNative`. Both fall back to a pure-Kotlin
in-memory path when the native library isn't loaded.

| Class | Loot | Native symbols |
|---|---|---|
| `SynheartPriority` | #3 multi-source priority | `synheart_core_priority_set_provider`, `_set_metric_override`, `_effective_rank`, `_resolve` |
| `SynheartResilience` | #4 HRV-CV resilience | `synheart_core_resilience_compute_v1` |

**Loot #5 (Apple Health XML backfill) is intentionally NOT bound on
Android.** `export.zip` is iOS-only; Android's equivalent is the
Health Connect export, which is a different format and idempotency
recipe. A future `HealthConnectBackfillSink.kt` can reuse the same
underlying runtime ingest symbols with a format-tagged payload
variant.

### Changed
- Core business logic (storage, crypto, sync, consent, artifact pipeline, cloud connector, SRM)
  migrated to the native runtime (Rust). SDK is now a thin FFI shell.
- RuntimeBridge/RuntimeModule replaced by CoreRuntimeBridge (FFI to libsynheart_core_runtime)
- HSI state updates delivered via native callback mechanism instead of platform-specific streams
- Lab protocol API now routes through CoreRuntimeBridge

### Removed
- StorageManager, ArtifactCrypto, SMK, URK, SyncEngine, SyncModule, ArtifactPipeline
- RuntimeBridge, RuntimeModule (replaced by CoreRuntimeBridge)
- CloudConnector, UploadQueue, UploadClient, HsiSchemaTransformer
- SRM computation modules (SRMModule, SRMBuffer, SRMSnapshotStorage)

## [1.3.0] - 2026-03-07

### Removed

- **`configure()` method** — Removed legacy `configure()` entry point. The single entry point is now `suspend fun initialize(context: Context, config: SynheartConfig? = null, userId: String? = null, autoStart: Boolean = false)`.
- **Feature provider interfaces** — Removed `WearFeatureProvider`, `PhoneFeatureProvider`, and `BehaviorFeatureProvider` interfaces. Modules now operate without external provider abstractions.
- **Legacy config fields** — Removed `enableWear`, `enablePhone`, `enableBehavior` from `SynheartConfig`. Module activation is controlled via `activate()` / `deactivate()` and the four-authority model.
- **Empty directories** — Removed unused `heads/` and `core/` directories.

### Changed

- Updated documentation (README.md, ARCHITECTURE.md) to reflect removed APIs and simplified initialization.

## [1.2.0] - 2026-02-23

### Removed

- **FeatureExtractor** — Deleted empty `BehaviorFeatureExtractor` placeholder class (`modules/behavior/FeatureExtractor.kt`). All feature computation lives in synheart-engine per RFC-CORE-0007.

### Changed

- Removed all TODO/FIXME comments across the SDK (Synheart.kt, CapabilityModule.kt, CloudConnectorModule.kt).
- Replaced stale TODO comments in FocusHead and EmotionHead reevaluation branches with concise `// FocusHead: HSI JSON parser pending.` / `// EmotionHead: HSI JSON parser pending.` notes.
- **README.md** — Updated version badge, fixed HSV→HSI terminology, updated code examples to use `activate()` API and `Flow<String>` types, removed "(planned)" labels, removed stale "Next Steps", updated platform integration sections, added patent pending notice.

### Added

- **SRM snapshot persistence** — SRM baseline model is now persisted to `EncryptedSharedPreferences` (AES256-GCM) and automatically restored on SDK initialization. Prevents baseline loss on app restart. New `SRMSnapshotStorage` class mirrors the `ConsentStorage` pattern.
- **HSI stream consent gating** — Local `onHSIUpdate` Flow now checks `biosignals` consent before forwarding HSI frames to consumers. Previously only cloud upload was gated; now local streams respect consent too.
- **Single-thread FFI dispatcher** — `RuntimeModule` now uses `Dispatchers.Default.limitedParallelism(1)` to serialize all FFI calls to synheart-engine, preventing concurrent native access from multiple coroutines (RFC §8.2).
- **JSON serialization for SRM types** — `SRMSnapshot`, `StratumSnapshot`, `BufferEntry`, and `MetricReference` now have `toJson()`/`fromJson()` methods for persistence.
- **HSI consent gate tests** — New `ConsentGateTest.kt` with 3 JUnit tests verifying HSI frames are blocked when biosignal consent is denied.
- **synheart-engine installed** — Android `.so` files (4 ABIs) now bundled in `jniLibs/` via `make install-kotlin`.

## [1.1.0] - 2026-02-22

### Changed

- **RuntimeBridge** (renamed from `FluxFFIProvider`) — now wraps synheart-engine C ABI via JNA instead of calling synheart-flux directly. synheart-engine composes the full session → state → flux pipeline internally.
  - `synheart_engine_new(config_json)` replaces `flux_processor_create()`
  - `synheart_engine_push_rr()`, `push_hr()`, `push_accel()`, `push_behavior()` for signal ingestion
  - `synheart_engine_tick(now_ms)` returns HSI JSON when a window completes
  - `synheart_engine_free_string()` for memory management
  - Backward-compatible: `createIfAvailable()` still returns null when native library is absent
- **RuntimeModule** (renamed from `HSVRuntimeModule`) — orchestrates signal collection and pipeline execution via RuntimeBridge.
- Updated stale comments across 10 source files to reference current module names.

## [1.0.0] - 2026-02-21

First stable release supporting HSI 1.x.

### Added

- **Flux FFI Integration** — Live pipeline from Core SDK to synheart-flux (Rust) via JNA
  - `FluxFFIProvider` — concrete `FluxProvider` calling `flux_processor_process_window()` via JNA
  - `FluxNative` — JNA interface binding to `libsynheart_flux.so`
  - Serializes raw `WearSample`, `PhoneDataPoint`, `BehaviorEvent` into WindowInput JSON
  - Maps returned Flux HSV JSON into Core `HumanStateVector` (physiology, quality, provenance, embedding)
  - Stores raw Flux HSV JSON in `MetaState.rawFluxHsv` for downstream access
  - Baseline persistence: `saveBaselines()` / `loadBaselines()` for session continuity
  - Graceful degradation: `createIfAvailable()` returns null when native library is absent
  - Memory-safe: all `flux_free_string()` calls paired with FFI allocations
  - JNA dependency: `net.java.dev.jna:jna:5.14.0@aar`

- **synheart-flux 0.4.0 Alignment** — HSV types updated to match Rust HSV specification
  - `HsvAxisValue` — score + confidence pair for per-axis readings (replaces hardcoded 0.8 confidence)
  - `PhysiologyState` — wearable-derived physiology domain with 11 axes (sleep efficiency, recovery, HRV deviation, etc.)
  - `StateQuality` — aggregated quality assessment (overall confidence, modality count, degraded flag, quality flags)
  - `ProvenanceInfo` — data provenance tracking (source IDs, vendors, device ID, timezone, baseline days)
  - `ExportPolicy` — controls which domains/axes appear in exported HSI, with confidence threshold filtering
  - `HumanStateVector` gains `physiology`, `stateQuality`, and `provenance` fields
  - `FluxBridge.export()` now accepts optional `ExportPolicy` parameter
  - FluxBridge uses per-axis confidence from `HsvAxisValue` for physiology readings
  - FluxBridge meta block includes `modality_count`, `overall_confidence`, and `vendors`

### Changed

- **EmotionHead** — Removed placeholder heuristics; now cleanly delegates to external `EmotionModel` interface (implemented by synheart-emotion-kotlin). Returns HSV unchanged if no model is configured.
- **FocusHead** — Removed placeholder heuristics; now cleanly delegates to external `FocusModel` interface (implemented by synheart-focus-kotlin). Returns HSV unchanged if no model is configured.
- **EmotionHead/FocusHead** — Feature extraction now includes `PhysiologyState` fields (recovery_score, sleep_efficiency, hrv_deviation, strain) from synheart-flux 0.4.0 HSV.

- **Capability Token Validation** — SDK now validates server-signed capability tokens during initialization
  - New `SynheartConfig` fields: `capabilityToken`, `capabilitySecret`, `allowUnsignedCapabilities`
  - When token and secret are provided, `CapabilityModule.loadFromToken()` validates HMAC signature and expiry
  - `allowUnsignedCapabilities = true` serves as a debug escape hatch (logs a warning)
  - Without a valid token or explicit opt-in, initialization throws `IllegalStateException`

- **Consent Revocation Deactivates Modules** — Revoking consent mid-session now stops affected modules immediately
  - `biosignals` revoked → stops `WearModule`, cancels Emotion/Focus heads
  - `behavior` revoked → stops `BehaviorModule`
  - `phoneContext` revoked → stops `PhoneModule`
  - `cloudUpload` revoked → stops `CloudConnectorModule`
  - Granting consent re-starts the corresponding module
  - Each stop/start is isolated — one module failure does not cascade
  - Consent listener registered during initialization, cleaned up on dispose

### Breaking Changes

- `Synheart.initialize()` now requires either a valid capability token or `allowUnsignedCapabilities = true` in config. Existing callers that relied on implicit `loadDefaults()` must pass `SynheartConfig(allowUnsignedCapabilities = true)`.

## [0.1.0] - 2025-12-30

### Added

- Initial release of Synheart Core SDK for Kotlin/Android
- Module orchestration: Capabilities, Consent, Wear, Phone, Behavior, HSV Runtime, Cloud Connector
- Optional interpretation modules: Emotion Head, Focus Head
- HSI export via FluxBridge (HSV → HSI 1.0 canonical format)
- Kotlin Flow-based reactive streams (`onHSIUpdate`, `onEmotionUpdate`, `onFocusUpdate`)
- Session lifecycle: `initialize()`, `startSession()`, `stopSession()`, `dispose()`
- Consent management: `grantConsent()`, `revokeConsent()`, `hasConsent()`, `updateConsent()`
- Cloud upload: `enableCloud()`, `uploadNow()`, `flushUploadQueue()`, `disableCloud()`
- Capability-based feature gating with HMAC-SHA256 token verification
- On-device processing with privacy-first design
- Module manager with dependency tracking and lifecycle management
- Platform support: Android API 24+ (minSdk 24, targetSdk 34)
