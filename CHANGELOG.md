# Changelog

All notable changes to this package will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added — runtime version gate

- **The SDK now states which runtime its bindings assume and checks it at
  init.** `RuntimeCompat.WRITTEN_AGAINST` (`0.31.1`) and `RuntimeCompat.MINIMUM`
  (`0.20.0`) are compared against `build_info.core_runtime` once the bridge is
  created; the result is logged and exposed as `Synheart.runtimeCompatibility`.
  Below the minimum, `initialize` refuses with an `IllegalStateException`
  naming the fix (`synheart install runtime`); between minimum and
  written-against it warns once. Until now nothing in this package recorded
  the runtime version the hand-written JNA surface was written for, so every
  behavioural change in the runtime's `SDK-CONTRACT-CHANGES.md` was invisible
  to a consumer — the C ABI is additive, so an old vendored `.so` links fine
  and diverges silently.

### Added — research instance fan-in

- **`SynheartInstance` can now be given an app identity and keystroke
  context.** `pushAppForeground`, `pushContextEvent`, `pushContextEventJson`
  and `supportsRichBehaviorEvents` are the per-instance equivalents of the
  `Synheart.*` calls, which reach the personal runtime only. A host running a
  second (research) instance had no way to feed either, so every research
  window resolved to the `Unknown` app category — an all-zero
  interpretation-mask row — and carried `context_label: UK` with no evidence
  behind it, starving CFI / Cognitive Load's digital term, Valence's friction
  index and the behaviour-only Stress path on every research row. No new
  native calls: both route through the existing bridge symbols.

### Fixed — HSI callback lifetime (runtime ≥ 0.31.1)

- **HSI delivery is now buffered (pull-based) when the runtime supports it.**
  The push path hands the runtime a function pointer into a JNA trampoline
  whose lifetime its tokio workers know nothing about; `retiredCallbacks` kept
  that pointer valid for a handle's lifetime, but nothing could once the
  Kotlin side that owned the peer was gone while the native runtime, its
  workers and the HSI listener survived in the process — the next completed
  window was dispatched through a dangling pointer and the process aborted on
  a `tokio-rt-worker` thread. On a runtime ≥ 0.31.1 the bridge now calls
  `synheart_core_init_hsi_buffered` instead of registering a callback and
  drains `synheart_core_drain_hsi` from a daemon thread every
  `CoreRuntimeBridge.hsiDrainIntervalMs` (1 s; ring
  `CoreRuntimeBridge.hsiBufferCapacity`, default 64 — the oldest frame is
  evicted when full). `Synheart.tick` / `tickAll` / `flushPending` drain in
  the same call, so a host that ticks itself sees no added latency; delivery
  stays deduplicated by `hsi_id`. Older runtimes fall back to the push
  callback unchanged. `Synheart.isHsiDeliveryBuffered` and
  `Synheart.droppedHsiFrames` expose the mode and the runtime's eviction
  counter. Same pattern as the buffered logging path.
- **Stream callback teardown uses `synheart_core_clear_stream_callback`**
  when exported (≥ 0.31.1) and drops the peer immediately; older runtimes
  keep the retire-until-`synheart_core_free` path. The stream callback itself
  is still a pushed function pointer — 0.31.1 adds clear-only, no buffered
  mode.

### Fixed — `secure_load` no longer reports a failed read as "no such key"

- The secure-storage callbacks opened `EncryptedSharedPreferences` through a
  `lazy` that memoised a failed first attempt, and `load` returned NULL for
  every failure — Keystore not ready after boot, OEM Keystore faults, a read
  that threw. The runtime reads NULL as "absent", so one such launch minted a
  new storage master key over the existing one and orphaned every sealed
  blob. The store is now reopened on each failure, absence is decided by
  `contains`, an unavailable store is retried with a bounded ~1 s backoff, and
  the cause is logged. The callback still has to return NULL when storage is
  genuinely unavailable — the C signature has no error channel. On runtime
  ≥ 0.31.1 the provisioning marker turns that into
  `ERR_SECURE_STORAGE_UNAVAILABLE` (retryable) rather than a re-mint; older
  runtimes keep the re-mint exposure.

## [0.2.0] - 2026-09-02

