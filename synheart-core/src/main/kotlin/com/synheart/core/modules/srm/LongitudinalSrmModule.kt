package com.synheart.core.modules.srm

import com.synheart.core.models.CanonicalWearableEvent
import com.synheart.core.modules.runtime.RuntimeBridge
import com.synheart.core.storage.StorageManager
import java.time.Instant

/**
 * Bridges wearable events into the Rust SRM longitudinal baselines.
 *
 * Maps recognised event types to SRM dimension names, extracts a daily
 * scalar value from the event payload, and pushes it to the native
 * runtime via [RuntimeBridge].
 */
class LongitudinalSrmModule {

    private data class DimensionExtractor(
        val dimension: String,
        val payloadKey: String
    )

    companion object {
        private val EVENT_DIMENSION_MAP: Map<String, List<DimensionExtractor>> = mapOf(
            "sleep.summary.recorded" to listOf(
                DimensionExtractor("sleep_need", "duration_seconds"),
                DimensionExtractor("sleep_regularity", "midpoint_time")
            ),
            "hrv.recorded" to listOf(
                DimensionExtractor("hrv_rmssd", "rmssd_ms")
            ),
            "heart_rate.resting.recorded" to listOf(
                DimensionExtractor("resting_hr", "bpm")
            ),
            "recovery.summary.recorded" to listOf(
                DimensionExtractor("recovery_score", "score")
            )
        )

        private fun fidelityToInt(fidelity: String): Int = when (fidelity) {
            "raw" -> 0
            "derived" -> 1
            "estimated" -> 2
            else -> 2
        }
    }

    /**
     * Ingest a wearable event, extract daily values, and push them to the
     * native SRM via [bridge]. No-op if [bridge] is null or the event type
     * is not mapped.
     */
    fun ingestEvent(
        event: CanonicalWearableEvent,
        storage: StorageManager,
        bridge: RuntimeBridge?
    ) {
        val extractors = EVENT_DIMENSION_MAP[event.type]
        if (extractors == null || bridge == null) return

        val dayIndex = (Instant.parse(event.observedAt).toEpochMilli() / 86400000).toInt()

        for (extractor in extractors) {
            val rawValue = event.payload[extractor.payloadKey] ?: continue

            val value: Double = when (rawValue) {
                is Number -> rawValue.toDouble()
                else -> rawValue.toString().toDoubleOrNull() ?: continue
            }

            bridge.pushWearableDailyValue(
                extractor.dimension,
                dayIndex,
                value,
                event.confidence,
                fidelityToInt(event.sourceFidelity)
            )
        }

        val todayDayIndex = (System.currentTimeMillis() / 86400000).toInt()
        bridge.triggerWearableRecompute(0, todayDayIndex)
    }

    /** Return the current wearable reference JSON from the native SRM, or null. */
    fun getWearableReference(bridge: RuntimeBridge?): String? {
        return bridge?.getWearableReference()
    }
}
