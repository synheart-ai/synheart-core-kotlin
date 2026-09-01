@file:Suppress("unused")

package ai.synheart.core.example

import ai.synheart.core.Synheart
import ai.synheart.core.config.ApiEndpoints
import ai.synheart.core.config.ConsentConfig
import ai.synheart.core.config.DeviceAuthConfig
import ai.synheart.core.config.SynheartConfig
import ai.synheart.core.config.SynheartFeature
import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Minimal Synheart Core usage — the smallest thing that produces HSI.
 *
 * This is the snippet the README shows. The runnable app lives beside it in
 * `MainActivity` + `screens/`, which walks the same lifecycle across four
 * screens: setup, consent, session, diagnostics.
 *
 * Local-only unless a build names an environment: nothing leaves the device.
 *
 * @param scope a scope that outlives the session — a `lifecycleScope` in an
 *   activity, or a `viewModelScope`. The collectors below run until it is
 *   cancelled.
 */
suspend fun simpleExample(context: Context, scope: CoroutineScope) {
    // 0. Platform origin, supplied at build time — never hard-coded.
    //
    //    The SDK ships no built-in host, so nothing cloud-bound works until one
    //    is named. Empty leaves the runtime on its own default, which is what a
    //    local-only run wants. Must be set BEFORE initialize.
    //
    //    Baking a URL in here would silently point every fork at whatever host
    //    happened to be written down when the file was authored.
    if (BuildConfig.SYNHEART_BASE_URL.isNotEmpty()) {
        ApiEndpoints.baseUrlOverride = BuildConfig.SYNHEART_BASE_URL
    }

    // 1. Configure and load the native runtime.
    //
    //    `appId` and `subjectId` are both REQUIRED — validate() rejects an empty
    //    value before any native work happens. `subjectId` must be STABLE across
    //    restarts: the runtime scopes storage, baselines, and device identity to
    //    it, so a value that changes per launch looks like a new person every
    //    time and baselines never mature. Use your own account id.
    //
    //    Note that passing `userId` to initialize() does NOT populate
    //    `config.subjectId`; set it on the config.
    try {
        Synheart.initialize(
            context = context,
            config = SynheartConfig(
                appId = "com.example.my_app",
                subjectId = "usr_stable_identifier",
                // Development only. Production gates capabilities on a verified
                // consent token instead.
                allowUnsignedCapabilities = true,
                // Required for the runtime consent-form flow below.
                consentConfig = ConsentConfig(
                    deviceId = "dev_stable_identifier",
                    platform = "android",
                    userId = "usr_stable_identifier",
                ),
                // Omitted entirely when no origin was supplied: this snippet
                // stays local-only unless a build names an environment.
                deviceAuthConfig = ApiEndpoints.resolvedAuthBaseUrl
                    .takeIf { it.isNotEmpty() }
                    ?.let {
                        DeviceAuthConfig(
                            authBaseUrl = it,
                            // The real installed package, which Play Integrity
                            // verifies — NOT the platform-issued `app_…` id.
                            packageName = "com.example.my_app",
                            // A debug build, an emulator, or a de-Googled ROM
                            // produces no Play Integrity material, so the
                            // runtime skips registration and stays local-only.
                            // This asks the server to admit it anyway, sending
                            // `format:"none"` with an empty blob — nothing fake
                            // is sent.
                            //
                            // Gate it on BuildConfig.DEBUG. Hard-coding `true`
                            // ships a store build that asks to skip attestation
                            // on every launch.
                            //
                            // The flag alone does nothing: development mode must
                            // also be enabled for this app id server-side, and
                            // it must be a DEVELOPMENT app id. A device admitted
                            // this way is recorded `unattested` — it still signs
                            // every request with a hardware key, it just carries
                            // no provenance claim. Check with
                            // `Synheart.coreDeviceAuthStatus()`.
                            allowUnattestedDevRegistration = BuildConfig.DEBUG,
                        )
                    },
            ),
            autoStart = false,
        )
    } catch (e: Exception) {
        // SynheartCoreError messages name the offending field and how to fix it.
        println("Configuration rejected — ${e.message}")
        return
    }

    // 2. Consent, via the runtime's editable-form flow.
    //
    //    The runtime persists the choice offline-first and, when cloud is
    //    enabled, reconciles it against the cloud default profile. Read the
    //    form, edit it, submit it.
    Synheart.consentGetEditableFormTyped()?.let { form ->
        Synheart.consentSubmitFormTyped(
            form = form.copy(biosignals = true, behavior = true, allowCloud = false),
            deviceId = "dev_stable_identifier",
            userId = "usr_stable_identifier",
        )
    }

    // The runtime may grant less than was asked for. Gate features on the
    // EFFECTIVE state, never on the submitted form.
    val effective = Synheart.consentEffectiveStateTyped()
    if (effective?.behavior != true && effective?.biosignals != true) {
        println("No collection channel granted — a session would collect nothing. Stopping.")
        Synheart.dispose()
        return
    }

    // 3. Subscribe before starting, so the first completed window is not missed.
    val hsi = scope.launch {
        Synheart.onStateUpdate.collect { state ->
            if (state.hasParseError) {
                println("HSI parse failed: ${state.parseError}")
                return@collect
            }
            println("focus=${state.hsi.focus?.value} stress=${state.hsi.stress?.value}")
        }
    }

    // Signal comes from the modules the config activated — the wear module reads
    // Health Connect or a paired strap and pushes into the runtime itself.
    // Observe what arrives:
    val samples = scope.launch {
        Synheart.wearSampleStream.collect { sample ->
            println("hr=${sample.hr} rmssd=${sample.hrvRmssd}")
        }
    }

    // 4. Collection starts here — initialize() alone collects nothing.
    //
    //    startSession() is the whole of it: it brings the module manager up and
    //    then starts every module whose activation, consent and capability line
    //    up. Do NOT also call startWearCollection() — that is the granular API
    //    for running one collector without a session, and a module's start()
    //    throws if it is already running.
    Synheart.startSession()

    //    Read back which modules actually ended up running. This is the
    //    conjunction the SDK enforces, not just "did I grant consent":
    println(
        "wear=" + Synheart.isFeatureOperational(SynheartFeature.WEAR) +
            " behavior=" + Synheart.isFeatureOperational(SynheartFeature.BEHAVIOR) +
            " runtime=" + Synheart.isRuntimeAvailable,
    )

    // Behavior needs a host-side hook: Android has no view-tree hook, so
    // nothing observes taps until the host records them. Override
    // `dispatchTouchEvent` on your activity:
    //
    //   override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
    //     if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
    //       Synheart.behaviorEvents?.recordTap(ev.x.toDouble(), ev.y.toDouble())
    //     }
    //     return super.dispatchTouchEvent(ev)
    //   }
    //
    // Touches are a genuine signal, so recording them is safe — and interaction
    // alone is enough to ground the HSI digital axes.

    // If you own a biosignal source the SDK does not adapt, push ITS REAL
    // READINGS. Never placeholder values: pushed samples feed the runtime's
    // longitudinal baselines, so fabricated beats corrupt the user's actual
    // reference ranges.
    //
    // When one sensor notification carries several RR intervals, prefer
    // pushRrBatch over looping pushRr — the runtime reconstructs a distinct
    // timestamp per beat instead of collapsing them onto the shared arrival
    // time, so no beat is lost to HRV.
    //
    //   myStrap.onPacket { packet ->
    //     Synheart.pushRrBatch(packet.arrivalMs, packet.rrIntervalsMs, provider = "ble_hrm")
    //   }

    delay(30_000)

    // 5. Clean shutdown.
    Synheart.stopSession()
    hsi.cancel()
    samples.cancel()
    Synheart.dispose()
}
