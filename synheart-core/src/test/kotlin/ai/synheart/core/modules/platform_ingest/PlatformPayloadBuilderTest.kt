package ai.synheart.core.modules.platform_ingest

import ai.synheart.core.config.SynheartConfig
import ai.synheart.core.modules.behavior.BehaviorEvent
import ai.synheart.core.modules.behavior.BehaviorEventType
import ai.synheart.core.modules.phone.PhoneDataPoint
import ai.synheart.core.modules.wear.WearSample
import org.junit.Assert.*
import org.junit.Test

class PlatformPayloadBuilderTest {

    // ---------------------------------------------------------------
    // Helper factories
    // ---------------------------------------------------------------

    private fun defaultConfig() = SynheartConfig(
        appId = "com.test.app",
        subjectId = "user-42",
        appName = "TestApp",
        appVersion = "1.2.3",
        category = "Health",
        developer = "TestDev"
    )

    private fun buildSessionDefaults(
        durationMs: Long = 60_000,
        wearSamples: List<WearSample> = emptyList(),
        behaviorEvents: List<BehaviorEvent> = emptyList(),
        phoneDataPoints: List<PhoneDataPoint> = emptyList(),
        previousSessionEndMs: Long? = null
    ): Map<String, Any?> {
        val startMs = 1_700_000_000_000L
        return PlatformPayloadBuilder.buildSession(
            sessionId = "session-1",
            deviceId = "device-A",
            appId = "com.test.app",
            userId = "user-42",
            startedAtMs = startMs,
            endedAtMs = startMs + durationMs,
            dataOnCloud = false,
            wearSamples = wearSamples,
            behaviorEvents = behaviorEvents,
            phoneDataPoints = phoneDataPoints,
            previousSessionEndMs = previousSessionEndMs
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun windowData(session: Map<String, Any?>): Map<String, Any?> {
        val windows = session["session_window"] as List<Map<String, Any?>>
        val window = windows.first()
        return (window["window_data"] as Map<String, Any?>)
    }

    @Suppress("UNCHECKED_CAST")
    private fun stateData(session: Map<String, Any?>): Map<String, Any?> {
        return windowData(session)["state_data"] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun wearData(session: Map<String, Any?>): Map<String, Any?> {
        return stateData(session)["wear_data"] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun behaviorData(session: Map<String, Any?>): Map<String, Any?> {
        return stateData(session)["behavior_data"] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun behaviorMetrics(session: Map<String, Any?>): Map<String, Any?> {
        return behaviorData(session)["behavioral_metrics"] as Map<String, Any?>
    }

    @Suppress("UNCHECKED_CAST")
    private fun typingSummary(session: Map<String, Any?>): Map<String, Any> {
        return behaviorData(session)["typing_session_summary"] as Map<String, Any>
    }

    @Suppress("UNCHECKED_CAST")
    private fun motionState(session: Map<String, Any?>): Map<String, Any> {
        return behaviorData(session)["motion_state"] as Map<String, Any>
    }

    // ---------------------------------------------------------------
    // 1. buildMetadata
    // ---------------------------------------------------------------

    @Test
    fun `buildMetadata produces correct top-level keys`() {
        val result = PlatformPayloadBuilder.buildMetadata(
            config = defaultConfig(),
            deviceId = "dev-1",
            platform = "android",
            osVersion = "14"
        )

        assertTrue(result.containsKey("app"))
        assertTrue(result.containsKey("user"))
        assertTrue(result.containsKey("devices"))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `buildMetadata uses config fields correctly`() {
        val cfg = defaultConfig()
        val result = PlatformPayloadBuilder.buildMetadata(
            config = cfg,
            deviceId = "dev-1",
            platform = "android",
            osVersion = "14"
        )

        val app = result["app"] as Map<String, Any?>
        assertEquals("com.test.app", app["app_id"])
        assertEquals("TestApp", app["app_name"])
        assertEquals("1.2.3", app["app_version"])
        assertEquals("Health", app["category"])
        assertEquals("TestDev", app["developer"])
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `buildMetadata handles null userInfo gracefully`() {
        val result = PlatformPayloadBuilder.buildMetadata(
            config = defaultConfig(),
            deviceId = "dev-1",
            platform = "android",
            osVersion = "14",
            userInfo = null
        )

        val user = result["user"] as Map<String, Any?>
        assertEquals("user-42", user["user_id"])
        assertEquals("", user["birthdate"])
        assertEquals("", user["gender"])
        assertNull(user["blood_type"])
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `buildMetadata populates devices list`() {
        val result = PlatformPayloadBuilder.buildMetadata(
            config = defaultConfig(),
            deviceId = "dev-1",
            platform = "ios",
            osVersion = "17.2"
        )

        val devices = result["devices"] as List<Map<String, Any?>>
        assertEquals(1, devices.size)

        val device = devices[0]["device"] as Map<String, Any?>
        assertEquals("dev-1", device["device_id"])
        assertEquals("ios", device["platform"])
        assertEquals("17.2", device["device_os_version"])
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `buildMetadata uses userInfo when provided`() {
        val userInfo = mapOf<String, Any?>(
            "birthdate" to "1990-01-01",
            "gender" to "female",
            "blood_type" to "O+",
            "device_type" to "Tablet"
        )

        val result = PlatformPayloadBuilder.buildMetadata(
            config = defaultConfig(),
            deviceId = "dev-1",
            platform = "android",
            osVersion = "14",
            userInfo = userInfo
        )

        val user = result["user"] as Map<String, Any?>
        assertEquals("1990-01-01", user["birthdate"])
        assertEquals("female", user["gender"])
        assertEquals("O+", user["blood_type"])

        val devices = result["devices"] as List<Map<String, Any?>>
        val device = devices[0]["device"] as Map<String, Any?>
        assertEquals("Tablet", device["device_type"])
    }

    // ---------------------------------------------------------------
    // 2. buildSession - top-level structure
    // ---------------------------------------------------------------

    @Test
    fun `buildSession has correct top-level keys`() {
        val session = buildSessionDefaults()

        assertEquals("session-1", session["id"])
        assertTrue(session.containsKey("session_metadata"))
        assertTrue(session.containsKey("session_failure"))
        assertTrue(session.containsKey("session_window"))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `buildSession session_metadata has correct fields`() {
        val session = buildSessionDefaults()
        val meta = session["session_metadata"] as Map<String, Any?>

        assertEquals("device-A", meta["device_id"])
        assertEquals("com.test.app", meta["app_id"])
        assertEquals("user-42", meta["user_id"])
        assertEquals(false, meta["data_on_cloud"])
        assertTrue(meta.containsKey("started_at"))
        assertTrue(meta.containsKey("ended_at"))
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `buildSession session_failure defaults to empty list`() {
        val session = buildSessionDefaults()
        val failures = session["session_failure"] as List<*>
        assertTrue(failures.isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `buildSession session_window contains one root window node`() {
        val session = buildSessionDefaults()
        val windows = session["session_window"] as List<Map<String, Any?>>
        assertEquals(1, windows.size)

        val window = windows[0]
        assertEquals("session-1", window["id"])
        assertNull(window["parent_id"])
        assertTrue(window.containsKey("window_metadata"))
        assertTrue(window.containsKey("window_data"))
    }

    // ---------------------------------------------------------------
    // 3. Wear data aggregation
    // ---------------------------------------------------------------

    @Test
    fun `empty wear samples yields has_wear_data false and null means`() {
        val session = buildSessionDefaults(wearSamples = emptyList())
        val wear = wearData(session)

        assertEquals(false, wear["has_wear_data"])
        assertNull(wear["hr_mean"])
        assertNull(wear["hrv_rmssd_mean"])
    }

    @Test
    fun `wear samples with data yields correct hr_mean and hrv_rmssd_mean`() {
        val samples = listOf(
            WearSample(timestamp = 1000L, hr = 60.0, hrvRmssd = 40.0),
            WearSample(timestamp = 2000L, hr = 80.0, hrvRmssd = 50.0),
            WearSample(timestamp = 3000L, hr = 100.0, hrvRmssd = 60.0)
        )

        val session = buildSessionDefaults(wearSamples = samples)
        val wear = wearData(session)

        assertEquals(true, wear["has_wear_data"])
        assertEquals(80.0, wear["hr_mean"] as Double, 0.001)
        assertEquals(50.0, wear["hrv_rmssd_mean"] as Double, 0.001)
    }

    @Test
    fun `wear samples include hr_min and hr_max when hr data exists`() {
        val samples = listOf(
            WearSample(timestamp = 1000L, hr = 55.0, hrvRmssd = 30.0),
            WearSample(timestamp = 2000L, hr = 90.0, hrvRmssd = 50.0),
            WearSample(timestamp = 3000L, hr = 72.0, hrvRmssd = 45.0)
        )

        val session = buildSessionDefaults(wearSamples = samples)
        val wear = wearData(session)

        assertEquals(55.0, wear["hr_min"] as Double, 0.001)
        assertEquals(90.0, wear["hr_max"] as Double, 0.001)
    }

    @Test
    fun `wear samples with null hr still computes hrv_rmssd_mean`() {
        val samples = listOf(
            WearSample(timestamp = 1000L, hr = null, hrvRmssd = 40.0),
            WearSample(timestamp = 2000L, hr = null, hrvRmssd = 60.0)
        )

        val session = buildSessionDefaults(wearSamples = samples)
        val wear = wearData(session)

        assertEquals(true, wear["has_wear_data"])
        assertNull(wear["hr_mean"])
        assertEquals(50.0, wear["hrv_rmssd_mean"] as Double, 0.001)
        assertFalse(wear.containsKey("hr_min"))
        assertFalse(wear.containsKey("hr_max"))
    }

    // ---------------------------------------------------------------
    // 4. Behavior data aggregation
    // ---------------------------------------------------------------

    @Test
    fun `empty behavior events produce zero total_events`() {
        val session = buildSessionDefaults(behaviorEvents = emptyList())
        val behavior = behaviorData(session)

        @Suppress("UNCHECKED_CAST")
        val activity = behavior["activity_summary"] as Map<String, Any?>
        assertEquals(0, activity["total_events"])
    }

    @Test
    fun `tap scroll and keyDown events counted correctly`() {
        val base = 1_700_000_000_000L
        val events = listOf(
            BehaviorEvent(type = BehaviorEventType.TAP, timestamp = base + 1000),
            BehaviorEvent(type = BehaviorEventType.TAP, timestamp = base + 2000),
            BehaviorEvent(type = BehaviorEventType.SCROLL, timestamp = base + 3000),
            BehaviorEvent(type = BehaviorEventType.KEY_DOWN, timestamp = base + 4000),
            BehaviorEvent(type = BehaviorEventType.KEY_DOWN, timestamp = base + 5000),
            BehaviorEvent(type = BehaviorEventType.KEY_DOWN, timestamp = base + 6000)
        )

        val session = buildSessionDefaults(behaviorEvents = events, durationMs = 60_000)
        val behavior = behaviorData(session)

        @Suppress("UNCHECKED_CAST")
        val activity = behavior["activity_summary"] as Map<String, Any?>
        assertEquals(6, activity["total_events"])
    }

    @Test
    fun `micro_session is true when duration less than 30 seconds`() {
        val base = 1_700_000_000_000L
        val events = listOf(
            BehaviorEvent(type = BehaviorEventType.TAP, timestamp = base + 1000)
        )

        val session = buildSessionDefaults(
            durationMs = 20_000,
            behaviorEvents = events
        )
        val behavior = behaviorData(session)

        assertEquals(true, behavior["micro_session"])
    }

    @Test
    fun `micro_session is false when duration 30 seconds or more`() {
        val base = 1_700_000_000_000L
        val events = listOf(
            BehaviorEvent(type = BehaviorEventType.TAP, timestamp = base + 1000)
        )

        val session = buildSessionDefaults(
            durationMs = 60_000,
            behaviorEvents = events
        )
        val behavior = behaviorData(session)

        assertEquals(false, behavior["micro_session"])
    }

    // ---------------------------------------------------------------
    // 5. Typing session analysis
    // ---------------------------------------------------------------

    @Test
    fun `no keystrokes yields zero typing data`() {
        val session = buildSessionDefaults(behaviorEvents = emptyList())
        val typing = typingSummary(session)

        assertEquals(0, typing["typing_session_count"])
        assertEquals(0.0, typing["total_typing_duration"] as Double, 0.001)
        assertEquals(0, typing["average_keystrokes_per_session"])
    }

    @Test
    fun `keystrokes within 2s grouped into single typing session`() {
        val base = 1_700_000_000_000L
        val events = listOf(
            BehaviorEvent(type = BehaviorEventType.KEY_DOWN, timestamp = base + 1000),
            BehaviorEvent(type = BehaviorEventType.KEY_DOWN, timestamp = base + 1500),
            BehaviorEvent(type = BehaviorEventType.KEY_DOWN, timestamp = base + 2000),
            BehaviorEvent(type = BehaviorEventType.KEY_DOWN, timestamp = base + 2400)
        )

        val session = buildSessionDefaults(behaviorEvents = events, durationMs = 60_000)
        val typing = typingSummary(session)

        assertEquals(1, typing["typing_session_count"])
    }

    @Test
    fun `gap greater than 2s creates new typing session`() {
        val base = 1_700_000_000_000L
        // Session 1: keys at +1000, +1500
        // Gap of 3000ms (> 2000ms threshold)
        // Session 2: keys at +4500, +5000
        // Gap of 5000ms
        // Session 3: key at +10000
        val events = listOf(
            BehaviorEvent(type = BehaviorEventType.KEY_DOWN, timestamp = base + 1000),
            BehaviorEvent(type = BehaviorEventType.KEY_DOWN, timestamp = base + 1500),
            BehaviorEvent(type = BehaviorEventType.KEY_DOWN, timestamp = base + 4500),
            BehaviorEvent(type = BehaviorEventType.KEY_DOWN, timestamp = base + 5000),
            BehaviorEvent(type = BehaviorEventType.KEY_DOWN, timestamp = base + 10000)
        )

        val session = buildSessionDefaults(behaviorEvents = events, durationMs = 60_000)
        val typing = typingSummary(session)

        assertEquals(3, typing["typing_session_count"])
    }

    @Test
    fun `typing_session_count reflects correct grouping with exact 2s gap`() {
        val base = 1_700_000_000_000L
        // Gap of exactly 2000ms should NOT create new session (threshold is > 2000)
        val events = listOf(
            BehaviorEvent(type = BehaviorEventType.KEY_DOWN, timestamp = base + 1000),
            BehaviorEvent(type = BehaviorEventType.KEY_DOWN, timestamp = base + 3000)
        )

        val session = buildSessionDefaults(behaviorEvents = events, durationMs = 60_000)
        val typing = typingSummary(session)

        assertEquals(1, typing["typing_session_count"])
    }

    // ---------------------------------------------------------------
    // 6. Motion state
    // ---------------------------------------------------------------

    @Test
    fun `empty motion points yields unknown state`() {
        val session = buildSessionDefaults(phoneDataPoints = emptyList())
        val motion = motionState(session)

        assertEquals("unknown", motion["major_state"])
        assertEquals(listOf("unknown"), motion["state"])
    }

    @Test
    fun `low motion yields stationary state`() {
        val base = 1_700_000_000_000L
        val points = listOf(
            PhoneDataPoint(timestamp = base + 1000, motionLevel = 0.02, screenOn = null, appSwitch = null, notification = null),
            PhoneDataPoint(timestamp = base + 2000, motionLevel = 0.05, screenOn = null, appSwitch = null, notification = null),
            PhoneDataPoint(timestamp = base + 3000, motionLevel = 0.03, screenOn = null, appSwitch = null, notification = null)
        )

        val session = buildSessionDefaults(phoneDataPoints = points)
        val motion = motionState(session)

        assertEquals("stationary", motion["major_state"])
    }

    @Test
    fun `mixed motion yields correct major_state`() {
        val base = 1_700_000_000_000L
        // 3 stationary + 1 walking + 1 running -> major = stationary
        val points = listOf(
            PhoneDataPoint(timestamp = base + 1000, motionLevel = 0.02, screenOn = null, appSwitch = null, notification = null),
            PhoneDataPoint(timestamp = base + 2000, motionLevel = 0.05, screenOn = null, appSwitch = null, notification = null),
            PhoneDataPoint(timestamp = base + 3000, motionLevel = 0.03, screenOn = null, appSwitch = null, notification = null),
            PhoneDataPoint(timestamp = base + 4000, motionLevel = 0.25, screenOn = null, appSwitch = null, notification = null),
            PhoneDataPoint(timestamp = base + 5000, motionLevel = 0.50, screenOn = null, appSwitch = null, notification = null)
        )

        val session = buildSessionDefaults(phoneDataPoints = points)
        val motion = motionState(session)

        assertEquals("stationary", motion["major_state"])
        assertEquals(0.6, motion["major_state_pct"] as Double, 0.001)
    }

    @Test
    fun `walking dominant motion yields walking major_state`() {
        val base = 1_700_000_000_000L
        val points = listOf(
            PhoneDataPoint(timestamp = base + 1000, motionLevel = 0.15, screenOn = null, appSwitch = null, notification = null),
            PhoneDataPoint(timestamp = base + 2000, motionLevel = 0.20, screenOn = null, appSwitch = null, notification = null),
            PhoneDataPoint(timestamp = base + 3000, motionLevel = 0.30, screenOn = null, appSwitch = null, notification = null)
        )

        val session = buildSessionDefaults(phoneDataPoints = points)
        val motion = motionState(session)

        assertEquals("walking", motion["major_state"])
        assertEquals(1.0, motion["major_state_pct"] as Double, 0.001)
    }

    // ---------------------------------------------------------------
    // 7. Deep focus blocks
    // ---------------------------------------------------------------

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `no events yields empty deep focus blocks`() {
        val session = buildSessionDefaults(behaviorEvents = emptyList())
        val metrics = behaviorMetrics(session)
        val blocks = metrics["deep_focus_blocks"] as List<*>

        assertTrue(blocks.isEmpty())
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `continuous activity over 2 min detected as deep focus block`() {
        val base = 1_700_000_000_000L
        // Events every 5 seconds for 3 minutes (36 events), all within maxGap of 10s
        val events = (0 until 36).map { i ->
            BehaviorEvent(
                type = BehaviorEventType.TAP,
                timestamp = base + (i * 5000L)
            )
        }

        val session = buildSessionDefaults(
            behaviorEvents = events,
            durationMs = 300_000
        )
        val metrics = behaviorMetrics(session)
        val blocks = metrics["deep_focus_blocks"] as List<Map<String, Any>>

        assertTrue(blocks.isNotEmpty())
        val block = blocks.first()
        val durationMs = block["duration_ms"] as Long
        assertTrue("Block duration should be >= 120000ms, was $durationMs", durationMs >= 120_000L)
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `gap greater than 10s breaks deep focus block`() {
        val base = 1_700_000_000_000L
        // First cluster: events every 5s for 1.5 min (18 events) -> 85s total, too short
        // Then gap of 15s (> 10s)
        // Second cluster: events every 5s for 1.5 min (18 events) -> 85s total, too short
        val cluster1 = (0 until 18).map { i ->
            BehaviorEvent(type = BehaviorEventType.TAP, timestamp = base + (i * 5000L))
        }
        val gapMs = 15_000L
        val cluster2Start = base + (17 * 5000L) + gapMs
        val cluster2 = (0 until 18).map { i ->
            BehaviorEvent(type = BehaviorEventType.TAP, timestamp = cluster2Start + (i * 5000L))
        }

        val allEvents = cluster1 + cluster2
        val session = buildSessionDefaults(
            behaviorEvents = allEvents,
            durationMs = 300_000
        )
        val metrics = behaviorMetrics(session)
        val blocks = metrics["deep_focus_blocks"] as List<Map<String, Any>>

        // Each cluster is only 85s (< 120s minimum), so no deep focus blocks
        assertTrue(
            "Both sub-blocks are under 2 min so no deep focus blocks expected",
            blocks.isEmpty()
        )
    }

    @Suppress("UNCHECKED_CAST")
    @Test
    fun `single long block detected even with small internal gaps`() {
        val base = 1_700_000_000_000L
        // Events every 8 seconds for 4 minutes (30 events)
        // All gaps are 8s which is <= 10s maxGap, so one continuous block
        val events = (0 until 30).map { i ->
            BehaviorEvent(
                type = BehaviorEventType.TAP,
                timestamp = base + (i * 8000L)
            )
        }

        val session = buildSessionDefaults(
            behaviorEvents = events,
            durationMs = 300_000
        )
        val metrics = behaviorMetrics(session)
        val blocks = metrics["deep_focus_blocks"] as List<Map<String, Any>>

        assertEquals(1, blocks.size)
    }

    // ---------------------------------------------------------------
    // 8. Statistical helpers (via buildSession output)
    // ---------------------------------------------------------------

    @Test
    fun `idle_ratio reflects gaps greater than 5 seconds`() {
        val base = 1_700_000_000_000L
        // Two events with a 30s gap in a 60s session
        val events = listOf(
            BehaviorEvent(type = BehaviorEventType.TAP, timestamp = base + 0),
            BehaviorEvent(type = BehaviorEventType.TAP, timestamp = base + 30_000)
        )

        val session = buildSessionDefaults(
            behaviorEvents = events,
            durationMs = 60_000
        )
        val metrics = behaviorMetrics(session)
        val idleRatio = metrics["idle_ratio"] as Double

        // 30s gap > 5s threshold, so idleRatio = 30000 / 60000 = 0.5
        assertEquals(0.5, idleRatio, 0.001)
    }

    @Test
    fun `idle_ratio is zero when all gaps under 5 seconds`() {
        val base = 1_700_000_000_000L
        // Events every 2 seconds - all gaps under 5s threshold
        val events = (0 until 10).map { i ->
            BehaviorEvent(type = BehaviorEventType.TAP, timestamp = base + (i * 2000L))
        }

        val session = buildSessionDefaults(
            behaviorEvents = events,
            durationMs = 60_000
        )
        val metrics = behaviorMetrics(session)
        val idleRatio = metrics["idle_ratio"] as Double

        assertEquals(0.0, idleRatio, 0.001)
    }

    @Test
    fun `burstiness is higher for bursty events than uniform events`() {
        val base = 1_700_000_000_000L

        // Uniform events: every 1 second
        val uniformEvents = (0 until 20).map { i ->
            BehaviorEvent(type = BehaviorEventType.TAP, timestamp = base + (i * 1000L))
        }

        // Bursty events: clusters at start and end with large gap
        val burstyEvents = mutableListOf<BehaviorEvent>()
        // Cluster 1: 10 events in 1 second
        for (i in 0 until 10) {
            burstyEvents.add(BehaviorEvent(type = BehaviorEventType.TAP, timestamp = base + (i * 100L)))
        }
        // Cluster 2: 10 events after a 15s gap
        for (i in 0 until 10) {
            burstyEvents.add(BehaviorEvent(type = BehaviorEventType.TAP, timestamp = base + 15_000 + (i * 100L)))
        }

        val uniformSession = buildSessionDefaults(
            behaviorEvents = uniformEvents,
            durationMs = 60_000
        )
        val burstySession = buildSessionDefaults(
            behaviorEvents = burstyEvents,
            durationMs = 60_000
        )

        val uniformBurstiness = behaviorMetrics(uniformSession)["burstiness"] as Double
        val burstyBurstiness = behaviorMetrics(burstySession)["burstiness"] as Double

        assertTrue(
            "Bursty ($burstyBurstiness) should be greater than uniform ($uniformBurstiness)",
            burstyBurstiness > uniformBurstiness
        )
    }

    @Test
    fun `idle_ratio is 1 when no events`() {
        val session = buildSessionDefaults(
            behaviorEvents = emptyList(),
            durationMs = 60_000
        )
        val metrics = behaviorMetrics(session)
        val idleRatio = metrics["idle_ratio"] as Double

        assertEquals(1.0, idleRatio, 0.001)
    }

    @Test
    fun `burstiness is zero with fewer than 2 events`() {
        val base = 1_700_000_000_000L
        val events = listOf(
            BehaviorEvent(type = BehaviorEventType.TAP, timestamp = base + 1000)
        )

        val session = buildSessionDefaults(
            behaviorEvents = events,
            durationMs = 60_000
        )
        val metrics = behaviorMetrics(session)
        val burstiness = metrics["burstiness"] as Double

        assertEquals(0.0, burstiness, 0.001)
    }

    // ---------------------------------------------------------------
    // Additional coverage
    // ---------------------------------------------------------------

    @Test
    fun `session with cohortId includes it in metadata`() {
        val startMs = 1_700_000_000_000L
        @Suppress("UNCHECKED_CAST")
        val session = PlatformPayloadBuilder.buildSession(
            sessionId = "session-1",
            deviceId = "device-A",
            appId = "com.test.app",
            userId = "user-42",
            startedAtMs = startMs,
            endedAtMs = startMs + 60_000,
            dataOnCloud = false,
            cohortId = "cohort-abc",
            wearSamples = emptyList(),
            behaviorEvents = emptyList(),
            phoneDataPoints = emptyList()
        )

        val meta = session["session_metadata"] as Map<String, Any?>
        assertEquals("cohort-abc", meta["cohort_id"])
    }

    @Test
    fun `session_spacing computed from previousSessionEndMs`() {
        val base = 1_700_000_000_000L
        val prevEnd = base - 120_000L // 120 seconds before session start

        val events = listOf(
            BehaviorEvent(type = BehaviorEventType.TAP, timestamp = base + 1000)
        )

        val session = buildSessionDefaults(
            behaviorEvents = events,
            durationMs = 60_000,
            previousSessionEndMs = prevEnd
        )
        val behavior = behaviorData(session)
        val spacing = behavior["session_spacing"] as Int

        // sessionStartMs (first event) = base + 1000
        // spacing = (base + 1000 - (base - 120000)) / 1000 = 121
        assertEquals(121, spacing)
    }
}
