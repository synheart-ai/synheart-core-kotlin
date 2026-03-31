package ai.synheart.core.modules.lab_ingest

import ai.synheart.core.config.SynheartConfig
import ai.synheart.core.modules.behavior.BehaviorEvent
import ai.synheart.core.modules.behavior.BehaviorEventType
import ai.synheart.core.modules.phone.PhoneDataPoint
import ai.synheart.core.modules.wear.WearSample
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Builds lab ingestion payloads from SDK internal data.
 *
 * Aggregates raw wear samples, behavior events, and phone context
 * into the structured format expected by the lab ingestion API.
 */
object LabPayloadBuilder {

    private val isoFormatter = DateTimeFormatter.ISO_INSTANT

    private fun msToIso(ms: Long): String =
        isoFormatter.format(Instant.ofEpochMilli(ms))

    // -- Metadata payload --------------------------------------------------

    /**
     * Build a metadata payload from SDK config and user-provided data.
     *
     * [userInfo] contains user-specific fields (birthdate, gender, etc.)
     * that are not part of SynheartConfig.
     */
    fun buildMetadata(
        config: SynheartConfig,
        deviceId: String,
        platform: String,
        osVersion: String,
        userInfo: Map<String, Any?>? = null,
        deviceExtra: Map<String, Any?>? = null
    ): Map<String, Any?> {
        val now = msToIso(System.currentTimeMillis())

        return mapOf(
            "app" to mapOf(
                "created_at" to now,
                "app_id" to config.appId,
                "app_name" to config.appName,
                "app_version" to config.appVersion,
                "category" to config.category,
                "developer" to config.developer,
                "extra_data" to config.additionalAppMetadata
            ),
            "user" to mapOf(
                "user_id" to config.subjectId,
                "birthdate" to (userInfo?.get("birthdate") ?: ""),
                "gender" to (userInfo?.get("gender") ?: ""),
                "blood_type" to userInfo?.get("blood_type"),
                "skin_type" to userInfo?.get("skin_type"),
                "race" to userInfo?.get("race"),
                "extra_data" to (userInfo?.get("extra_data") ?: emptyMap<String, Any>())
            ),
            "devices" to listOf(
                mapOf(
                    "created_at" to now,
                    "device" to mapOf(
                        "device_id" to deviceId,
                        "device_type" to (userInfo?.get("device_type") ?: "Phone"),
                        "device_model" to (userInfo?.get("device_model") ?: ""),
                        "platform" to platform,
                        "device_os_version" to osVersion,
                        "extra_data" to (deviceExtra ?: emptyMap<String, Any>())
                    )
                )
            )
        )
    }

    // -- Session payload ---------------------------------------------------

    /**
     * Build a session payload from SDK internal data collected during a session.
     */
    fun buildSession(
        sessionId: String,
        deviceId: String,
        appId: String,
        userId: String,
        startedAtMs: Long,
        endedAtMs: Long,
        dataOnCloud: Boolean,
        cohortId: String? = null,
        extraData: Map<String, Any?>? = null,
        failures: List<Map<String, Any?>>? = null,
        wearSamples: List<WearSample>,
        behaviorEvents: List<BehaviorEvent>,
        phoneDataPoints: List<PhoneDataPoint>,
        insightData: Map<String, Any?>? = null,
        childWindows: List<Map<String, Any?>>? = null,
        previousSessionEndMs: Long? = null
    ): Map<String, Any?> {
        val startedAt = msToIso(startedAtMs)
        val endedAt = msToIso(endedAtMs)
        val durationMs = endedAtMs - startedAtMs

        val sessionMetadata = mutableMapOf<String, Any?>(
            "device_id" to deviceId,
            "app_id" to appId,
            "user_id" to userId,
            "started_at" to startedAt,
            "ended_at" to endedAt,
            "data_on_cloud" to dataOnCloud
        )
        if (cohortId != null) sessionMetadata["cohort_id"] = cohortId
        if (extraData != null) sessionMetadata["extra_data"] = extraData

        return mapOf(
            "id" to sessionId,
            "session_metadata" to sessionMetadata,
            "session_failure" to (failures ?: emptyList<Map<String, Any?>>()),
            "session_window" to listOf(
                buildWindowNode(
                    windowId = sessionId,
                    parentId = null,
                    startedAt = startedAt,
                    endedAt = endedAt,
                    durationMs = durationMs,
                    wearSamples = wearSamples,
                    behaviorEvents = behaviorEvents,
                    phoneDataPoints = phoneDataPoints,
                    insightData = insightData,
                    children = childWindows ?: emptyList(),
                    previousSessionEndMs = previousSessionEndMs
                )
            )
        )
    }

