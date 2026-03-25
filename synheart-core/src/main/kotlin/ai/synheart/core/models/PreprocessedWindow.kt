package ai.synheart.core.models

import org.json.JSONObject

/**
 * Pre-processed Window - INTERNAL ONLY
 *
 * Intermediate signal processing and feature extraction output from synheart-runtime.
 * Contains quality metrics, derived features, SRM baseline context, and embeddings.
 *
 * Used internally for:
 * - On-device model training (transfer learning, fine-tuning)
 * - Research & development (feature engineering, validation)
 * - Diagnostics & debugging (signal quality, pipeline inspection)
 * - Custom inference (alternative models, multi-modal fusion)
 */
data class PreprocessedWindow(
    val schemaVersion: String = "1.0.0",
    val windowStartMs: Long = 0,
    val windowEndMs: Long = 0,
    val sessionId: String = "",
    val quality: Quality = Quality(),
    val derivedFeatures: DerivedFeatures = DerivedFeatures(),
    val behaviorFeatures: BehaviorFeatures? = null,
    val srmContext: SrmContext = SrmContext(),
    val embeddings: Embeddings = Embeddings(),
) {
    companion object {
        fun fromJson(jsonStr: String): PreprocessedWindow {
            return try {
                val json = JSONObject(jsonStr)
                PreprocessedWindow(
                    schemaVersion = json.optString("schema_version", "1.0.0"),
                    windowStartMs = json.optLong("window_start_ms", 0),
                    windowEndMs = json.optLong("window_end_ms", 0),
                    sessionId = json.optString("session_id", ""),
                    quality = Quality.fromJson(json.optJSONObject("quality")),
                    derivedFeatures = DerivedFeatures.fromJson(json.optJSONObject("derived_features")),
                    behaviorFeatures = if (json.has("behavior_features") && !json.isNull("behavior_features")) {
                        BehaviorFeatures.fromJson(json.getJSONObject("behavior_features"))
                    } else null,
                    srmContext = SrmContext.fromJson(json.optJSONObject("srm_context")),
                    embeddings = Embeddings.fromJson(json.optJSONObject("embeddings")),
                )
            } catch (e: Exception) {
                PreprocessedWindow()
            }
        }
    }

    fun toJson(): String {
        return JSONObject().apply {
            put("schema_version", schemaVersion)
            put("window_start_ms", windowStartMs)
            put("window_end_ms", windowEndMs)
            put("session_id", sessionId)
            put("quality", JSONObject().apply {
                put("score", quality.score)
                put("coverage_pct", quality.coveragePct)
                put("dropout_count", quality.dropoutCount)
                put("rr_count", quality.rrCount)
                put("artifact_pct", quality.artifactPct)
            })
            // (can be extended for derived_features, behavior_features, etc.)
        }.toString()
    }
}

/**
 * Quality metrics for the window
 */
data class Quality(
    val score: Double = 0.0,
    val coveragePct: Double = 0.0,
    val dropoutCount: Int = 0,
    val rrCount: Int = 0,
    val artifactPct: Double = 0.0,
) {
    companion object {
        fun fromJson(json: JSONObject?): Quality {
            return if (json != null) {
                Quality(
                    score = json.optDouble("score", 0.0),
                    coveragePct = json.optDouble("coverage_pct", 0.0),
                    dropoutCount = json.optInt("dropout_count", 0),
                    rrCount = json.optInt("rr_count", 0),
                    artifactPct = json.optDouble("artifact_pct", 0.0),
                )
            } else {
                Quality()
            }
        }
    }
}

/**
 * HRV features derived from RR intervals
 */
data class HrvFeatures(
    val rmssdMs: Double = 0.0,
    val sdnnMs: Double = 0.0,
    val pnn50: Double = 0.0,
    val meanRrMs: Double = 0.0,
    val hrMeanBpm: Double = 0.0,
    val hrStdBpm: Double = 0.0,
    val rrCount: Int = 0,
) {
    companion object {
        fun fromJson(json: JSONObject?): HrvFeatures? {
            return if (json != null) {
                HrvFeatures(
                    rmssdMs = json.optDouble("rmssd_ms", 0.0),
                    sdnnMs = json.optDouble("sdnn_ms", 0.0),
                    pnn50 = json.optDouble("pnn50", 0.0),
                    meanRrMs = json.optDouble("mean_rr_ms", 0.0),
                    hrMeanBpm = json.optDouble("hr_mean_bpm", 0.0),
                    hrStdBpm = json.optDouble("hr_std_bpm", 0.0),
                    rrCount = json.optInt("rr_count", 0),
                )
            } else null
        }
    }
}

/**
 * Motion features from accelerometer
 */
data class MotionFeatures(
    val accelRms: Double = 0.0,
    val accelVar: Double = 0.0,
    val stepsEst: Int = 0,
    val postureProxy: Double = 0.0,
    val sampleCount: Int = 0,
) {
    companion object {
        fun fromJson(json: JSONObject?): MotionFeatures? {
            return if (json != null) {
                MotionFeatures(
                    accelRms = json.optDouble("accel_rms", 0.0),
                    accelVar = json.optDouble("accel_var", 0.0),
                    stepsEst = json.optInt("steps_est", 0),
                    postureProxy = json.optDouble("posture_proxy", 0.0),
                    sampleCount = json.optInt("sample_count", 0),
                )
            } else null
        }
    }
}

