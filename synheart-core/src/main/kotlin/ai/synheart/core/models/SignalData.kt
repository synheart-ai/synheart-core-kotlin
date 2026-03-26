package ai.synheart.core.models

import kotlinx.serialization.Serializable

/**
 * Raw signal data collected from various sources
 */
@Serializable
data class SignalData(
    val timestamp: Long,
    val source: SignalSource,
    val type: SignalType,
    val values: Map<String, Float>,
    val metadata: Map<String, String> = emptyMap()
)

enum class SignalSource {
    WEAR_SDK,
    PHONE_SDK,
    CONTEXT_ADAPTER,
    SENSOR_API
}

enum class SignalType {
    HEART_RATE,
    HRV,
    MOTION,
    SLEEP,
    TYPING,
    SCROLLING,
    APP_SWITCH,
    CONVERSATION,
    DEVICE_STATE,
    USER_PATTERN
}

