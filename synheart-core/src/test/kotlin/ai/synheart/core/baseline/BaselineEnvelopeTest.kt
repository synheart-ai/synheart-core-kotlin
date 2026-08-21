package ai.synheart.core.baseline

import ai.synheart.core.models.WearableReferenceView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BaselineKindTest {

    @Test
    fun `wire strings round-trip`() {
        for (k in BaselineKind.entries) {
            assertEquals(k, BaselineKind.fromWire(k.wire))
        }
    }

    @Test
    fun `an unknown wire yields null rather than throwing`() {
        // Forward-compat: an older SDK reading a snapshot from a newer runtime
        // logs and skips rather than failing the whole hydration.
        assertNull(BaselineKind.fromWire("session.something_new"))
        assertNull(BaselineKind.fromWire(null))
        assertNull(BaselineKind.fromWire(""))
    }

    @Test
    fun `session scoping follows the wire prefix`() {
        assertTrue(BaselineKind.SESSION_HSI_AXES.isSessionScoped)
        assertTrue(BaselineKind.SESSION_SRM_METRICS.isSessionScoped)
        assertFalse(BaselineKind.LONGITUDINAL_WEAR.isSessionScoped)
    }

    @Test
    fun `srm metric status parses and defaults safely`() {
        assertEquals(SrmMetricStatus.READY, SrmMetricStatus.fromWire("READY"))
        assertNull(SrmMetricStatus.fromWire("ROTTEN"))
        assertNull(SrmMetricStatus.fromWire(null))
    }
}

class BaselinePayloadsTest {

    @Test
    fun `hsi axes payload round-trips`() {
        val payload = HsiAxesBaseline(
            schemaVersion = 2,
            axes = mapOf("focus" to AxisStats(mean = 0.6, std = 0.1, confidence = 0.9)),
        )
        val back = HsiAxesBaseline.fromJson(payload.toJson())
        assertEquals(2, back.schemaVersion)
        assertEquals(0.6, back.axes.getValue("focus").mean, 1e-9)
        assertEquals(0.1, back.axes.getValue("focus").std, 1e-9)
        assertEquals(0.9, back.axes.getValue("focus").confidence, 1e-9)
    }

    @Test
    fun `hsi axes payload defaults its schema version`() {
        val back = HsiAxesBaseline.fromJson(JSONObject())
        assertEquals(1, back.schemaVersion)
        assertTrue(back.axes.isEmpty())
    }

    @Test
    fun `srm metrics payload round-trips`() {
        val payload = SessionSrmMetricsBaseline(
            schemaVersion = 1,
            metrics = mapOf(
                "rmssd" to SrmMetricBaseline(
                    muTilde = 42.0,
                    sigmaTilde = 5.5,
                    status = SrmMetricStatus.WARMING,
                    nEff = 12,
                ),
            ),
        )
        val back = SessionSrmMetricsBaseline.fromJson(payload.toJson())
        val m = back.metrics.getValue("rmssd")
        assertEquals(42.0, m.muTilde, 1e-9)
        assertEquals(SrmMetricStatus.WARMING, m.status)
        assertEquals(12, m.nEff)
    }

    @Test
    fun `an unknown srm status falls back to EMPTY`() {
        val json = JSONObject().put(
            "metrics",
            JSONObject().put("x", JSONObject().put("status", "NONSENSE")),
        )
        assertEquals(
            SrmMetricStatus.EMPTY,
            SessionSrmMetricsBaseline.fromJson(json).metrics.getValue("x").status,
        )
    }

    @Test
    fun `longitudinal wear payload keeps the reference flat on the wire`() {
        val payload = LongitudinalWearBaseline(
            schemaVersion = 3,
            reference = WearableReferenceView(
                status = "Ready",
                modelVersion = "v2",
                dimensions = mapOf("hrv_rmssd_ms" to 42.0),
                confidence = mapOf("hrv_rmssd_ms" to 0.8),
            ),
        )
        val json = payload.toJson()
        // Flat: status/model_version/dimensions sit beside schema_version, with
        // no nested `reference` object.
        assertEquals("Ready", json.getString("status"))
        assertFalse(json.has("reference"))

        val back = LongitudinalWearBaseline.fromJson(json)
        assertEquals(3, back.schemaVersion)
        assertEquals("Ready", back.reference.status)
        assertEquals(42.0, back.reference.dimensions.getValue("hrv_rmssd_ms").toDouble(), 1e-9)
        assertEquals(0.8, back.reference.confidence.getValue("hrv_rmssd_ms"), 1e-9)
    }
}

class BaselineEnvelopeTest {

    private fun envelopeJson(kind: String = "session.hsi_axes"): JSONObject = JSONObject()
        .put(
            "header",
            JSONObject()
                .put("artifact_id", "art_1")
                .put("subject_id", "sub_1")
                .put("session_id", "sess_1")
                .put("schema", JSONObject().put("name", "baseline").put("version", "1"))
                .put("time_range", JSONObject().put("start_ms", 1000).put("end_ms", 2000))
                .put("created_at_ms", 1500),
        )
        .put("kind", kind)
        .put("kind_schema_version", 1)
        .put("computed_at_ms", 1600)
        .put(
            "engine",
            JSONObject().put("name", "srm").put("version", "1.0").put("config_hash", "deadbeef"),
        )
        .put(
            "coverage",
            JSONObject()
                .put("window_start_ms", 1000)
                .put("window_end_ms", 2000)
                .put("observations", 10)
                .put("dimensions_present", 3)
                .put("dimensions_total", 4),
        )
        .put(
            "payload",
            JSONObject().put("schema_version", 1).put(
                "axes",
                JSONObject().put(
                    "focus",
                    JSONObject().put("mean", 0.5).put("std", 0.2).put("confidence", 0.7),
                ),
            ),
        )

