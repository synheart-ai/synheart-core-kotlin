package ai.synheart.core.models

import org.json.JSONObject

/** A single HSI axis reading with value and confidence. */
data class HSIAxisValue(
    val value: Double,
    val confidence: Double
)

/** The canonical HSI axes surfaced to hosts. */
data class HSIAxes(
    val focus: HSIAxisValue? = null,
    val arousal: HSIAxisValue? = null,
    val capacity: HSIAxisValue? = null,
    val sleep: HSIAxisValue? = null,
    /**
     * Multimodal stress reading (motion-gated autonomic primary fused with a
     * behavioral corroborator). New in engine v0.10.0; null on the legacy/1.2
     * path that never carried it.
     */
    val stress: HSIAxisValue? = null
)

/** Typed HSI state emitted by `Synheart.onStateUpdate`. */
data class HSIState(
    val subjectId: String,
    val timestampMs: Long,
    val hsi: HSIAxes,
    val rawJson: String
) {
    companion object {
        fun fromJson(json: String, subjectId: String = ""): HSIState {
            return try {
                val map = JSONObject(json)
                val timestampMs = when {
                    map.has("timestamp_ms") -> map.getLong("timestamp_ms")
                    map.has("observed_at_ms") -> map.getLong("observed_at_ms")
                    else -> System.currentTimeMillis()
                }
                val hsiObj = if (map.has("hsi")) map.getJSONObject("hsi") else map
                val sid = if (map.has("subject_id")) map.getString("subject_id") else subjectId

                HSIState(
                    subjectId = sid,
                    timestampMs = timestampMs,
                    hsi = parseAxes(hsiObj),
                    rawJson = json
                )
            } catch (_: Exception) {
                HSIState(subjectId = subjectId, timestampMs = System.currentTimeMillis(),
                    hsi = HSIAxes(), rawJson = json)
            }
        }

        private fun parseAxes(obj: JSONObject): HSIAxes {
            fun parseAxis(key: String): HSIAxisValue? {
                if (!obj.has(key)) return null
                val a = obj.optJSONObject(key) ?: return null
                return HSIAxisValue(
                    value = a.optDouble("value", 0.0),
                    confidence = a.optDouble("confidence", 0.0)
                )
            }
            return HSIAxes(
                focus = parseAxis("focus"),
                arousal = parseAxis("arousal"),
                capacity = parseAxis("capacity"),
                sleep = parseAxis("sleep"),
                stress = parseAxis("stress")
            )
        }
    }
}
