package com.synheart.core.models

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.sqrt

/**
 * HSI 1.0 Export Extension
 *
 * Converts internal HSV (Human State Vector) to **HSI 1.0 canonical format**,
 * matching the schema in `synheart/hsi/schema/hsi-1.0.schema.json`.
 *
 * HSI 1.0 is the language-agnostic JSON wire format for interoperability.
 */

/**
 * Convert HSV to HSI 1.0 format
 *
 * @param producerName Name of the producer (e.g., "Synheart Core SDK")
 * @param producerVersion Version of the producer (e.g., "1.0.0")
 * @param instanceId Producer instance identifier (UUID string)
 * @param windowLabel Canonical label for the time window (e.g., "micro", "short")
 * @param windowDurationSeconds Duration of the window in seconds
 * @param computedAtUtc Processing-time for this payload (defaults to now)
 * @param access Optional access context used to emit explicit null-with-reason readings
 * @return Schema-valid HSI 1.0 payload as a JsonObject
 */
fun HumanStateVector.toHSI10(
    producerName: String,
    producerVersion: String,
    instanceId: String,
    windowLabel: String = "micro",
    windowDurationSeconds: Long = 30,
    computedAtUtc: Instant = Instant.now(),
    access: HSIExportAccessContext? = null
): JsonObject {
    val observedAtUtc = Instant.ofEpochMilli(timestamp)
    val windowEnd = observedAtUtc
    val windowStart = observedAtUtc.minusSeconds(windowDurationSeconds)

    val windowId = "w1"
    val iso = DateTimeFormatter.ISO_INSTANT.withZone(ZoneOffset.UTC)

    val embeddingVectors = exportableEmbedding(access)

    return buildJsonObject {
        // Required top-level fields
        put("hsi_version", "1.0")
        put("observed_at_utc", iso.format(observedAtUtc))
        put("computed_at_utc", iso.format(computedAtUtc))

        putJsonObject("producer") {
            put("name", producerName)
            put("version", producerVersion)
            put("instance_id", instanceId)
        }

        putJsonArray("window_ids") {
            add(JsonPrimitive(windowId))
        }

        putJsonObject("windows") {
            putJsonObject(windowId) {
                put("start", iso.format(windowStart))
                put("end", iso.format(windowEnd))
                put("label", windowLabel)
            }
        }

        // Optional axes (HSI schema: affect/engagement/behavior domains only)
        val axes = this@toHSI10.buildAxes(windowId = windowId, access = access)
        if (axes.isNotEmpty()) {
            putJsonObject("axes") {
                axes["affect"]?.let { domain ->
                    putJsonObject("affect") {
                        putJsonArray("readings") { domain.forEach { add(it) } }
                    }
                }
                axes["engagement"]?.let { domain ->
                    putJsonObject("engagement") {
                        putJsonArray("readings") { domain.forEach { add(it) } }
                    }
                }
                axes["behavior"]?.let { domain ->
                    putJsonObject("behavior") {
                        putJsonArray("readings") { domain.forEach { add(it) } }
                    }
                }
            }
        }

        // Optional embeddings
        if (embeddingVectors != null) {
            val vectorHash = sha256Hex(embeddingVectors.joinToString(","))
            putJsonArray("embeddings") {
                add(
                    buildJsonObject {
                        put("window_id", windowId)
                        put("dimension", embeddingVectors.size)
                        put("encoding", "float32")
                        put("confidence", 0.85)
                        putJsonArray("vector") {
                            embeddingVectors.forEach { add(JsonPrimitive(it)) }
                        }
                        put("vector_hash", "sha256:$vectorHash")
                        put("model", "hsi-fusion-v1")
                    }
                )
            }
        }

        // Required privacy block
        putJsonObject("privacy") {
            put("contains_pii", false)
            put("raw_biosignals_allowed", false)
            put("derived_metrics_allowed", true)
            put("embedding_allowed", embeddingVectors != null)
            put("consent", "explicit")
        }

        // Optional meta (HSI schema allows only primitive values)
        putJsonObject("meta") {
            put("sdk_version", meta.version)
            put("session_id", meta.sessionId)
            put("platform", meta.device.platform)
            put("os_version", meta.device.osVersion)

            access?.let {
                put("capability_hsi", it.capabilityHsi.lowercase())
                put("capability_cloud", it.capabilityCloud.lowercase())
                put("consent_biosignals", it.consentBiosignals)
                put("consent_phone_context", it.consentPhoneContext)
                put("consent_behavior", it.consentBehavior)
                put("consent_emotion", it.consentEmotionEstimation)
                put("consent_focus", it.consentFocusEstimation)
                put("consent_cloud_upload", it.consentCloudUpload)
            }
        }
    }
}