    // -- Window node builder -----------------------------------------------

    private fun buildWindowNode(
        windowId: String,
        parentId: String?,
        startedAt: String,
        endedAt: String,
        durationMs: Long,
        wearSamples: List<WearSample>,
        behaviorEvents: List<BehaviorEvent>,
        phoneDataPoints: List<PhoneDataPoint>,
        insightData: Map<String, Any?>? = null,
        children: List<Map<String, Any?>> = emptyList(),
        previousSessionEndMs: Long? = null
    ): Map<String, Any?> {
        return mapOf(
            "id" to windowId,
            "parent_id" to parentId,
            "window_metadata" to mapOf(
                "started_at" to startedAt,
                "ended_at" to endedAt,
                "duration_ms" to durationMs
            ),
            "window_data" to mapOf(
                "state_data" to mapOf(
                    "wear_data" to aggregateWearData(wearSamples),
                    "behavior_data" to aggregateBehaviorData(
                        behaviorEvents, phoneDataPoints, durationMs,
                        previousSessionEndMs = previousSessionEndMs
                    )
                ),
                "insight_data" to (insightData ?: emptyMap<String, Any>())
            ),
            "children" to children
        )
    }

    // -- Wear data aggregation ---------------------------------------------

    private fun aggregateWearData(samples: List<WearSample>): Map<String, Any?> {
        if (samples.isEmpty()) {
            return mapOf(
                "has_wear_data" to false,
                "hr_mean" to null,
                "hrv_rmssd_mean" to null
            )
        }

        val hrs = samples.mapNotNull { it.hr }
        val hrvs = samples.mapNotNull { it.hrvRmssd }

        val result = mutableMapOf<String, Any?>(
            "has_wear_data" to true,
            "hr_mean" to if (hrs.isNotEmpty()) hrs.average() else null,
            "hrv_rmssd_mean" to if (hrvs.isNotEmpty()) hrvs.average() else null
        )
        if (hrs.isNotEmpty()) {
            result["hr_min"] = hrs.min()
            result["hr_max"] = hrs.max()
        }
        return result
    }

    // -- Behavior data aggregation -----------------------------------------

