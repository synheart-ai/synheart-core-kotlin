package ai.synheart.core.modules.wear

import ai.synheart.core.SynheartLogger
import ai.synheart.wear.SynheartWear
import ai.synheart.wear.config.SynheartWearConfig
import ai.synheart.wear.models.DeviceAdapter
import ai.synheart.wear.models.MetricType
import ai.synheart.wear.models.PermissionType
import ai.synheart.wear.models.WearMetrics
import android.content.Context
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge

/**
 * A real biosignal source, bridging `synheart-wear` into [WearModule].
 *
 * This is the Kotlin counterpart of the Flutter SDK's
 * `SynheartWearSourceHandler`, and it closes what was the largest functional gap
 * between the two: nothing in this SDK registered a wear source, so the only
 * one that ever ran was [MockWearSourceHandler] — invented data — and with that
 * correctly disabled the wear module had no source at all. Biosignals could only
 * arrive if the host pushed them itself through `pushWearHr` / `pushRr`.
 *
 * `synheart-wear` decides which backend actually serves a metric (Health
 * Connect, a BLE strap, or a vendor cloud); this class only adapts its
 * [WearMetrics] to the core [WearSample] shape.
 *
 * Nothing is fabricated here. A poll that resolves no metric yields a sample
 * with null fields, which is the truth and is what lets a host tell "the source
 * is running but silent" from "no source is attached" — see the Session screen
 * in the example app.
 */
class SynheartWearSourceHandler(
    private val context: Context,
    /**
     * Which backends to enable. Defaults to Health Connect plus a BLE strap:
     * the two that need no vendor credentials, so a host gets a working source
     * without configuring anything.
     */
    private val config: SynheartWearConfig = SynheartWearConfig(
        enabledAdapters = setOf(DeviceAdapter.HEALTH_CONNECT, DeviceAdapter.BLE_HRM),
    ),
    /** Poll cadence for the HR and HRV streams, in milliseconds. */
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
) : WearSourceHandler {

    private var wear: SynheartWear? = null

    override val sourceType: WearSourceType
        get() = when {
            DeviceAdapter.HEALTH_CONNECT in config.enabledAdapters ->
                WearSourceType.HEALTH_CONNECT
            DeviceAdapter.GARMIN in config.enabledAdapters -> WearSourceType.GARMIN
            DeviceAdapter.WHOOP in config.enabledAdapters -> WearSourceType.WHOOP
            else -> WearSourceType.HEALTH_CONNECT
        }

    /**
     * Always true: `synheart-wear` resolves platform availability per adapter
     * internally, and a permission that has not been granted yet is a *later*
     * condition, not an absent source.
     *
     * Deliberately not a permission check. Reporting unavailable before the user
     * has been asked would make the module skip a source that is about to start
     * working, and the host could never tell the two apart.
     */
    override val isAvailable: Boolean = true

    override suspend fun initialize() {
        if (wear != null) return
        wear = runCatching { SynheartWear(context, config).also { it.initialize() } }
            .onFailure {
                SynheartLogger.log(
                    "[SynheartWearSourceHandler] initialize failed: ${it.message}; " +
                        "no biosignals will arrive from this source",
                )
            }
            .getOrNull()
    }

    /**
     * HR and HRV merged into one sample stream.
     *
     * Each upstream emits independently, so a sample generally carries one of
     * the two rather than both — the runtime handles them as separate signals,
     * and pairing them here would mean inventing a timestamp for whichever had
     * not arrived.
     *
     * Failures are logged and end that stream rather than propagating: a revoked
     * Health Connect permission mid-session must not take down the session.
     */
    override val sampleFlow: Flow<WearSample>
        get() {
            val w = wear ?: return emptyFlow()
            return merge(
                w.streamHR(intervalMs).catch { logStreamFailure("HR", it) },
                w.streamHRV(intervalMs).catch { logStreamFailure("HRV", it) },
            ).map { it.toWearSample() }
        }

    override suspend fun dispose() {
        wear = null
    }

    /**
     * Ask for the health permissions this source needs.
     *
     * Health Connect grants are a runtime prompt, not a manifest declaration, so
     * a host that only declares them in the manifest reads an empty store
     * forever — the source emits a sample every tick carrying nothing, which
     * looks identical to a paired-but-silent wearable.
     *
     * Returns the resulting grant map, or an empty map when the source never
     * initialized.
     */
    suspend fun requestPermissions(
        types: Set<PermissionType> = DEFAULT_PERMISSIONS,
    ): Map<PermissionType, Boolean> {
        val w = wear ?: return emptyMap()
        return runCatching { w.requestPermissions(types) }
            .onFailure {
                SynheartLogger.log(
                    "[SynheartWearSourceHandler] permission request failed: ${it.message}",
                )
            }
            .getOrDefault(emptyMap())
    }

    /** Current grant state, without prompting. */
    fun permissionStatus(): Map<PermissionType, Boolean> =
        runCatching { wear?.getPermissionStatus() }.getOrNull() ?: emptyMap()

    private fun logStreamFailure(which: String, cause: Throwable) {
        SynheartLogger.log(
            "[SynheartWearSourceHandler] $which stream ended: ${cause.message}",
        )
    }

    private companion object {
        /** One second, matching the cadence the core wear cache expects. */
        const val DEFAULT_INTERVAL_MS = 1_000L

        /**
         * Heart rate and HRV only — the two the HSI physiological axes are
         * built from. Asking for steps or sleep here would request data the
         * runtime does not consume, which is a permission a user should not be
         * asked for.
         */
        val DEFAULT_PERMISSIONS = setOf(PermissionType.HEART_RATE, PermissionType.HRV)

        fun WearMetrics.toWearSample(): WearSample = WearSample(
            timestamp = timestamp,
            hr = getMetric(MetricType.HR),
            hrvRmssd = getMetric(MetricType.HRV_RMSSD),
            // Empty and null both mean "no beats in this sample"; normalising to
            // null keeps `rrIntervals?.isNotEmpty()` checks honest.
            rrIntervals = rrIntervals?.takeIf { it.isNotEmpty() },
        )
    }
}
