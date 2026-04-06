package ai.synheart.core.modules.srm

import ai.synheart.core.bridge.CoreRuntimeBridge
import ai.synheart.core.models.CanonicalWearableEvent
import java.time.Instant

/**
 * Bridges wearable events into the Rust SRM longitudinal baselines.
 *
 * Maps recognised event types to SRM dimension names, extracts a daily
 * scalar value from the event payload, and pushes it to the native
 * runtime via [CoreRuntimeBridge].
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
                DimensionExtractor("sleep_regularity", "midpoint_time"),
                DimensionExtractor("sleep_efficiency", "efficiency_pct")
            ),
            "hrv.recorded" to listOf(
                DimensionExtractor("hrv_rmssd", "rmssd_ms")
            ),
            "heart_rate.resting.recorded" to listOf(
                DimensionExtractor("resting_hr", "bpm")
            ),
            "recovery.summary.recorded" to listOf(
                DimensionExtractor("recovery_score", "score"),
                DimensionExtractor("hrv_rmssd", "hrv_rmssd_ms"),
                DimensionExtractor("resting_hr", "resting_hr_bpm")
            ),
            "workout.summary.recorded" to listOf(
                DimensionExtractor("strain_score", "strain_score")
            ),
            "stress.summary.recorded" to listOf(
                DimensionExtractor("stress_score", "score")
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
        bridge: CoreRuntimeBridge?
    ) {
        val extractors = EVENT_DIMENSION_MAP[event.type]
        if (extractors == null || bridge == null) return

        val dayIndex = (Instant.parse(event.observedAt).toEpochMilli() / 86400000).toInt()
        val nowMs = System.currentTimeMillis()

        val dimensions = mutableListOf<org.json.JSONObject>()
        for (extractor in extractors) {
            val rawValue = event.payload[extractor.payloadKey] ?: continue

            val value: Double = when (rawValue) {
                is Number -> rawValue.toDouble()
                else -> rawValue.toString().toDoubleOrNull() ?: continue
            }

            dimensions.add(org.json.JSONObject().apply {
                put("dimension", extractor.dimension)
                put("day_index", dayIndex)
                put("value", value)
                put("confidence", event.confidence)
                put("fidelity", fidelityToInt(event.sourceFidelity))
            })
        }

        if (dimensions.isNotEmpty()) {
            val batchJson = org.json.JSONObject().apply {
                put("type", "wearable_daily_values")
                put("dimensions", org.json.JSONArray(dimensions))
                put("recompute_from", 0)
                put("recompute_to", (nowMs / 86400000).toInt())
            }.toString()
            bridge.ingestBatch(batchJson, nowMs)
        }
    }

    /** Return the current wearable reference JSON from the native SRM, or null. */
    fun getWearableReference(bridge: CoreRuntimeBridge?): String? {
        return bridge?.baselinesJson()
    }
}