    private fun aggregateBehaviorData(
        events: List<BehaviorEvent>,
        phonePoints: List<PhoneDataPoint>,
        durationMs: Long,
        previousSessionEndMs: Long? = null
    ): Map<String, Any?> {
        val durationSec = durationMs / 1000.0
        if (durationSec <= 0) return zeroBehaviorData()

        // Count event types
        var tapCount = 0
        var scrollCount = 0
        var keyDownCount = 0
        var appSwitchCount = 0
        var notifReceived = 0
        var notifOpened = 0

        val keyDownTimestamps = mutableListOf<Long>()

        for (e in events) {
            when (e.type) {
                BehaviorEventType.TAP -> tapCount++
                BehaviorEventType.SCROLL -> scrollCount++
                BehaviorEventType.KEY_DOWN -> {
                    keyDownCount++
                    keyDownTimestamps.add(e.timestamp)
                }
                BehaviorEventType.KEY_UP -> { /* counted but not used */ }
                BehaviorEventType.APP_SWITCH -> appSwitchCount++
                BehaviorEventType.NOTIFICATION_RECEIVED -> notifReceived++
                BehaviorEventType.NOTIFICATION_OPENED -> notifOpened++
            }
        }

        val totalEvents = events.size
        val notifIgnored = notifReceived - notifOpened
        val notifIgnoreRate = if (notifReceived > 0) notifIgnored.toDouble() / notifReceived else 0.0

        // Phone context aggregation
        val motionPoints = phonePoints.filter { it.motionLevel != null }
        val screenPoints = phonePoints.filter { it.screenOn != null }
        val appSwitchPoints = phonePoints.filter { it.appSwitch == true }
        val notifPoints = phonePoints.filter { it.notification == true }

        // Typing session analysis
        val typingAnalysis = analyzeTypingSessions(keyDownTimestamps, durationSec)

        // Compute behavioral metrics
        val interactionIntensity = if (totalEvents > 0)
            (totalEvents / durationSec).coerceIn(0.0, 1.0) else 0.0

        val taskSwitchRate = appSwitchCount / durationSec

        val idleRatio = computeIdleRatio(events, durationMs)
        val activeTime = durationSec * (1.0 - idleRatio)
        val burstiness = computeBurstiness(events, durationMs)

        val notifLoad = notifReceived / max(durationSec / 60.0, 1.0)
        val normalizedNotifLoad = (notifLoad / 10.0).coerceIn(0.0, 1.0)

        val distractionScore = computeDistractionScore(
            appSwitchCount = appSwitchCount,
            notifIgnored = notifIgnored,
            notifReceived = notifReceived,
            scrollCount = scrollCount,
            idleRatio = idleRatio
        )
        val focusHint = (1.0 - distractionScore).coerceIn(0.0, 1.0)

        val deepFocusBlocks = detectDeepFocusBlocks(events, durationMs)

        // Session spacing
        var sessionSpacing = 0
        if (previousSessionEndMs != null && events.isNotEmpty()) {
            val sessionStartMs = events.first().timestamp
            if (sessionStartMs > 0) {
                sessionSpacing = max(0, ((sessionStartMs - previousSessionEndMs) / 1000).toInt())
            }
        }

        val microSession = durationMs < 30000

        val scrollJitterRate = if (scrollCount > 0)
            (scrollCount.toDouble() / max(totalEvents, 1)).coerceIn(0.0, 1.0) else 0.0

        @Suppress("UNCHECKED_CAST")
        val correctionKeyCount = typingAnalysis["correction_key_count"] as Int

        return mapOf(
            "micro_session" to microSession,
            "session_spacing" to sessionSpacing,
            "motion_state" to buildMotionState(motionPoints),
            "device_context" to buildDeviceContext(screenPoints),
            "activity_summary" to mapOf(
                "total_events" to totalEvents,
                "app_switch_count" to (appSwitchCount + appSwitchPoints.size)
            ),
            "notification_summary" to mapOf(
                "notification_count" to (notifReceived + notifPoints.size),
                "notification_ignored" to notifIgnored,
                "notification_ignore_rate" to notifIgnoreRate,
                "notification_clustering_index" to computeNotifClustering(events, durationMs),
                "call_count" to 0,
                "call_ignored" to 0
            ),
            "system_state" to mapOf(
                "internet_state" to true,
                "do_not_disturb" to false,
                "charging" to false
            ),
            "typing_session_summary" to typingAnalysis,
            "behavioral_metrics" to mapOf(
                "correction_rate" to if (correctionKeyCount > 0)
                    correctionKeyCount.toDouble() / max(keyDownCount, 1) else 0.0,
                "editing_friction" to (typingAnalysis["clipboard_activity_rate"] as Number).toDouble(),
                "interaction_intensity" to interactionIntensity,
                "task_switch_rate" to taskSwitchRate,
                "task_switch_cost" to 0,
                "idle_ratio" to idleRatio,
                "active_interaction_time" to activeTime,
                "burstiness" to burstiness,
                "notification_load" to normalizedNotifLoad,
                "fragmented_idle_ratio" to computeFragmentedIdleRatio(events, durationMs),
                "scroll_jitter_rate" to scrollJitterRate,
                "distraction_score" to distractionScore,
                "focus_hint" to focusHint,
                "deep_focus_blocks" to deepFocusBlocks
            )
        )
    }

    // -- Typing session analysis -------------------------------------------

