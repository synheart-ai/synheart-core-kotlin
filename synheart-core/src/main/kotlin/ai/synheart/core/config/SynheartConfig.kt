package ai.synheart.core.config

import ai.synheart.core.modules.interfaces.AuthProvider
import java.util.UUID

/** Storage sub-configuration. */
data class StorageConfig(
    val enabled: Boolean = true,
    val retentionDays: Int? = null
)

/** Sync sub-configuration. */
data class SyncConfig(
    val enabled: Boolean = false,
    /**
     * Platform origin for sync + the runtime's cloud clients. Empty defers to
     * [ApiEndpoints.resolvedAuthBaseUrl], and then to the runtime's own
     * default — see [ApiEndpoints] on why nothing is baked in here.
     */
    val baseUrl: String = ""
)

/**
 * Device-auth sub-configuration.
 *
 * Supplying this is what enables hardware-backed device identity: the runtime
 * refuses a config with `device_auth.enabled` but no auth origin, so the
 * absence of this object is how a local-only host opts out.
 *
 * Runtime-only policy: SDK layers must not perform outbound auth API calls
 * directly.
 */
data class DeviceAuthConfig(
    /** Base URL for the device auth service. */
    val authBaseUrl: String,
    /** Base URL for the capability token service. Defaults to [authBaseUrl]. */
    val capabilityBaseUrl: String? = null,
    /**
     * The app's package id, used to bind device registration to the app.
     * Optional; leave empty if not applicable.
     */
    val packageName: String = "",
    /**
     * **Development only.** Register the device even when the platform
     * produces no attestation material (Play Integrity unavailable — an
     * emulator, a de-Googled ROM, a sideloaded debug build).
     *
     * With this false (the default), such a device skips registration and runs
     * local-only. With it true, the runtime submits the registration with
     * `attestation.format = "none"` and an empty blob, and the **server**
     * decides.
     *
     * This is **not** a security bypass. It only stops the client giving up
     * early. Acceptance still requires development mode on this app's record
     * in the Synheart dashboard; with that off, registration is refused
     * server-side. Never enable it on a production app id — use a separate
     * development app id.
     */
    val allowUnattestedDevRegistration: Boolean = false
) {
    val resolvedCapabilityBaseUrl: String get() = capabilityBaseUrl ?: authBaseUrl
}

/**
 * Wear (biosignal) module configuration.
 *
 * Declaring this **activates** the wear feature — that is the load-bearing part,
 * matching the sibling platform SDKs. The tuning fields below are carried for
 * parity and are not yet consumed by any collector on any platform; they are
 * here so a config written against another SDK ports across unchanged.
 */
data class WearConfig(
    /** Enable high-frequency HRV sampling (requires extended capability). */
    val enableHighFrequencyHrv: Boolean = false,
    /** Enable offline caching. */
    val enableCaching: Boolean = true,
    /** Sample rate in Hz. */
    val sampleRateHz: Double = 1.0,
)

/**
 * Phone-context module configuration.
 *
 * Declaring this activates the phone-context feature. As with [WearConfig], the
 * tuning fields are parity carriers rather than live settings.
 */
data class PhoneConfig(
    /** Enable motion tracking. */
    val enableMotion: Boolean = true,
    /** Enable screen state tracking. */
    val enableScreenState: Boolean = true,
    /** Enable app switching tracking (hashed). */
    val enableAppTracking: Boolean = false,
    /** Motion sensitivity, 0.0–1.0. */
    val motionSensitivity: Double = 0.5,
)

/**
 * Behavior module configuration.
 *
 * Declaring this activates the behavior feature. Note that activation is not
 * sufficient on Android: the SDK has no view-tree hook, so a host must also
 * record interaction itself — see `Synheart.behaviorEvents`.
 */