    @Test
    fun `a full envelope parses and round-trips`() {
        val env = BaselineEnvelope.fromJson(envelopeJson())
        assertEquals(BaselineKind.SESSION_HSI_AXES, env.kind)
        assertEquals("art_1", env.header.artifactId)
        assertEquals("sess_1", env.header.sessionId)
        assertEquals(1600L, env.computedAtMs)
        assertEquals("deadbeef", env.engine.configHash)
        assertEquals(10, env.coverage.observations)

        val back = BaselineEnvelope.fromJson(env.toJson())
        assertEquals(env.kind, back.kind)
        assertEquals(env.header.artifactId, back.header.artifactId)
        assertEquals(env.coverage.dimensionsTotal, back.coverage.dimensionsTotal)
    }

    @Test
    fun `a missing header is a format error`() {
        val json = envelopeJson()
        json.remove("header")
        try {
            BaselineEnvelope.fromJson(json)
            fail("expected BaselineFormatException")
        } catch (e: BaselineFormatException) {
            assertTrue(e.message!!.contains("header"))
        }
    }

    @Test
    fun `an unknown kind is a format error, not a silent default`() {
        try {
            BaselineEnvelope.fromJson(envelopeJson(kind = "session.brand_new"))
            fail("expected BaselineFormatException")
        } catch (e: BaselineFormatException) {
            assertTrue(e.message!!.contains("session.brand_new"))
        }
    }

    @Test
    fun `the typed extractor decodes its own kind`() {
        val axes = BaselineEnvelope.fromJson(envelopeJson()).payloadHsiAxes()
        assertEquals(0.5, axes.axes.getValue("focus").mean, 1e-9)
    }

    @Test
    fun `asking for the wrong kind throws a mismatch`() {
        val env = BaselineEnvelope.fromJson(envelopeJson())
        try {
            env.payloadLongitudinalWear()
            fail("expected BaselineKindMismatch")
        } catch (e: BaselineKindMismatch) {
            assertEquals(BaselineKind.LONGITUDINAL_WEAR, e.expected)
            assertEquals(BaselineKind.SESSION_HSI_AXES, e.actual)
        }
    }
}

class BaselineSnapshotsTest {

    private fun envelope(kind: BaselineKind, payload: JSONObject): BaselineEnvelope =
        BaselineEnvelope(
            header = ai.synheart.core.artifacts.ArtifactHeader(
                artifactId = "a",
                subjectId = "s",
                schema = ai.synheart.core.artifacts.SchemaRef("baseline", "1"),
                timeRange = ai.synheart.core.artifacts.TimeRange(0, 1),
                createdAtMs = 1,
            ),
            kind = kind,
            kindSchemaVersion = 1,
            computedAtMs = 1,
            engine = BaselineEngineRef("e", "1", "h"),
            coverage = BaselineCoverage(0, 1, 1, 1, 1),
            payload = payload,
        )

    @Test
    fun `caching is last-write-wins per kind`() {
        val s = BaselineSnapshots()
        assertNull(s.latestHsiAxes())

        s.cache(
            envelope(
                BaselineKind.SESSION_HSI_AXES,
                JSONObject().put("schema_version", 1).put(
                    "axes",
                    JSONObject().put(
                        "focus",
                        JSONObject().put("mean", 0.1).put("std", 0.0).put("confidence", 1.0),
                    ),
                ),
            ),
        )
        assertEquals(0.1, s.latestHsiAxes()!!.axes.getValue("focus").mean, 1e-9)

        s.cache(
            envelope(
                BaselineKind.SESSION_HSI_AXES,
                JSONObject().put("schema_version", 1).put(
                    "axes",
                    JSONObject().put(
                        "focus",
                        JSONObject().put("mean", 0.9).put("std", 0.0).put("confidence", 1.0),
                    ),
                ),
            ),
        )
        assertEquals(0.9, s.latestHsiAxes()!!.axes.getValue("focus").mean, 1e-9)
        assertEquals(1, s.envelopesByKind().size)
    }

    @Test
    fun `reset forgets everything`() {
        val s = BaselineSnapshots()
        s.cache(envelope(BaselineKind.SESSION_SRM_METRICS, JSONObject()))
        s.reset()
        assertNull(s.latestSrmMetrics())
        assertTrue(s.envelopesByKind().isEmpty())
    }

    @Test
    fun `hydrate is a no-op with no wired hydrator`() = kotlinx.coroutines.test.runTest {
        assertTrue(BaselineSnapshots().hydrateFromLocal().isEmpty())
    }

    @Test
    fun `hydrate skips envelopes it cannot decode`() = kotlinx.coroutines.test.runTest {
        // Forward-compat: one snapshot with a kind from a newer runtime must
        // not sink the whole hydration.
        val s = BaselineSnapshots()
        s.wireLocalHydrator {
            """
            {"snapshots":[
              {"kind":"kind.from.the.future","header":{},"payload":{}},
              {"kind":"session.hsi_axes","header":{"artifact_id":"a"},
               "computed_at_ms":1,"engine":{},"coverage":{},
               "payload":{"schema_version":1,"axes":{}}}
            ]}
            """.trimIndent()
        }
        val out = s.hydrateFromLocal()
        assertEquals(1, out.size)
        assertEquals(BaselineKind.SESSION_HSI_AXES, out.first().kind)
    }

    @Test
    fun `hydrate returns empty on an error payload`() = kotlinx.coroutines.test.runTest {
        val s = BaselineSnapshots()
        s.wireLocalHydrator { """{"error":"runtime not available"}""" }
        assertTrue(s.hydrateFromLocal().isEmpty())
    }
}
