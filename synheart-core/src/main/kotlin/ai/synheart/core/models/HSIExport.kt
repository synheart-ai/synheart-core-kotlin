package ai.synheart.core.models

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * HSI Snapshot — versioned JSON snapshot produced by synheart-engine.
 *
 * This is the ONLY serializable, transport-safe, cloud-ingestable representation
 * of human state. HSI generation is handled exclusively by synheart-engine
 * the Core SDK receives the finished
 * JSON string via [CoreRuntimeBridge] HSI callback and wraps it here for type safety.
 */
data class HSISnapshot(
    val payload: JsonObject
) {
    val hsiVersion: String get() = payload["hsi_version"]?.jsonPrimitive?.content ?: "1.1"
    val observedAtUtc: String? get() = payload["observed_at_utc"]?.jsonPrimitive?.content
    val computedAtUtc: String? get() = payload["computed_at_utc"]?.jsonPrimitive?.content
}
