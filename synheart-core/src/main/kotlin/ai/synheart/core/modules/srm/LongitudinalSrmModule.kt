package ai.synheart.core.modules.srm

import ai.synheart.core.SynheartLogger
import ai.synheart.core.bridge.CoreRuntimeBridge
import ai.synheart.core.models.CanonicalWearableEvent
import ai.synheart.core.models.DailyMetric
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Bridges wearable events into the native personal baselines.
 *
 * Maps recognised event types to daily metric names ([DailyMetric]), extracts
 * the value in the unit the name ends with, and pushes it to the runtime with
 * `srm_push_wearable_daily` under the event's provider and the person's local
 * date. After a push it asks the runtime to rebuild the wearable reference
 * from the days before today.
 *
 * Event fields with no daily metric (sleep efficiency, a single workout's
 * strain, stress) are not pushed: a per-workout strain is not the day's
 * strain, and the runtime refuses any name outside [DailyMetric.ALL].
 */
class LongitudinalSrmModule(
    private val zone: () -> ZoneId = { ZoneId.systemDefault() },
) {

    private data class MetricExtractor(
        val metric: String,
        val payloadKey: String,
        /** Payload value → value in the metric's unit; null drops it. */
        val convert: (Double) -> Double? = { it },
    )

    companion object {
        private val EVENT_METRIC_MAP: Map<String, List<MetricExtractor>> = mapOf(
            "sleep.summary.recorded" to listOf(
                MetricExtractor(DailyMetric.SLEEP_DURATION_S, "duration_seconds"),
                MetricExtractor(DailyMetric.SLEEP_DEEP_MIN, "deep_sleep_seconds") { it / 60.0 },
                MetricExtractor(DailyMetric.SLEEP_REM_MIN, "rem_sleep_seconds") { it / 60.0 },
            ),
            "hrv.recorded" to listOf(
                MetricExtractor(DailyMetric.OVERNIGHT_RMSSD_MS, "rmssd_ms"),
            ),
            "heart_rate.resting.recorded" to listOf(
                MetricExtractor(DailyMetric.RESTING_HR_BPM, "bpm"),
            ),
            "recovery.summary.recorded" to listOf(
                MetricExtractor(DailyMetric.VENDOR_RECOVERY_FRAC, "score", ::recoveryFraction),
                MetricExtractor(DailyMetric.OVERNIGHT_RMSSD_MS, "hrv_rmssd_ms"),
                MetricExtractor(DailyMetric.RESTING_HR_BPM, "resting_hr_bpm"),
            ),
        )

        /**
         * Providers report recovery either as a fraction (`0.72`) or as a
         * percentage (`72`); the runtime takes the fraction. Anything outside
         * `0..100` is not a recovery score and is dropped.
         */
        internal fun recoveryFraction(score: Double): Double? = when {
            score < 0.0 || score > 100.0 -> null
            score > 1.0 -> score / 100.0
            else -> score
        }

        /**
         * The local date [observedAt] belongs to. An instant is read in
         * [zone]; a provider's calendar date (`2026-03-28`) is already one.
         */
        internal fun localDateOf(observedAt: String, zone: ZoneId): LocalDate? =
            runCatching { Instant.parse(observedAt).atZone(zone).toLocalDate() }
                .recoverCatching { LocalDate.parse(observedAt) }
                .getOrNull()

        /** Hours after local midnight of an ISO instant, `0..24`. */
        internal fun midpointHours(iso: String, zone: ZoneId): Double? =
            runCatching {
                val t = Instant.parse(iso).atZone(zone).toLocalTime()
                t.hour + t.minute / 60.0 + t.second / 3600.0
            }.getOrNull()
    }

    /**
     * Ingest a wearable event, extract its daily values, and push them to the
     * runtime via [bridge]. No-op if [bridge] is null, the event type is not
     * mapped, or its date cannot be read. Returns the number of values stored.
     */
    fun ingestEvent(
        event: CanonicalWearableEvent,
        bridge: CoreRuntimeBridge?
    ): Int {
        val extractors = EVENT_METRIC_MAP[event.type]
        if (extractors == null || bridge == null) return 0

        val tz = zone()
        // A night belongs to the date the person wakes on, which is the local
        // date of the summary's end / observation time.
        val date = localDateOf(event.observedAt, tz) ?: return 0

        val values = mutableListOf<Pair<String, Double>>()
        for (extractor in extractors) {
            val raw = event.payload[extractor.payloadKey] ?: continue
            val number = when (raw) {
                is Number -> raw.toDouble()
                else -> raw.toString().toDoubleOrNull() ?: continue
            }
            val value = extractor.convert(number) ?: continue
            if (!value.isFinite()) continue
            values += extractor.metric to value
        }
        if (event.type == "sleep.summary.recorded") {
            (event.payload["midpoint_time"] as? String)
                ?.let { midpointHours(it, tz) }
                ?.let { values += DailyMetric.SLEEP_MIDPOINT_H to it }
        }

        var stored = 0
        for ((metric, value) in values) {
            val rc = bridge.srmPushWearableDaily(
                provider = event.provider,
                metric = metric,
                year = date.year,
                month = date.monthValue,
                day = date.dayOfMonth,
                value = value,
                vendorSummary = true,
            )
            if (rc == 0) {
                stored++
            } else {
                SynheartLogger.log(
                    "[LongitudinalSrm] ${event.provider}/$metric on $date not stored (rc=$rc)"
                )
            }
        }

        if (stored > 0) {
            // The reference is built from the days before the given date, so
            // today keeps every complete day in and no day in its own baseline.
            val today = LocalDate.now(tz)
            bridge.srmRefreshWearableReference(today.year, today.monthValue, today.dayOfMonth)
        }
        return stored
    }

    /** Return the current wearable reference JSON from the native SRM, or null. */
    fun getWearableReference(bridge: CoreRuntimeBridge?): String? {
        return bridge?.baselinesJson()
    }
}