### Fixed
- **Every behavior event was silently dropped.** `BehaviorEventStream` backed its
  bus with `MutableSharedFlow()` — replay 0, no extra capacity, i.e. a
  *rendezvous* flow — while every `record*` reaches it through `tryEmit`, whose
  `Boolean` result was discarded. `tryEmit` on a rendezvous flow fails unless a
  subscriber is suspended awaiting a value at that instant, and hosts record from
  synchronous UI callbacks like `dispatchTouchEvent`. So taps, scrolls and
  keystrokes never arrived: `behaviorEventStream` stayed empty, the aggregator
  saw nothing, nothing reached `synheart_core_push_behavior`, and the HSI digital
  modality never appeared. Behavior is the only source that needs no sensor, so
  this made the SDK look inert on any device without a wearable while reporting
  itself healthy. The bus is now buffered with `DROP_OLDEST`, which suits
  interaction telemetry — high-rate, lossy-tolerant, and `SUSPEND` cannot be
  honoured from a synchronous callback anyway.
- **Consent did not survive a restart, so sessions collected nothing.**
  `initialize()` never adopted the runtime's persisted consent into the SDK's own
  `consentModule` mirror; only `consentSubmitForm` did. On every launch after the
  first, the runtime reported all channels granted while `consentModule.current()`
  said none was, and everything gating on the mirror failed closed:
  `moduleManager.startAll()` started each module and `reevaluateAllFeatures()`
  stopped it a millisecond later. A session collected **nothing** while the
  consent UI showed all-granted. The cloud self-heal was dead for the same
  reason — it checks `current().cloudUpload`, which was never true, so no consent
  token was minted and every upload reported a closed gate.
  `syncConsentModuleFromRuntime()` already existed and was correct; it simply was
  not called at init.
- **The wear module fabricated biosignals by default.** `WearModule` fell back to
  `MockWearSourceHandler` whenever a host passed no sources — which on Android is
  always, since the SDK registers no real one. Its `isAvailable` is
  unconditionally `true` and it invents a heart rate and RMSSD every second.
  Those samples are not cosmetic: they reach `wearSampleStream`, where a host
  renders them as measurements, and they flow through
  `WearModuleBiosignalAdapter` into the session engine and the runtime's
  longitudinal baselines (SRM) — so a host that merely granted biosignals consent
  silently corrupted that subject's real reference ranges with fabricated beats,
  on their own device, with nothing on screen indicating the data was invented. A
  module with no source now reports no signal, which is the truth; the generator
  is opt-in via `SynheartConfig.allowSyntheticBiosignals` and logs a warning when
  active.
- **Device registration was impossible on Android.** Two defects compounded.
  `DeviceAuthCallbacks.getAttestation` was a hardcoded `null`, but the runtime's
  FFI contract is a JSON object `{"format":…,"blob":…}` and it treats a NULL
  pointer as a hard `PlatformCrypto("null callback result")` — so registration
  could never pass step 4/7 whatever the configuration. That also made
  `DeviceAuthConfig.allowUnattestedDevRegistration` unreachable: its documented
  behaviour, sending `format:"none"` with an empty blob so the server decides,
  requires the callback to *answer*. A null is not "no material available", it is
  "the callback is broken", and the runtime cannot tell a device that cannot
  attest from a host that never wired this up. Separately, every failure path in
  `DeviceAuthCrypto` was `catch (e: Exception) { null }`, so the runtime could
  only ever report "null callback result" — indistinguishable from an unwired
  callback, which has a completely different fix. The callback now reports no
  material in the shape the contract expects, and the crypto causes are logged.
- **Documentation corrections.** The README's quick-start activated features but
  never granted consent or called `startSession()`, so the first thing a
  developer copied collected nothing, silently — a feature needs all four
  authorities (activation, consent, capability, running session) and the
  README documented none of that. It also claimed a double `initialize()`
  throws (it is a no-op), documented a `CapabilityException` that does not
  exist (it is `CapabilityRequiredError`), showed `SynheartConfig` samples with
  empty `appId`/`subjectId` that `validate()` rejects, referenced an internal
  `Hsv` type by the wrong name, and stated no `minSdk` at all.
