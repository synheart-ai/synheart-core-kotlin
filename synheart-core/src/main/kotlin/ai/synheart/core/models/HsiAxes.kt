package ai.synheart.core.models

import org.json.JSONArray
import org.json.JSONObject

/**
 * HSV state axes — the core state representation indices.
 *
 * These axes form the internal HSV (Human State Vector) representation. All
 * indices are normalized to the `[0.0, 1.0]` range. A missing signal is null,
 * never `0.0` — the difference between "measured as zero" and "not measured"
 * is load-bearing downstream.
 */

/** Affect axis — physiological arousal and emotional stability. */
data class AffectAxis(
    val arousalIndex: Double? = null,
    val valenceStability: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        arousalIndex?.let { put("arousalIndex", it) }
        valenceStability?.let { put("valenceStability", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): AffectAxis = AffectAxis(
            arousalIndex = json.optDoubleOrNull("arousalIndex"),
            valenceStability = json.optDoubleOrNull("valenceStability"),
        )

        fun empty(): AffectAxis = AffectAxis()
    }
}

/** Engagement axis — digital interaction patterns. */
data class EngagementAxis(
    val engagementStability: Double? = null,
    val interactionCadence: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        engagementStability?.let { put("engagementStability", it) }
        interactionCadence?.let { put("interactionCadence", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): EngagementAxis = EngagementAxis(
            engagementStability = json.optDoubleOrNull("engagementStability"),
            interactionCadence = json.optDoubleOrNull("interactionCadence"),
        )

        fun empty(): EngagementAxis = EngagementAxis()
    }
}

/** Activity axis — physical activity and motion. */
data class ActivityAxis(
    val motionIndex: Double? = null,
    val postureStability: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        motionIndex?.let { put("motionIndex", it) }
        postureStability?.let { put("postureStability", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): ActivityAxis = ActivityAxis(
            motionIndex = json.optDoubleOrNull("motionIndex"),
            postureStability = json.optDoubleOrNull("postureStability"),
        )

        fun empty(): ActivityAxis = ActivityAxis()
    }
}

/** Context axis — environmental and device state. */
data class ContextAxis(
    val screenActiveRatio: Double? = null,
    val sessionFragmentation: Double? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        screenActiveRatio?.let { put("screenActiveRatio", it) }
        sessionFragmentation?.let { put("sessionFragmentation", it) }
    }

    companion object {
        fun fromJson(json: JSONObject): ContextAxis = ContextAxis(
            screenActiveRatio = json.optDoubleOrNull("screenActiveRatio"),
            sessionFragmentation = json.optDoubleOrNull("sessionFragmentation"),
        )

        fun empty(): ContextAxis = ContextAxis()
    }
}

/** State embedding — dense vector representation of fused multimodal state. */
data class StateEmbedding(
    val vector: List<Double>,
    val timestamp: Long,
    val windowType: String,
    val dimension: Int = DEFAULT_DIMENSION,
    val model: String = DEFAULT_MODEL,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("vector", JSONArray(vector))
        put("dimension", dimension)
        put("model", model)
        put("timestamp", timestamp)
        put("windowType", windowType)
    }

    companion object {
        const val DEFAULT_DIMENSION = 64
        const val DEFAULT_MODEL = "hsi-fusion-v1"

        fun fromJson(json: JSONObject): StateEmbedding {
            val arr = json.optJSONArray("vector")
            val vector = if (arr == null) {
                emptyList()
            } else {
                (0 until arr.length()).map { arr.optDouble(it, 0.0) }
            }
            return StateEmbedding(
                vector = vector,
                dimension = json.optInt("dimension", DEFAULT_DIMENSION),
                model = json.optString("model").takeIf { it.isNotEmpty() } ?: DEFAULT_MODEL,
                timestamp = json.optLong("timestamp"),
                windowType = json.optString("windowType"),
            )
        }

        fun empty(timestamp: Long): StateEmbedding = StateEmbedding(
            vector = List(DEFAULT_DIMENSION) { 0.0 },
            timestamp = timestamp,
            windowType = "micro",
        )
    }
}

/**
 * Read a double that may legitimately be absent.
 *
 * `JSONObject.optDouble` collapses "missing" and "present but unparseable"
 * into `NaN`, which these axes must keep apart from a real reading.
 */
internal fun JSONObject.optDoubleOrNull(key: String): Double? {
    if (!has(key) || isNull(key)) return null
    val v = opt(key)
    return when (v) {
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull()
        else -> null
    }
}
