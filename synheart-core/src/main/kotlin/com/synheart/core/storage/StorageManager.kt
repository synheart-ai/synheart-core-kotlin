package com.synheart.core.storage

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.content.ContentValues
import android.util.Log
import com.synheart.core.models.MetricEvent
import com.synheart.core.models.StorageUsage
import org.json.JSONObject

/** Record representing a session row in the catalog. */
data class SessionRecord(
    val sessionId: String,
    val subjectId: String,
    val mode: String,
    val createdAtUtc: Long,
    val startUtc: Long,
    val endUtc: Long? = null,
    val appId: String,
    val appVersion: String,
    val deviceId: String,
    val platform: String,
    val state: String = "active",
    val syncedState: String = "disabled"
)

/** Record representing an artifact row in the catalog. */
data class ArtifactRecord(
    val artifactId: String,
    val sessionId: String? = null,
    val subjectId: String,
    val type: String,
    val schemaName: String,
    val schemaVersion: String,
    val startMs: Long,
    val endMs: Long,
    val seq: Int? = null,
    val createdAtMs: Long,
    val encAlg: String,
    val payload: ByteArray,
    val payloadSha256: String,
    val syncState: String = "pending"
)

/**
 * SQLite-based artifact and session storage (RFC-CORE-0004).
 *
 * Uses Android's SQLiteOpenHelper for lifecycle management.
 */
