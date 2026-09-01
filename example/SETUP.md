# Example app setup

Two things the app needs beyond a clone: the **native runtime** (for any HSI at
all) and **credentials** (for cloud upload). Neither is committed.

The app builds and runs without both — it just stays local-only and produces no
state. Nothing fails loudly, which is why each step below ends in a check.

For the platform side — creating the organization, tenant, app, app policy and
consent profile that cloud upload needs — see
[../doc/INTEGRATION.md](../doc/INTEGRATION.md). Cloud upload does not work
without a consent profile for the app id, and that failure is easy to misread as
a consent problem.

---

## Baseline: no credentials

```bash
synheart install runtime
./gradlew :example:installDebug
```

The config the app builds is in `sdk/SynheartController.kt` (`buildConfig()`),
and the Setup screen prints it verbatim so you can copy it:

```kotlin
SynheartConfig(
  appId = "ai.synheart.core.example",  // required
  subjectId = <persisted, stable>,     // required
  appVersion = "1.0.0",
  deviceId = <persisted>,
  mode = SynheartMode.PERSONAL,

  // Development only. Production gates capabilities on a verified consent token.
  allowUnsignedCapabilities = true,

  // Declaring a module config activates that feature; omitting one leaves that
  // module inert. Read back what ended up active with Synheart.isActivated(...).
  wearConfig = WearConfig(),
  phoneConfig = PhoneConfig(),
  behaviorConfig = BehaviorConfig(),

  // Surfaces the runtime's own logs. Without a filter the runtime logs nowhere,
  // so the lines explaining a stalled integration simply do not exist.
  runtimeLogEnvFilter = "info",

  // Required for the runtime consent-form flow — consentSubmitFormTyped needs a
  // non-empty deviceId and platform, or consent can never be written.
  consentConfig = ConsentConfig(
    deviceId = <persisted>,
    platform = "android",
    userId = <subjectId>,
  ),
)
```

Two fields are non-negotiable:

- **`appId`** and **`subjectId`** are required. `validate()` rejects an empty
  value before any native work happens.
- **`subjectId` must be stable across restarts.** The runtime scopes storage,
  baselines, and device identity to it, so a value that changes per launch looks
  like a new person every time and baselines never mature. Passing `userId` to
  `initialize()` does **not** populate it — set it on the config.

### Behavior collection needs a host-side hook

The SDK has no view-tree hook. Nothing observes taps until the host records
them:

```kotlin
override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
        Synheart.behaviorEvents?.recordTap(ev.x.toDouble(), ev.y.toDouble())
    }
    return super.dispatchTouchEvent(ev)
}
```

Activating `SynheartFeature.BEHAVIOR` and granting behavior consent is not
enough on its own. `MainActivity` does this; a host that skips it collects no
behavior and sees no digital modality, with no error.

### One `startSession()`, not two paths

`startSession()` brings the module manager up and then starts every module whose
activation, consent and capability line up. Do **not** also call
`startWearCollection()` / `startBehaviorCollection()` — those are the granular
API for running one collector without a session, and a module's `start()`
**throws** if it is already running, failing the session with *"Module must be
initialized or stopped before starting"*.

The corollary is that `Synheart.isWearCollecting` reads **false** after a plain
`startSession()`, because it tracks only the granular API. Use
`Synheart.isFeatureOperational(SynheartFeature.WEAR)` — the real conjunction the
SDK enforces.

---

## Native runtime

State is computed in a native runtime loaded over JNA. It is not in git and not
in the published AAR.

```bash
synheart install runtime
```

That writes to `example/synheart/vendor/runtime/android/jniLibs/<abi>/`. The
module already points at that path (`jniLibs.srcDirs` in `example/build.gradle`),
because AGP reads only `src/main/jniLibs` by default.

**Check it landed in the APK:**

```bash
./gradlew :example:assembleDebug
unzip -l example/build/outputs/apk/debug/example-debug.apk | grep libsynheart_core_runtime
```

Three entries (arm64-v8a, armeabi-v7a, x86_64) means it worked. Nothing means
the APK shipped without a runtime — re-run the install, then `./gradlew clean`.
A stale build directory will happily keep shipping the old artifacts.

At runtime:

```kotlin
println(Synheart.isRuntimeAvailable)   // false = not bundled
```