- **BREAKING (behavioural): `synheart_core_push_rr` was declared with the wrong
  arity.** The runtime takes a fourth `provider: *const c_char` argument; the
  JNA declaration had three. Every RR push read whatever happened to be in the
  provider register. `Synheart.pushRr` now takes a `provider` (default
  `"default_sensor"`).
- **`synheart_core_push_behavior` passed a `String` where the runtime takes a
  `c_int`.** Behavior events crossing the FFI boundary carried a pointer where
  an event code was expected. The code now comes from `RuntimeBehaviorEvent`.
- **`synheart_core_lab_is_available` is not a runtime symbol** — it is
  `synheart_core_is_lab_available`. `isLabAvailable` always threw
  `UnsatisfiedLinkError` internally.
- **Consent keys were not translated across the FFI boundary.** The runtime
  keys consent in snake_case (`cloud_upload`); the SDK API uses camelCase
  (`cloudUpload`). `grantConsent` / `revokeConsent` / `hasConsent` passed the
  caller's spelling through unchanged, so a grant the SDK recorded never
  reached the runtime's gate. Both spellings are now accepted and translated.
- **`Synheart.hasConsent` never consulted the runtime.** It read only the SDK's
  own snapshot, so it could not see the cloud gate and disagreed with
  `consentEffectiveState`. It now prefers the runtime, falling back to the
  snapshot when no runtime is loaded.
- **`SessionRecord` read `start_utc`, but the runtime emits `started_at_ms`.**
  Every record carried `startUtc == 0`, so orphan-session sweeps — which filter
  on `startUtc > 0` — silently matched nothing. Both spellings are now read,
  and `state` / `endedAtUtc` / `isActive` are parsed.
- **`Synheart.runtimeVersion` returned the whole diagnostics blob** instead of
  the runtime's semantic version.
- **A cleared HSI or stream callback could be collected while a native worker
  was still inside the dispatch path.** `synheart_core_clear_hsi_callback` only
  aborts the listener task without joining it, so releasing the JNA peer at
  clear time is a use-after-free. Retired callbacks are now retained until the
  handle is freed.

### Changed
- **BREAKING: module activation now follows the config.** Declaring
  `wearConfig`, `phoneConfig` or `behaviorConfig` activates that feature;
  omitting one leaves the module inert. `ActivationManager` previously turned
  wear, phone and behavior on unconditionally and ignored the config entirely, so
  a host could not run one collector without the others — and `deviceRole`,
  documented as controlling which modules are enabled, was read by nothing at
  all. This is the rule the sibling platform SDKs have always used.

  **A host that declares none of the three now collects nothing.** Add the module
  configs for the collectors you want. Activation is additionally intersected
  with `DeviceRole.supportedFeatures`, so a watch build cannot activate
  phone-context or behavior collection just because a config object was passed.
- **BREAKING: the SDK no longer ships a built-in API host.** `ApiEndpoints`
  resolves the platform origin from `ApiEndpoints.baseUrlOverride`, the
  `synheart.baseUrl` system property, or `SYNHEART_BASE_URL`, defaulting to
  empty — with none set, no origin is passed to the runtime and the runtime
  applies its own default. `CloudConfig.baseUrl`, `ConsentConfig.consentServiceUrl`
  and `LabIngestConfig.baseUrl` now default to `""` rather than
  `https://api.synheart.ai`. A host baked into the library becomes the
  destination for any build that forgot to name one, including forks and
  self-hosted deployments.
- **The runtime config handed to `synheart_core_new` is now built by
  `buildRuntimeConfigMap`** — a pure, unit-tested function shared by the facade
  and `SynheartInstance`. It adds the `org_id`, `client_id`, `data_dir`,
  `storage`, `ingest`, `device_auth`, `sync` and `privacy` keys the previous
  inline map omitted, and gates `ingest.*` on a non-empty org id and
  `device_auth.enabled` on a `DeviceAuthConfig` — enabling either without its
  prerequisite makes the runtime reject the whole config and return a null
  handle.

### Added
- **Watch sessions: `startWatchSession`, `stopWatchSession`, `getWatchStatus`,
  `watchSessionEvents`, `isWatchSessionActive`, `activeWatchSessionId`**, backed
  by a new `WatchSessionModule`. Runs a session on a paired Wear OS watch over
  the Wearable Data Layer, matching the shared facade.

  This needed `synheart-session` to publish the relay first: the implementation
  existed only inside the cross-platform session plugin's Android module, which
  declares no `maven-publish`, so it was compiled into a plugin AAR that nothing
  could depend on. See that repo's changelog.
