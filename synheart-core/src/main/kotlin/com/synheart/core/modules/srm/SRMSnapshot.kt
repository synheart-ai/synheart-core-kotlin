package com.synheart.core.modules.srm

import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant

/// Per-stratum snapshot for serialization.
data class StratumSnapshot(
    val stratum: SRMStratum,
    val status: SRMBaselineStatus,
    val entries: List<BufferEntry>,
    val reference: Map<String, MetricReference>,
    val distinctDays: Int
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("stratum", stratum.name.lowercase())
        put("status", status.name)
        put("entries", JSONArray().apply {
            for (entry in entries) {
                put(entry.toJson())
            }
        })
        put("reference", JSONObject().apply {
            for ((key, ref) in reference) {
                put(key, ref.toJson())
            }
        })
        put("distinct_days", distinctDays)
    }

    companion object {
        fun fromJson(json: JSONObject): StratumSnapshot {
            val entriesArray = json.getJSONArray("entries")
            val entries = (0 until entriesArray.length()).map { i ->
                BufferEntry.fromJson(entriesArray.getJSONObject(i))
            }

            val refObj = json.getJSONObject("reference")
            val reference = mutableMapOf<String, MetricReference>()
            for (key in refObj.keys()) {
                reference[key] = MetricReference.fromJson(refObj.getJSONObject(key))
            }

            return StratumSnapshot(
                stratum = SRMStratum.valueOf(json.getString("stratum").uppercase()),
                status = SRMBaselineStatus.valueOf(json.getString("status")),
                entries = entries,
                reference = reference,
                distinctDays = json.getInt("distinct_days")
            )
        }
    }
}

/// Complete SRM snapshot — all strata, versioned.
///
/// Used for in-memory save/restore of SRM state across session boundaries.
data class SRMSnapshot(
    val srmVersion: String,
    val createdAtUtc: Instant,
    val strata: Map<SRMStratum, StratumSnapshot>
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("srm_version", srmVersion)
        put("created_at_utc", createdAtUtc.toString())
        put("strata", JSONObject().apply {
            for ((stratum, snapshot) in strata) {
                put(stratum.name.lowercase(), snapshot.toJson())
            }
        })
    }

    companion object {
        fun fromJson(json: JSONObject): SRMSnapshot {
            val strataObj = json.getJSONObject("strata")
            val strata = mutableMapOf<SRMStratum, StratumSnapshot>()
            for (key in strataObj.keys()) {
                val stratum = SRMStratum.valueOf(key.uppercase())
                strata[stratum] = StratumSnapshot.fromJson(strataObj.getJSONObject(key))
            }

            return SRMSnapshot(
                srmVersion = json.getString("srm_version"),
                createdAtUtc = Instant.parse(json.getString("created_at_utc")),
                strata = strata
            )
        }
    }
}