---

## Credentials

Download the credentials file from
[platform.synheart.ai](https://platform.synheart.ai) after creating the app —
the four ids are issued together:

```json
{ "org_id": "org_…", "tenant_id": "ten_…", "project_id": "prj_…", "app_id": "app_…_and_…" }
```

All four are **identifiers, not secrets**; none is an API key. They are
organization-specific, so the populated file stays out of git.

The template uses the same key names as the download, so drop it straight in:

```bash
cp ~/Downloads/app-credentials*.json example/env/synheart.credentials.json
```

Then add the two fields the download does not carry:

```jsonc
{
  "org_id":       "org_…",             // used — gates cloud ingest
  "app_id":       "app_…_and_…",       // used — the ingest scope resolves from this
  "tenant_id":    "ten_…",             // not used by this SDK
  "project_id":   "prj_…",             // not used by this SDK

  "base_url":     "https://api.synheart.ai",
  "package_name": "ai.synheart.core.example"
}
```

`example/env/*.json` is gitignored apart from the template, so a populated file
stays on your machine. The build reads it into `BuildConfig` — the Kotlin
read at configure time. A missing or partial file is
fine: every field falls back to empty and the app runs local-only, so a fresh
clone builds without anyone's organization ids.

**Check they were picked up:** the Setup tab's **Credentials** card lists every
key with whether it was supplied, whether this SDK consumes it, and what its
absence costs. A freshly dropped-in platform download reads `2/4 used` with
`base_url` marked **missing** — that is the field the download does not carry,
and it is why *Device attestation* says `off`.

Or from the shell:

```bash
./gradlew :example:assembleDebug
grep SYNHEART_ example/build/generated/source/buildConfig/debug/ai/synheart/core/example/BuildConfig.java
```

### Three notes

- **`org_id` is load-bearing.** The runtime rejects a configuration that enables
  cloud ingest without one — `synheart_core_new` returns a null handle rather
  than a targeted error, so initialization looks like it succeeded while nothing
  works. With no `org_id` the example passes no `CloudConfig` at all, keeping
  ingest gated off instead of failing to start.
- **`tenant_id` and `project_id` are not used by this SDK.** The runtime config
  carries `app_id` and `org_id` and nothing else. They are in the template so a
  credential file can be transcribed whole.
- **The app id is not the package name.** `app_id` is the platform-issued
  `app_…` identifier an ingest scope resolves from; `package_name` is the real
  installed bundle id that Play Integrity verifies against. Passing the `app_…`
  value as the package name fails attestation.

---

## Cloud upload also needs a consent profile

Create a default consent profile for the app id in the dashboard. Without one
the consent service never issues a token, and the runtime holds the cloud gate
closed — so **every consent type reads as denied regardless of what the user
granted**. The symptom is a consent UI showing "granted" while nothing uploads.

```kotlin
Synheart.ingestion.flushIfEligible()   // errorMessage explains a closed gate
```

---

## Enabling device attestation and cloud upload

These are **two independent opt-ins**, and attestation does not need an org id.

- `base_url` alone adds a `DeviceAuthConfig`, and that is all attestation needs
  — every registration trigger in the SDK keys off `DeviceAuthConfig` and none
  of them consults the cloud config.
- `org_id` adds a `CloudConfig` on top, which is what enables HSI upload. Cloud
  ingest stays disabled without a non-empty org id, because the runtime rejects
  an entire configuration whose ingest is enabled without one.

Upload also depends on attestation: the runtime signs every ingest request with
the device key, so an org id alone does nothing.

### When attestation actually runs

**Registration is triggered by cloud-upload consent, not by `initialize()`.**
Configuring `DeviceAuthConfig` only makes it *possible*. The flow starts at
whichever of these happens first:

| Stage | Trigger |
| --- | --- |
| `initialize()` | only if cloud consent was already granted in a previous launch |
| `grantConsent("cloudUpload")` | starts registration in the background |
| `consentSubmitFormTyped(form.copy(allowCloud = true))` | same, via the runtime consent form |
| `startSession()` | preflight; skipped entirely when cloud consent is off |
| `ensureDeviceAuthRegistered()` | explicit and idempotent |
| `reregisterDeviceAuth()` | forced re-attestation |

The two consent paths deliberately **do not await** registration. The flow binds
Play Integrity, mints a key, and performs an HTTPS round-trip; awaiting it on a
consent handler can block long enough to trip the OS watchdog. Poll
`Synheart.coreDeviceAuthStatus()` instead.

### Testing it

1. Put a `base_url` in `env/synheart.credentials.json` (an org id is not needed
   to test attestation) and reinstall.
2. **Setup** tab → Initialize SDK. The *Device attestation* card shows
   `pending`, with `status` from the runtime.
3. **Consent** tab → enable **Cloud upload** → Submit. This is the moment
   registration begins.
4. Back on **Setup**, the card moves to `registered` and shows the device id.

*Register now* forces an attempt without changing consent (idempotent — it
no-ops when already registered). *Re-attest* bypasses locally restored state,
which is what you want when the server has lost or revoked the device record.

### Attestation on an emulator

A debug build or emulator usually cannot produce Play Integrity material. To let
the **server** decide instead of the client giving up:

```kotlin
DeviceAuthConfig(authBaseUrl = …, allowUnattestedDevRegistration = BuildConfig.DEBUG)
```

It sends the registration carrying `attestation.format = "none"` and an empty
blob — nothing fake is transmitted. Not a security bypass, and the flag alone
does nothing: acceptance still requires development mode on the app's record in
the dashboard, and it must be a **development** app id. A device admitted this
way is recorded `unattested` — it holds a real hardware key and signs every
request, it just carries no provenance claim, which is why the Setup screen
shows `attestation` separately from `status`.

Gate it on `BuildConfig.DEBUG` so a store build can never ship it enabled, as
the controller does.

> Never put an API key or signing secret in an app bundle. `CloudConfig.apiKey`
> and `ConsentConfig.appApiKey` are deprecated and are not forwarded to the
> runtime — requests are signed with the attested device identity instead.

---

## Troubleshooting

**Configuration rejected on Initialize.** The message names the field and the
fix. The usual cause is a missing `appId` or `subjectId`, or passing `userId` to
`initialize()` and expecting it to populate `config.subjectId`.

**Native runtime reports "missing" on the Runtime tab.** Run
`synheart install runtime`, then `./gradlew clean :example:installDebug`. A
stale build directory will keep shipping the old artifacts.

**Symbols listed under Native symbols.** The vendored runtime predates this SDK
release. The features behind those symbols return `null` / `-1` / empty rather
than throwing. Run `synheart install runtime` to update it. Note the inverse is
not proof of health: the list only names symbols something already tried to
resolve, so an empty list means "nothing has failed yet".

**Session starts but no HSI.** Check the Signal sources card. HSI physiology is
built from heart rate and HRV, so with no wearable paired and no Health Connect
access, every axis stays at zero confidence by design. Watch the **carrying
data** count, not **samples emitted** — the wear source emits an empty sample
every poll tick either way. Behavior does reach the runtime; it shows up as the
digital modality, and digital readings lag by one window.

**Start session is disabled.** Grant biosignals, behavior, or phone context.
Cloud upload, vendor sync, and research do not make any sensor readable on their
own, so a session granted only those would collect nothing.

**Behavior events stay at 0.** Nothing records them unless the host does — see
*Behavior collection needs a host-side hook* above.

**Attestation stays `pending`.** Check the *Device attestation* card's
`ABI available` row first — but only after Initialize, since before that it
reads `unknown — runtime not loaded` rather than a verdict. `false` on a loaded
runtime means it does not export `sdk_build_proof_header`, so run
`synheart install runtime`. If it is true and status
never advances, the device cannot attest (emulator, rooted, or a build without
Play Integrity); the SDK stays local-only by design rather than failing the
session.

---

## Security notes

- Never commit credentials. Put them in `env/synheart.credentials.json`, which
  is gitignored. Only `env/synheart.credentials.example.json` — placeholders —
  is checked in.
- `allowUnsignedCapabilities = true` is development-only. It disables the
  capability lattice; production drives it from a verified consent token.
- The Runtime tab's **Wipe local data** clears the runtime SQLite store, the SRM
  snapshot, and cached consent records for the current subject.

---

## See also

- [../doc/INTEGRATION.md](../doc/INTEGRATION.md) — the ordered integration path
- [README.md](README.md) — what the example demonstrates