data class BehaviorConfig(
    /** Enable gesture tracking. */
    val enableGestureTracking: Boolean = true,
    /** Enable typing pattern tracking. */
    val enableTypingTracking: Boolean = true,
    /** Minimum idle gap to record, in seconds. */
    val minIdleGapSeconds: Double = 1.0,
    /**
     * Enable on-device motion-state inference in `synheart-behavior`.
     *
     * Carried for cross-platform parity; this SDK's behavior module has no
     * motion pipeline yet, so it currently changes nothing.
     */
    val enableMotionLite: Boolean = false,
    /**
     * Forward raw 50 Hz accelerometer samples into the engine runtime so it
     * derives motion features and classifies posture.
     *
     * Independent of [enableMotionLite]. Also parity-only for now.
     */
    val emitRawMotionSamples: Boolean = false,

    /**
     * Report which application is in the foreground, so the engine has an app
     * identity to type each window against.
     *
     * On by default, and the default matters: with no identity the runtime's
     * `current_app` stays `None`, which resolves to the `Unknown` app category,
     * whose interpretation-mask row is **all zeros**. CFI / Cognitive Load,
     * Stress `B`, Mental Fatigue `B` and Focus's deviation sub-terms then read
     * `0` for a person who was working the whole time.
     *
     * The identity reported is [foregroundAppId] when set, else the
     * `DeviceAuthConfig.packageName`, else the application's own package name.
     * Only reports while the app is foregrounded — naming your own app while
     * the person is in someone else's attributes that app's window to yours.
     * Supply [foregroundAppSource] to report the *real* foreground app
     * (Android `UsageStatsManager`).
     */
    val reportForegroundApp: Boolean = true,

    /** Explicit application identifier to report — an Android package name. */
    val foregroundAppId: String? = null,

    /**
     * A real foreground-app source, replacing the self-reporting default.
     * Implement over `UsageStatsManager` (permission `PACKAGE_USAGE_STATS`,
     * granted through Settings).
     */
    val foregroundAppSource: ai.synheart.core.modules.behavior.ForegroundAppSource? = null,
)

/** Privacy sub-configuration. */
data class PrivacyConfig(
    val allowResearch: Boolean = false
)

/**
 * Main configuration for Synheart SDK
 */