class StorageManager private constructor(
    private val context: Context,
    private val helper: DbHelper
) {
    private var db: SQLiteDatabase? = null

    val isOpen: Boolean get() = db != null && db!!.isOpen

    /** Open the database for writing. Attempts recovery if the database is corrupt. */
    fun open() {
        try {
            db = helper.writableDatabase
        } catch (e: Exception) {
            Log.e("StorageManager", "Database open failed, attempting recovery: $e")
            try {
                // Delete the corrupt database file and recreate
                helper.close()
                context.deleteDatabase(helper.databaseName)
                db = helper.writableDatabase
                Log.i("StorageManager", "Database recovered successfully after deletion")
            } catch (retryEx: Exception) {
                Log.e("StorageManager", "Database recovery failed: $retryEx")
                throw retryEx
            }
        }
    }

    /** Close the database. */
    fun close() {
        db?.close()
        db = null
    }

    // MARK: - Sessions

    fun insertSession(record: SessionRecord) {
        val cv = ContentValues().apply {
            put("session_id", record.sessionId)
            put("subject_id", record.subjectId)
            put("mode", record.mode)
            put("created_at_utc", record.createdAtUtc)
            put("start_utc", record.startUtc)
            record.endUtc?.let { put("end_utc", it) }
            put("app_id", record.appId)
            put("app_version", record.appVersion)
            put("device_id", record.deviceId)
            put("platform", record.platform)
            put("state", record.state)
            put("synced_state", record.syncedState)
        }
        db!!.insertOrThrow("sessions", null, cv)
    }

    fun updateSession(sessionId: String, state: String, endUtc: Long) {
        val cv = ContentValues().apply {
            put("state", state)
            put("end_utc", endUtc)
        }
        db!!.update("sessions", cv, "session_id = ?", arrayOf(sessionId))
    }

    fun listSessions(subjectId: String): List<SessionRecord> {
        val cursor = db!!.rawQuery(
            "SELECT * FROM sessions WHERE subject_id = ? ORDER BY created_at_utc DESC",
            arrayOf(subjectId)
        )
        val results = mutableListOf<SessionRecord>()
        cursor.use {
            while (it.moveToNext()) {
                results.add(SessionRecord(
                    sessionId = it.getString(it.getColumnIndexOrThrow("session_id")),
                    subjectId = it.getString(it.getColumnIndexOrThrow("subject_id")),
                    mode = it.getString(it.getColumnIndexOrThrow("mode")),
                    createdAtUtc = it.getLong(it.getColumnIndexOrThrow("created_at_utc")),
                    startUtc = it.getLong(it.getColumnIndexOrThrow("start_utc")),
                    endUtc = if (it.isNull(it.getColumnIndexOrThrow("end_utc"))) null
                             else it.getLong(it.getColumnIndexOrThrow("end_utc")),
                    appId = it.getString(it.getColumnIndexOrThrow("app_id")),
                    appVersion = it.getString(it.getColumnIndexOrThrow("app_version")),
                    deviceId = it.getString(it.getColumnIndexOrThrow("device_id")),
                    platform = it.getString(it.getColumnIndexOrThrow("platform")),
                    state = it.getString(it.getColumnIndexOrThrow("state")),
                    syncedState = it.getString(it.getColumnIndexOrThrow("synced_state"))
                ))
            }
        }
        return results
    }

    fun deleteSession(sessionId: String) {
        db!!.delete("artifacts", "session_id = ?", arrayOf(sessionId))
        db!!.delete("summaries", "session_id = ?", arrayOf(sessionId))
        db!!.delete("sessions", "session_id = ?", arrayOf(sessionId))
    }

    // MARK: - Artifacts

    fun insertArtifact(record: ArtifactRecord) {
        val cv = ContentValues().apply {
            put("artifact_id", record.artifactId)
            put("session_id", record.sessionId)
            put("subject_id", record.subjectId)
            put("type", record.type)
            put("schema_name", record.schemaName)
            put("schema_version", record.schemaVersion)
            put("start_ms", record.startMs)
            put("end_ms", record.endMs)
            record.seq?.let { put("seq", it) }
            put("created_at_ms", record.createdAtMs)
            put("enc_alg", record.encAlg)
            put("payload", record.payload)
            put("payload_sha256", record.payloadSha256)
            put("sync_state", record.syncState)
        }
        db!!.insertOrThrow("artifacts", null, cv)
    }

    fun getArtifact(artifactId: String): ArtifactRecord? {
        val cursor = db!!.rawQuery(
            "SELECT * FROM artifacts WHERE artifact_id = ?", arrayOf(artifactId)
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return cursorToArtifact(it)
        }
    }

    fun getArtifactsBySession(sessionId: String, type: String): List<ArtifactRecord> {
        val cursor = db!!.rawQuery(
            "SELECT * FROM artifacts WHERE session_id = ? AND type = ? ORDER BY seq ASC",
            arrayOf(sessionId, type)
        )
        return cursorToArtifactList(cursor)
    }

    fun getArtifactsByTimeRange(
        subjectId: String, type: String, fromMs: Long, toMs: Long
    ): List<ArtifactRecord> {
        val cursor = db!!.rawQuery(
            "SELECT * FROM artifacts WHERE subject_id = ? AND type = ? AND start_ms >= ? AND end_ms <= ? ORDER BY start_ms ASC",
            arrayOf(subjectId, type, fromMs.toString(), toMs.toString())
        )
        return cursorToArtifactList(cursor)
    }

    /** Find an artifact matching the merge key (session_id, type, start_ms, schema_version). */
    fun findByMergeKey(sessionId: String, type: String, startMs: Long, schemaVersion: String): ArtifactRecord? {
        val cursor = db!!.rawQuery(
            "SELECT * FROM artifacts WHERE session_id = ? AND type = ? AND start_ms = ? AND schema_version = ? LIMIT 1",
            arrayOf(sessionId, type, startMs.toString(), schemaVersion)
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return cursorToArtifact(it)
        }
    }

    // MARK: - Tombstones

    fun insertTombstone(artifactId: String, deletedAtMs: Long, reason: String) {
        val cv = ContentValues().apply {
            put("artifact_id", artifactId)
            put("deleted_at_ms", deletedAtMs)
            put("reason", reason)
        }
        db!!.insertOrThrow("tombstones", null, cv)
    }

    fun isDeleted(artifactId: String): Boolean {
        val cursor = db!!.rawQuery(
            "SELECT 1 FROM tombstones WHERE artifact_id = ? LIMIT 1", arrayOf(artifactId)
        )
        cursor.use { return it.moveToFirst() }
    }

    // MARK: - Summary cache

    fun insertSummaryCache(sessionId: String, artifactId: String, summaryJson: String) {
        val cv = ContentValues().apply {
            put("session_id", sessionId)
            put("artifact_id", artifactId)
            put("summary_json", summaryJson)
        }
        db!!.insertOrThrow("summaries", null, cv)
    }

    fun getSummaryJson(sessionId: String): String? {
        val cursor = db!!.rawQuery(
            "SELECT summary_json FROM summaries WHERE session_id = ? LIMIT 1",
            arrayOf(sessionId)
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return it.getString(0)
        }
    }

    // MARK: - Metrics

    fun insertMetric(sessionId: String, event: MetricEvent) {
        val valueJson = when (val v = event.value) {
            is Number -> v.toString()
            is Boolean -> v.toString()
            is String -> JSONObject.quote(v)
            else -> "null"
        }
        val cv = ContentValues().apply {
            put("session_id", sessionId)
            put("name", event.name)
            put("timestamp_ms", event.timestampMs)
            put("value", valueJson)
            event.tags?.let { put("tags", JSONObject(it as Map<*, *>).toString()) }
        }
        db!!.insertOrThrow("metrics", null, cv)
    }

    fun aggregateMetrics(sessionId: String): List<Map<String, Any>> {
        val cursor = db!!.rawQuery(
            "SELECT name, COUNT(*) as cnt, AVG(CAST(value AS REAL)) as avg_val FROM metrics WHERE session_id = ? GROUP BY name",
            arrayOf(sessionId)
        )
        val results = mutableListOf<Map<String, Any>>()
        cursor.use {
            while (it.moveToNext()) {
                results.add(mapOf(
                    "name" to it.getString(0),
                    "count" to it.getInt(1),
                    "mean" to it.getDouble(2)
                ))
            }
        }
        return results
    }

    // MARK: - Storage Usage

    fun getStorageUsage(): StorageUsage {
        val cursor = db!!.rawQuery(
            "SELECT session_id, SUM(LENGTH(payload)) as bytes FROM artifacts GROUP BY session_id",
            null
        )
        var total = 0L
        val bySession = mutableMapOf<String, Long>()
        cursor.use {
            while (it.moveToNext()) {
                val sid = if (it.isNull(0)) "~" else it.getString(0)
                val bytes = it.getLong(1)
                bySession[sid] = bytes
                total += bytes
            }
        }
        return StorageUsage(totalBytes = total, bySessionBytes = bySession)
    }

    // MARK: - Retention

    fun enforceRetention(cutoffMs: Long): Int {
        val cutoffUtc = cutoffMs / 1000
        val cursor = db!!.rawQuery(
            "SELECT session_id FROM sessions WHERE start_utc < ? AND state != 'deleted'",
            arrayOf(cutoffUtc.toString())
        )
        val sessionIds = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                sessionIds.add(it.getString(0))
            }
        }
        for (sid in sessionIds) {
            deleteSession(sid, createTombstones = true)
        }
        return sessionIds.size
    }

    // MARK: - Deletion

    fun deleteSession(sessionId: String, createTombstones: Boolean = false) {
        if (createTombstones) {
            val cursor = db!!.rawQuery(
                "SELECT artifact_id FROM artifacts WHERE session_id = ?",
                arrayOf(sessionId)
            )
            val nowMs = System.currentTimeMillis()
            cursor.use {
                while (it.moveToNext()) {
                    val aid = it.getString(0)
                    val tombId = "tombstone_$aid"
                    insertTombstone(tombId, nowMs, "session_deleted")
                    // Insert tombstone as a syncable artifact so it gets pushed during sync
                    val payloadStr = """{"target":"$aid","reason":"session_deleted"}"""
                    val cv = ContentValues().apply {
                        put("artifact_id", tombId)
                        put("session_id", sessionId)
                        put("subject_id", "")
                        put("type", "tombstone")
                        put("schema_name", "tombstone")
                        put("schema_version", "1.0")
                        put("start_ms", nowMs)
                        put("end_ms", nowMs)
                        put("created_at_ms", nowMs)
                        put("enc_alg", "none")
                        put("payload", payloadStr.toByteArray(Charsets.UTF_8))
                        put("payload_sha256", "")
                        put("sync_state", "pending")
                    }
                    db!!.insertWithOnConflict("artifacts", null, cv, SQLiteDatabase.CONFLICT_IGNORE)
                }
            }
        }
        db!!.delete("metrics", "session_id = ?", arrayOf(sessionId))
        db!!.delete("summaries", "session_id = ?", arrayOf(sessionId))
        db!!.delete("artifacts", "session_id = ? AND type != 'tombstone'", arrayOf(sessionId))
        val cv = ContentValues().apply { put("state", "deleted") }
        db!!.update("sessions", cv, "session_id = ?", arrayOf(sessionId))
    }

    // MARK: - Sync State

    fun setSyncState(key: String, value: String) {
        val cv = ContentValues().apply {
            put("key", key)
            put("value", value)
        }
        db!!.insertWithOnConflict("sync_state", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun getSyncState(key: String): String? {
        val cursor = db!!.rawQuery(
            "SELECT value FROM sync_state WHERE key = ?", arrayOf(key)
        )
        cursor.use {
            if (!it.moveToFirst()) return null
            return it.getString(0)
        }
    }

    fun markSynced(artifactId: String) {
        val cv = ContentValues().apply { put("sync_state", "synced") }
        db!!.update("artifacts", cv, "artifact_id = ?", arrayOf(artifactId))
    }

    fun getUnsyncedArtifacts(limit: Int = 50): List<ArtifactRecord> {
        val cursor = db!!.rawQuery(
            "SELECT * FROM artifacts WHERE sync_state = 'pending' ORDER BY created_at_ms ASC LIMIT ?",
            arrayOf(limit.toString())
        )
        return cursorToArtifactList(cursor)
    }

    fun getUnsyncedCount(): Int {
        val cursor = db!!.rawQuery(
            "SELECT COUNT(*) FROM artifacts WHERE sync_state = 'pending'", null
        )
        cursor.use {
            if (!it.moveToFirst()) return 0
            return it.getInt(0)
        }
    }

    // MARK: - Wipe

    fun wipeAll() {
        db!!.execSQL("DELETE FROM metrics")
        db!!.execSQL("DELETE FROM tombstones")
        db!!.execSQL("DELETE FROM summaries")
        db!!.execSQL("DELETE FROM artifacts")
        db!!.execSQL("DELETE FROM sessions")
        db!!.execSQL("DELETE FROM sync_state")
    }

    // MARK: - Private helpers

    private fun cursorToArtifact(c: android.database.Cursor): ArtifactRecord {
        val seqIdx = c.getColumnIndexOrThrow("seq")
        return ArtifactRecord(
            artifactId = c.getString(c.getColumnIndexOrThrow("artifact_id")),
            sessionId = if (c.isNull(c.getColumnIndexOrThrow("session_id"))) null
                        else c.getString(c.getColumnIndexOrThrow("session_id")),
            subjectId = c.getString(c.getColumnIndexOrThrow("subject_id")),
            type = c.getString(c.getColumnIndexOrThrow("type")),
            schemaName = c.getString(c.getColumnIndexOrThrow("schema_name")),
            schemaVersion = c.getString(c.getColumnIndexOrThrow("schema_version")),
            startMs = c.getLong(c.getColumnIndexOrThrow("start_ms")),
            endMs = c.getLong(c.getColumnIndexOrThrow("end_ms")),
            seq = if (c.isNull(seqIdx)) null else c.getInt(seqIdx),
            createdAtMs = c.getLong(c.getColumnIndexOrThrow("created_at_ms")),
            encAlg = c.getString(c.getColumnIndexOrThrow("enc_alg")),
            payload = c.getBlob(c.getColumnIndexOrThrow("payload")),
            payloadSha256 = c.getString(c.getColumnIndexOrThrow("payload_sha256"))
        )
    }

    private fun cursorToArtifactList(cursor: android.database.Cursor): List<ArtifactRecord> {
        val results = mutableListOf<ArtifactRecord>()
        cursor.use {
            while (it.moveToNext()) {
                results.add(cursorToArtifact(it))
            }
        }
        return results
    }

    companion object {
        private const val DB_NAME = "synheart_artifacts.db"
        private const val DB_VERSION = 1

        /** Create a StorageManager backed by the app's databases directory. */
        fun create(context: Context): StorageManager {
            return StorageManager(context, DbHelper(context, DB_NAME))
        }

        /** Create a StorageManager with a custom DB name (for testing). */
        fun createWithName(context: Context, dbName: String): StorageManager {
            return StorageManager(context, DbHelper(context, dbName))
        }
    }

    private class DbHelper(context: Context, name: String) :
        SQLiteOpenHelper(context, name, null, DB_VERSION) {

        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("PRAGMA journal_mode=WAL")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS sessions (
                    session_id TEXT PRIMARY KEY,
                    subject_id TEXT NOT NULL,
                    mode TEXT NOT NULL,
                    created_at_utc INTEGER NOT NULL,
                    start_utc INTEGER NOT NULL,
                    end_utc INTEGER,
                    app_id TEXT NOT NULL DEFAULT '',
                    app_version TEXT NOT NULL DEFAULT '0.0.0',
                    device_id TEXT NOT NULL DEFAULT '',
                    platform TEXT NOT NULL DEFAULT 'android',
                    state TEXT NOT NULL DEFAULT 'active',
                    synced_state TEXT NOT NULL DEFAULT 'disabled'
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS artifacts (
                    artifact_id TEXT PRIMARY KEY,
                    session_id TEXT,
                    subject_id TEXT NOT NULL,
                    type TEXT NOT NULL,
                    schema_name TEXT NOT NULL,
                    schema_version TEXT NOT NULL,
                    start_ms INTEGER NOT NULL,
                    end_ms INTEGER NOT NULL,
                    seq INTEGER,
                    created_at_ms INTEGER NOT NULL,
                    enc_alg TEXT NOT NULL,
                    payload BLOB NOT NULL,
                    payload_sha256 TEXT NOT NULL,
                    sync_state TEXT NOT NULL DEFAULT 'pending'
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS summaries (
                    session_id TEXT PRIMARY KEY,
                    artifact_id TEXT NOT NULL,
                    summary_json TEXT NOT NULL
                )
            """)

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS tombstones (
                    artifact_id TEXT PRIMARY KEY,
                    deleted_at_ms INTEGER NOT NULL,
                    reason TEXT NOT NULL DEFAULT ''
                )
            """)

            db.execSQL("CREATE INDEX IF NOT EXISTS idx_artifacts_session ON artifacts(session_id, type)")
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_artifacts_time ON artifacts(subject_id, type, start_ms, end_ms)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS metrics (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    session_id TEXT NOT NULL,
                    name TEXT NOT NULL,
                    timestamp_ms INTEGER NOT NULL,
                    value TEXT NOT NULL,
                    tags TEXT,
                    FOREIGN KEY(session_id) REFERENCES sessions(session_id)
                )
            """)
            db.execSQL("CREATE INDEX IF NOT EXISTS idx_metrics_session ON metrics(session_id)")

            db.execSQL("""
                CREATE TABLE IF NOT EXISTS sync_state (
                    key TEXT PRIMARY KEY,
                    value TEXT NOT NULL
                )
            """)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // Future migrations go here
        }
    }
}