- **Watch heart rate reaches the engine.** `WatchSessionModule` forwards the
  companion's samples into `pushWearHr`, so HSI has physiology on a phone that
  has no PPG of its own — without it every canonical axis stays at zero
  confidence however long the watch measures. `Synheart.watchHrSampleCount`
  reports how many arrived, separately from the session's event count: the two
  fail independently, since a companion can relay session events while its
  sensor reads nothing.
- **`Synheart.recordTouchEvent(MotionEvent)`** — the Android counterpart of the
  widget-tree gesture detector the sibling SDKs ship. Android has no equivalent
  hook, so a host forwards its `dispatchTouchEvent`
  and this derives tap and scroll events. Every host previously reimplemented
  the same bookkeeping, including the part that is easy to get wrong: recording
  per `ACTION_MOVE` floods the aggregator, since one drag dispatches dozens.
- **A real biosignal source on Android.** `SynheartWearSourceHandler` bridges
  `synheart-wear` (already a dependency) into `WearModule`, matching the wear
  source handler the sibling SDKs ship. Nothing in this SDK previously registered a
  wear source, so the only one that ever ran was the synthetic generator — and
  with that correctly disabled the wear module had no source at all, leaving
  biosignals reachable only if the host pushed them itself. Attached when the
  config declares `wearConfig`; Health Connect and BLE are enabled by default
  since neither needs vendor credentials.
- **`Synheart.requestWearPermissions()` / `wearPermissionStatus()` /
  `hasWearPermissions`.** Health Connect gates reads behind a runtime prompt, so
  a manifest declaration alone leaves the source polling an empty store forever —
  every sample arrives carrying nothing, which is indistinguishable from a
  paired-but-silent wearable. Keyed by permission name rather than
  `synheart-wear`'s `PermissionType`, which is not on a consumer's compile
  classpath.
- **`WearConfig`, `PhoneConfig` and `BehaviorConfig`**, matching
  the sibling platform SDKs. Declaring one activates that feature. The tuning
  fields (`sampleRateHz`, `motionSensitivity`, `enableGestureTracking`, …) are
  carried for parity and are not consumed by any collector in either SDK yet, so
  a config written against another platform SDK ports across unchanged.
- **`SynheartConfig.runtimeLogEnvFilter`** — a `tracing` filter for the native
  runtime's own logs, applied by `initialize()` before any native work. Without
  it the runtime logs nowhere, so the lines that explain a stalled integration —
  an unattestable device, a closed cloud gate, a failing ingest POST — do not
  exist, and the silence reads as "nothing happened" rather than "you never asked
  to be told".
- **`SynheartConfig.batchIngestOnStop`** — config-level default for the existing
  runtime property.
- **`WearSourceType.HEALTH_CONNECT`.**
- **`SynheartConfig.allowSyntheticBiosignals`** (default `false`) — opt-in for the
  synthetic wear generator. Development only: its samples enter the session engine
  and the runtime's SRM baselines exactly as real readings would, so it must never
  run against a real subject, and any UI showing the values should label them.
- **`HSIState.modalities` and `HSIState.tiers`**, derived from
  `meta.provenance.sources[*].signals` and `meta.synheart.tiers`. The five
  canonical axes are physiology-derived, so on hardware with no wearable they all
  sit at zero confidence while behavior and motion are in fact arriving — a
  consumer reading only the axes reasonably concludes the SDK is broken.
  `modalities` is what distinguishes "nothing was collected" from "signal
  arrived, just not physiological". Physiological tier reports the *worst*
  (highest) tier among contributing sources, since a fused window is only as
  trustworthy as its weakest input.
- **`HSIState.parseError` / `hasParseError`** — a failed parse yields all-null
  axes, previously indistinguishable from "the engine had no basis for any axis".
- **`HSIAxes.hasDigital`** — whether any interaction-derived axis resolved.
- **Full native ABI coverage.** `CoreRuntimeNative` now declares all 163
  `synheart_core_*` symbols the runtime exports (up from 82), each verified
  against the runtime's `extern "C"` signatures for arity, argument width and
  return type.
