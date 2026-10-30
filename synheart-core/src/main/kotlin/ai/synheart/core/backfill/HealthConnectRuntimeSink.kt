// SPDX-License-Identifier: Apache-2.0
//
// Health Connect (Android) historical-read backfill — Kotlin parallel to
// the sibling platform SDKs' Health Connect backfill sink.
//
// Architecture, shared across the platform SDKs:
//
//   wear (synheart-wear-kotlin)               core (this file)
//   ──────────────────────────                ──────────────────
//   HealthConnectAdapter
//     .fetchSleepNights()        ─────►   HealthConnectRuntimeSink
//     .fetchOvernightPhysiology()             .backfill()
//                                               ↓
//                                          aggregate per local wake-day
//                                               ↓
//                                          CoreRuntimeBridge
//                                            .srmPushWearableDaily(...)
//
// Core stays Health-Connect-agnostic. All Health Connect SDK calls,
// permission gating, and availability checks live in wear. Core only
// knows: "given these typed daily summaries, push them into the runtime's
// personal baselines as six daily metrics: sleep.duration_s,
// sleep.deep_min, sleep.rem_min, cardiac.overnight_rmssd_ms,
// cardiac.overnight_hr_bpm, cardiac.resting_hr_bpm."
//
// Android retention is per-record-type and outside our control. As of
// 2026: heart rate ~30 days, sleep sessions 1–2 years, steps/activity
// years. Caller decides `daysBack`; the wear adapter returns whatever
// the platform retained.

package ai.synheart.core.backfill

import ai.synheart.core.SynheartLogger
import ai.synheart.core.bridge.CoreRuntimeBridge
import ai.synheart.core.models.DailyMetric
import ai.synheart.wear.backfill.HealthHistoryReader
import ai.synheart.wear.backfill.OvernightPhysiologySummary
import ai.synheart.wear.backfill.SleepNightSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// NOTE on wiring: types live in synheart-wear-kotlin's
// `ai.synheart.wear.backfill` package (matching the sibling SDKs, where
// SleepNightSummary / OvernightPhysiologySummary live in synheart_wear).
// Requires a synheart-wear-kotlin version that ships the
// `ai.synheart.wear.backfill` package — bump the `ai.synheart:synheart-wear`
// dependency in `synheart-core/build.gradle` once it's released.

/** Outcome of a Health Connect historical pull. Shared shape across SDKs. */
data class HealthConnectBackfillResult(
    val requestedDaysBack: Int,
    val daysIngested: Int,
    val dimensionsPushed: Int,
    val skipped: Boolean,
    val skipReason: String?,
    val durationMs: Long,
)

/**
 * Push-daily callback — the shape of `srm_push_wearable_daily`: one value of
 * one [DailyMetric] for one local date. Returns the runtime's code (`0`
 * stored, `1004` refused, `5002` out of bounds or not permitted, `-1` no
 * pipeline). Injectable for tests.
 */
typealias PushDailyCallback = (
    provider: String,
    metric: String,
    date: LocalDate,
    value: Double,
    vendorSummary: Boolean,
) -> Int

/** Rebuild the wearable reference once the backfill has stored its values. */
typealias TriggerRecomputeCallback = () -> Unit

/**
 * High-level "bring your Health Connect history" call. Pulls
 * sleep + overnight HR/HRV from [reader] across `daysBack` days,
 * aggregates per local wake-day, and pushes each value into the runtime's
 * personal baselines with `srm_push_wearable_daily` under the provider
 * `health_connect`, then rebuilds the wearable reference as of today.
 *
 * Health Connect's nightly figures are platform summaries, so every value is
 * pushed as a vendor summary.
 *
 * ```kotlin
 * val sink = HealthConnectRuntimeSink(
 *     reader = healthConnectAdapter,  // from synheart-wear-kotlin
 *     bridge = Synheart.coreRuntime,
 * )
 * val result = sink.backfill(daysBack = 365)
 * ```
 */