data class SynheartConfig(
    val appId: String = "",
    val subjectId: String = "",
    val mode: SynheartMode = SynheartMode.PERSONAL,
    val appVersion: String = "0.0.0",
    /** Human-readable app name (used in platform metadata). */
    val appName: String = "",
    /** App category (e.g. "Game", "Health", "Productivity"). */
    val category: String = "",
    /** Developer name or organization. */
    val developer: String = "",
    /** Additional app-level metadata for lab ingestion. */
    val additionalAppMetadata: Map<String, Any> = emptyMap(),
    val deviceId: String = "",
    val platform: String = "android",
    /** Device role — controls which modules are enabled. Defaults to PHONE. */
    val deviceRole: DeviceRole = DeviceRole.PHONE,
    val storage: StorageConfig = StorageConfig(),
    val sync: SyncConfig = SyncConfig(),
    val privacy: PrivacyConfig = PrivacyConfig(),

    /**
     * Per-module configuration. Declaring one **activates** that feature; a null
     * leaves the module inert.
     *
     * This matches the sibling platform SDKs, and is a change in behaviour: the
     * activation manager previously turned wear, phone and behavior on
     * unconditionally, ignoring the config entirely, so a host had no way to run
     * (say) behavior alone. Passing none of the three keeps every collector off,
     * which is also what the sibling SDKs do.
     */
    val wearConfig: WearConfig? = null,
    val phoneConfig: PhoneConfig? = null,
    val behaviorConfig: BehaviorConfig? = null,

    val cloudConfig: CloudConfig? = null,
    val consentConfig: ConsentConfig? = null,
    /**
     * Hardware-backed device identity. Null leaves device auth disabled and
     * the SDK local-only — see [DeviceAuthConfig].
     */
    val deviceAuthConfig: DeviceAuthConfig? = null,
    val labIngestConfig: LabIngestConfig? = null,
    /** Server-signed capability token for feature gating */
    val capabilityToken: ai.synheart.core.modules.capabilities.CapabilityToken? = null,
    /** HMAC secret for verifying the capability token signature */
    val capabilitySecret: String? = null,
    /** When true, allows SDK to run with default capabilities and no signed token (debug only) */
    val allowUnsignedCapabilities: Boolean = false,
    /**
     * **Development only.** Attach a synthetic biosignal generator when no real
     * wear source is configured.
     *
     * Off by default, and that default is load-bearing. [WearModule] used to
     * fall back to [ai.synheart.core.modules.wear.MockWearSourceHandler]
     * whenever a host passed no sources — which on Android is always, since
     * nothing in the SDK registers a real one. Its `isAvailable` is
     * unconditionally true and it emits an invented heart rate and RMSSD every
     * second.
     *
     * Those samples are not cosmetic. They reach `wearSampleStream`, where a
     * host renders them as measurements, and they flow through
     * `WearModuleBiosignalAdapter` into the session engine and the runtime's
     * longitudinal baselines (SRM) — so a host that merely granted biosignals
     * consent silently corrupted that user's real reference ranges with
     * fabricated beats, on their actual device, with nothing on screen to
     * indicate the data was invented.
     *
     * Enable it only to exercise the pipeline, never on a build that touches a
     * real subject's baselines, and label it in any UI that shows the values.
     */
    val allowSyntheticBiosignals: Boolean = false,

    /**
     * Flush the upload queue when a session stops.
     *
     * Null leaves the decision to the runtime's own upload cadence, which is the
     * default. `Synheart.setBatchIngestOnStop` overrides this at runtime.
     */
    val batchIngestOnStop: Boolean? = null,

    /**
     * `tracing`-style filter for the native runtime's own logs, e.g. `"info"` or
     * `"synheart_core_runtime=debug"`.
     *
     * Null leaves runtime logging off. Worth setting during integration: without
     * it the runtime logs nowhere, so the lines that explain a stalled
     * integration — an unattestable device, a closed cloud gate, a failing ingest
     * POST — simply do not exist, and the silence reads as "nothing happened"
     * rather than "you never asked to be told".
     */
    val runtimeLogEnvFilter: String? = null,

    /**
     * Host declarations that change engine output — `sensing`, `device_class`,
     * `mask_profile`, `cfi_structural_components`.
     *
     * Defaults to declaring nothing, so the runtime reproduces pre-0.16.0
     * behaviour exactly. Declaring any of them is a deliberate act with
     * consequences documented on [HostDeclarations] — most sharply, declaring
     * `device_class` invalidates every persisted SRM baseline and costs the
     * person a 30-observation re-warm across 3 days.
     */
    val hostDeclarations: HostDeclarations = HostDeclarations(),

    /**
     * Opt-in kinematic heads. Empty by default; they also require a body-worn
     * accelerometer placement before they produce anything.
     */
    val extraHeads: List<ExtraHead> = emptyList(),

    /**
     * Publish per-head evidence terms in `meta.synheart.diagnostics`.
     *
     * The block carries each head's component breakdown (`cognitive_load` →
     * `{physio_raw, digital_term, cfi, ip}`, `capacity` → `{ia, delta,
     * a_prev}`, `mental_fatigue` → `{t, b, p, a, r}`, …), the raw `notes` a
     * head wrote when it withheld, and the keystroke-timing signals that are
     * *inputs* to the digital axes rather than axes of their own.
     *
     * **Off by default, and it should stay off for a product surface**: the
     * axes are the contract and this is debug telemetry that roughly doubles
     * the size of every stored window. Turn it on for a research capture or a
     * validation build — it is the difference between an export a reviewer can
     * audit and a list of scores they have to take on trust, because
     * score-plus-confidence alone cannot distinguish "this head computed and
     * the evidence really was zero" from "this head never received its
     * evidence".
     */
    val emitDiagnostics: Boolean = false,

    /**
     * Use the single-visit baseline profile (`d_min = 1`) without switching
     * the whole instance into [SynheartMode.RESEARCH].
     *
     * `d_min` is the number of *distinct calendar days* a metric needs before
     * its personal baseline is promoted from Warming to Ready. The default of
     * 3 makes Ready unreachable in a supervised single-visit protocol however
     * much clean data it collects, so every snapshot reports Warming and every
     * score leaning on a personal percentile stays a cold-start reading.
     *
     * [SynheartMode.RESEARCH] already implies this, but it also caps the step
     * to 60 s **and** auto-opens/finalizes a lab session on session
     * start/stop — a host that manages its own lab-window lifecycle must not
     * take those, or it ends up with two competing lifecycles. This flag is
     * the baseline profile alone.
     *
     * Data matured under it is a **within-session reference, not a personal
     * baseline**, and analysis must report it as such. Off by default.
     */
    val researchBaseline: Boolean = false,

    /**
     * Inference window length in ms. Null leaves the runtime default (60 000).
     *
     * Note `step_ms` is **not** settable through this family — the full
     * `synheart_core_*` runtime derives it from [mode]. Only the bare
     * `synheart_core_edge_*` handle takes an explicit `step_ms`.
     */
    val windowMs: Long? = null,
) {
    /** Validate config and throw on violations. */
    fun validate() {
        if (mode == SynheartMode.RESEARCH && !privacy.allowResearch) {
            throw SynheartCoreError.ResearchNotAllowed()
        }
        if (appId.isEmpty()) {
            throw SynheartCoreError.NotConfigured("appId must not be empty")
        }
        if (subjectId.isEmpty()) {
            throw SynheartCoreError.NotConfigured("subjectId must not be empty")
        }
        if (subjectId.contains("|")) {
            throw SynheartCoreError.InvalidMode("subjectId must not contain pipe character")
        }
    }
}

