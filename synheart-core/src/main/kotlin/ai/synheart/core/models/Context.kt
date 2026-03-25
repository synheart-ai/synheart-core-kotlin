package ai.synheart.core.models

import kotlinx.serialization.Serializable

/**
 * Context information for human state inference
 */
@Serializable
data class ContextState(
    /** Conversation timing metrics */
    val conversation: ConversationContext? = null,
    /** Device state information */
    val device: DeviceStateContext? = null,
    /** User pattern information */
    val userPatterns: UserPatternsContext? = null
)

@Serializable
data class ConversationContext(
    /** Whether a conversation is currently active */
    val isActive: Boolean = false,
    /** Average reply delay in seconds */
    val avgReplyDelaySec: Double? = null,
    /** Number of messages in conversation */
    val messageCount: Int? = null,
    /** Burstiness of conversation (0.0-1.0) */
    val burstiness: Double? = null
)

@Serializable
data class DeviceStateContext(
    /** Whether screen is on */
    val screenOn: Boolean = true,
    /** Whether device is charging */
    val isCharging: Boolean = false,
    /** Battery level (0.0-1.0) */
    val batteryLevel: Float? = null,
    /** Network type (e.g., "wifi", "cellular") */
    val networkType: String? = null,
    /** Focus mode (e.g., "work", "personal", "none") */
    val focusMode: String? = null
)

@Serializable
data class UserPatternsContext(
    /** Time of day in seconds since midnight */
    val timeOfDay: Double = 0.0,
    /** Day of week (0-6, 0=Sunday) */
    val dayOfWeek: Int = 0,
    /** Average session length in minutes */
    val avgSessionMinutes: Double? = null,
    /** Activity pattern (e.g., "work", "exercise", "rest") */
    val activityPattern: String? = null
)
