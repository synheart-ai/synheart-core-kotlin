# Changelog

All notable changes to this package will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [0.0.5] - 2026-05-25

### Added — cross-SDK API parity
- **`Synheart.recordMetrics(events: List<MetricEvent>)`** — batch wrapper over `recordMetric` for hosts that capture bursts of metrics.
- **`Synheart.setAmbientCapture(enabled: Boolean)` / `Synheart.getAmbientCapture()`** — surface for the runtime's ambient-capture mode (forwards every closed HSI window to the host's HSI callback regardless of session state). New JNA bindings to `synheart_core_set_ambient_capture` / `synheart_core_get_ambient_capture`, surfaced through `CoreRuntimeBridge`.

### Fixed
- `Baselines.isReady` (renamed from `isStable`) now also checks runtime READY status.
- `Synheart.dispose()` now clears the native HSI callback before nulling
  modules, eliminating a race where an in-flight callback could
  null-deref `sessionModule` / `consentModule` mid-teardown.
- `Synheart.initialize()` and `dispose()` are now serialized via a
  coroutine `Mutex`, preventing double-init and interleaved-teardown
  races when called from multiple threads.
- `ConsentTokenStorage` no longer crashes the host on devices with a
  broken Android Keystore (rooted, AOSP variants, Keystore lockout).
  Persistence is disabled with a logged warning; the host must re-grant
  consent each process start.
- Build now defaults `VERSION_NAME` to `0.0.5` when not passed via
  `-PVERSION_NAME`, matching `gradle.properties`.

### Docs
- `Synheart.recordMetric`, `listLocalSessions`, `hasConsent`,
  `grantConsent`, `revokeConsent` — expanded KDoc with accepted
  `consentType` values, throw conditions, and behavior notes.

## [0.0.4] - 2026-05-07

Initial open-source release of the Synheart Core SDK for Android.

The SDK is a thin FFI shell over the native runtime — storage,
crypto, sync, consent, the artifact pipeline, the cloud connector,
and SRM live in the runtime, and this package exposes them through
a Kotlin surface.

### Public surface
- `Synheart` facade with coroutine-friendly initialize / activate /
  deactivate lifecycle.
- `SynheartConfig` (single source of truth for app metadata, modules,
  cloud, consent, capabilities, device auth).
- `CoreRuntimeBridge` — JNA bridge to `libsynheart_core_runtime`,
  serialized via a single-thread dispatcher.
- New public APIs: `SynheartPriority` (multi-source priority
  resolution) and `SynheartResilience` (HRV-CV resilience). Both
  fall back to a pure-Kotlin in-memory path when the native library
  is not loaded.
- HSI state updates delivered via the runtime callback mechanism.
- Lab protocol API routed through `CoreRuntimeBridge`.

### Breaking
- `CloudConfig.tenantId` removed — dead field. The cloud resolves
  `(org_id, tenant_id, project_id)` from `app_id` server-side.
- `CloudConfig.hmacSecret` removed — dead field. Request signing is
  performed by the runtime's hardware-backed ECDSA key, not HMAC.
- `require(hmacSecret != null || authProvider != null)` precondition
  removed alongside `hmacSecret`. `authProvider` is now optional.
- `InvalidTenantError` removed — never raised on the SDK→ingest path.
- `Synheart.cancelAccountDeletion()` is now `suspend` and returns
  `DeletionRequestResult` instead of `Boolean`, mirroring
  `requestAccountDeletion()` and the Dart/Swift counterparts.

### Distribution
- Maven Central: `ai.synheart:synheart-core:0.0.4`

[Unreleased]: https://github.com/synheart-ai/synheart-core-kotlin/compare/v0.0.5...HEAD
[0.0.5]: https://github.com/synheart-ai/synheart-core-kotlin/releases/tag/v0.0.5
[0.0.4]: https://github.com/synheart-ai/synheart-core-kotlin/releases/tag/v0.0.4