/**
 * Minimal access context used to produce explicit null-with-reason readings.
 *
 * NOTE: HSI 1.0 schema restricts `meta` to primitive values only, so this is
 * flattened into primitive meta fields and `notes` strings on individual readings.
 */
data class HSIExportAccessContext(
    val capabilityHsi: String,
    val capabilityCloud: String,
    val consentBiosignals: Boolean,
    val consentPhoneContext: Boolean,
    val consentBehavior: Boolean,
    val consentCloudUpload: Boolean,
    val consentEmotionEstimation: Boolean,
    val consentFocusEstimation: Boolean
)

private fun HumanStateVector.exportableEmbedding(access: HSIExportAccessContext?): List<Float>? {
    if (hsiEmbedding.isEmpty()) return null

    // Best-effort export if no access context is provided.
    if (access == null) return l2Normalize(hsiEmbedding)

    // Access-control gating: Capability(app, hsi, compute/export) AND Consent(user, any upstream).
    if (access.capabilityHsi.equals("none", ignoreCase = true)) return null
    if (!(access.consentBiosignals || access.consentPhoneContext || access.consentBehavior)) return null

    // Core tier requires normalized embeddings.
    return if (access.capabilityHsi.equals("core", ignoreCase = true)) {
        l2Normalize(hsiEmbedding)
    } else {
        hsiEmbedding
    }
}

