# Synheart Core SDK example

A small reference app that walks the SDK lifecycle in the order a host app
performs it. Every SDK call lives in one file, so the integration reads
top-to-bottom rather than being scattered across screens.

**It runs with no credentials.** No `CloudConfig`, no `DeviceAuthConfig` —
collection, HSI computation, consent, and local storage all work offline.

Cloud upload and device attestation are opt-in, and independent of each other.
The Setup tab shows attestation progress live. Note that registration is
triggered by **cloud-upload consent**, not by `initialize()`.
See [SETUP.md](SETUP.md).

## Running it

**Local-only — no credentials, works on any device:**

```bash
./gradlew :example:installDebug
```

**With credentials.** Copy the template, fill in the ids you were issued at
platform.synheart.ai, and rebuild:

```bash
cp env/synheart.credentials.example.json env/synheart.credentials.json
# edit env/synheart.credentials.json
./gradlew :example:installDebug
```

`env/*.json` is gitignored apart from the template, so a populated file stays on
your machine. Gradle reads it at configure time and surfaces the values as
`BuildConfig` fields. An absent or partial file is fine: every field falls back
to empty and the SDK runs local-only.

The Setup tab's **Credentials** card shows which keys were picked up and what
each missing one costs — the fastest way to see why attestation or upload is
off. The platform download carries the four ids only; `base_url` is not among
them and is what gates both.

**One origin, not two.** Every per-service URL resolves from a single origin
through `ApiEndpoints`. Set `base_url` and device auth, consent, and ingest all
follow it — there is no way to split a run across two environments by setting
only half of a pair.

**Attestation only**, without enabling upload — an org id is not required. Set
`base_url` and leave `org_id` empty.

## Prerequisites

The native runtime installed into this directory:

```bash
# Install the CLI once.
curl -fsSL https://synheart.sh/install | sh
synheart login

# Run from this directory (example/).
synheart install runtime
```

`synheart install runtime` places the `.so` files at
`example/synheart/vendor/runtime/android/jniLibs/`, which `build.gradle` adds to
`jniLibs.srcDirs`. AGP only picks up `src/main/jniLibs` by default, so without
that wiring the runtime is never packaged and the SDK falls back to "native
library not loaded" with no error.

The app builds and runs without it, but `Synheart.isRuntimeAvailable` is false
and no HSI ever arrives — check the **Runtime** tab first.

### Android

Health Connect permissions are declared in `src/main/AndroidManifest.xml`. Grant
them at first launch to let the wear module read heart rate. With no wearable
paired, Health Connect has no data to give and the app says so rather than
inventing values.

Without heart rate or HRV, all five HSI axes stay at zero confidence — including
focus and capacity, which are physiology-derived. Behavior still reaches the
runtime and shows up as the digital modality, which the Session screen renders
separately; phone context is collected but has no runtime wiring at all. The
Session screen labels each source with whether it actually feeds the runtime.

`minSdk` is 26 rather than the SDK's declared 24, because `:synheart-core`
depends on `ai.synheart:syni`, whose manifest declares 26. A consumer app at 24
fails manifest merge.

## Code map

```
src/main/kotlin/ai/synheart/core/example/
  MainActivity.kt                 app shell, tab navigation, touch capture
  sdk/
    SynheartController.kt         THE ONLY FILE THAT CALLS THE SDK
  screens/
    SetupScreen.kt                config + initialize
    ConsentScreen.kt              runtime editable-form consent flow
    SessionScreen.kt              start/stop, live HSI, signal sources
    DiagnosticsScreen.kt          runtime version, native symbol audit
  ui/Ui.kt                        shared building blocks
  SimpleExample.kt                minimal standalone snippet
```

Start with `sdk/SynheartController.kt`. No screen imports `ai.synheart.core.*`
— they read state from the controller and call its methods, so the whole
integration is one readable file.

The UI is Jetpack Compose; the four tabs mirror the lifecycle order.

## What it demonstrates

- **The lifecycle** — initialize → consent → session → diagnostics, in order,
  with each step gated on the previous one.
- **Canonical consent** — `consentGetEditableFormTyped` →
  `consentSubmitFormTyped` → `consentEffectiveStateTyped`. The runtime persists
  the choice offline-first and reconciles with the cloud profile when cloud is
  on. It intersects the submitted form against that profile, so asking for a
  channel does not guarantee getting it — gate features on the **effective
  state**, never on the form you submitted. `grantConsent("biosignals")` is
  deliberately not used: it writes a single channel without going through that
  intersection.
- **Honest signal reporting** — the app never fabricates biosignals. Pushed
  samples feed the runtime's longitudinal baselines, so synthetic beats would
  corrupt real reference ranges on whatever device ran the demo. It shows which
  sources are attached, which actually feed the runtime, and says plainly when
  none is. Source state comes from `isFeatureOperational`, not
  `isWearCollecting` — the latter tracks only the granular per-module API and
  reads false after a plain `startSession()`.
- **Per-module configs gate activation.** Declaring `wearConfig`, `phoneConfig`
  or `behaviorConfig` activates that feature; omitting one leaves the module
  inert. This example declares all three.
- **Behavior capture is the host's job** — the Kotlin SDK ships no gesture
  detector, so `MainActivity.dispatchTouchEvent` records taps and scrolls
  through `Synheart.behaviorEvents`. Activating the feature and granting consent
  is not enough on its own; without that override nothing is observed.
- **Runtime diagnostics** — `runtimeDiagnostics()` reports which optional native
  symbols failed to resolve. The list only names symbols something already
  tried to use, so an empty list means "nothing has failed yet", not "the
  runtime exports everything". The screen says so.

## See also

- [../doc/INTEGRATION.md](../doc/INTEGRATION.md) — the ordered integration path
- [../README.md](../README.md) — full API reference
