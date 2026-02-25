package com.synheart.core.modules.srm

import com.synheart.core.modules.base.BaseSynheartModule
import java.time.Instant
import com.synheart.core.SynheartLogger

/// SRM (Synheart Reference Model) module.
///
/// Maintains per-stratum bounded buffers of quality-gated candidate windows
/// and computes robust reference statistics (median/MAD) for each tracked
/// metric. This enables baseline-relative scoring when status reaches READY.
///
/// Implements RFC-CORE-0006 §3.3 and SRM.pdf.
class SRMModule(
    private val config: SRMConfig = SRMConfig.DEFAULTS,
    private val storage: SRMSnapshotStorage? = null
) : BaseSynheartModule("srm") {

    /// Per-stratum buffers.
    private val buffers = mutableMapOf<SRMStratum, SRMBuffer>()

    // ---------------------------------------------------------------------------
    // Module lifecycle
    // ---------------------------------------------------------------------------

    override suspend fun onInitialize() {
        SynheartLogger.log("[SRM] Initializing SRM module...")
        for (stratum in SRMStratum.entries) {
            buffers[stratum] = SRMBuffer(stratum = stratum, config = config)
        }

        // Restore persisted snapshot if available
        if (storage != null) {
            try {
                val saved = storage.load()
                if (saved != null) {
                    restoreSnapshot(saved)
                    SynheartLogger.log("[SRM] Restored persisted snapshot")
                }
            } catch (e: Exception) {
                SynheartLogger.log("[SRM] Warning: failed to load persisted snapshot: $e")
            }
        }

        SynheartLogger.log("[SRM] SRM module initialized (${buffers.size} strata)")
    }

    override suspend fun onStart() {
        SynheartLogger.log("[SRM] SRM module started")
    }

    override suspend fun onStop() {
        if (storage != null) {
            try {
                storage.save(snapshot())
            } catch (e: Exception) {
                SynheartLogger.log("[SRM] Warning: failed to persist snapshot on stop: $e")
            }
        }
        SynheartLogger.log("[SRM] SRM module stopped")
    }

    override suspend fun onDispose() {
        SynheartLogger.log("[SRM] Disposing SRM module...")
        if (storage != null) {
            try {
                storage.save(snapshot())
            } catch (e: Exception) {
                SynheartLogger.log("[SRM] Warning: failed to persist snapshot on dispose: $e")
            }
        }
        buffers.clear()
    }

    // ---------------------------------------------------------------------------
    // Public API (RFC-CORE-0006 §4.1)
    // ---------------------------------------------------------------------------

    /// Submit a candidate window for SRM consideration.
    fun submitCandidate(candidate: CandidateWindow): SRMResult {
        val buffer = buffers[candidate.stratum]
            ?: return SRMResult(
                accepted = false,
                rejectionReason = "unknown_stratum",
                baselineStatus = SRMBaselineStatus.EMPTY,
                srmSnapshotId = "srm_unknown_0",
                srmVersion = config.srmVersion
            )
        return buffer.submit(candidate)
    }

    /// Query the current reference for a stratum.
    fun queryReference(stratum: SRMStratum): SRMReference? {
        val buffer = buffers[stratum] ?: return null
        if (buffer.count == 0) return null
        return SRMReference(
            stratum = stratum,
            status = buffer.baselineStatus,
            metrics = buffer.reference,
            bufferCount = buffer.count,
            distinctDays = buffer.distinctDays
        )
    }

    /// Get baseline status for a stratum.
    fun baselineStatus(stratum: SRMStratum): SRMBaselineStatus {
        return buffers[stratum]?.baselineStatus ?: SRMBaselineStatus.EMPTY
    }

    /// Get the overall baseline status (worst across all strata with data).
    val overallBaselineStatus: SRMBaselineStatus
        get() {
            var worst = SRMBaselineStatus.READY
            var hasData = false
            for (buffer in buffers.values) {
                if (buffer.count > 0) {
                    hasData = true
                    val s = buffer.baselineStatus
                    if (s.ordinal < worst.ordinal) worst = s
                }
            }
            return if (hasData) worst else SRMBaselineStatus.EMPTY
        }

    /// Total number of accepted windows across all strata.
    val totalAcceptedWindows: Int
        get() = buffers.values.sumOf { it.count }

    /// Total distinct calendar days across all strata.
    val totalDistinctDays: Int
        get() {
            val days = mutableSetOf<String>()
            for (buffer in buffers.values) {
                for (entry in buffer.entries) {
                    days.add(entry.dayKey)
                }
            }
            return days.size
        }

    /// Take an in-memory snapshot of all SRM state.
    fun snapshot(): SRMSnapshot {
        val strata = mutableMapOf<SRMStratum, StratumSnapshot>()
        for ((stratum, buffer) in buffers) {
            strata[stratum] = StratumSnapshot(
                stratum = stratum,
                status = buffer.baselineStatus,
                entries = buffer.entries.toList(),
                reference = buffer.reference.toMap(),
                distinctDays = buffer.distinctDays
            )
        }
        return SRMSnapshot(
            srmVersion = config.srmVersion,
            createdAtUtc = Instant.now(),
            strata = strata
        )
    }

    /// Restore SRM state from a snapshot.
    fun restoreSnapshot(snapshot: SRMSnapshot) {
        if (snapshot.srmVersion != config.srmVersion) {
            SynheartLogger.log("[SRM] Warning: snapshot version ${snapshot.srmVersion} differs from config version ${config.srmVersion}")
        }
        for ((stratum, stratumSnapshot) in snapshot.strata) {
            buffers[stratum]?.restore(stratumSnapshot.entries)
        }
        SynheartLogger.log("[SRM] Restored snapshot (${snapshot.strata.size} strata)")
    }

    /// Reset all buffers.
    fun reset() {
        for (buffer in buffers.values) {
            buffer.reset()
        }
        SynheartLogger.log("[SRM] All buffers reset")
    }

    /// Access to config (for integration modules that need thresholds).
    fun getConfig(): SRMConfig = config
}
