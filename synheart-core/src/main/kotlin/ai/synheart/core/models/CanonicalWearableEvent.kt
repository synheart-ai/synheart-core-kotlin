package ai.synheart.core.models

import java.security.MessageDigest
import org.json.JSONObject

/**
 * Canonical wearable event model.
 *
 * Represents a single wearable-sourced health or activity event in a
 * provider-agnostic format suitable for local storage and SRM ingestion.
 */
data class CanonicalWearableEvent(
    val eventId: String,
    val subjectId: String,
    val deviceInstallId: String,
    val eventClass: String,
    val type: String,
    val provider: String,
    val providerRecordId: String? = null,
    val observedAt: String,
    val ingestedAt: String,
    val effectiveStart: String? = null,
    val effectiveEnd: String? = null,
    val payload: Map<String, Any>,
    val unit: String? = null,
    val confidence: Double,
    val sourceFidelity: String,
    val provenance: Map<String, Any>? = null,
    val schemaVersion: Int = 1
) {
    companion object {
        /**
         * Compute a deterministic wearable event ID using the canonical
         * identity format.
         *
         * If [providerRecordId] is present the canonical string is:
         *   `kind=wearable_event|provider={provider}|provider_record_id={id}|v=1`
         *
         * Otherwise:
         *   `effective_end={end_or_~}|effective_start={start_or_~}|kind=wearable_event|observed={observedAt}|provider={provider}|subject={subjectId}|type={type}|v=1`
         *
         * The SHA-256 digest is truncated to 24 bytes and hex-encoded with a
         * `we_` prefix.
         */
        fun computeEventId(
            subjectId: String,
            type: String,
            provider: String,
            providerRecordId: String? = null,
            observedAt: String,
            effectiveStart: String? = null,
            effectiveEnd: String? = null
        ): String {
            val canonical: String = if (providerRecordId != null) {
                "kind=wearable_event|provider=$provider|provider_record_id=$providerRecordId|v=1"
            } else {
                val endStr = effectiveEnd ?: "~"
                val startStr = effectiveStart ?: "~"
                "effective_end=$endStr|effective_start=$startStr|kind=wearable_event|observed=$observedAt|provider=$provider|subject=$subjectId|type=$type|v=1"
            }

            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(canonical.toByteArray(Charsets.UTF_8))
            val truncated = hash.copyOfRange(0, 24)
            val hex = truncated.joinToString("") { "%02x".format(it) }
            return "we_$hex"
        }

        /** Reconstruct a [CanonicalWearableEvent] from a database row map. */
        fun fromMap(map: Map<String, Any?>): CanonicalWearableEvent {
            val payloadStr = map["payload"] as String
            val payloadMap = JSONObject(payloadStr).let { json ->
                val result = mutableMapOf<String, Any>()
                json.keys().forEach { key -> result[key] = json.get(key) }
                result
            }
            val provenanceStr = map["provenance"] as? String
            val provenanceMap = provenanceStr?.let { str ->
                val json = JSONObject(str)
                val result = mutableMapOf<String, Any>()
                json.keys().forEach { key -> result[key] = json.get(key) }
                result
            }
            return CanonicalWearableEvent(
                eventId = map["event_id"] as String,
                subjectId = map["subject_id"] as String,
                deviceInstallId = map["device_install_id"] as String,
                eventClass = map["event_class"] as String,
                type = map["event_type"] as String,
                provider = map["provider"] as String,
                providerRecordId = map["provider_record_id"] as? String,
                observedAt = map["observed_at"] as String,
                ingestedAt = map["ingested_at"] as String,
                effectiveStart = map["effective_start"] as? String,
                effectiveEnd = map["effective_end"] as? String,
                payload = payloadMap,
                unit = map["unit"] as? String,
                confidence = (map["confidence"] as Number).toDouble(),
                sourceFidelity = map["source_fidelity"] as String,
                provenance = provenanceMap,
                schemaVersion = (map["schema_version"] as? Number)?.toInt() ?: 1
            )
        }
    }

    /** Convert to a flat map suitable for SQLite insertion via ContentValues. */
    fun toMap(): Map<String, Any?> = mapOf(
        "event_id" to eventId,
        "subject_id" to subjectId,
        "device_install_id" to deviceInstallId,
        "event_class" to eventClass,
        "event_type" to type,
        "provider" to provider,
        "provider_record_id" to providerRecordId,
        "observed_at" to observedAt,
        "ingested_at" to ingestedAt,
        "effective_start" to effectiveStart,
        "effective_end" to effectiveEnd,
        "payload" to JSONObject(payload).toString(),
        "unit" to unit,
        "confidence" to confidence,
        "source_fidelity" to sourceFidelity,
        "provenance" to provenance?.let { JSONObject(it).toString() },
        "schema_version" to schemaVersion
    )
}
