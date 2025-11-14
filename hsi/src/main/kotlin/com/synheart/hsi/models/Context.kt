package com.synheart.hsi.models

import kotlinx.serialization.Serializable

/**
 * Context information for human state inference
 */
@Serializable
data class ContextState(
    val conversation: ConversationContext? = null,
    val device: DeviceStateContext? = null,
    val userPatterns: UserPatternsContext? = null
)

@Serializable
data class ConversationContext(
    val isActive: Boolean,
    val lastActivityTime: Long? = null,
    val messageCount: Int = 0,
    val averageResponseTime: Float? = null
)

@Serializable
data class DeviceStateContext(
    val batteryLevel: Int,
    val isCharging: Boolean,
    val screenOn: Boolean,
    val networkType: String? = null,
    val timeOfDay: Long // Unix timestamp
)

@Serializable
data class UserPatternsContext(
    val typicalActivityHours: List<Int> = emptyList(),
    val averageDailyUsage: Float? = null,
    val preferredApps: List<String> = emptyList()
)

