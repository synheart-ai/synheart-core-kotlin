package ai.synheart.core.sync

import java.util.Base64 as JBase64
import ai.synheart.core.crypto.ArtifactCrypto
import ai.synheart.core.crypto.SMK
import ai.synheart.core.crypto.URK
import ai.synheart.core.storage.ArtifactRecord
import ai.synheart.core.storage.StorageManager
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** Core sync engine — handles push/pull with E2EE (RFC-CORE-0005). */
class SyncEngine(
    private val storage: StorageManager,
    private val baseUrl: String
) {
    var accessToken: String? = null

    /** Encrypt a local artifact for sync upload. */
    fun encryptForSync(urk: ByteArray, record: ArtifactRecord): ArtifactEnvelope {
        val artKey = URK.deriveArtifactKey(urk, record.artifactId)
        val payload = record.payload

        val sha256Hex = sha256(payload)

        // AES-256-GCM encrypt
        val iv = ByteArray(12)
        java.security.SecureRandom().nextBytes(iv)
        val key = SecretKeySpec(artKey, "AES")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val encrypted = cipher.doFinal(payload)

        return ArtifactEnvelope(
            artifactId = record.artifactId,
            subjectId = record.subjectId,
            sessionId = record.sessionId,
            type = record.type,
            startMs = record.startMs,
            endMs = record.endMs,
            seq = record.seq,
            schemaName = record.schemaName,
            schemaVersion = record.schemaVersion,
            nonceB64 = JBase64.getEncoder().encodeToString(iv),
            payloadSha256 = sha256Hex,
            ciphertextB64 = JBase64.getEncoder().encodeToString(encrypted)
        )
    }

    /** Push unsynced artifacts to the server. */
    fun push(urk: ByteArray): Int {
        val token = accessToken ?: return 0

        val artifacts = storage.getUnsyncedArtifacts(limit = 50)
        if (artifacts.isEmpty()) return 0

        val envelopesArray = JSONArray()
        for (art in artifacts) {
            val env = encryptForSync(urk, art)
            envelopesArray.put(env.toJson())
        }

        val conn = URL("$baseUrl/sync/v1/push").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.doOutput = true

        val body = JSONObject().apply { put("envelopes", envelopesArray) }
        OutputStreamWriter(conn.outputStream).use { it.write(body.toString()) }

        if (conn.responseCode == 200) {
            for (art in artifacts) {
                storage.markSynced(art.artifactId)
            }
            return artifacts.size
        }
        return 0
    }

    /** Pull new artifacts from the server. */
    fun pull(urk: ByteArray, smk: SMK, subjectId: String, cursor: String?): Int {
        val token = accessToken ?: return 0
        var totalPulled = 0
        var currentCursor = cursor

        while (true) {
            val urlStr = buildString {
                append("$baseUrl/sync/v1/pull?subject_id=$subjectId&limit=100")
                currentCursor?.let { append("&cursor=$it") }
            }

            val conn = URL(urlStr).openConnection() as HttpURLConnection
            conn.setRequestProperty("Authorization", "Bearer $token")

            if (conn.responseCode != 200) break

            val responseBody = JSONObject(conn.inputStream.bufferedReader().readText())
            val envelopes = responseBody.getJSONArray("envelopes")

            for (i in 0 until envelopes.length()) {
                val envJson = envelopes.getJSONObject(i)
                val env = ArtifactEnvelope.fromJson(envJson)

                if (storage.isDeleted(env.artifactId)) continue
                if (storage.getArtifact(env.artifactId) != null) continue

                // Decrypt
                val artKey = URK.deriveArtifactKey(urk, env.artifactId)
                val ciphertext = JBase64.getDecoder().decode(env.ciphertextB64)
                val iv = JBase64.getDecoder().decode(env.nonceB64)

                val key = SecretKeySpec(artKey, "AES")
                val cipher = Cipher.getInstance("AES/GCM/NoPadding")
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))

                val plaintext: ByteArray
                try {
                    plaintext = cipher.doFinal(ciphertext)
                } catch (_: Exception) {
                    continue
                }

                // Verify integrity
                if (sha256(plaintext) != env.payloadSha256) continue

                // Merge conflict check: if a local artifact with the same merge key
                // exists and has a lexicographically smaller artifact_id, skip incoming
                if (env.sessionId != null) {
                    val existing = storage.findByMergeKey(
                        sessionId = env.sessionId,
                        type = env.type,
                        startMs = env.startMs,
                        schemaVersion = env.schemaVersion
                    )
                    if (existing != null && existing.artifactId < env.artifactId) continue
                }

                // Re-encrypt with SMK and store
                val localEncrypted = ArtifactCrypto.encrypt(smk, plaintext)
                storage.insertArtifact(ArtifactRecord(
                    artifactId = env.artifactId,
                    sessionId = env.sessionId,
                    subjectId = env.subjectId,
                    type = env.type,
                    schemaName = env.schemaName,
                    schemaVersion = env.schemaVersion,
                    startMs = env.startMs,
                    endMs = env.endMs,
                    seq = env.seq,
                    createdAtMs = System.currentTimeMillis(),
                    encAlg = ArtifactCrypto.ENC_ALG,
                    payload = localEncrypted.ciphertext,
                    payloadSha256 = env.payloadSha256
                ))
                totalPulled++
            }

            currentCursor = if (responseBody.has("next_cursor")) responseBody.getString("next_cursor") else null
            val hasMore = responseBody.optBoolean("has_more", false)
            if (!hasMore) break
        }

        if (currentCursor != null) {
            storage.setSyncState("cursor", currentCursor)
        }

        return totalPulled
    }

    private fun sha256(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(data).joinToString("") { "%02x".format(it) }
    }
}
