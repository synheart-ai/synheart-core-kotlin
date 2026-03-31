package ai.synheart.core.sync

import android.content.Context
import ai.synheart.core.modules.consent.ConsentModule
import ai.synheart.core.crypto.SMK
import ai.synheart.core.crypto.URK
import ai.synheart.core.storage.StorageManager
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * High-level sync orchestrator (RFC-CORE-0005).
 *
 * Uses ConsentModule for access tokens (scoped JWT from consent-service).
 * session_secret and subjectId are provided directly by the Synheart entry point.
 */
class SyncModule(
    private val consent: ConsentModule,
    private val storage: StorageManager,
    private val smk: SMK?,
    private val context: Context,
    baseUrl: String,
    private val subjectId: String? = null,
    private val sessionSecret: String? = null
) {
    private val baseUrl: String = baseUrl
    private val engine = SyncEngine(storage, baseUrl)
    private var urk: URK? = null
    var enabled: Boolean = false
        private set
    var syncReady: Boolean = false
        private set

    /** Get the current access token from ConsentModule. */
    private val accessToken: String?
        get() = consent.getCurrentToken()?.token

    /** Check if we have a valid consent token. */
    private val hasValidToken: Boolean
        get() = consent.getCurrentToken() != null

    /** Enable or disable sync. */
    fun setSyncEnabled(enabled: Boolean) {
        this.enabled = enabled
        storage.setSyncState("sync_enabled", if (enabled) "true" else "false")

        if (enabled && urk == null && hasValidToken) {
            provisionURK()
        }
    }

    /** Execute a sync cycle (push + pull). */
    fun syncNow(): SyncResult {
        if (!enabled || !hasValidToken) return SyncResult()

        engine.accessToken = accessToken

        if (urk == null) {
            urk = URK.unwrap(context)
            if (urk == null) provisionURK()
        }

        val currentUrk = urk ?: return SyncResult(errors = listOf("URK unavailable"))
        val currentSmk = smk ?: return SyncResult(errors = listOf("SMK unavailable"))

        val errors = mutableListOf<String>()
        var pushed = 0
        var pulled = 0
        val maxRetries = 3
        val backoffMs = longArrayOf(1000, 2000, 4000)
        var authRefreshed = false

        // Push with retry
        for (attempt in 0 until maxRetries) {
            try {
                pushed = engine.push(currentUrk.bytes)
                break
            } catch (e: Exception) {
                val isAuthError = e.message?.contains("401") == true ||
                    e.message?.contains("auth", ignoreCase = true) == true
                if (isAuthError && !authRefreshed) {
                    try {
                        kotlinx.coroutines.runBlocking { consent.refreshTokenIfNeeded() }
                        engine.accessToken = accessToken
                        authRefreshed = true
                    } catch (_: Exception) {}
                }
                if (attempt == maxRetries - 1) {
                    errors.add("Push failed: $e")
                } else {
                    try { Thread.sleep(backoffMs[attempt]) } catch (_: InterruptedException) {}
                }
            }
        }

        // Pull with retry
        authRefreshed = false
        for (attempt in 0 until maxRetries) {
            try {
                val cursor = storage.getSyncState("cursor")
                pulled = engine.pull(
                    urk = currentUrk.bytes,
                    smk = currentSmk,
                    subjectId = subjectId ?: "",
                    cursor = cursor
                )
                break
            } catch (e: Exception) {
                val isAuthError = e.message?.contains("401") == true ||
                    e.message?.contains("auth", ignoreCase = true) == true
                if (isAuthError && !authRefreshed) {
                    try {
                        kotlinx.coroutines.runBlocking { consent.refreshTokenIfNeeded() }
                        engine.accessToken = accessToken
                        authRefreshed = true
                    } catch (_: Exception) {}
                }
                if (attempt == maxRetries - 1) {
                    errors.add("Pull failed: $e")
                } else {
                    try { Thread.sleep(backoffMs[attempt]) } catch (_: InterruptedException) {}
                }
            }
        }

        if (errors.isEmpty()) {
            storage.setSyncState("last_sync_ms", System.currentTimeMillis().toString())
        }

        return SyncResult(pushed = pushed, pulled = pulled, errors = errors)
    }

    /** Get current sync status. */
    fun getStatus(): SyncStatus {
        val cursor = storage.getSyncState("cursor")
        val lastSyncStr = storage.getSyncState("last_sync_ms")
        val pendingCount = storage.getUnsyncedCount()

        return SyncStatus(
            enabled = enabled,
            lastSuccessMs = lastSyncStr?.toLongOrNull(),
            pendingUploadCount = pendingCount,
            cursor = cursor
        )
    }

    private fun provisionURK() {
        val secret = sessionSecret ?: return
        val subject = subjectId ?: return
        val token = accessToken ?: return

        // 1. Try to fetch existing URK bundle from server
        try {
            val conn = URL("$baseUrl/sync/v1/urk-bundle").openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")

            if (conn.responseCode == 200) {
                val body = JSONObject(conn.inputStream.bufferedReader().readText())
                val bundle = mapOf(
                    "urk_bundle_version" to body.getString("urk_bundle_version"),
                    "ciphertext_b64" to body.getString("ciphertext_b64"),
                    "nonce_b64" to body.getString("nonce_b64"),
                    "kdf_info" to body.getString("kdf_info")
                )
                val restored = URK.decryptBundle(bundle, secret, subject)
                restored.wrapAndStore(context)
                this.urk = restored
                syncReady = true
                return
            }
        } catch (_: Exception) {
            // Fall through to generate new URK
        }

        // 2. No existing bundle — generate new URK and upload
        val newUrk = URK.generate()
        try {
            val bundle = URK.encryptBundle(newUrk.bytes, secret, subject)
            val conn = URL("$baseUrl/sync/v1/urk-bundle").openConnection() as HttpURLConnection
            conn.requestMethod = "PUT"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.doOutput = true

            val payload = JSONObject()
            for ((k, v) in bundle) { payload.put(k, v) }
            OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }
            conn.responseCode // trigger the request
        } catch (_: Exception) {
            // Upload failed; still store locally
        }

        newUrk.wrapAndStore(context)
        this.urk = newUrk
        syncReady = true
    }

    fun dispose() {
        urk = null
        enabled = false
    }
}
