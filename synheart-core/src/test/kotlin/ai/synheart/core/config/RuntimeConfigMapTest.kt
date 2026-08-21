package ai.synheart.core.config

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the JSON handed to `synheart_core_new`.
 *
 * The bug these exist for: `ingest.enabled` and `device_auth.enabled` being
 * hardcoded true. A host with no CloudConfig then hits
 *
 *     ERR_NOT_CONFIGURED: cloud connector org_id must not be empty when HSI
 *     ingest is enabled
 *
 * inside `synheart_core_new`. The handle comes back null, the bridge stays
 * null, and `initialize()` goes on to log "Initialization complete" — with no
 * HSI, no consent store, and no storage. Local-only operation cannot start at
 * all, and nothing fails loudly, because nothing exercises the config map.
 */
class RuntimeConfigMapTest {

    @After
    fun clearEndpointOverrides() {
        ApiEndpoints.baseUrlOverride = ""
        ApiEndpoints.authBaseUrlOverride = ""
    }

    private fun localOnly() = SynheartConfig(appId = "com.example.app", subjectId = "usr_abc")

    private fun withCloud(orgId: String = "org_123") = SynheartConfig(
        appId = "com.example.app",
        subjectId = "usr_abc",
        cloudConfig = CloudConfig(subjectId = "usr_abc", instanceId = "inst_1", orgId = orgId),
    )

    // ── ingest gate ──────────────────────────────────────────────────────

    @Test
    fun `ingest stays off for a local-only config`() {
        val ingest = buildRuntimeConfigMap(localOnly()).getJSONObject("ingest")
        // All three must be false together: the runtime rejects the entire
        // config when any ingest channel is on without an org_id.
        assertFalse(ingest.getBoolean("enabled"))
        assertFalse(ingest.getBoolean("hsi"))
        assertFalse(ingest.getBoolean("lab"))
    }

    @Test
    fun `ingest turns on when a CloudConfig supplies an org id`() {
        val ingest = buildRuntimeConfigMap(withCloud()).getJSONObject("ingest")
        assertTrue(ingest.getBoolean("enabled"))
        assertTrue(ingest.getBoolean("hsi"))
        assertTrue(ingest.getBoolean("lab"))
    }

    @Test
    fun `ingest stays off when a CloudConfig has an empty org id`() {
        // Enabling ingest here reproduces the original failure: the org_id the
        // runtime demands is exactly what is missing.
        val map = buildRuntimeConfigMap(withCloud(orgId = ""))
        assertFalse(map.getJSONObject("ingest").getBoolean("enabled"))
        assertEquals("", map.getString("org_id"))
    }

    // ── device_auth gate ─────────────────────────────────────────────────

    @Test
    fun `device auth stays off without a DeviceAuthConfig`() {
        val auth = buildRuntimeConfigMap(localOnly()).getJSONObject("device_auth")
        assertFalse(auth.getBoolean("enabled"))
        assertEquals("", auth.getString("auth_base_url"))
    }

    @Test
    fun `device auth turns on and carries the URLs when configured`() {
        val config = SynheartConfig(
            appId = "com.example.app",
            subjectId = "usr_abc",
            deviceAuthConfig = DeviceAuthConfig(
                authBaseUrl = "https://auth.example.com",
                packageName = "com.example.app",
            ),
        )
        val auth = buildRuntimeConfigMap(config).getJSONObject("device_auth")
        assertTrue(auth.getBoolean("enabled"))
        assertEquals("https://auth.example.com", auth.getString("auth_base_url"))
        assertEquals("com.example.app", auth.getString("package_name"))
    }

    @Test
    fun `unattested dev registration is off unless asked for`() {
        val off = buildRuntimeConfigMap(localOnly()).getJSONObject("device_auth")
        assertFalse(off.getBoolean("allow_unattested_dev_registration"))

        val config = SynheartConfig(
            appId = "app",
            subjectId = "sub",
            deviceAuthConfig = DeviceAuthConfig(
                authBaseUrl = "https://auth.example.com",
                allowUnattestedDevRegistration = true,
            ),
        )
        val on = buildRuntimeConfigMap(config).getJSONObject("device_auth")
        assertTrue(on.getBoolean("allow_unattested_dev_registration"))
    }

    // ── identity + origin ────────────────────────────────────────────────

    @Test
    fun `client_id mirrors subject_id`() {
        // The runtime derives the device identity's subject from client_id and
        // rejects the config when device_auth is on without one.
        val map = buildRuntimeConfigMap(localOnly())
        assertEquals("usr_abc", map.getString("client_id"))
        assertEquals("usr_abc", map.getString("subject_id"))
    }

    @Test
    fun `api_base_url is omitted rather than sent empty when unconfigured`() {
        // The SDK carries no built-in host, so with neither SyncConfig.baseUrl
        // nor an environment override set there is nothing to resolve to.
        // Sending "" would assert an origin the host never chose; omitting the
        // key lets the runtime apply its own default.
        val map = buildRuntimeConfigMap(localOnly())
        assertFalse(map.has("api_base_url"))
        assertFalse(apiBaseUrlConfigured(localOnly()))
    }

    @Test
    fun `api_base_url is sent when the host names an origin`() {
        val config = SynheartConfig(
            appId = "app_x",
            subjectId = "sub_x",
            sync = SyncConfig(baseUrl = "https://api.example.test"),
        )
        val map = buildRuntimeConfigMap(config)
        assertEquals("https://api.example.test", map.getString("api_base_url"))
        assertTrue(apiBaseUrlConfigured(config))
    }

    @Test
    fun `api_base_url falls back to the endpoint override`() {
        ApiEndpoints.baseUrlOverride = "https://env.example.test"
        val map = buildRuntimeConfigMap(localOnly())
        assertEquals("https://env.example.test", map.getString("api_base_url"))
    }

    @Test
    fun `data_dir is omitted rather than sent empty when unresolved`() {
        // An empty data_dir would land the SRM snapshot and SQLite in the OS
        // temp dir, where they do not survive an app restart.
        assertFalse(buildRuntimeConfigMap(localOnly()).has("data_dir"))
        assertEquals(
            "/tmp/x",
            buildRuntimeConfigMap(localOnly(), dataDir = "/tmp/x").getString("data_dir"),
        )
    }

    @Test
    fun `mode storage sync and privacy reflect the config`() {
        val config = SynheartConfig(
            appId = "com.example.app",
            subjectId = "usr_abc",
            mode = SynheartMode.RESEARCH,
            storage = StorageConfig(enabled = false),
            sync = SyncConfig(enabled = true, baseUrl = "https://s.example.com"),
            privacy = PrivacyConfig(allowResearch = true),
        )
        val map = buildRuntimeConfigMap(config)

        assertEquals("research", map.getString("mode"))
        assertFalse(map.getJSONObject("storage").getBoolean("enabled"))
        assertTrue(map.getJSONObject("sync").getBoolean("enabled"))
        assertEquals("https://s.example.com", map.getJSONObject("sync").getString("base_url"))
        assertTrue(map.getJSONObject("privacy").getBoolean("allow_research"))
    }
}