private fun HumanStateVector.buildAxes(
    windowId: String,
    access: HSIExportAccessContext?
): Map<String, List<JsonObject>> {
    val affectAxes = mutableListOf<JsonObject>()
    val engagementAxes = mutableListOf<JsonObject>()
    val behaviorAxes = mutableListOf<JsonObject>()

    val hsiDenied = access?.capabilityHsi?.equals("none", ignoreCase = true) == true

    // Emotion-derived axes (require interpretation consent + biosignals consent)
    val emotionAllowed = access == null || (
        !hsiDenied && access.consentEmotionEstimation && access.consentBiosignals
        )

    affectAxes.add(
        axisReading(
            axis = "stress",
            score = if (emotionAllowed) this.emotion?.stress?.toDouble() else null,
            windowId = windowId,
            direction = "higher_is_more",
            notes = if (emotionAllowed) null else denialNotes(reason = if (hsiDenied) "capability_insufficient" else "consent_denied", dependsOn = "emotionEstimation+biosignals")
        )
    )
    affectAxes.add(
        axisReading(
            axis = "calm",
            score = if (emotionAllowed) this.emotion?.calm?.toDouble() else null,
            windowId = windowId,
            direction = "higher_is_more",
            notes = if (emotionAllowed) null else denialNotes(reason = if (hsiDenied) "capability_insufficient" else "consent_denied", dependsOn = "emotionEstimation+biosignals")
        )
    )
    affectAxes.add(
        axisReading(
            axis = "arousal",
            score = if (emotionAllowed) this.emotion?.activation?.toDouble() else null,
            windowId = windowId,
            direction = "higher_is_more",
            notes = if (emotionAllowed) null else denialNotes(reason = if (hsiDenied) "capability_insufficient" else "consent_denied", dependsOn = "emotionEstimation+biosignals")
        )
    )
    affectAxes.add(
        axisReading(
            axis = "valence",
            score = if (emotionAllowed) this.emotion?.valence?.let { ((it + 1f) / 2f).toDouble().coerceIn(0.0, 1.0) } else null,
            windowId = windowId,
            direction = "bidirectional",
            notes = if (emotionAllowed) null else denialNotes(reason = if (hsiDenied) "capability_insufficient" else "consent_denied", dependsOn = "emotionEstimation+biosignals")
        )
    )

    // Focus-derived axes (require interpretation consent + behavior consent)
    val focusAllowed = access == null || (
        !hsiDenied && access.consentFocusEstimation && access.consentBehavior
        )

    engagementAxes.add(
        axisReading(
            axis = "focus_score",
            score = if (focusAllowed) this.focus?.score?.toDouble() else null,
            windowId = windowId,
            notes = if (focusAllowed) null else denialNotes(reason = if (hsiDenied) "capability_insufficient" else "consent_denied", dependsOn = "focusEstimation+behavior")
        )
    )
    engagementAxes.add(
        axisReading(
            axis = "cognitive_load",
            score = if (focusAllowed) this.focus?.cognitiveLoad?.toDouble() else null,
            windowId = windowId,
            notes = if (focusAllowed) null else denialNotes(reason = if (hsiDenied) "capability_insufficient" else "consent_denied", dependsOn = "focusEstimation+behavior")
        )
    )
    engagementAxes.add(
        axisReading(
            axis = "clarity",
            score = if (focusAllowed) this.focus?.clarity?.toDouble() else null,
            windowId = windowId,
            notes = if (focusAllowed) null else denialNotes(reason = if (hsiDenied) "capability_insufficient" else "consent_denied", dependsOn = "focusEstimation+behavior")
        )
    )
    engagementAxes.add(
        axisReading(
            axis = "distraction",
            score = if (focusAllowed) this.focus?.distraction?.toDouble() else null,
            windowId = windowId,
            notes = if (focusAllowed) null else denialNotes(reason = if (hsiDenied) "capability_insufficient" else "consent_denied", dependsOn = "focusEstimation+behavior")
        )
    )

    // Behavior axes (require behavior consent)
    val behaviorAllowed = access == null || (!hsiDenied && access.consentBehavior)

    behaviorAxes.add(
        axisReading(
            axis = "interaction_intensity",
            score = if (behaviorAllowed) this.behavior?.interactionIntensity?.toDouble() else null,
            windowId = windowId,
            notes = if (behaviorAllowed) null else denialNotes(reason = if (hsiDenied) "capability_insufficient" else "consent_denied", dependsOn = "behavior")
        )
    )
    engagementAxes.add(
        axisReading(
            axis = "engagement_level",
            score = if (behaviorAllowed) this.behavior?.engagementLevel?.toDouble() else null,
            windowId = windowId,
            notes = if (behaviorAllowed) null else denialNotes(reason = if (hsiDenied) "capability_insufficient" else "consent_denied", dependsOn = "behavior")
        )
    )

    return buildMap {
        // Keep domains even if scores are null (explicit null readings are allowed/desired).
        if (affectAxes.isNotEmpty()) put("affect", affectAxes)
        if (engagementAxes.isNotEmpty()) put("engagement", engagementAxes)
        if (behaviorAxes.isNotEmpty()) put("behavior", behaviorAxes)
    }
}

private fun axisReading(
    axis: String,
    score: Double?,
    windowId: String,
    confidence: Double = 0.8,
    direction: String = "higher_is_more",
    notes: String? = null
): JsonObject {
    return buildJsonObject {
        put("axis", axis)
        if (score == null) {
            put("score", JsonPrimitive(null as String?))
        } else {
            put("score", score.coerceIn(0.0, 1.0))
        }
        put("confidence", confidence.coerceIn(0.0, 1.0))
        put("window_id", windowId)
        put("direction", direction)
        notes?.let { put("notes", it) }
    }
}

private fun denialNotes(reason: String, dependsOn: String): String {
    // Keep machine-readable payload even though `notes` is a string in the schema.
    return """{"reason":"$reason","depends_on":"$dependsOn"}"""
}

private fun l2Normalize(values: List<Float>): List<Float> {
    val sumSq = values.fold(0.0) { acc, v -> acc + (v.toDouble() * v.toDouble()) }
    if (sumSq <= 0.0) return values
    val norm = sqrt(sumSq)
    return values.map { (it / norm.toFloat()) }
}

private fun sha256Hex(input: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }
}
