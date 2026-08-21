package ai.synheart.core.baseline

import ai.synheart.core.artifacts.ArtifactHeader
import ai.synheart.core.artifacts.SchemaRef
import ai.synheart.core.artifacts.TimeRange
import org.json.JSONObject

/** Engine reference embedded in every baseline envelope. */
data class BaselineEngineRef(
    val name: String,
    val version: String,
    /**
     * Deterministic hash of the engine's runtime config. Two snapshots with
     * the same [configHash] were computed under identical settings.
     */
    val configHash: String,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("name", name)
        put("version", version)
        put("config_hash", configHash)
    }

    companion object {
        fun fromJson(json: JSONObject): BaselineEngineRef = BaselineEngineRef(
            name = json.optString("name"),
            version = json.optString("version"),
            configHash = json.optString("config_hash"),
        )
    }
}

/**
 * Coverage metadata — how much input fed this snapshot, and how many
 * dimensions / axes / metrics it covers. Used by cross-device merge (per-kind,
 * prefer higher observations / dimensions / recency) and by readers gating on
 * freshness.
 */
data class BaselineCoverage(
    val windowStartMs: Long,
    val windowEndMs: Long,
    val observations: Int,
    val dimensionsPresent: Int,
    val dimensionsTotal: Int,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("window_start_ms", windowStartMs)
        put("window_end_ms", windowEndMs)
        put("observations", observations)
        put("dimensions_present", dimensionsPresent)
        put("dimensions_total", dimensionsTotal)
    }

    companion object {
        fun fromJson(json: JSONObject): BaselineCoverage = BaselineCoverage(
            windowStartMs = json.optLong("window_start_ms", 0L),
            windowEndMs = json.optLong("window_end_ms", 0L),
            observations = json.optInt("observations", 0),
            dimensionsPresent = json.optInt("dimensions_present", 0),
            dimensionsTotal = json.optInt("dimensions_total", 0),
        )
    }
}

/**
 * One baseline snapshot, ready to upload to the cloud or restore locally.
 *
 * The payload is held as raw JSON so the envelope stays kind-agnostic — one
 * class for all kinds, one reader. Producers use the typed builders; consumers
 * use the typed extractors ([payloadHsiAxes], [payloadSrmMetrics],
 * [payloadLongitudinalWear]) which dispatch on [kind] and throw
 * [BaselineKindMismatch] on the wrong call.
 */