    private fun analyzeTypingSessions(
        keyTimestamps: List<Long>,
        durationSec: Double
    ): Map<String, Any> {
        if (keyTimestamps.isEmpty()) {
            return zeroTypingData()
        }

        // Group keystrokes into sessions (gap > 2s = new session)
        val sessionGapMs = 2000
        val sorted = keyTimestamps.sorted()
        val sessions = mutableListOf(mutableListOf(sorted.first()))

        for (i in 1 until sorted.size) {
            val gap = sorted[i] - sorted[i - 1]
            if (gap > sessionGapMs) {
                sessions.add(mutableListOf(sorted[i]))
            } else {
                sessions.last().add(sorted[i])
            }
        }

        // Inter-tap intervals (seconds)
        val intervals = mutableListOf<Double>()
        for (i in 1 until sorted.size) {
            intervals.add((sorted[i] - sorted[i - 1]) / 1000.0)
        }

        val avgIti = if (intervals.isEmpty()) 0.0 else intervals.average()

        // Cadence stability (1 - coefficient of variation)
        var cadenceStability = 0.0
        if (intervals.size > 1) {
            val mean = avgIti
            val variance = intervals.map { (it - mean) * (it - mean) }.average()
            val std = sqrt(variance)
            cadenceStability = if (mean > 0) (1.0 - (std / mean)).coerceIn(0.0, 1.0) else 0.0
        }

        // Session durations
        val sessionDurations = sessions.map { s ->
            if (s.size < 2) 0.0 else (s.last() - s.first()) / 1000.0
        }
        val totalTypingDuration = sessionDurations.sum()

        // Session gaps
        val sessionGaps = mutableListOf<Double>()
        for (i in 1 until sessions.size) {
            sessionGaps.add((sessions[i].first() - sessions[i - 1].last()) / 1000.0)
        }
        val avgGap = if (sessionGaps.isEmpty()) 0.0 else sessionGaps.average()

        // Burstiness of typing
        val typingBurstiness = if (sessions.size > 1)
            (sessions.count { it.size > 5 }.toDouble() / sessions.size).coerceIn(0.0, 1.0) else 0.0

        return mapOf(
            "correction_key_count" to 0,
            "clipboard_copy_count" to 0,
            "clipboard_paste_count" to 0,
            "clipboard_activity_rate" to 0.0,
            "typing_session_count" to sessions.size,
            "average_keystrokes_per_session" to (sorted.size.toDouble() / sessions.size).toInt(),
            "average_typing_session_duration" to if (sessionDurations.isEmpty()) 0.0
                else totalTypingDuration / sessions.size,
            "average_typing_speed" to if (totalTypingDuration > 0)
                sorted.size / totalTypingDuration else 0.0,
            "average_typing_gap" to avgGap,
            "average_inter-tap_interval" to avgIti,
            "average_typing_cadence_stability" to cadenceStability,
            "average_burstiness_of_typing" to typingBurstiness,
            "total_typing_duration" to totalTypingDuration,
            "active_typing_ratio" to if (durationSec > 0)
                (totalTypingDuration / durationSec).coerceIn(0.0, 1.0) else 0.0,
            "deep_typing_blocks" to sessions.count { it.size > 20 }
        )
    }

    // -- Motion state ------------------------------------------------------

    private fun buildMotionState(motionPoints: List<PhoneDataPoint>): Map<String, Any> {
        if (motionPoints.isEmpty()) {
            return mapOf(
                "state" to listOf("unknown"),
                "major_state" to "unknown",
                "major_state_pct" to 0.0,
                "ml_model" to "none",
                "confidence" to 0.0
            )
        }

        // Classify: <0.1 = stationary, <0.4 = walking, else running
        val states = motionPoints.map { p ->
            val level = p.motionLevel ?: 0.0
            when {
                level < 0.1 -> "stationary"
                level < 0.4 -> "walking"
                else -> "running"
            }
        }

        val counts = states.groupingBy { it }.eachCount()
        val majorEntry = counts.maxByOrNull { it.value }!!
        val majorState = majorEntry.key
        val majorPct = majorEntry.value.toDouble() / states.size

        // Deduplicate consecutive states
        val uniqueStates = mutableListOf(states.first())
        for (i in 1 until states.size) {
            if (states[i] != states[i - 1]) uniqueStates.add(states[i])
        }

        return mapOf(
            "state" to uniqueStates,
            "major_state" to majorState,
            "major_state_pct" to majorPct,
            "ml_model" to "motion_heuristic_v1",
            "confidence" to majorPct
        )
    }