- **`SynheartInstance`** — a second, fully independent runtime handle with its
  own engine, storage and attested device identity, for running a research
  instance alongside the personal one.
- **Typed baseline snapshots** — `BaselineKind`, `BaselineEnvelope`,
  `HsiAxesBaseline`, `SessionSrmMetricsBaseline`, `LongitudinalWearBaseline`
  and the `Synheart.baselineSnapshots` facade, hydrated from local storage on
  init.
- **Cross-device sync** — `syncNow`, `checkSyncReadiness`, space create / join /
  recover / leave / delete, pairing, device listing and revocation, plus
  `SyncNativeError` / `SyncNativeException` so a failure envelope keeps its
  reason instead of collapsing to a bare null.
- **Realtime event stream** — `startEventStream` / `startVendorSync`,
  `rawRamenEvents` (with the ping-vs-stream `DeliveryHint`), `vendorEvents`,
  and `onDataDeletionUpdate`.
- **GDPR Article 17 surface** — `requestDataDeletion`, `dataDeletionStatus`,
  `listDataDeletions`, `deleteLocalData`, `deleteModuleData`, and the
  `DataDeletionRequest` / `DataDeletionEvent` models.
- **Scores** — `computeSleepScore`, `computeRecoveryScore`,
  `computeReadinessScore` (plus raw-JSON and traced variants),
  `attachSleepScore`, `attachRecoveryScoreToday`, and `wearableReference`.
- **Personalization** — `TaskType`, `FocusKind`, workout events,
  `personalizationContextJson`, and the SRM wearable-daily import path.
- **Cloud ingestion facade** — `Synheart.ingestion` (`SynheartIngestion`),
  `cloudSyncStatus`, HSI history (`listHsiHistory`, `fetchCloudHsiWindows`,
  `clearHsiHistory`), and last-upload bookkeeping.
- **Lab protocol surface** — start / open / close / finalize, metadata, and
  session re-enqueue.
- **Consent** — `ConsentForm`, `ConsentEffectiveState`, `ConsentTypeMeta`
  (`wireKey` / `runtimeKey` / `displayName`), `consentChanges`,
  `requestConsent`, `setConsentUIProvider`, and a `RESEARCH` consent type.
- **Collection control** — per-module `start*Collection` / `stop*Collection`,
  `wearSampleStream`, `behaviorEventStream`, behavior sessions with
  `BehaviorSessionResults`, and notification-listener helpers.
- **Runtime logging** — `initRuntimeLogging`, buffered mode with
  `drainRuntimeLogs`, and `runtimeDiagnostics()` annotated with the symbols the
  loaded library turned out not to export.
- **`SyniContextBuilder`** — projects live HSI plus session history into the
  Syni conditioning payload, skipping the heavy blocks for trivial messages.
- **`doc/INTEGRATION.md`** — the ordered walkthrough from an empty project to a
  device that uploads, adapted from the sibling SDKs to Gradle,
  `BuildConfig`, `jniLibs` and Play Integrity. Includes a symptom-keyed
  troubleshooting table and a pre-bug-report diagnostics checklist.
- **`example/README.md` and `example/SETUP.md`** — what the example
  demonstrates, and how to provision the native runtime and credentials.
- **`Synheart.isRuntimeAvailable`** and a stable `isAvailable` key in
  `runtimeDiagnostics()`. The runtime's own diagnostics shape is not guaranteed
  to carry one, and "did the native library load" is the first question to ask
  when no state arrives.
- **A version-sync guard** (`VersionSyncTest`) over `gradle.properties`,
  `SYNHEART_CORE_VERSION`, the README badge, and every README Maven coordinate.
  The README shipped `0.0.8` against a `0.1.0` build, so the documented
  dependency line was a version behind.
- **Example-app credentials are read from `example/env/synheart.credentials.json`**
  into `BuildConfig`, read at configure time. The key names match the credentials download from
  platform.synheart.ai so it can be dropped in whole. Populated files are
  gitignored; only the template is checked in. A missing file leaves every
  field empty and the SDK local-only, so a fresh clone still builds. Previously
  the example hardcoded `appId = "ai.synheart.example"` and set no `orgId` at
  all, so cloud ingest could never be exercised.
