# Integration guide

The ordered path from an empty Android project to a device that collects HSI
locally and uploads it. Each part ends in something you can verify, so a
failure is attributable to the step that caused it rather than discovered three
steps later.

The SDK reference lives in [README.md](../README.md). This document is the
walkthrough: what to do, in what order, and what each step is for.

**Contents**

1. [Platform prerequisites](#1-platform-prerequisites)
2. [Install](#2-install)
3. [Configure](#3-configure)
4. [Consent](#4-consent)
5. [Collect](#5-collect)
6. [Upload](#6-upload)
7. [Device attestation](#7-device-attestation)
8. [Troubleshooting](#8-troubleshooting)

---

## 1. Platform prerequisites

Everything here happens at [platform.synheart.ai](https://platform.synheart.ai)
before you write any code. **The SDK runs local-only with none of it** — skip
to [Install](#2-install) if you only need on-device HSI. Cloud upload needs all
of it.

The chain is organization → tenant → project → app. Each one scopes the next,
and the app is what an ingest scope ultimately resolves from.

### 1.1 Download the credentials

After creating the app, download its credentials file. The four ids are issued
together:

```json
{ "org_id": "org_…", "tenant_id": "ten_…", "project_id": "prj_…", "app_id": "app_…_and_…" }
```

All four are **identifiers, not secrets** — none is an API key. They are
organization-specific, so a populated file stays out of git.

Only `org_id` and `app_id` are used by this SDK. `tenant_id` and `project_id`
exist so a credential file can be transcribed whole; setting them changes no
behavior here.

### 1.2 Create a consent profile

The app needs a default consent profile before the consent service will issue a
token. Without one, every mint fails with `PROFILE_NOT_FOUND`, and — because
the runtime holds the cloud gate closed until a token exists — **every consent
type reads as denied regardless of what the user granted**. This is the single
most common cause of "consent is on but nothing uploads".

---

## 2. Install

### 2.1 Add the dependency

```gradle
dependencies {
    implementation 'ai.synheart:synheart-core:0.2.0'
    coreLibraryDesugaring 'com.android.tools:desugar_jdk_libs:2.0.4'
}

android {
    defaultConfig {
        // 26, not 24 — see the note in the README's Requirements table.
        minSdk 26
    }
    compileOptions {
        coreLibraryDesugaringEnabled true
        sourceCompatibility JavaVersion.VERSION_17
        targetCompatibility JavaVersion.VERSION_17
    }
}
```

### 2.2 Provision the native runtime

State is computed in a native runtime loaded over JNA. It is **not** bundled in
the published AAR:

```bash
synheart install runtime
```

That writes the binaries to
`<your-app-module>/synheart/vendor/runtime/android/jniLibs/<abi>/`. AGP reads
only `src/main/jniLibs` by default, so point your module at the vendored path:

```gradle
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

Without this the APK builds cleanly and ships no runtime.

### 2.3 Verify before writing any integration code

```kotlin
println(Synheart.isRuntimeAvailable)                       // true once the bridge loaded
println(Synheart.runtimeDiagnostics()?.opt("missingSymbols")) // empty on a matching runtime
```

`isRuntimeAvailable == false` means the library was not bundled — usually a
build that predates `synheart install runtime`, or the missing `jniLibs.srcDirs`
above. Run `./gradlew clean` and rebuild; a stale build directory will happily
keep shipping the old artifacts.

A non-empty `missingSymbols` means the runtime loaded but is a different version
from the one this SDK expects. Symbols bound through the guarded path degrade to
null/false rather than crashing, so check this explicitly instead of discovering
it later as a feature that silently does nothing.

---

## 3. Configure

### 3.1 Identity

```kotlin
SynheartConfig(
    appId = BuildConfig.SYNHEART_APP_ID,
    subjectId = stableSubjectId,          // NOT a per-launch UUID
    allowUnsignedCapabilities = true,     // dev only
)
```

`subjectId` is the identity everything attaches to — HSI uploads, baselines,
consent tokens, device identity. A value that changes per launch looks like a
new person every time and baselines never mature. Passing `userId` to
`initialize()` does **not** populate it; set it on the config.

Both `appId` and `subjectId` are required — `validate()` rejects an empty value
with `ERR_NOT_CONFIGURED` before the runtime is touched.

### 3.2 Endpoints

The SDK ships **no built-in API host**. A host baked into the library becomes
the destination for any build that forgot to name one, including forks and
self-hosted deployments. Resolution order, first non-empty wins:

1. `ApiEndpoints.baseUrlOverride` (and the per-service overrides)
2. the `synheart.baseUrl` system property
3. the `SYNHEART_BASE_URL` environment variable

```kotlin
ApiEndpoints.baseUrlOverride = BuildConfig.SYNHEART_BASE_URL
```

With none set, no origin is passed to the runtime and the runtime applies its
own default. That is correct for a local-only build, which makes no network
calls at all — but a **cloud** build that forgets to name an origin inherits
the default rather than failing. Check `apiBaseUrlConfigured(config)` before
enabling anything cloud-bound.

Prefer setting the one base URL over a per-service override. Setting only one
override produces the confusing failure where authentication works and ingest
does not, or vice versa.

### 3.3 Cloud identity

```kotlin
cloudConfig = if (orgId.isEmpty()) null else CloudConfig(
    subjectId = subjectId,
    orgId = orgId,
)
```

`org_id` is load-bearing. The runtime **rejects the entire configuration** when
cloud ingest is enabled without one, and `synheart_core_new` returns a null
handle rather than a targeted error — so initialization appears to succeed
while nothing works. Pass no `CloudConfig` at all when you have no org id; the
SDK then runs local-only.

### 3.4 Keep populated files out of git

The credential ids are not secrets, but they are organization-specific. Keep
the populated file local and check in only a placeholder template:

```gitignore
example/env/*.json
!example/env/synheart.credentials.example.json
```

The vendored runtime is large (the full tree runs to gigabytes) and must never
be committed:

```gitignore
*.so
*.dylib
native/
example/synheart/vendor/
```

---

## 4. Consent

### 4.1 The canonical flow

```kotlin
Synheart.setConsentUIProvider { profiles -> showYourConsentSheet(profiles) }
Synheart.requestConsent()
```

Or grant channels directly:

```kotlin
Synheart.grantConsent("biosignals")
Synheart.grantConsent("cloudUpload")   // mints a consent token as a side effect
```

Both camelCase (`cloudUpload`) and the runtime's snake_case (`cloud_upload`) are
accepted and translated. The two vocabularies are real: the runtime keys consent
in snake_case, the SDK API and `ConsentSnapshot` use camelCase.

### 4.2 Gate on the effective state, never the form

```kotlin
val state = Synheart.consentEffectiveStateTyped()
if (state?.cloudUpload == true) { /* … */ }
```

The form (`consentGetEditableFormTyped()`) is what the user is *editing*. The
effective state is what is *in force*.

### 4.3 `hasConsent()` answers a different question

`hasConsent()` reports whether a channel is **enforceable right now**, which is
not the same as what the user chose. Once a cloud consent client is configured,
the runtime returns false for every type until the consent service has issued a
token:

```rust
if cloud_configured && self.consent_status() != ConsentStatus::Granted {
    return false;
}
```

So a false result does **not** mean the user declined. Use `hasConsent()` to
gate an action that must not proceed without cloud confirmation — an upload.
Use `consentEffectiveStateTyped()` to render UI.

### 4.4 Activation and consent are both required

A feature collects only when all four hold:

```
operational = activated && consented && capability-allowed && session-running
```

Activating `WEAR` without granting `biosignals` produces nothing, and so does
granting consent without `startSession()`. There is no error in either case,
because neither is a failure. `Synheart.isFeatureOperational(feature)` collapses
all four into one answer.

---

## 5. Collect

```kotlin
Synheart.activate(SynheartFeature.WEAR)
Synheart.grantConsent("biosignals")
Synheart.startSession()

Synheart.onStateUpdate.collect { state -> render(state) }
```

### What grounds which axes

| Axis family | Needs |
|---|---|
| Physiological (arousal, recovery, stress) | R-R intervals or heart rate |
| Kinematic | accelerometer |
| Digital | interaction events |

**Interaction alone produces HSI.** With no wearable attached, feed touches and
you still get digital axes:

```kotlin
Synheart.pushBehaviorTouch()
```

On a bare phone with no biosignal source, windows arrive with physiological
axes at `confidence: 0`. That is expected, not a fault.

### Push R-R in batches

When a packet carries several intervals sharing one arrival timestamp — the BLE
Heart Rate Measurement convention — push them together:

```kotlin
Synheart.pushRrBatch(anchorTsMs, rrMs = doubleArrayOf(812.0, 798.0, 805.0), order = 0)
```

Looping `pushRr` per value discards the inter-beat timing that RMSSD is computed
from.

---

## 6. Upload

Uploads are driven by the runtime on its own cadence once the gate is open. The
gate needs: cloud consent granted, a consent token issued, a registered device,
and an `org_id`.

```kotlin
when (Synheart.cloudSyncStatus) {
    CloudSyncStatus.LOCAL_ONLY -> "Nothing leaves the device"
    CloudSyncStatus.SYNCING    -> "Uploading…"
    CloudSyncStatus.SYNCED     -> "Up to date"
    CloudSyncStatus.PENDING    -> "Will sync when ready"
}
```

Do not surface `uploadQueueLength` to end users — it fluctuates on every flush
tick and reads as alarming noise. Drive user-facing copy from `cloudSyncStatus`
or `lastIngestSuccessAtMs`.

To force a flush and see why one failed:

```kotlin
val result = Synheart.ingestion.flushIfEligible()
if (!result.success) println(result.errorMessage)   // explains a closed gate
```

---

## 7. Device attestation

### 7.1 What triggers it

Registration happens on the first cloud-bound operation, or explicitly:

```kotlin
Synheart.ensureDeviceAuthRegistered()
```

It needs a `DeviceAuthConfig`. Without one, `device_auth.enabled` stays false
and the SDK is local-only by construction.

### 7.2 The app id is not the package name

```kotlin
DeviceAuthConfig(
    authBaseUrl = ApiEndpoints.resolvedAuthBaseUrl,
    packageName = "com.example.app",   // the REAL installed bundle id
)
```

`app_id` is the platform-issued `app_…` identifier an ingest scope resolves
from. `packageName` is what Play Integrity verifies against. Passing the
`app_…` value as the package name fails attestation.

### 7.3 Reading a failure

```kotlin
try {
    Synheart.syncNow()
} catch (e: SyncNativeException) {
    when {
        e.isUnsupported   -> degradeToLocalOnlyPermanently()
        e.isMisconfigured -> reportToDeveloper(e)
        e.retryable       -> scheduleRetry(e.retryAfterMs)
        else              -> showError(e.message)
    }
}
```

Branch on `reason` / the `is*` helpers, not `retryable` alone. `retryable` says
whether to try again; `reason` says whether it can ever work:

- `unsupported` — no attestation material and never will be on this hardware
  (emulator, de-Googled ROM, no Play Services). Stop asking, across relaunches.
- `misconfigured` — setup is wrong. A human has to fix it.
- `policy` — the server refused this device. It could attest; it was declined.

### 7.4 Development builds that cannot attest

```kotlin
DeviceAuthConfig(
    authBaseUrl = …,
    allowUnattestedDevRegistration = true,   // debug builds only
)
```

This is **not** a security bypass. It only stops the client giving up early —
the registration is submitted with `attestation.format = "none"` and the
**server** decides. Acceptance still requires development mode on the app's
record in the dashboard. Never enable it on a production app id; use a separate
development app id.

---

## 8. Troubleshooting

Keyed on what you will actually see.

| Symptom | Cause | Fix |
| --- | --- | --- |
| "Initialization complete" but `isRuntimeAvailable` is false | Native runtime not bundled | `synheart install runtime` + the `jniLibs.srcDirs` wiring ([2.2](#22-provision-the-native-runtime)), then `./gradlew clean` |
| `uses-sdk:minSdkVersion 24 cannot be smaller than version 26` | `syni` requires 26 | Set your app to `minSdk 26` |
| `requires core library desugaring to be enabled` | Transitive requirement | Enable it ([2.1](#21-add-the-dependency)) |
| `ERR_NOT_CONFIGURED: appId must not be empty` | `appId` / `subjectId` unset | Both are required |
| Initialization "succeeds" but nothing works, no error | Cloud ingest enabled with no `org_id` — null handle | Pass no `CloudConfig` when you have no org id ([3.3](#33-cloud-identity)) |
| Activated and consented, still no data | No session | `Synheart.startSession()` ([4.4](#44-activation-and-consent-are-both-required)) |
| Windows arrive, all axes `confidence: 0` | No biosignal source | Expected on a bare phone. Read the digital axes, or attach a BLE strap |
| `PROFILE_NOT_FOUND` on the consent profile | No consent profile for the app id | [1.2](#12-create-a-consent-profile) |
| "cloudUpload consent not granted" while the UI shows it granted | No consent token issued | Almost always `PROFILE_NOT_FOUND` above |
| `hasConsent()` false but the user granted it | Cloud gate closed, not a refusal | [4.3](#43-hasconsent-answers-a-different-question) |
| `REGISTRATION_REJECTED`, `reason: policy` | Server refused this device | Check the app id is provisioned in this environment |
| `no attestation material` | Debug build or emulator | [7.4](#74-development-builds-that-cannot-attest) |
| Auth works, ingest does not (or vice versa) | Split endpoints | Set the one base URL, not a single per-service override ([3.2](#32-endpoints)) |
| A feature silently does nothing on an older runtime | Symbol absent, degraded to null | Check `runtimeDiagnostics()["missingSymbols"]` |

### Checklist before filing a bug

```kotlin
Synheart.isRuntimeAvailable                 // did the library load
Synheart.runtimeDiagnostics()               // includes missingSymbols
Synheart.consentEffectiveStateTyped()       // what the user actually has
Synheart.hasConsent("cloudUpload")          // whether it is enforceable
Synheart.coreDeviceAuthStatus()             // registration + attestation
Synheart.uploadQueueLength                  // is anything queued
Synheart.lastUploadError                    // why the last attempt failed
```

---

## See also

- [README.md](../README.md) — full API reference
- [example/](../example) — a runnable app that walks this same sequence