    // -- Device context ----------------------------------------------------

    @Suppress("UNUSED_PARAMETER")
    private fun buildDeviceContext(screenPoints: List<PhoneDataPoint>): Map<String, Any> {
        return mapOf(
            "avg_screen_brightness" to 0.5,
            "start_orientation" to "portrait",
            "orientation_changes" to 0
        )
    }

    // -- Statistical helpers -----------------------------------------------

    private fun computeIdleRatio(events: List<BehaviorEvent>, durationMs: Long): Double {
        if (events.isEmpty() || durationMs <= 0) return 1.0

        val idleThresholdMs = 5000
        val sorted = events.sortedBy { it.timestamp }

        var idleMs = 0L
        for (i in 1 until sorted.size) {
            val gap = sorted[i].timestamp - sorted[i - 1].timestamp
            if (gap > idleThresholdMs) idleMs += gap
        }

        return (idleMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
    }

    private fun computeFragmentedIdleRatio(events: List<BehaviorEvent>, durationMs: Long): Double {
        if (events.isEmpty() || durationMs <= 0) return 0.0

        val shortIdleMin = 5000L
        val shortIdleMax = 30000L
        val sorted = events.sortedBy { it.timestamp }

        var fragmentedMs = 0L
        for (i in 1 until sorted.size) {
            val gap = sorted[i].timestamp - sorted[i - 1].timestamp
            if (gap in shortIdleMin..shortIdleMax) fragmentedMs += gap
        }

        return (fragmentedMs.toDouble() / durationMs).coerceIn(0.0, 1.0)
    }

    private fun computeBurstiness(events: List<BehaviorEvent>, durationMs: Long): Double {
        if (events.size < 2 || durationMs <= 0) return 0.0

        val sorted = events.sortedBy { it.timestamp }
        val intervals = mutableListOf<Double>()
        for (i in 1 until sorted.size) {
            intervals.add((sorted[i].timestamp - sorted[i - 1].timestamp).toDouble())
        }

        if (intervals.isEmpty()) return 0.0
        val mean = intervals.average()
        if (mean <= 0) return 0.0
        val variance = intervals.map { (it - mean) * (it - mean) }.average()
        val std = sqrt(variance)

        // Fano factor normalized to 0-1
        return ((std / mean) / 2.0).coerceIn(0.0, 1.0)
    }

    private fun computeNotifClustering(events: List<BehaviorEvent>, durationMs: Long): Double {
        val notifs = events.filter {
            it.type == BehaviorEventType.NOTIFICATION_RECEIVED ||
            it.type == BehaviorEventType.NOTIFICATION_OPENED
        }.sortedBy { it.timestamp }

        if (notifs.size < 2) return 0.0

        var clustered = 0
        for (i in 1 until notifs.size) {
            if (notifs[i].timestamp - notifs[i - 1].timestamp < 60_000) {
                clustered++
            }
        }
        return (clustered.toDouble() / (notifs.size - 1)).coerceIn(0.0, 1.0)
    }

    private fun computeDistractionScore(
        appSwitchCount: Int,
        notifIgnored: Int,
        notifReceived: Int,
        scrollCount: Int,
        idleRatio: Double
    ): Double {
        val switchFactor = (appSwitchCount / 10.0).coerceIn(0.0, 1.0) * 0.3
        val notifFactor = if (notifReceived > 0)
            ((1.0 - notifIgnored.toDouble() / notifReceived) * 0.2) else 0.0
        val idleFactor = idleRatio * 0.3
        val scrollFactor = (scrollCount / 50.0).coerceIn(0.0, 1.0) * 0.2

        return (switchFactor + notifFactor + idleFactor + scrollFactor).coerceIn(0.0, 1.0)
    }

    private fun detectDeepFocusBlocks(
        events: List<BehaviorEvent>,
        durationMs: Long
    ): List<Map<String, Any>> {
        if (events.isEmpty()) return emptyList()

        val minBlockMs = 120000L
        val maxGapMs = 10000L
        val sorted = events.sortedBy { it.timestamp }
        val blocks = mutableListOf<Map<String, Any>>()

        var blockStart = sorted.first().timestamp
        var blockEnd = sorted.first().timestamp

        for (i in 1 until sorted.size) {
            val gap = sorted[i].timestamp - sorted[i - 1].timestamp
            if (gap <= maxGapMs) {
                blockEnd = sorted[i].timestamp
            } else {
                val blockDuration = blockEnd - blockStart
                if (blockDuration >= minBlockMs) {
                    blocks.add(mapOf(
                        "started_at" to msToIso(blockStart),
                        "ended_at" to msToIso(blockEnd),
                        "duration_ms" to blockDuration
                    ))
                }
                blockStart = sorted[i].timestamp
                blockEnd = sorted[i].timestamp
            }
        }

        // Check final block
        val lastDuration = blockEnd - blockStart
        if (lastDuration >= minBlockMs) {
            blocks.add(mapOf(
                "started_at" to msToIso(blockStart),
                "ended_at" to msToIso(blockEnd),
                "duration_ms" to lastDuration
            ))
        }

        return blocks
    }

    // -- Zero data helpers -------------------------------------------------

    private fun zeroTypingData(): Map<String, Any> = mapOf(
        "correction_key_count" to 0,
        "clipboard_copy_count" to 0,
        "clipboard_paste_count" to 0,
        "clipboard_activity_rate" to 0.0,
        "typing_session_count" to 0,
        "average_keystrokes_per_session" to 0,
        "average_typing_session_duration" to 0.0,
        "average_typing_speed" to 0.0,
        "average_typing_gap" to 0.0,
        "average_inter-tap_interval" to 0.0,
        "average_typing_cadence_stability" to 0.0,
        "average_burstiness_of_typing" to 0.0,
        "total_typing_duration" to 0.0,
        "active_typing_ratio" to 0.0,
        "deep_typing_blocks" to 0
    )

    private fun zeroBehaviorData(): Map<String, Any?> = mapOf(
        "micro_session" to false,
        "session_spacing" to 0,
        "motion_state" to mapOf(
            "state" to listOf("unknown"),
            "major_state" to "unknown",
            "major_state_pct" to 0.0,
            "ml_model" to "none",
            "confidence" to 0.0
        ),
        "device_context" to mapOf(
            "avg_screen_brightness" to 0.0,
            "start_orientation" to "portrait",
            "orientation_changes" to 0
        ),
        "activity_summary" to mapOf("total_events" to 0, "app_switch_count" to 0),
        "notification_summary" to mapOf(
            "notification_count" to 0,
            "notification_ignored" to 0,
            "notification_ignore_rate" to 0.0,
            "notification_clustering_index" to 0.0,
            "call_count" to 0,
            "call_ignored" to 0
        ),
        "system_state" to mapOf(
            "internet_state" to true,
            "do_not_disturb" to false,
            "charging" to false
        ),
        "typing_session_summary" to zeroTypingData(),
        "behavioral_metrics" to mapOf(
            "correction_rate" to 0.0,
            "editing_friction" to 0.0,
            "interaction_intensity" to 0.0,
            "task_switch_rate" to 0.0,
            "task_switch_cost" to 0.0,
            "idle_ratio" to 0.0,
            "active_interaction_time" to 0.0,
            "burstiness" to 0.0,
            "notification_load" to 0.0,
            "fragmented_idle_ratio" to 0.0,
            "scroll_jitter_rate" to 0.0,
            "distraction_score" to 0.0,
            "focus_hint" to 0.0,
            "deep_focus_blocks" to emptyList<Map<String, Any>>()
        )
    )
}
