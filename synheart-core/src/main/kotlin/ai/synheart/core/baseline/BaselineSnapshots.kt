package ai.synheart.core.baseline

import java.util.concurrent.ConcurrentHashMap

/**
 * Hook signature for "read every latest baseline envelope per kind for the
 * configured subject from local storage". No network — pure SQLite +
 * decryption. Wired by `Synheart` at SDK init from the runtime bridge.
 */
typealias BaselineLocalHydrator = suspend () -> String?

/**
 * Host-facing facade for typed baseline-snapshot access.
 *
 * Exposed via `Synheart.baselineSnapshots`. Replaces the raw-JSON legacy
 * access pattern with synchronous per-kind typed getters:
 *
 * ```kotlin
 * val wear = Synheart.baselineSnapshots.latestLongitudinalWear()
 * if (wear != null) {
 *     println("HRV baseline: ${wear.reference.dimensions["hrv_rmssd_ms"]}")
 * }
 * ```
 *
 * ## Cache model
 *
 * In-memory cache keyed by [BaselineKind], last-write-wins per kind.
 * Populated by:
 *  - the on-device producer (engine state → envelope writer) via [cache];
 *  - [hydrateFromLocal] on cold start, reading the latest of each kind from
 *    on-device storage (including envelopes synced from other devices).
 *
 * ## Cross-device transport
 *
 * Baselines do **not** have their own cloud client on this facade. They ride
 * the existing sync engine: an envelope written here lands in the local
 * `artifacts` table with `sync_state='pending'` and the sync engine pushes it
 * on its normal cadence. Envelopes pulled from other devices land in the same
 * table and are picked up by [hydrateFromLocal] on init (or by any explicit
 * re-hydrate).
 */
class BaselineSnapshots {

    private val cache = ConcurrentHashMap<BaselineKind, BaselineEnvelope>()

    @Volatile
    private var localHydrator: BaselineLocalHydrator? = null

    /**
     * The most recent envelope for [kind], or null if no producer has cached
     * one yet. Synchronous — reads in-memory state.
     */
    fun envelopeFor(kind: BaselineKind): BaselineEnvelope? = cache[kind]

    /** Latest `session.hsi_axes` baseline, or null if none is cached. */
    fun latestHsiAxes(): HsiAxesBaseline? =
        cache[BaselineKind.SESSION_HSI_AXES]?.payloadHsiAxes()

    /** Latest `session.srm_metrics` baseline, or null if none is cached. */
    fun latestSrmMetrics(): SessionSrmMetricsBaseline? =
        cache[BaselineKind.SESSION_SRM_METRICS]?.payloadSrmMetrics()

    /** Latest `longitudinal.wear` baseline, or null if none is cached. */
    fun latestLongitudinalWear(): LongitudinalWearBaseline? =
        cache[BaselineKind.LONGITUDINAL_WEAR]?.payloadLongitudinalWear()

    /**
     * Immutable snapshot of every kind that currently has a cached envelope.
     * Powers the first-launch hydration UI ("show me one summary per baseline
     * kind the user has on file").
     */
    fun envelopesByKind(): Map<BaselineKind, BaselineEnvelope> = cache.toMap()

    /**
     * Push an envelope into the cache. Called by the on-device producer
     * (engine state → envelope writer) after each successful local snapshot,
     * and by [hydrateFromLocal] after a storage read. Last-write-wins per kind.
     */
    fun cache(envelope: BaselineEnvelope) {
        cache[envelope.kind] = envelope
    }

    /** Forget every cached envelope. Called on logout / user switch. */
    fun reset() {
        cache.clear()
    }

    // ---- Local hydration ------------------------------------------------

    /**
     * Inject the local-storage hydrator. Called by `Synheart` after the
     * runtime bridge is configured. Pass null to clear (e.g. after disposing
     * the bridge).
     */
    fun wireLocalHydrator(hydrator: BaselineLocalHydrator?) {
        localHydrator = hydrator
    }

    /**
     * Populate the in-memory cache from locally-persisted artifacts. Called by
     * `Synheart` on bridge construction so the typed getters return real data
     * immediately after a cold start, without requiring a producer to run
     * first.
     *
     * A no-op when the hydrator hasn't been wired (older runtime binaries that
     * don't ship the hydrate FFI). Returns the envelopes that successfully
     * landed in the cache (empty when nothing was on disk). Envelopes whose
     * kind this SDK can't decode are silently skipped — forward-compat for
     * kinds added in a newer runtime.
     */
    suspend fun hydrateFromLocal(): List<BaselineEnvelope> {
        val hydrator = localHydrator ?: return emptyList()
        val raw = hydrator() ?: return emptyList()
        val resp = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return emptyList()
        if (resp.optString("error").isNotEmpty()) return emptyList()

        val snapshots = resp.optJSONArray("snapshots") ?: return emptyList()
        val out = mutableListOf<BaselineEnvelope>()
        for (i in 0 until snapshots.length()) {
            val entry = snapshots.optJSONObject(i) ?: continue
            try {
                val envelope = BaselineEnvelope.fromJson(entry)
                cache(envelope)
                out.add(envelope)
            } catch (e: BaselineFormatException) {
                // Forward-compat: skip envelopes this SDK can't decode.
                continue
            }
        }
        return out
    }
}