/**
 * Cloud Connector configuration
 *
 * Required for cloud upload functionality.
 *
 * Example:
 * ```kotlin
 * val cloudConfig = CloudConfig(
 *     subjectId = "pseudonymous_user_123",
 *     instanceId = UUID.randomUUID().toString()
 * )
 * ```
 */
data class CloudConfig(
    /**
     * Ingest origin. Empty resolves through [ApiEndpoints.resolvedCloudBaseUrl]
     * and then the runtime's own default; nothing is baked in here.
     */
    val baseUrl: String = "",
    /**
     * Organization id. Cloud ingest is only legal with a non-empty org id —
     * the runtime rejects the whole configuration otherwise, so
     * [buildRuntimeConfigMap] gates `ingest.*` on this being present.
     */
    val orgId: String = "",
    /**
     * Auth provider for request signing. The runtime signs every ingest
     * request with the device's hardware-backed ECDSA P-256 key; this is
     * an override hook for hosts that need to stub it (e.g. in tests).
     */
    val authProvider: AuthProvider? = null,
    val subjectId: String,
    val subjectType: String = "pseudonymous_user",
    val instanceId: String = UUID.randomUUID().toString(),
    val maxQueueSize: Int = 100,
    val batchSize: Int = 10,
    val uploadIntervalMs: Long = 300_000,
    val maxRetries: Int = 3,
    val enableBacklog: Boolean = true
)

/**
 * Consent service configuration.
 *
 * Required for cloud consent service integration (JWT-based consent tokens).
 *
 * Example:
 * ```kotlin
 * val consentConfig = ConsentConfig(
 *     consentServiceUrl = "https://consent.synheart.ai",
 *     appId = "your_app_id",
 *     appApiKey = "your_app_api_key"
 * )
 * ```
 */
data class ConsentConfig(
    /** Base URL for consent service */
    val consentServiceUrl: String = "",

    /** App ID for consent service */
    val appId: String? = null,

    /** App API key for consent service authentication */
    val appApiKey: String? = null,

    /** Device ID (UUID for this device, auto-generated if not provided) */
    val deviceId: String? = null,

    /** Platform identifier ('android', 'ios', etc.) */
    val platform: String = "android",

    /** User ID (optional, for pseudonymous identification) */
    val userId: String? = null,

    /** Region code (e.g., "US", "EU") */
    val region: String? = null
) {
    /** Check if consent service is configured */
    val isConfigured: Boolean
        get() = appId != null && appApiKey != null
}
