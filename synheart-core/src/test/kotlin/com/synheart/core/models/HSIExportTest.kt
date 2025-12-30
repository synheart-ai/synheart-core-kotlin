package com.synheart.core.models

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HSIExportTest {
    @Test
    fun toHSI10_producesSchemaCompatibleTopLevelShape() {
        val hsv = HumanStateVector(
            timestamp = 1_735_000_000_000L,
            meta = MetaState(
                device = DeviceInfo(platform = "Android", osVersion = "15"),
                sessionId = "session_123",
                version = "1.0.0"
            ),
            hsiEmbedding = List(64) { 0.1f },
            emotion = EmotionState(
                stress = 0.2f,
                calm = 0.8f,
                engagement = 0.5f,
                activation = 0.6f,
                valence = -0.5f
            ),
            focus = FocusState(
                score = 0.7f,
                cognitiveLoad = 0.4f,
                clarity = 0.6f,
                distraction = 0.3f
            ),
            behavior = BehaviorState(
                interactionIntensity = 0.9f,
                engagementLevel = 0.55f
            )
        )

        val hsi = hsv.toHSI10(
            producerName = "Synheart Core SDK",
            producerVersion = "1.0.0",
            instanceId = "0b6f3ac9-62f5-4c9f-9f0d-4c4b3f6b2a3b"
        )

        // Required keys (per schema)
        assertTrue(hsi.containsKey("hsi_version"))
        assertTrue(hsi.containsKey("observed_at_utc"))
        assertTrue(hsi.containsKey("computed_at_utc"))
        assertTrue(hsi.containsKey("producer"))
        assertTrue(hsi.containsKey("window_ids"))
        assertTrue(hsi.containsKey("windows"))
        assertTrue(hsi.containsKey("privacy"))

        // Keys that MUST NOT exist in HSI 1.0 (these were present in old exporter)
        assertFalse(hsi.containsKey("timestamp"))
        assertFalse(hsi.containsKey("window_type"))
        assertFalse(hsi.containsKey("subject"))
        assertFalse(hsi.containsKey("device"))
        assertFalse(hsi.containsKey("state"))
        assertFalse(hsi.containsKey("embedding")) // correct key is "embeddings"

        // Producer object should exist
        val producer = hsi["producer"] as JsonObject
        assertEquals("Synheart Core SDK", (producer["name"] as JsonPrimitive).content)
        assertEquals("1.0.0", (producer["version"] as JsonPrimitive).content)
        assertNotNull(producer["instance_id"])
    }

    @Test
    fun toHSI10_emitsNullWithReasonWhenConsentMissing() {
        val hsv = HumanStateVector(
            timestamp = 1_735_000_000_000L,
            meta = MetaState(
                device = DeviceInfo(platform = "Android", osVersion = "15"),
                sessionId = "session_123",
                version = "1.0.0"
            ),
            emotion = EmotionState(
                stress = 0.2f,
                calm = 0.8f,
                engagement = 0.5f,
                activation = 0.6f,
                valence = 0.25f
            )
        )

        val hsi = hsv.toHSI10(
            producerName = "Synheart Core SDK",
            producerVersion = "1.0.0",
            instanceId = "0b6f3ac9-62f5-4c9f-9f0d-4c4b3f6b2a3b",
            access = HSIExportAccessContext(
                capabilityHsi = "CORE",
                capabilityCloud = "CORE",
                consentBiosignals = false,
                consentPhoneContext = false,
                consentBehavior = false,
                consentCloudUpload = false,
                consentEmotionEstimation = false,
                consentFocusEstimation = false
            )
        )

        val axes = hsi["axes"] as JsonObject
        val affect = axes["affect"] as JsonObject
        val readings = affect["readings"]
        assertNotNull(readings)

        // Spot-check that the stress axis is explicitly present with score=null and notes containing reason.
        val readingsArr = (affect["readings"] as kotlinx.serialization.json.JsonArray)
        val stressReading = readingsArr
            .map { it as JsonObject }
            .first { (it["axis"] as JsonPrimitive).content == "stress" }

        assertTrue(stressReading["score"] is JsonNull)
        val notes = (stressReading["notes"] as JsonPrimitive).content
        assertTrue(notes.contains("consent_denied") || notes.contains("capability_insufficient"))
    }
}


