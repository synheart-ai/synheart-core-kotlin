package ai.synheart.core.auth

import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Timer
import java.util.TimerTask

/** Authentication status (RFC-CORE-0008). */
data class AuthStatus(
    val authenticated: Boolean,
    val subjectId: String? = null,
    val provider: String? = null,
    val syncReady: Boolean = false
) {
    companion object {
        val UNAUTHENTICATED = AuthStatus(authenticated = false)
    }
}

/** Result of an authentication attempt. */
data class AuthResult(
    val subjectId: String,
    val accessToken: String? = null,
    val refreshToken: String? = null,
    val sessionSecret: String? = null,
    val syncReady: Boolean = false
)

class AuthError(message: String) : Exception(message)

/**
 * Manages authentication and token lifecycle (RFC-CORE-0008).
 */
class AuthModule(
    private val appId: String,
    private val baseUrl: String = "https://api.synheart.ai",
    private val tokenStorage: TokenStorage
) {
    companion object {
        private const val DEFAULT_EXPIRY_SECONDS = 3600L
    }

    var status: AuthStatus = AuthStatus.UNAUTHENTICATED
        private set
    var accessToken: String? = null
        private set
    var sessionSecret: String? = null
        private set
    private var refreshTimer: Timer? = null

    val subjectId: String? get() = status.subjectId
    val isAuthenticated: Boolean get() = status.authenticated

    /** Anonymous auth — generates a local subject_id, no sync capability. */
    fun authenticateAnonymous(): AuthResult {
        val id = "anon_${java.util.UUID.randomUUID().toString().replace("-", "").take(16)}"
        tokenStorage.saveSubjectId(id)

        status = AuthStatus(authenticated = false, subjectId = id, provider = "anonymous", syncReady = false)
        return AuthResult(subjectId = id, syncReady = false)
    }

    /** Token-based auth — exchanges a provider token for Synheart credentials. */
    fun authenticate(provider: String, token: String): AuthResult {
        val conn = URL("$baseUrl/auth/v1/exchange").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val payload = JSONObject().apply {
            put("provider", provider)
            put("token", token)
            put("app_id", appId)
        }
        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

        if (conn.responseCode != 200) {
            throw AuthError("Authentication failed: ${conn.responseCode}")
        }

        val body = JSONObject(conn.inputStream.bufferedReader().readText())
        val sid = body.getString("subject_id")
        val at = body.getString("access_token")
        val rt = body.getString("refresh_token")
        val ss = body.optString("session_secret", null)

        accessToken = at
        sessionSecret = ss

        tokenStorage.saveRefreshToken(rt)
        tokenStorage.saveSubjectId(sid)
        if (ss != null) tokenStorage.saveSessionSecret(ss)

        status = AuthStatus(authenticated = true, subjectId = sid, provider = provider, syncReady = false)

        val expiresIn = if (body.has("expires_in")) body.getLong("expires_in") else DEFAULT_EXPIRY_SECONDS
        scheduleRefresh(expiresIn)

        return AuthResult(subjectId = sid, accessToken = at, refreshToken = rt, sessionSecret = ss, syncReady = false)
    }

    /** Refresh the access token. */
    fun refreshToken() {
        val rt = tokenStorage.loadRefreshToken()
            ?: throw AuthError("No refresh token available")

        val conn = URL("$baseUrl/auth/v1/refresh").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.doOutput = true

        val payload = JSONObject().apply { put("refresh_token", rt) }
        OutputStreamWriter(conn.outputStream).use { it.write(payload.toString()) }

        if (conn.responseCode != 200) {
            status = AuthStatus(
                authenticated = false,
                subjectId = status.subjectId,
                provider = status.provider,
                syncReady = false
            )
            throw AuthError("ERR_AUTH_EXPIRED")
        }

        val body = JSONObject(conn.inputStream.bufferedReader().readText())
        accessToken = body.getString("access_token")
        body.optString("refresh_token", null)?.let { tokenStorage.saveRefreshToken(it) }

        val expiresIn = if (body.has("expires_in")) body.getLong("expires_in") else DEFAULT_EXPIRY_SECONDS
        scheduleRefresh(expiresIn)
    }

    /** Schedule a proactive token refresh at 80% of the token lifetime. */
    private fun scheduleRefresh(expiresInSeconds: Long) {
        refreshTimer?.cancel()
        val delayMillis = (expiresInSeconds * 800) // 80% in milliseconds
        refreshTimer = Timer("auth-refresh", true).apply {
            schedule(object : TimerTask() {
                override fun run() {
                    try {
                        refreshToken()
                    } catch (_: Exception) {
                        // Silent failure — token refresh will be retried on next API call.
                    }
                }
            }, delayMillis)
        }
    }

    /** Mark sync as ready (called after URK provisioning). */
    fun markSyncReady() {
        status = status.copy(syncReady = true)
    }

    /** Log out — clear tokens and reset state. */
    fun logout() {
        refreshTimer?.cancel()
        refreshTimer = null
        accessToken = null
        sessionSecret = null
        tokenStorage.clearAll()
        status = AuthStatus.UNAUTHENTICATED
    }

    /** Restore auth state from stored tokens. */
    fun restoreSession(): Boolean {
        val sid = tokenStorage.loadSubjectId() ?: return false

        val rt = tokenStorage.loadRefreshToken()
        sessionSecret = tokenStorage.loadSessionSecret()

        if (rt != null) {
            status = AuthStatus(authenticated = true, subjectId = sid, provider = "restored", syncReady = false)
            return try {
                refreshToken()
                true
            } catch (_: Exception) {
                false
            }
        }

        status = AuthStatus(authenticated = false, subjectId = sid, provider = "anonymous", syncReady = false)
        return true
    }
}