data class BaselineEnvelope(
    val header: ArtifactHeader,
    val kind: BaselineKind,
    val kindSchemaVersion: Int,
    val computedAtMs: Long,
    val engine: BaselineEngineRef,
    val coverage: BaselineCoverage,
    val payload: JSONObject,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("header", headerToJson(header))
        put("kind", kind.wire)
        put("kind_schema_version", kindSchemaVersion)
        put("computed_at_ms", computedAtMs)
        put("engine", engine.toJson())
        put("coverage", coverage.toJson())
        put("payload", payload)
    }

    /**
     * Decode this envelope's payload as an [HsiAxesBaseline].
     *
     * @throws BaselineKindMismatch when [kind] is not
     *   [BaselineKind.SESSION_HSI_AXES].
     */
    fun payloadHsiAxes(): HsiAxesBaseline {
        requireKind(BaselineKind.SESSION_HSI_AXES)
        return HsiAxesBaseline.fromJson(payload)
    }

    /**
     * Decode this envelope's payload as a [SessionSrmMetricsBaseline].
     *
     * @throws BaselineKindMismatch when [kind] is not
     *   [BaselineKind.SESSION_SRM_METRICS].
     */
    fun payloadSrmMetrics(): SessionSrmMetricsBaseline {
        requireKind(BaselineKind.SESSION_SRM_METRICS)
        return SessionSrmMetricsBaseline.fromJson(payload)
    }

    /**
     * Decode this envelope's payload as a [LongitudinalWearBaseline].
     *
     * @throws BaselineKindMismatch when [kind] is not
     *   [BaselineKind.LONGITUDINAL_WEAR].
     */
    fun payloadLongitudinalWear(): LongitudinalWearBaseline {
        requireKind(BaselineKind.LONGITUDINAL_WEAR)
        return LongitudinalWearBaseline.fromJson(payload)
    }

    private fun requireKind(expected: BaselineKind) {
        if (kind != expected) {
            throw BaselineKindMismatch(expected = expected, actual = kind)
        }
    }

    companion object {
        /**
         * Parse an envelope from JSON.
         *
         * @throws BaselineFormatException on a missing header or an
         *   unrecognized `kind`. A typed-payload mismatch is raised at
         *   extraction time, not here.
         */
        fun fromJson(json: JSONObject): BaselineEnvelope {
            val headerJson = json.optJSONObject("header")
                ?: throw BaselineFormatException("baseline envelope missing header")
            val kindWire = json.optString("kind").takeIf { it.isNotEmpty() }
            val kind = BaselineKind.fromWire(kindWire)
                ?: throw BaselineFormatException(
                    "unknown baseline kind: ${kindWire ?: "<null>"}. " +
                        "Update the SDK or skip this snapshot.",
                )
            return BaselineEnvelope(
                header = headerFromJson(headerJson),
                kind = kind,
                kindSchemaVersion = json.optInt("kind_schema_version", 1),
                computedAtMs = json.optLong("computed_at_ms", 0L),
                engine = BaselineEngineRef.fromJson(
                    json.optJSONObject("engine") ?: JSONObject(),
                ),
                coverage = BaselineCoverage.fromJson(
                    json.optJSONObject("coverage") ?: JSONObject(),
                ),
                payload = json.optJSONObject("payload") ?: JSONObject(),
            )
        }

        /**
         * `ArtifactHeader` is a kotlinx-serialization type while the envelope
         * round-trips through `org.json`; these two adapters keep the seam in
         * one place rather than pulling a second JSON engine into the path.
         */
        private fun headerToJson(h: ArtifactHeader): JSONObject = JSONObject().apply {
            put("artifact_id", h.artifactId)
            put("subject_id", h.subjectId)
            h.sessionId?.let { put("session_id", it) }
            put(
                "schema",
                JSONObject().apply {
                    put("name", h.schema.name)
                    put("version", h.schema.version)
                },
            )
            put(
                "time_range",
                JSONObject().apply {
                    put("start_ms", h.timeRange.startMs)
                    put("end_ms", h.timeRange.endMs)
                },
            )
            put("created_at_ms", h.createdAtMs)
        }

        private fun headerFromJson(json: JSONObject): ArtifactHeader {
            val schema = json.optJSONObject("schema") ?: JSONObject()
            val range = json.optJSONObject("time_range") ?: JSONObject()
            return ArtifactHeader(
                artifactId = json.optString("artifact_id"),
                subjectId = json.optString("subject_id"),
                sessionId = json.optString("session_id").takeIf { it.isNotEmpty() },
                schema = SchemaRef(
                    name = schema.optString("name"),
                    version = schema.optString("version"),
                ),
                timeRange = TimeRange(
                    startMs = range.optLong("start_ms", 0L),
                    endMs = range.optLong("end_ms", 0L),
                ),
                createdAtMs = json.optLong("created_at_ms", 0L),
            )
        }
    }
}

/** Thrown when a baseline envelope cannot be decoded from its wire form. */
class BaselineFormatException(message: String) : Exception(message)

/**
 * Thrown by a typed payload extractor when the envelope's kind doesn't match
 * the caller's expected kind.
 */
class BaselineKindMismatch(
    val expected: BaselineKind,
    val actual: BaselineKind,
) : Exception(
    "BaselineKindMismatch: envelope is ${actual.wire}, caller asked for ${expected.wire}",
)
