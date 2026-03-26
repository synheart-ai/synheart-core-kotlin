package ai.synheart.core.modules.platform_ingest

import ai.synheart.core.SynheartLogger
import ai.synheart.core.config.PlatformIngestConfig
import ai.synheart.core.modules.base.BaseSynheartModule
import ai.synheart.core.modules.consent.ConsentModule

/**
 * Module for custom platform session and metadata ingestion.
 *
 * Wraps [PlatformIngestClient] with consent gating via [ConsentModule].
 * Uploads are on-demand (not streaming), so [onStart]/[onStop] are no-ops.
 */
class PlatformIngestModule(
    private val consentModule: ConsentModule,
    private val config: PlatformIngestConfig
) : BaseSynheartModule("platform_ingest") {

    private lateinit var _client: PlatformIngestClient

    /** The underlying client — exposed for standalone/background usage. */
    val client: PlatformIngestClient
        get() = _client

    override suspend fun onInitialize() {
        _client = PlatformIngestClient(
            baseUrl = config.baseUrl,
            timeoutMs = config.timeoutMs,
            maxRetries = config.maxRetries
        )
    }

    override suspend fun onStart() {
        // On-demand uploads — nothing to start.
    }

    override suspend fun onStop() {
        // On-demand uploads — nothing to stop.
    }

    override suspend fun onDispose() {
        _client.dispose()
    }

    /**
     * Ingest a session payload. Requires `behavior` consent.
     */
    suspend fun ingestSession(payload: Map<String, Any?>): PlatformIngestResponse {
        val consent = consentModule.current()
        if (!consent.behavior) {
            return PlatformIngestResponse(
                success = false,
                statusCode = 0,
                errorMessage = "Behavior consent not granted"
            )
        }

        return _client.ingestSession(
            payload = payload,
            hmacSecret = config.hmacSecret ?: "",
            apiKey = config.apiKey ?: ""
        )
    }

    /**
     * Ingest a metadata payload. Requires `biosignals` consent.
     */
    suspend fun ingestMetadata(payload: Map<String, Any?>): PlatformIngestResponse {
        val consent = consentModule.current()
        if (!consent.biosignals) {
            return PlatformIngestResponse(
                success = false,
                statusCode = 0,
                errorMessage = "Biosignals consent not granted"
            )
        }

        return _client.ingestMetadata(
            payload = payload,
            hmacSecret = config.hmacSecret ?: "",
            apiKey = config.apiKey ?: ""
        )
    }
}