- **The example app packages the native runtime.** `:example` now points
  `jniLibs.srcDirs` at `example/synheart/vendor/runtime/android/jniLibs`
  (overridable via the `synheart.runtime.android.jniLibs` property), mirroring
  the sibling SDKs. AGP reads only `src/main/jniLibs` by default, so the
  vendored `libsynheart_core_runtime.so` was present on disk but absent from
  the APK — every FFI call would have degraded to "native library not loaded".
- **`.gitignore` now covers the vendored native binaries** (`*.so`, `*.dylib`,
  `native/`, `example/synheart/vendor/`), matching the sibling SDKs. The
  vendored tree is ~1.8 GB and was previously untracked but unignored, so a
  `git add .` would have tried to commit it.
- **The example app is now a real Gradle module** (`:example`) that CI
  compiles. It was previously two loose source files nothing referenced, and
  had drifted out of sync with the SDK — `CanonicalExample` read
  `HSIState.hsiVersion` and `.observedAtUtc`, neither of which has ever existed
  in any platform SDK. It is not published.
- **`SYNHEART_CORE_VERSION`**, `BoundedBuffer`, `HsiDeliveryDeduper`,
  `MotionStateSnapshot`, `WearModuleStatus`, `HsiAxes`, and `SyncResult` /
  `SyncStatus`.

### Known gaps
- **`minSdk 24` is not achievable by consumers.** `:synheart-core` declares
  `minSdk 24`, but it depends on `ai.synheart:syni:0.0.3`, whose manifest
  declares `minSdkVersion 26`. A library module never runs the full manifest
  merge, so this was invisible here; a consumer **app** at 24 fails with
  `uses-sdk:minSdkVersion 24 cannot be smaller than version 26 declared in
  library [ai.synheart:syni:0.0.3]`. The example app is pinned to 26 to build.
  Either raise the SDK's `minSdk` to 26, or make the Syni dependency optional
  so a host that does not use it can stay at 24.
- **Watch-initiated start does not reach the phone.** The phone drives the
  watch (`startWatchSession` / `stopWatchSession`) and receives its heart rate,
  but a session begun on the watch itself raises no phone-side event, so the
  two sides can disagree about whether a session is running.

### Distribution
- Maven Central: `ai.synheart:synheart-core:0.2.0`

## [0.1.0] - 2026-06-29

### Added
- **Cloud consent token binding** — `Synheart.ensureCloudConsentReady()`,
  `Synheart.subjectId`, and `consentTokenSubjectStale()`. Mints/refreshes a
  consent token scoped to the current subject (configure-cloud on init,
  mint-on-grant, init self-heal) so uploads are attributed to that subject.

### Removed
- **BREAKING:** deprecated `PhoneContextConsent.motion` / `.screenState` and
  `BehaviorConsent.enabled` aliases — use `deviceMotion` / `systemState` /
  the individual behavior channels.

### Distribution
- Maven Central: `ai.synheart:synheart-core:0.1.0`

## [0.0.8] - 2026-06-17

### Added
- **`HSIAxes.stress`** — typed accessor for the engine's multimodal stress
  reading (engine v0.10.0; HSI 1.3 `axes.affective[].stress`). Hosts get a named
  field instead of digging through `rawJson`. Resolves to `null` on the legacy
  1.2 path that never carried it. Parity with the Dart/Swift bindings.
