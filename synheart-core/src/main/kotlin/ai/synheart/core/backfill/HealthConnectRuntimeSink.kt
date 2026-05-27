// SPDX-License-Identifier: Apache-2.0
//
// Health Connect (Android) historical-read backfill — Kotlin parallel to
// Flutter's `lib/src/backfill/health_connect_runtime_sink.dart`.
//
// Architecture mirrors Flutter:
//
//   wear (synheart-wear-kotlin)               core (this file)
//   ──────────────────────────                ──────────────────
//   HealthConnectAdapter
//     .fetchSleepNights()        ─────►   HealthConnectRuntimeSink
//     .fetchOvernightPhysiology()             .backfill()
//                                               ↓
//                                          aggregate per local wake-day
//                                               ↓
//                                          CoreRuntimeBridge.ingestBatch(
//                                            type="wearable_daily_values")
//
// Core stays Health-Connect-agnostic. All Health Connect SDK calls,
// permission gating, and availability checks live in wear. Core only
// knows: "given these typed daily summaries, push them into the runtime
// SRM as five dimensions: sleep_need, deep_sleep_min, rem_sleep_min,
// resting_hr, hrv_rmssd."
//
// Android retention is per-record-type and outside our control. As of
// 2026: heart rate ~30 days, sleep sessions 1–2 years, steps/activity
// years. Caller decides `daysBack`; the wear adapter returns whatever
// the platform retained.

package ai.synheart.core.backfill

import ai.synheart.core.bridge.CoreRuntimeBridge
import ai.synheart.wear.backfill.HealthHistoryReader
import ai.synheart.wear.backfill.OvernightPhysiologySummary
import ai.synheart.wear.backfill.SleepNightSummary
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// NOTE on wiring: types live in synheart-wear-kotlin's
// `ai.synheart.wear.backfill` package (matches Flutter, where
// SleepNightSummary / OvernightPhysiologySummary live in synheart_wear).
// Requires a synheart-wear-kotlin version that ships the
// `ai.synheart.wear.backfill` package — bump the `ai.synheart:synheart-wear`
// dependency in `synheart-core/build.gradle` once it's released.

/** Outcome of a Health Connect historical pull. Mirrors Flutter's `HealthConnectBackfillResult`. */
data class HealthConnectBackfillResult(
    val requestedDaysBack: Int,
    val daysIngested: Int,
    val dimensionsPushed: Int,
    val skipped: Boolean,
    val skipReason: String?,
    val durationMs: Long,
)

/** Push-daily callback — matches the runtime's `wearable_daily_values` dimension entry. Injectable for tests. */
typealias PushDailyCallback = (
    dimension: String,
    dayIndex: Int,
    value: Double,
    confidence: Double,
    fidelity: Int,
) -> Unit

typealias TriggerRecomputeCallback = () -> Unit

/**
 * High-level "bring your Health Connect history" call. Pulls
 * sleep + overnight HR/HRV from [reader] across `daysBack` days,
 * aggregates per local wake-day, and replays into the runtime SRM
 * via the `wearable_daily_values` batch path on [CoreRuntimeBridge].
 *
 * Confidence values (`0.85`) match the Apple Health XML import so the
 * two backfill paths produce comparable SRM weights.
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
            val dayIndex = day.toEpochDay().toInt()
            var dayDidPush = false

            sleep[day]?.let { night ->
                if (night.totalAsleepMinutes > 0) {
                    pushDaily("sleep_need", dayIndex, night.totalAsleepMinutes * 60.0, 0.85, 1)
                    dimensionsPushed++; dayDidPush = true

                    // Stage pushes are nested under the totalAsleep > 0 gate
                    // (matches Flutter). A night with stages but zero asleep
                    // minutes is treated as no usable sleep data.
                    night.deepMinutes?.takeIf { it > 0 }?.let {
                        pushDaily("deep_sleep_min", dayIndex, it, 0.85, 1)
                        dimensionsPushed++
                    }
                    night.remMinutes?.takeIf { it > 0 }?.let {
                        pushDaily("rem_sleep_min", dayIndex, it, 0.85, 1)
                        dimensionsPushed++
                    }
                }
            }

            overnight[day]?.let { o ->
                o.hrvRmssdMs?.takeIf { it > 0 }?.let {
                    pushDaily("hrv_rmssd", dayIndex, it, 0.85, 1)
                    dimensionsPushed++; dayDidPush = true
                }
                o.restingHrBpm?.takeIf { it > 0 }?.let {
                    pushDaily("resting_hr", dayIndex, it, 0.85, 1)
                    dimensionsPushed++; dayDidPush = true
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
        /**
         * Default push routes through the runtime's `wearable_daily_values`
         * ingest batch. Each call enqueues a single-dimension batch — same
         * per-(day, dimension) granularity as Flutter's `srmPushWearableDaily`.
         */
        fun defaultPushDaily(bridge: CoreRuntimeBridge?): PushDailyCallback =
            { dimension, dayIndex, value, confidence, fidelity ->
                if (bridge != null) {
                    val nowMs = System.currentTimeMillis()
                    val dim = JSONObject().apply {
                        put("dimension", dimension)
                        put("day_index", dayIndex)
                        put("value", value)
                        put("confidence", confidence)
                        put("fidelity", fidelity)
                    }
                    val batch = JSONObject().apply {
                        put("type", "wearable_daily_values")
                        put("dimensions", JSONArray().put(dim))
                        put("recompute_from", 0)
                        put("recompute_to", (nowMs / 86_400_000).toInt())
                    }
                    bridge.ingestBatch(batch.toString(), nowMs)
                }
            }

        /**
         * The runtime recomputes inline on each `wearable_daily_values`
         * ingest (see `recompute_from/to` in [defaultPushDaily]), so the
         * default trigger is a no-op. Kept as a hook for API parity with
         * Flutter and for tests that want an end-of-backfill signal.
         */
        fun defaultTriggerRecompute(@Suppress("UNUSED_PARAMETER") bridge: CoreRuntimeBridge?): TriggerRecomputeCallback =
            { /* runtime recomputes inline via ingestBatch */ }
    }
}