class HealthConnectRuntimeSink(
    private val reader: HealthHistoryReader,
    bridge: CoreRuntimeBridge?,
    private val pushDaily: PushDailyCallback = defaultPushDaily(bridge),
    private val triggerRecompute: TriggerRecomputeCallback = defaultTriggerRecompute(bridge),
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    suspend fun backfill(daysBack: Int = 365): HealthConnectBackfillResult {
        val startedAt = System.currentTimeMillis()

        if (daysBack <= 0) {
            return skipped(daysBack, "daysBack must be positive", startedAt)
        }

        if (!reader.isAvailable()) {
            return skipped(daysBack, "Health Connect not available on this device", startedAt)
        }

        val end = Instant.now()
        val start = LocalDate.now(zone)
            .minusDays(daysBack.toLong())
            .atStartOfDay(zone)
            .toInstant()

        val sleep: Map<LocalDate, SleepNightSummary>
        val overnight: Map<LocalDate, OvernightPhysiologySummary>
        coroutineScope {
            val sleepDeferred = async { reader.fetchSleepNights(start, end, zone) }
            val overnightDeferred = async { reader.fetchOvernightPhysiology(start, end, zone) }
            sleep = sleepDeferred.await()
            overnight = overnightDeferred.await()
        }

        var dimensionsPushed = 0
        var daysIngested = 0
        val days = (sleep.keys + overnight.keys).toSortedSet()
        for (day in days) {
            var dayDidPush = false
            // `day` is the local wake date the reader bucketed the night
            // under, which is the date the runtime files it against. Counts
            // only what the runtime accepted: a refused value (no running
            // session, out of bounds) is not ingested.
            fun push(metric: String, value: Double) {
                val rc = pushDaily(PROVIDER, metric, day, value, true)
                if (rc != 0) {
                    SynheartLogger.log("[HealthConnectRuntimeSink] $metric on $day not stored (rc=$rc)")
                    return
                }
                dimensionsPushed++
                dayDidPush = true
            }

            sleep[day]?.let { night ->
                if (night.totalAsleepMinutes > 0) {
                    push(DailyMetric.SLEEP_DURATION_S, night.totalAsleepMinutes * 60.0)

                    // Stage pushes are nested under the totalAsleep > 0 gate
                    // (as the sibling SDKs do). A night with stages but zero asleep
                    // minutes is treated as no usable sleep data.
                    night.deepMinutes?.takeIf { it > 0 }?.let { push(DailyMetric.SLEEP_DEEP_MIN, it) }
                    night.remMinutes?.takeIf { it > 0 }?.let { push(DailyMetric.SLEEP_REM_MIN, it) }
                }
            }

            overnight[day]?.let { o ->
                o.hrvRmssdMs?.takeIf { it > 0 }?.let { push(DailyMetric.OVERNIGHT_RMSSD_MS, it) }
                // The reader's figure is the mean HR inside the night's sleep
                // sessions, the night's only heart rate. It feeds both
                // baselines: the Sleep Score's overnight HR, and the resting HR
                // Recovery and Readiness read (the runtime's rule for a source
                // with one overnight figure).
                o.restingHrBpm?.takeIf { it > 0 }?.let {
                    push(DailyMetric.OVERNIGHT_HR_BPM, it)
                    push(DailyMetric.RESTING_HR_BPM, it)
                }
            }

            if (dayDidPush) daysIngested++
        }

        if (dimensionsPushed > 0) triggerRecompute()

        return HealthConnectBackfillResult(
            requestedDaysBack = daysBack,
            daysIngested = daysIngested,
            dimensionsPushed = dimensionsPushed,
            skipped = false,
            skipReason = null,
            durationMs = System.currentTimeMillis() - startedAt,
        )
    }

    private fun skipped(daysBack: Int, reason: String, startedAt: Long) =
        HealthConnectBackfillResult(
            requestedDaysBack = daysBack,
            daysIngested = 0,
            dimensionsPushed = 0,
            skipped = true,
            skipReason = reason,
            durationMs = System.currentTimeMillis() - startedAt,
        )

    companion object {
        /** The provider name every Health Connect value is stored under. */
        const val PROVIDER = "health_connect"

        /** Default push: `srm_push_wearable_daily` on [bridge]; `-1` without one. */
        fun defaultPushDaily(bridge: CoreRuntimeBridge?): PushDailyCallback =
            { provider, metric, date, value, vendorSummary ->
                bridge?.srmPushWearableDaily(
                    provider = provider,
                    metric = metric,
                    year = date.year,
                    month = date.monthValue,
                    day = date.dayOfMonth,
                    value = value,
                    vendorSummary = vendorSummary,
                ) ?: -1
            }

        /**
         * Default trigger: rebuild the wearable reference as of today. The
         * runtime builds it from the dates before the one given, so every
         * backfilled night is in and no day is in its own baseline.
         */
        fun defaultTriggerRecompute(bridge: CoreRuntimeBridge?): TriggerRecomputeCallback =
            {
                val today = LocalDate.now()
                bridge?.srmRefreshWearableReference(today.year, today.monthValue, today.dayOfMonth)
            }
    }
}
