package ai.synheart.core.modules.behavior

import ai.synheart.core.SynheartLogger
import org.json.JSONObject

/**
 * Coarse motion / posture class (per the motion-state spec).
 *
 * [UNKNOWN] is emitted by the runtime when required features are missing.
 */
enum class MotionStateClass { LAYING, SITTING, STANDING, MOVING, UNKNOWN }

/**
 * Snapshot of the runtime's most recent motion-state inference, parsed from
 * the `axes.behavior` axis of an HSI 1.2 snapshot.
 */
data class MotionStateSnapshot(
    val value: MotionStateClass,
    val confidence: Double,
    val windowStartMs: Long? = null,
    val windowEndMs: Long? = null,
) {
    override fun toString(): String =
        "MotionStateSnapshot(value=$value, confidence=$confidence)"

    companion object {
        private fun parseClass(s: String?): MotionStateClass = when (s) {
            "LAYING" -> MotionStateClass.LAYING
            "SITTING" -> MotionStateClass.SITTING
            "STANDING" -> MotionStateClass.STANDING
            "MOVING" -> MotionStateClass.MOVING
            else -> MotionStateClass.UNKNOWN
        }

        /**
         * Walk an HSI 1.2 JSON snapshot and pull out the motion-state axis if
         * present. Returns null when the snapshot has no `motion_state`
         * reading.
         *
         * The runtime emits MotionState as an HSV with `notes` carrying the
         * canonical string (`LAYING` | `SITTING` | …). The engine maps that
         * into `axes.behavior[*]` with `name == "motion_state"`.
         */
        fun parseFromHsiJson(hsiJson: String): MotionStateSnapshot? {
            return try {
                val behavior = JSONObject(hsiJson)
                    .optJSONObject("axes")
                    ?.optJSONArray("behavior")
                    ?: return null
                for (i in 0 until behavior.length()) {
                    val reading = behavior.optJSONObject(i) ?: continue
                    if (reading.optString("name") != "motion_state") continue
                    val classStr = reading.opt("notes") as? String
                    val conf = (reading.opt("score") as? Number)?.toDouble()
                        ?: (reading.opt("confidence") as? Number)?.toDouble()
                        ?: 0.0
                    return MotionStateSnapshot(
                        value = parseClass(classStr),
                        confidence = conf,
                        windowStartMs = (reading.opt("window_start_ms") as? Number)?.toLong(),
                        windowEndMs = (reading.opt("window_end_ms") as? Number)?.toLong(),
                    )
                }
                null
            } catch (e: Exception) {
                SynheartLogger.log("[MotionStateSnapshot] parse error: $e")
                null
            }
        }
    }
}
