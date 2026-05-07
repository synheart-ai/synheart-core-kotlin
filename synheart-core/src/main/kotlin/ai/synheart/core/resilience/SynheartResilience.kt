// SPDX-License-Identifier: Apache-2.0
//
// HRV-CV resilience score — Kotlin binding (Loot #4).
//
// Mirror of the Flutter and Swift wrappers. Stateless; takes
// samples + sleep windows + config and returns a score with
// provenance.
//
// When the loaded native library doesn't expose the FFI symbol
// (older runtime, headless host JVM with no native lib), `compute()`
// throws [ResilienceError.RuntimeUnavailable].

package ai.synheart.core.resilience

import ai.synheart.core.bridge.CoreRuntimeNative
import org.json.JSONArray
import org.json.JSONObject

class SynheartResilience(
    private val native: CoreRuntimeNative? = CoreRuntimeNative.INSTANCE,
) {
    /** Whether the loaded runtime exposes the resilience symbol. */
    val isAvailable: Boolean
        get() = native != null

    /**
     * Compute a resilience score over the given samples + sleep
     * windows. Throws [ResilienceError.RuntimeUnavailable] when the
     * native library is missing; throws [ResilienceError.ComputeFailed]
     * on NULL or unparseable runtime response.
     */
    fun compute(
        samples: List<HrvSample>,
        sleepWindows: List<SleepWindow>,
        config: ResilienceConfig = ResilienceConfig(),
    ): ResilienceResult {
        val n = native ?: throw ResilienceError.RuntimeUnavailable

        val samplesJson = JSONArray(samples.map { JSONObject(it.toJsonObject() as Map<*, *>) }).toString()
        val windowsJson = JSONArray(sleepWindows.map { JSONObject(it.toJsonObject() as Map<*, *>) }).toString()
        val configJson = JSONObject(config.toJsonObject() as Map<*, *>).toString()

        val ptr = n.synheart_core_resilience_compute_v1(
            samplesJson, windowsJson, configJson
        ) ?: throw ResilienceError.ComputeFailed(
            "runtime returned NULL — likely malformed input"
        )

        val raw = try {
            ptr.getString(0, "UTF-8")
        } finally {
            n.synheart_core_free_string(ptr)
        }

        val obj = try {
            JSONObject(raw)
        } catch (e: Exception) {
            throw ResilienceError.ComputeFailed("could not parse runtime response: $raw")
        }
        return parse(obj)
    }

    private fun parse(obj: JSONObject): ResilienceResult {
        val score = if (obj.isNull("score")) null else obj.getInt("score")
        val rmssd = if (obj.isNull("rmssd_ow_ms")) null else obj.getDouble("rmssd_ow_ms")
        val sdnn = if (obj.isNull("sdnn_ow_ms")) null else obj.getDouble("sdnn_ow_ms")
        val cv = if (obj.isNull("hrv_cv_pct")) null else obj.getDouble("hrv_cv_pct")
        val daysUsed = obj.optInt("days_used", 0)
        val samplesUsed = obj.optInt("samples_used", 0)
        val confidence = obj.optDouble("confidence", 0.0)
        val reasonRaw = if (obj.isNull("reason")) null else obj.optString("reason", null)
        val reason = ResilienceReason.fromWire(reasonRaw)
        val modelId = obj.optString("model_id", "")
        val pipelineVersion = obj.optString("pipeline_version", "")
        val constantsHash = obj.optString("constants_hash", "")
        return ResilienceResult(
            score = score,
            rmssdOwMs = rmssd,
            sdnnOwMs = sdnn,
            hrvCvPct = cv,
            daysUsed = daysUsed,
            samplesUsed = samplesUsed,
            confidence = confidence,
            reason = reason,
            modelId = modelId,
            pipelineVersion = pipelineVersion,
            constantsHash = constantsHash,
        )
    }
}
