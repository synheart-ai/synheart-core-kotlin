package com.synheart.core.models

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * HSI 1.0 Export Extension
 *
 * Converts internal HSV (Human State Vector) to HSI 1.0 canonical format.
 *
 * HSI 1.0 is the language-agnostic JSON wire format for interoperability.
 */

/**
 * Convert HSV to HSI 1.0 format
 *
 * @param producerName Name of the producer (e.g., "Synheart Core SDK")
 * @param producerVersion Version of the producer (e.g., "1.0.0")
 * @param instanceId Instance/device identifier
 * @return Map representing HSI 1.0 payload
 */
fun HumanStateVector.toHSI10(
    producerName: String,
    producerVersion: String,
    instanceId: String
): Map<String, JsonElement> {
    return buildJsonObject {
        // HSI 1.0 header
        put("hsi_version", "1.0")
        put("timestamp", timestamp)
        put("window_type", "micro") // Default to micro, can be customized

        // Producer info
        putJsonObject("producer") {
            put("name", producerName)
            put("version", producerVersion)
            put("instance_id", instanceId)
        }

        // Subject info
        putJsonObject("subject") {
            put("subject_type", "pseudonymous_user")
            put("subject_id", "anon_${instanceId.take(8)}")
        }

        // Privacy guarantees
        putJsonObject("privacy") {
            put("contains_pii", false)
            put("derived_only", true)
            put("aggregation_window_sec", 30)
        }

        // Device info
        putJsonObject("device") {
            put("platform", meta.device.platform)
            put("os_version", meta.device.osVersion)
            meta.device.model?.let { put("model", it) }
            meta.device.manufacturer?.let { put("manufacturer", it) }
        }

        // State data (if available)
        putJsonObject("state") {
            // Biosignals
            heartRate?.let { put("heart_rate_bpm", it) }
            hrv?.let { put("hrv_rmssd_ms", it) }
            hrvSdnn?.let { put("hrv_sdnn_ms", it) }

            // Behavior metrics
            behavior?.let { behavior ->
                putJsonObject("behavior") {
                    behavior.typingSpeed?.let { put("typing_speed_cps", it) }
                    behavior.typingBurstiness?.let { put("typing_burstiness", it) }
                    behavior.scrollingVelocity?.let { put("scrolling_velocity_pps", it) }
                    behavior.appSwitchFrequency?.let { put("app_switch_frequency", it) }
                    behavior.interactionIntensity?.let { put("interaction_intensity", it) }
                    behavior.engagementLevel?.let { put("engagement_level", it) }
                }
            }

            // Context
            context?.let { ctx ->
                putJsonObject("context") {
                    ctx.conversation?.let { conv ->
                        putJsonObject("conversation") {
                            put("is_active", conv.isActive)
                            conv.lastActivityTime?.let { put("last_activity_time", it) }
                            put("message_count", conv.messageCount)
                            conv.averageResponseTime?.let { put("avg_response_time", it) }
                        }
                    }

                    ctx.device?.let { device ->
                        putJsonObject("device_state") {
                            put("battery_level", device.batteryLevel)
                            put("is_charging", device.isCharging)
                            put("screen_on", device.screenOn)
                            device.networkType?.let { put("network_type", it) }
                            put("time_of_day", device.timeOfDay)
                        }
                    }

                    ctx.userPatterns?.let { patterns ->
                        putJsonObject("user_patterns") {
                            putJsonArray("typical_activity_hours") {
                                patterns.typicalActivityHours.forEach { add(JsonPrimitive(it)) }
                            }
                            patterns.averageDailyUsage?.let { put("avg_daily_usage", it) }
                            putJsonArray("preferred_apps") {
                                patterns.preferredApps.forEach { add(JsonPrimitive(it)) }
                            }
                        }
                    }
                }
            }

            // Emotion state (if available)
            emotion?.let { emo ->
                putJsonObject("emotion") {
                    emo.valence?.let { put("valence", it) }
                    emo.arousal?.let { put("arousal", it) }
                    emo.stress?.let { put("stress", it) }
                    emo.engagement?.let { put("engagement", it) }
                }
            }

            // Focus state (if available)
            focus?.let { foc ->
                putJsonObject("focus") {
                    foc.score?.let { put("score", it) }
                    foc.depth?.let { put("depth", it) }
                    foc.stability?.let { put("stability", it) }
                }
            }

            // Embedding (if available)
            if (hsiEmbedding.isNotEmpty()) {
                putJsonArray("embedding") {
                    hsiEmbedding.forEach { add(JsonPrimitive(it)) }
                }
            }
        }

        // Metadata
        putJsonObject("meta") {
            put("session_id", meta.sessionId)
            put("version", meta.version)
        }

    }.toMap()
}

/**
 * Convert JsonObject to Map<String, JsonElement>
 */
private fun JsonObject.toMap(): Map<String, JsonElement> {
    return this.toMap()
}