- **`EdgeIngest`** — canonical phone-side consumer of the Synheart edge wire
  contract (watch → phone).
  Pure-JVM (no Android / Play Services dependency), unit-tests under plain JUnit.
  Parses `hr_sample` / `bio_sample` / `hsi_artifact` / session events and, for
  artifacts, dedupes by `artifact_id`, verifies `payload_hash_sha256` ==
  sha256(`payload_json`), validates the inner `hsi_version` against the supported
  set, and produces the `artifact_ack` body. Public surface:
  - Sealed `EdgeEvent` family (`HrEvent | BioEvent | ArtifactEvent |
    SessionEventWrap`) plus the typed payloads (`HrSample`, `BioSample`,
    `HsiArtifact`, `Accel`).
  - Reactive `events: SharedFlow<EdgeEvent>` hot stream, emitting in lock-step
    with the `Listener` callbacks (parity with the Swift `events` publisher and
    Dart `Stream<EdgeEvent>`).
  - `Listener` callbacks, including the Kotlin-only observability hooks
    `onUnsupportedHsiVersion(...)` / `onHashMismatch(...)` and the poison-pill /
    dead-letter hook `onPoisonPill(artifactId, expected, actual, attempts)`.
  - ACK helpers `drainPendingAcks()` / `buildAckBody(...)` / `drainAckBody()`.
  - Delivery hardening (the watch outbox is delete-on-ACK):
    - **Duplicate re-ack** — a duplicate `artifact_id` is not re-surfaced but is
      re-queued for ACK, so a lost ACK no longer makes the watch resend forever.
    - **Bounded dedupe set** — the seen-artifact set is a bounded LRU
      (`SEEN_LRU_CAPACITY`), keeping memory flat over a long-lived process.
    - **Poison-pill dead-letter** — an artifact that fails hash verification
      `POISON_PILL_THRESHOLD` (3) times for the same id is dead-lettered (reported
      via `onPoisonPill` and ack-to-discarded) so a deterministically-corrupt
      artifact stops blocking the outbox.
- **`EdgeIngestService`** — opt-in `WearableListenerService` adapter that decodes
  the Wear Data Layer `path`/`type` into `EdgeIngest` and sends the
  `artifact_ack` back via `MessageClient`. Not wired in by default; hosts opt in
  via manifest + `EdgeIngestService.bindings`.

### Changed
- The Wear Data Layer dependencies (`play-services-wearable`,
  `kotlinx-coroutines-play-services`) are now `compileOnly` instead of
  `implementation`, so consumers no longer inherit them transitively. The pure
  `EdgeIngest` core needs neither; hosts using the opt-in `EdgeIngestService`
  adapter must add `play-services-wearable` to their own build. No runtime
  behavior change.

## [0.0.7] - 2026-06-07

### Added
- `Synheart.requestStudyDataDeletion(dryRun)`: request erasure of the data the
  participant contributed to their study for this app — the deletion the consent
  copy promises alongside withdrawal. No identifiers are passed; the participant
  and app come from the device's signed credential. `dryRun` returns an inventory
  preview without deleting; a real request is accepted asynchronously and carries
  a `request_id`. Idempotent.

### Build
- Migrated the Dokka Gradle plugin from the deprecated **V1** to **V2**
  (`org.jetbrains.dokka` `2.0.0`, `pluginMode=V2EnabledWithHelpers`). Dokka V2's
  newer Kotlin analysis reads the binary `ai.synheart:syni:0.0.3` metadata that
  crashed AGP's `javaDocReleaseGeneration` under Dokka V1.
- Removed the empty-javadoc-jar workaround. The published `*-javadoc.jar` is now
  generated by Dokka (`dokkaGeneratePublicationJavadoc`, javadoc format) and
  ships real API documentation again.

## [0.0.6] - 2026-06-07

### Added
- Research-study enrolment API: `Synheart.enrolResearchStudy(accessCode, studyCode)`,
  `Synheart.validateResearchStudyCodes(accessCode, studyCode)`, and
  `Synheart.withdrawResearchStudy()`. Enrolment rides the device's signed cloud
  credential — no tokens are handled by the caller. Withdrawal is idempotent.

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

[0.2.0]: https://github.com/synheart-ai/synheart-core-kotlin/releases/tag/v0.2.0
[0.1.0]: https://github.com/synheart-ai/synheart-core-kotlin/releases/tag/v0.1.0
[0.0.8]: https://github.com/synheart-ai/synheart-core-kotlin/releases/tag/v0.0.8
[0.0.7]: https://github.com/synheart-ai/synheart-core-kotlin/releases/tag/v0.0.7
[0.0.6]: https://github.com/synheart-ai/synheart-core-kotlin/releases/tag/v0.0.6
[0.0.5]: https://github.com/synheart-ai/synheart-core-kotlin/releases/tag/v0.0.5
[0.0.4]: https://github.com/synheart-ai/synheart-core-kotlin/releases/tag/v0.0.4
