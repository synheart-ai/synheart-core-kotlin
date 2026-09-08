package ai.synheart.core.config

import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the JSON config handed to `synheart_core_new`.
 *
 * Pure and side-effect free, so it can be unit-tested without a native
 * runtime, and single-sourced so the facade and secondary instances cannot
 * drift apart.
 *
 * Both gates below are load-bearing:
 *
 *  - `ingest.*` requires a non-empty `org_id`. Enabling it without one makes
 *    the runtime reject the entire configuration, which surfaces as a null
 *    handle rather than a targeted error — so ingest is enabled only when a
 *    [CloudConfig] supplies an org id, leaving local-only hosts working.
 *  - `device_auth.enabled` requires a [DeviceAuthConfig]. Enabling it without
 *    one makes crypto-callback registration fail.
 */

/**
 * The platform origin this config will use, or empty when none was supplied.
 *
 * [SyncConfig.baseUrl] wins; otherwise [ApiEndpoints.resolvedAuthBaseUrl].
 * Both empty means the host named no environment and the runtime will fall
 * back to its own default.
 */
private fun resolveApiBaseUrl(config: SynheartConfig): String =
    config.sync.baseUrl.takeIf { it.isNotEmpty() } ?: ApiEndpoints.resolvedAuthBaseUrl

/**
 * Whether an explicit platform origin was configured.
 *
 * Worth checking before enabling anything cloud-bound: without one the runtime
 * silently uses its own default, so a build intended for a non-default
 * environment would talk to the wrong host with no error to explain it.
 */
fun apiBaseUrlConfigured(config: SynheartConfig): Boolean =
    resolveApiBaseUrl(config).isNotEmpty()

/** Build the runtime config object for [config], optionally rooted at [dataDir]. */
fun buildRuntimeConfigMap(config: SynheartConfig, dataDir: String? = null): JSONObject {
    val orgId = config.cloudConfig?.orgId.orEmpty()

    // Cloud ingest is only legal with a non-empty org id.
    val cloudReady = orgId.isNotEmpty()

    val deviceAuth = config.deviceAuthConfig
    val apiBaseUrl = resolveApiBaseUrl(config)

    return JSONObject().apply {
        put("app_id", config.appId)
        put("org_id", orgId)
        put("subject_id", config.subjectId)
        // The runtime derives the device identity's subject from client_id, and
        // rejects the config when device_auth is enabled without one.
        put("client_id", config.subjectId)

        // API gateway origin for consent / ingest / platform calls.
        //
        // Omitted rather than sent empty when no origin was configured. The SDK
        // carries no built-in host, so there is nothing to resolve to when
        // `SyncConfig.baseUrl` and the environment are both unset, and sending
        // `""` would assert an origin the host never chose.
        //
        // With the key absent the runtime applies its own default. That is fine
        // for a local-only build, which makes no network calls at all — but it
        // means a CLOUD build that forgets to set an origin inherits the
        // runtime's default rather than failing. Set it explicitly for anything
        // that talks to a non-default environment; see [apiBaseUrlConfigured].
        if (apiBaseUrl.isNotEmpty()) put("api_base_url", apiBaseUrl)

        put("mode", config.mode.name.lowercase())
        put("device_id", config.deviceId)
        put("app_version", config.appVersion)
        put("platform", config.platform)
        if (dataDir != null) put("data_dir", dataDir)

        put("storage", JSONObject().put("enabled", config.storage.enabled))
        put(
            "ingest",
            JSONObject().apply {
                put("enabled", cloudReady)
                put("hsi", cloudReady)
                put("lab", cloudReady)
            },
        )
        put(
            "device_auth",
            JSONObject().apply {
                put("enabled", deviceAuth != null)
                put("auth_base_url", deviceAuth?.authBaseUrl.orEmpty())
                put("package_name", deviceAuth?.packageName.orEmpty())
                put(
                    "allow_unattested_dev_registration",
                    deviceAuth?.allowUnattestedDevRegistration ?: false,
                )
            },
        )
        put(
            "sync",
            JSONObject().apply {
                put("enabled", config.sync.enabled)
                put("base_url", config.sync.baseUrl)
            },
        )
        put("privacy", JSONObject().put("allow_research", config.privacy.allowResearch))

        config.windowMs?.takeIf { it > 0 }?.let { put("window_ms", it) }
        if (config.extraHeads.isNotEmpty()) {
            put("extra_heads", JSONArray(config.extraHeads.map { it.wire }))
        }

        // Host declarations are spread rather than nested: the runtime reads
        // `sensing` / `device_class` / `mask_profile` / `cfi_structural_components`
        // as top-level keys. An absent key means *undeclared*, which is a
        // distinct state from a declared default — so nothing is emitted for a
        // null field.
        val declarations = config.hostDeclarations.toJson()
        for (key in declarations.keys()) put(key, declarations.get(key))
    }
}
