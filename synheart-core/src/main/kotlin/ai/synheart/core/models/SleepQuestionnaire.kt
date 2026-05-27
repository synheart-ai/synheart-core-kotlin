// SPDX-License-Identifier: Apache-2.0
//
// Self-reported sleep questionnaire — Phase-2 input lane for the
// RFC-SLEEP-SCORE-PIPELINE-0001 scorer. Converts a small set of
// subjective answers into the same `Aggregated` shape vendor payloads
// produce, so the engine can score without special-casing.
//
// The engine accepts null deep/REM on the aggregated path and falls
// back to duration/continuity components — that's deliberate. Don't
// invent stage durations from subjective signals; surface subjective
// quality / rested-feeling in the UI alongside the score instead.

package ai.synheart.core.models

import java.time.Instant
import java.time.temporal.ChronoUnit

/** "Did you wake up feeling rested?" */
enum class SleepFeltRested(val wire: String) {
    NO("no"),
    SOMEWHAT("somewhat"),
    YES("yes"),
}

/**
 * One night of self-reported sleep. All fields are optional except
 * [bedtime] and [wakeTime] — without them we can't bound TIB.
 */
data class SleepQuestionnaireAnswers(
    val bedtime: Instant,
    val wakeTime: Instant,
    val sleepLatencyMinutes: Int = 15,
    val awakenings: Int = 0,
    val subjectiveQuality: Int? = null,
    val feltRested: SleepFeltRested? = null,
) {
    /** Time-in-bed in minutes (wake − bedtime, never negative). */
    val timeInBedMinutes: Double
        get() {
            val diff = ChronoUnit.MINUTES.between(bedtime, wakeTime).toDouble()
            return if (diff < 0) 0.0 else diff
        }

    /**
     * Heuristic awake minutes: latency + 5 min per awakening (a
     * reasonable lower-bound; the user typically remembers fewer
     * awakenings than actually occurred, but we'd rather under-count
     * than fabricate).
     */
    val awakeMinutes: Double
        get() = (sleepLatencyMinutes + awakenings * 5).toDouble()

    /** Estimated total asleep minutes. */
    val totalSleepMinutes: Double
        get() {
            val asleep = timeInBedMinutes - awakeMinutes
            return if (asleep < 0) 0.0 else asleep
        }

    /**
     * Wire-shape payload for the SDK's `Baselines.ingestVendorSleep`
     * call. Engine treats `kind: aggregated` with null deep/rem as the
     * honest "we know totals, not stages" path.
     */
    fun toIngestPayload(): Map<String, Any?> {
        val payload = mutableMapOf<String, Any?>(
            "timestamp" to wakeTime.toString(),
            "self_report_data" to mapOf(
                "time_in_bed_minutes" to timeInBedMinutes,
                "total_sleep_minutes" to totalSleepMinutes,
                "awake_minutes" to awakeMinutes,
                "awakenings" to awakenings,
                "session_start_ms" to bedtime.toEpochMilli(),
                "session_end_ms" to wakeTime.toEpochMilli(),
            ),
        )
        if (subjectiveQuality != null) payload["subjective_quality"] = subjectiveQuality
        if (feltRested != null) payload["felt_rested"] = feltRested.wire
        return payload
    }
}