/**
 * Artifact filtering results
 */
data class ArtifactResult(
    val artifactPct: Double = 0.0,
    val ectopicLikePct: Double = 0.0,
    val originalCount: Int = 0,
) {
    companion object {
        fun fromJson(json: JSONObject?): ArtifactResult? {
            return if (json != null) {
                ArtifactResult(
                    artifactPct = json.optDouble("artifact_pct", 0.0),
                    ectopicLikePct = json.optDouble("ectopic_like_pct", 0.0),
                    originalCount = json.optInt("original_count", 0),
                )
            } else null
        }
    }
}

/**
 * Derived features (HRV, motion, artifact)
 */
data class DerivedFeatures(
    val hrv: HrvFeatures? = null,
    val motion: MotionFeatures? = null,
    val artifact: ArtifactResult? = null,
) {
    companion object {
        fun fromJson(json: JSONObject?): DerivedFeatures {
            return if (json != null) {
                DerivedFeatures(
                    hrv = HrvFeatures.fromJson(json.optJSONObject("hrv")),
                    motion = MotionFeatures.fromJson(json.optJSONObject("motion")),
                    artifact = ArtifactResult.fromJson(json.optJSONObject("artifact")),
                )
            } else {
                DerivedFeatures()
            }
        }
    }
}

/**
 * Behavior features from phone interaction
 */
data class BehaviorFeatures(
    val screenOnPct: Double = 0.0,
    val touchRatePerMin: Double = 0.0,
    val appSwitchesPerMin: Double = 0.0,
    val notificationInterruptions: Int = 0,
) {
    companion object {
        fun fromJson(json: JSONObject?): BehaviorFeatures? {
            return if (json != null) {
                BehaviorFeatures(
                    screenOnPct = json.optDouble("screen_on_pct", 0.0),
                    touchRatePerMin = json.optDouble("touch_rate_per_min", 0.0),
                    appSwitchesPerMin = json.optDouble("app_switches_per_min", 0.0),
                    notificationInterruptions = json.optInt("notification_interruptions", 0),
                )
            } else null
        }
    }
}

/**
 * SRM baseline deviation context
 */
data class SrmDeviation(
    val observed: Double = 0.0,
    val mu: Double = 0.0,
    val sigma: Double = 0.0,
    val zScore: Double = 0.0,
    val status: String = "Empty", // "Ready", "Warming", "Empty"
) {
    companion object {
        fun fromJson(json: JSONObject?): SrmDeviation? {
            return if (json != null) {
                SrmDeviation(
                    observed = json.optDouble("observed", 0.0),
                    mu = json.optDouble("mu", 0.0),
                    sigma = json.optDouble("sigma", 0.0),
                    zScore = json.optDouble("z_score", 0.0),
                    status = json.optString("status", "Empty"),
                )
            } else null
        }
    }
}

/**
 * SRM baseline context with deviations
 */
data class SrmContext(
    val readyCount: Int = 0,
    val totalCount: Int = 0,
    val deviations: Map<String, SrmDeviation> = emptyMap(),
) {
    companion object {
        fun fromJson(json: JSONObject?): SrmContext {
            return if (json != null) {
                val deviationsMap = mutableMapOf<String, SrmDeviation>()
                val deviationsJson = json.optJSONObject("deviations")
                if (deviationsJson != null) {
                    deviationsJson.keys().forEach { key ->
                        val deviation = SrmDeviation.fromJson(deviationsJson.optJSONObject(key))
                        if (deviation != null) {
                            deviationsMap[key] = deviation
                        }
                    }
                }
                SrmContext(
                    readyCount = json.optInt("ready_count", 0),
                    totalCount = json.optInt("total_count", 0),
                    deviations = deviationsMap,
                )
            } else {
                SrmContext()
            }
        }
    }
}

/**
 * Signal embedding
 */
data class SignalEmbedding(
    val vector: List<Double> = emptyList(),
    val dimension: Int = 0,
    val space: String = "",
) {
    companion object {
        fun fromJson(json: JSONObject?): SignalEmbedding? {
            return if (json != null) {
                val vectorList = mutableListOf<Double>()
                val array = json.optJSONArray("vector")
                if (array != null) {
                    for (i in 0 until array.length()) {
                        vectorList.add(array.getDouble(i))
                    }
                }
                SignalEmbedding(
                    vector = vectorList,
                    dimension = json.optInt("dimension", 0),
                    space = json.optString("space", ""),
                )
            } else null
        }
    }
}

/**
 * Embeddings (signal, behavior, combined)
 */
data class Embeddings(
    val signalEmbedding: SignalEmbedding = SignalEmbedding(),
) {
    companion object {
        fun fromJson(json: JSONObject?): Embeddings {
            return if (json != null) {
                Embeddings(
                    signalEmbedding = SignalEmbedding.fromJson(json.optJSONObject("signal_embedding")) ?: SignalEmbedding(),
                )
            } else {
                Embeddings()
            }
        }
    }
}
