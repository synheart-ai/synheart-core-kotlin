package com.synheart.core.artifacts

import com.synheart.core.config.SynheartMode
import com.synheart.core.crypto.ArtifactCrypto
import com.synheart.core.crypto.SMK
import com.synheart.core.storage.ArtifactRecord
import com.synheart.core.storage.StorageManager
import com.synheart.core.storage.StoragePolicy
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import org.json.JSONObject

/**
 * Bridges runtime HSI output to the artifact model and storage layer.
 *
 * Accumulates HSI frames into 30-second windows, encrypts, and persists.
 * Produces SessionSummary on session close.
 */
class ArtifactPipeline(
    private val storage: StorageManager,
    private val policy: StoragePolicy,
    private val smk: SMK,
    private val subjectId: String,
    private val appId: String,
    private val appVersion: String,
    private val deviceId: String,
    private val platform: String
) {
    companion object {
        private const val WINDOW_SIZE_MS = 30_000L
        private val json = Json { ignoreUnknownKeys = true }
    }

    private var currentSessionId: String? = null
    private var currentMode: SynheartMode? = null
    private var windowStartMs: Long? = null
    private var windowEndMs: Long? = null
    private val windowHsiFrames = mutableListOf<JsonObject>()
    private var windowSeq = 0

    fun onSessionStart(sessionId: String, mode: SynheartMode) {
        currentSessionId = sessionId
        currentMode = mode
        windowStartMs = null
        windowEndMs = null
        windowHsiFrames.clear()
        windowSeq = 0
    }

    /** Ingest an HSI JSON frame from the runtime tick. Returns an artifact if a window was finalized. */
    fun ingestHsiFrame(hsiJson: String, timestampMs: Long): HSIWindowArtifact? {
        if (currentSessionId == null) return null
        if (!policy.canPersistArtifact("hsi_window")) return null

        val hsiObj = try {
            Json.parseToJsonElement(hsiJson).jsonObject
        } catch (_: Exception) { return null }

        windowHsiFrames.add(hsiObj)
        if (windowStartMs == null) windowStartMs = timestampMs
        windowEndMs = timestampMs

        val elapsed = (windowEndMs ?: 0) - (windowStartMs ?: 0)
        if (elapsed >= WINDOW_SIZE_MS) {
            return finalizeWindow()
        }
        return null
    }

    /** Flush any partial window (called on session stop). */
    fun flushPendingWindow(): HSIWindowArtifact? {
        if (windowHsiFrames.isEmpty()) return null
        return finalizeWindow()
    }

    private fun finalizeWindow(): HSIWindowArtifact? {
        if (windowHsiFrames.isEmpty()) return null
        val sessionId = currentSessionId ?: return null

        val startMs = windowStartMs ?: 0
        val endMs = windowEndMs ?: 0
        val mergedHsi = windowHsiFrames.last()

        val artifact = HSIWindowArtifact.create(
            subjectId = subjectId,
            sessionId = sessionId,
            startMs = startMs,
            endMs = endMs,
            hsi = mergedHsi,
            source = platform,
            deviceId = deviceId,
            appId = appId,
            runtimeVersion = "1.0.0"
        )

        val artifactJsonStr = json.encodeToString(artifact)
        val encrypted = ArtifactCrypto.encrypt(smk, artifactJsonStr.toByteArray(Charsets.UTF_8))

        storage.insertArtifact(ArtifactRecord(
            artifactId = artifact.header.artifactId,
            sessionId = sessionId,
            subjectId = subjectId,
            type = "hsi_window",
            schemaName = artifact.header.schema.name,
            schemaVersion = artifact.header.schema.version,
            startMs = startMs,
            endMs = endMs,
            seq = windowSeq,
            createdAtMs = artifact.header.createdAtMs,
            encAlg = encrypted.encAlg,
            payload = encrypted.ciphertext,
            payloadSha256 = encrypted.sha256
        ))

        windowSeq++
        windowHsiFrames.clear()
        windowStartMs = null
        windowEndMs = null

        return artifact
    }

    /** Compute and persist a SessionSummary artifact. */
    fun finalizeSession(sessionStartMs: Long, sessionEndMs: Long): SessionSummaryArtifact? {
        val sessionId = currentSessionId ?: return null
        val mode = currentMode ?: return null
        if (!policy.canPersistArtifact("session_summary")) return null

        flushPendingWindow()

        val windowRecords = storage.getArtifactsBySession(sessionId, "hsi_window")

        val hsiPayloads = windowRecords.mapNotNull { record ->
            try {
                val plaintext = ArtifactCrypto.decrypt(smk, record.payload)
                JSONObject(String(plaintext, Charsets.UTF_8))
            } catch (_: Exception) { null }
        }

        val aggregates = computeAggregates(hsiPayloads)
        val includeMetrics = policy.canIncludeMetrics()

        val artifact = SessionSummaryArtifact.create(
            subjectId = subjectId,
            sessionId = sessionId,
            startMs = sessionStartMs,
            endMs = sessionEndMs,
            mode = mode.value,
            totalWindows = windowRecords.size,
            aggregates = aggregates,
            insightMetrics = if (includeMetrics) InsightMetrics() else null
        )

        val artifactJsonStr = json.encodeToString(artifact)
        val encrypted = ArtifactCrypto.encrypt(smk, artifactJsonStr.toByteArray(Charsets.UTF_8))

        storage.insertArtifact(ArtifactRecord(
            artifactId = artifact.header.artifactId,
            sessionId = sessionId,
            subjectId = subjectId,
            type = "session_summary",
            schemaName = artifact.header.schema.name,
            schemaVersion = artifact.header.schema.version,
            startMs = sessionStartMs,
            endMs = sessionEndMs,
            createdAtMs = artifact.header.createdAtMs,
            encAlg = encrypted.encAlg,
            payload = encrypted.ciphertext,
            payloadSha256 = encrypted.sha256
        ))

        storage.insertSummaryCache(
            sessionId = sessionId,
            artifactId = artifact.header.artifactId,
            summaryJson = artifactJsonStr
        )

        storage.updateSession(sessionId, state = "closed", endUtc = sessionEndMs / 1000)

        currentSessionId = null
        currentMode = null

        return artifact
    }

    /** Produce a BaselineSnapshot from native runtime SRM export. */
    fun produceBaselineSnapshot(srmSnapshotJson: String): BaselineSnapshotArtifact? {
        if (!policy.canPersistArtifact("baseline_snapshot")) return null

        val srmData = try {
            JSONObject(srmSnapshotJson)
        } catch (_: Exception) { return null }

        val axes = extractAxesFromSrm(srmData) ?: return null
        val nowMs = System.currentTimeMillis()
        val coverageStartMs = srmData.optLong("created_at_ms", nowMs - 7 * 24 * 3600 * 1000)
        val totalWindows = countSrmWindows(srmData)

        val baseline = BaselineData(
            coverage = BaselineCoverage(startMs = coverageStartMs, endMs = nowMs, totalWindows = totalWindows),
            axes = axes,
            model = BaselineModelRef(
                modelId = srmData.optString("srm_version", "srm_v1"),
                modelVersion = "1.0.0"
            )
        )

        val artifact = BaselineSnapshotArtifact.create(subjectId = subjectId, baseline = baseline)
        val artifactJsonStr = json.encodeToString(artifact)
        val encrypted = ArtifactCrypto.encrypt(smk, artifactJsonStr.toByteArray(Charsets.UTF_8))

        storage.insertArtifact(ArtifactRecord(
            artifactId = artifact.header.artifactId,
            subjectId = subjectId,
            type = "baseline_snapshot",
            schemaName = artifact.header.schema.name,
            schemaVersion = artifact.header.schema.version,
            startMs = coverageStartMs,
            endMs = nowMs,
            createdAtMs = artifact.header.createdAtMs,
            encAlg = encrypted.encAlg,
            payload = encrypted.ciphertext,
            payloadSha256 = encrypted.sha256
        ))

        return artifact
    }

    // MARK: - Private helpers

    private fun extractAxesFromSrm(srmData: JSONObject): BaselineAxes? {
        val strata = srmData.optJSONObject("strata") ?: return null
        if (strata.length() == 0) return null

        val restStratum = strata.optJSONObject("rest")
            ?: strata.optJSONObject(strata.keys().next())
            ?: return null

        val ref = restStratum.optJSONObject("reference") ?: JSONObject()
        val count = restStratum.optInt("count", 0)
        val confidence = if (count >= 50) 1.0 else count.toDouble() / 50.0

        fun mean(key: String): Double = ref.optJSONObject(key)?.optDouble("median", 0.0) ?: 0.0
        fun std(key: String): Double = ref.optJSONObject(key)?.optDouble("mad", 0.0) ?: 0.0

        return BaselineAxes(
            sleep = AxisStats(mean = mean("sleep"), std = std("sleep"), confidence = confidence),
            capacity = AxisStats(mean = mean("capacity"), std = std("capacity"), confidence = confidence),
            arousal = AxisStats(mean = mean("arousal"), std = std("arousal"), confidence = confidence),
            focus = AxisStats(mean = mean("focus"), std = std("focus"), confidence = confidence)
        )
    }

    private fun countSrmWindows(srmData: JSONObject): Int {
        val strata = srmData.optJSONObject("strata") ?: return 0
        var total = 0
        for (key in strata.keys()) {
            total += strata.optJSONObject(key)?.optInt("count", 0) ?: 0
        }
        return total
    }

    private fun computeAggregates(hsiPayloads: List<JSONObject>): SessionAggregates {
        val zero = AggregateAxis(mean = 0.0, min = 0.0, max = 0.0)
        if (hsiPayloads.isEmpty()) {
            return SessionAggregates(focus = zero, arousal = zero, capacity = zero, sleep = zero)
        }

        var fSum = 0.0; var fMin = Double.MAX_VALUE; var fMax = -Double.MAX_VALUE
        var aSum = 0.0; var aMin = Double.MAX_VALUE; var aMax = -Double.MAX_VALUE
        var cSum = 0.0; var cMin = Double.MAX_VALUE; var cMax = -Double.MAX_VALUE
        var sSum = 0.0; var sMin = Double.MAX_VALUE; var sMax = -Double.MAX_VALUE
        var count = 0

        for (payload in hsiPayloads) {
            val window = payload.optJSONObject("window") ?: continue
            val hsi = window.optJSONObject("hsi") ?: continue
            count++
            val f = hsi.optDouble("focus", 0.0)
            val a = hsi.optDouble("arousal", 0.0)
            val c = hsi.optDouble("capacity", 0.0)
            val s = hsi.optDouble("sleep", 0.0)

            fSum += f; fMin = minOf(fMin, f); fMax = maxOf(fMax, f)
            aSum += a; aMin = minOf(aMin, a); aMax = maxOf(aMax, a)
            cSum += c; cMin = minOf(cMin, c); cMax = maxOf(cMax, c)
            sSum += s; sMin = minOf(sMin, s); sMax = maxOf(sMax, s)
        }

        if (count == 0) {
            return SessionAggregates(focus = zero, arousal = zero, capacity = zero, sleep = zero)
        }

        val n = count.toDouble()
        return SessionAggregates(
            focus = AggregateAxis(mean = fSum / n, min = fMin, max = fMax),
            arousal = AggregateAxis(mean = aSum / n, min = aMin, max = aMax),
            capacity = AggregateAxis(mean = cSum / n, min = cMin, max = cMax),
            sleep = AggregateAxis(mean = sSum / n, min = sMin, max = sMax)
        )
    }
}
