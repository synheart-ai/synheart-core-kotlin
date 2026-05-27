package ai.synheart.core.modules.wear

import ai.synheart.core.SynheartLogger
import ai.synheart.core.bridge.CoreRuntimeBridge
import ai.synheart.core.models.CanonicalWearableEvent
import ai.synheart.core.modules.srm.LongitudinalSrmModule
import java.time.Instant

/**
 * Processes incoming RAMEN vendor events into the SynHeart pipeline:
 *   RamenEvent payload -> CanonicalWearableEvent -> SRM push -> runtime
 *
 * This is the bridge between the wear SDK's real-time event stream and the
 * core SDK's longitudinal SRM engine. Each vendor event (sleep, recovery,
 * HRV, strain) is normalized and pushed to the runtime for baseline
 */
class WearableEventProcessor(
    @Volatile private var bridge: CoreRuntimeBridge?,
    private val subjectId: String,
    private val deviceInstallId: String,
    private val srm: LongitudinalSrmModule = LongitudinalSrmModule()
) {

    /** Update the bridge after CoreRuntimeBridge is initialized. */
    fun updateBridge(newBridge: CoreRuntimeBridge?) {
        bridge = newBridge
    }

    /**
     * Process a raw vendor event from RAMEN.
     *
     * @param provider e.g. "whoop", "garmin"
     * @param eventType e.g. "sleep.updated", "recovery.updated"
     * @param payload decoded JSON payload from the EventEnvelope
     * @param eventId RAMEN event ID (used for dedup)
     * @param seq RAMEN sequence number
     * @return the canonical event if processed, null if skipped (unknown type or dedup)
     */
    fun processRamenEvent(
        provider: String,
        eventType: String,
        payload: Map<String, Any?>,
        eventId: String,
        seq: Int
    ): CanonicalWearableEvent? {
        // Map RAMEN event type to canonical event type
        val mapping = mapEventType(provider, eventType)
        if (mapping == null) {
            SynheartLogger.log(
                "[WearableEventProcessor] Unknown event type: $provider/$eventType -- skipping"
            )
            return null
        }

        // Extract timestamps
        val observedAt = extractTimestamp(payload, mapping.observedAtKey)
            ?: Instant.now().toString()
        val effectiveStart = extractTimestamp(payload, mapping.effectiveStartKey)
        val effectiveEnd = extractTimestamp(payload, mapping.effectiveEndKey)

        // Extract provider record ID for deterministic dedup
        val providerRecordId = payload[mapping.providerRecordIdKey]?.toString()

        // Extract confidence from payload or use provider default
        val confidence = extractConfidence(payload, provider)

        // Build canonical event
        val canonicalEventId = CanonicalWearableEvent.computeEventId(
            subjectId = subjectId,
            type = mapping.canonicalType,
            provider = provider,
            providerRecordId = providerRecordId,
            observedAt = observedAt,
            effectiveStart = effectiveStart,
            effectiveEnd = effectiveEnd
        )

        // Extract the sub-payload for the canonical event
        val canonicalPayload = mapping.extractPayload(payload)

        val nowUtc = Instant.now().toString()

        val event = CanonicalWearableEvent(
            eventId = canonicalEventId,
            subjectId = subjectId,
            deviceInstallId = deviceInstallId,
            eventClass = "PROVIDER_SUMMARY",
            type = mapping.canonicalType,
            provider = provider,
            providerRecordId = providerRecordId,
            observedAt = observedAt,
            ingestedAt = nowUtc,
            effectiveStart = effectiveStart,
            effectiveEnd = effectiveEnd,
            payload = canonicalPayload,
            confidence = confidence,
            sourceFidelity = "provider_summary",
            provenance = mapOf(
                "ramen_event_id" to eventId,
                "ramen_seq" to seq,
                "raw_event_type" to eventType
            )
        )
        // Push to longitudinal SRM via runtime bridge
        try {
            srm.ingestEvent(event, bridge)
            SynheartLogger.log(
                "[WearableEventProcessor] Processed $provider/${mapping.canonicalType} " +
                    "(seq=$seq, confidence=${"%.2f".format(confidence)})"
            )
        } catch (e: Exception) {
            SynheartLogger.log("[WearableEventProcessor] SRM push error: $e")
        }

        return event
    }

    // -- Event Mapping Registry --

    private fun mapEventType(provider: String, eventType: String): EventMapping? {
        val key = "$provider:$eventType"
        return EVENT_MAPPINGS[key] ?: EVENT_MAPPINGS[eventType]
    }

    private fun extractTimestamp(payload: Map<String, Any?>, key: String?): String? {
        if (key == null) return null
        val raw = payload[key] ?: return null
        return when (raw) {
            is String -> {
                // Validate it parses as an instant/date, then return as-is
                try {
                    Instant.parse(raw)
                    raw
                } catch (_: Exception) {
                    // Maybe it's a date-only string like "2024-03-28"; pass through
                    raw
                }
            }
            is Number -> Instant.ofEpochMilli(raw.toLong()).toString()
            else -> null
        }
    }

    private fun extractConfidence(payload: Map<String, Any?>, provider: String): Double {
        // Some providers include quality/confidence in payload
        val quality = payload["quality"]
        if (quality is Map<*, *>) {
            val conf = quality["confidence"]
            if (conf is Number) return conf.toDouble().coerceIn(0.0, 1.0)
        }
        // Provider-level defaults (vendor summaries are generally high confidence)
        return when (provider) {
            "whoop" -> 0.90
            "garmin" -> 0.85
            "oura" -> 0.88
            else -> 0.75
        }
    }

    companion object {

        private val EVENT_MAPPINGS: Map<String, EventMapping> = mapOf(
            // WHOOP events
            "whoop:recovery.updated" to EventMapping(
                canonicalType = "recovery.summary.recorded",
                observedAtKey = "created_at",
                providerRecordIdKey = "cycle_id",
                extractPayload = { p ->
                    buildPayload(
                        "score" to (asDouble(p["score"]) ?: asDouble(p["recovery_score"])),
                        "hrv_rmssd_ms" to asDouble(p["hrv"]),
                        "resting_hr_bpm" to asDouble(p["resting_heart_rate"]),
                        "spo2_pct" to asDouble(p["spo2_percentage"])
                    )
                }
            ),
            "whoop:sleep.updated" to EventMapping(
                canonicalType = "sleep.summary.recorded",
                observedAtKey = "end",
                effectiveStartKey = "start",
                effectiveEndKey = "end",
                providerRecordIdKey = "id",
                extractPayload = { p ->
                    buildPayload(
                        "duration_seconds" to (asInt(p["total_in_bed_time_milli"])
                            ?: asInt(p["duration_seconds"])),
                        "efficiency_pct" to asDouble(p["sleep_efficiency"]),
                        "midpoint_time" to midpointIso(p["start"]?.toString(), p["end"]?.toString())
                    )
                }
            ),
            "whoop:workout.updated" to EventMapping(
                canonicalType = "workout.summary.recorded",
                observedAtKey = "end",
                effectiveStartKey = "start",
                effectiveEndKey = "end",
                providerRecordIdKey = "id",
                extractPayload = { p ->
                    buildPayload(
                        "strain_score" to (asDouble(p["strain"]) ?: asDouble(p["score"])),
                        "duration_seconds" to asInt(p["duration_seconds"]),
                        "avg_hr_bpm" to asDouble(p["average_heart_rate"]),
                        "max_hr_bpm" to asDouble(p["max_heart_rate"]),
                        "calories" to asDouble(p["kilojoule"])
                    )
                }
            ),

            // Garmin events
            "garmin:sleep.updated" to EventMapping(
                canonicalType = "sleep.summary.recorded",
                observedAtKey = "calendarDate",
                providerRecordIdKey = "summaryId",
                extractPayload = { p ->
                    buildPayload(
                        "duration_seconds" to asInt(p["durationInSeconds"]),
                        "deep_sleep_seconds" to asInt(p["deepSleepDurationInSeconds"]),
                        "light_sleep_seconds" to asInt(p["lightSleepDurationInSeconds"]),
                        "rem_sleep_seconds" to asInt(p["remSleepInSeconds"]),
                        "awake_seconds" to asInt(p["awakeDurationInSeconds"])
                    )
                }
            ),
            "garmin:recovery.updated" to EventMapping(
                canonicalType = "recovery.summary.recorded",
                observedAtKey = "calendarDate",
                providerRecordIdKey = "summaryId",
                extractPayload = { p ->
                    val bodyBattery = asDouble(p["bodyBatteryChargedValue"])
                    buildPayload(
                        "score" to bodyBattery?.let { it / 100.0 },
                        "stress_avg" to asDouble(p["averageStressLevel"]),
                        "resting_hr_bpm" to asDouble(p["restingHeartRateInBeatsPerMinute"])
                    )
                }
            ),
            "garmin:hrv.updated" to EventMapping(
                canonicalType = "hrv.recorded",
                observedAtKey = "calendarDate",
                providerRecordIdKey = "summaryId",
                extractPayload = { p ->
                    buildPayload(
                        "rmssd_ms" to (asDouble(p["weeklyAvg"]) ?: asDouble(p["lastNightAvg"])),
                        "status" to p["hrvStatus"]
                    )
                }
            ),

            // Generic fallbacks (provider-agnostic event types)
            "recovery.updated" to EventMapping(
                canonicalType = "recovery.summary.recorded",
                observedAtKey = "created_at",
                extractPayload = { p ->
                    buildPayload(
                        "score" to (asDouble(p["score"]) ?: asDouble(p["recovery_score"]))
                    )
                }
            ),
            "sleep.updated" to EventMapping(
                canonicalType = "sleep.summary.recorded",
                observedAtKey = "end",
                effectiveStartKey = "start",
                effectiveEndKey = "end",
                extractPayload = { p ->
                    buildPayload(
                        "duration_seconds" to asInt(p["duration_seconds"]),
                        "efficiency_pct" to asDouble(p["efficiency"])
                    )
                }
            )
        )

        private fun asDouble(v: Any?): Double? {
            if (v == null) return null
            if (v is Number) return v.toDouble()
            return v.toString().toDoubleOrNull()
        }

        private fun asInt(v: Any?): Int? {
            if (v == null) return null
            if (v is Int) return v
            if (v is Number) return v.toInt()
            return v.toString().toIntOrNull()
        }

        private fun midpointIso(start: String?, end: String?): String? {
            if (start == null || end == null) return null
            return try {
                val s = Instant.parse(start)
                val e = Instant.parse(end)
                val midMs = s.toEpochMilli() + (e.toEpochMilli() - s.toEpochMilli()) / 2
                Instant.ofEpochMilli(midMs).toString()
            } catch (_: Exception) {
                null
            }
        }

        /**
         * Build a payload map, filtering out null values.
         */
        private fun buildPayload(vararg entries: Pair<String, Any?>): Map<String, Any> {
            val result = mutableMapOf<String, Any>()
            for ((key, value) in entries) {
                if (value != null) result[key] = value
            }
            return result
        }

    }
}

/**
 * Configuration for mapping a RAMEN event type to a canonical event.
 */
internal data class EventMapping(
    val canonicalType: String,
    val observedAtKey: String? = null,
    val effectiveStartKey: String? = null,
    val effectiveEndKey: String? = null,
    val providerRecordIdKey: String? = null,
    val extractPayload: (Map<String, Any?>) -> Map<String, Any>
)
