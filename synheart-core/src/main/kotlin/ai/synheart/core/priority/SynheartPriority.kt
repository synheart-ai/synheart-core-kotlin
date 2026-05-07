// SPDX-License-Identifier: Apache-2.0
//
// High-level Kotlin API for the multi-source priority resolver
// (Loot #3). Mirror of the Flutter and Swift wrappers.
//
// When the loaded native library exposes the Loot #3 symbols
// (synheart-core-runtime 5.4.0+), all calls route through them.
// Otherwise the class falls back to a pure-Kotlin in-memory store
// so unit tests and apps developed against older runtimes still
// work — same as the Dart and Swift equivalents.

package ai.synheart.core.priority

import ai.synheart.core.bridge.CoreRuntimeNative
import org.json.JSONArray
import org.json.JSONObject

/**
 * Process-wide priority API. Construct one [SynheartPriority] for
 * the lifetime of the runtime and reuse it.
 *
 * Thread-safety: the in-memory fallback is guarded by a lock; the
 * native path goes through the runtime's own mutex. Either way,
 * concurrent set/read calls are safe.
 *
 * @param native injectable native interface (defaults to the
 * lazily-loaded JNA `INSTANCE`). Pass `null` to force the in-memory
 * path (used by unit tests).
 */
class SynheartPriority(
    private val native: CoreRuntimeNative? = CoreRuntimeNative.INSTANCE,
) {
    private val lock = Any()
    private val providers = mutableMapOf<String, Int>()
    private val overrides = mutableMapOf<Pair<PriorityMetric, String>, Int>()

    /** Whether calls route through the runtime FFI. */
    val isUsingNativeStore: Boolean
        get() = native != null

    /** Set the global rank for a provider. Lower wins. */
    fun setProviderPriority(provider: String, rank: Int) {
        require(provider.isNotEmpty()) { "provider must not be empty" }
        val n = native
        if (n != null) {
            val rc = n.synheart_core_priority_set_provider(provider, rank)
            check(rc == 0) { "runtime rejected setProviderPriority (rc=$rc)" }
            return
        }
        synchronized(lock) { providers[provider] = rank }
    }

    /**
     * Set or clear the per-metric override. Pass `null` for [rank]
     * to clear; the metric falls back to the global rank.
     */
    fun setMetricOverride(metric: PriorityMetric, provider: String, rank: Int?) {
        require(provider.isNotEmpty()) { "provider must not be empty" }
        val n = native
        if (n != null) {
            val rc = n.synheart_core_priority_set_metric_override(
                metric.wireName,
                provider,
                if (rank == null) 0 else 1,
                rank ?: 0,
            )
            check(rc == 0) { "runtime rejected setMetricOverride (rc=$rc)" }
            return
        }
        synchronized(lock) {
            val key = metric to provider
            if (rank == null) overrides.remove(key) else overrides[key] = rank
        }
    }

    /**
     * Read the effective rank for `(metric, provider)`. Returns
     * [PRIORITY_UNRANKED] for unknown providers.
     */
    fun effectiveRank(metric: PriorityMetric, provider: String): Int {
        val n = native
        if (n != null) {
            return n.synheart_core_priority_effective_rank(metric.wireName, provider)
        }
        synchronized(lock) {
            val key = metric to provider
            return overrides[key] ?: providers[provider] ?: PRIORITY_UNRANKED
        }
    }

    /**
     * Resolve the winning source for [metric] given a `{provider:
     * count}` map. Returns `null` only when there's nothing to pick
     * (empty input).
     */
    fun resolve(metric: PriorityMetric, samplesByProvider: Map<String, Int>): SourceResolution? {
        if (samplesByProvider.isEmpty()) return null

        val n = native
        if (n != null) {
            val json = JSONObject(samplesByProvider as Map<*, *>).toString()
            val ptr = n.synheart_core_priority_resolve(metric.wireName, json) ?: return null
            return try {
                val raw = ptr.getString(0, "UTF-8")
                val obj = JSONObject(raw)
                if (obj.isNull("winner")) {
                    null
                } else {
                    val winner = obj.getString("winner")
                    val rank = obj.getInt("rank")
                    val alsoRanArr = obj.optJSONArray("also_ran") ?: JSONArray()
                    val alsoRan = (0 until alsoRanArr.length()).map { i ->
                        val o = alsoRanArr.getJSONObject(i)
                        RankedProvider(o.getString("provider"), o.getInt("rank"))
                    }
                    SourceResolution(winner, rank, alsoRan)
                }
            } finally {
                n.synheart_core_free_string(ptr)
            }
        }

        return resolveInMemory(metric, samplesByProvider)
    }

    private fun resolveInMemory(
        metric: PriorityMetric,
        samplesByProvider: Map<String, Int>,
    ): SourceResolution? {
        data class Candidate(val provider: String, val rank: Int, val count: Int)

        val candidates = samplesByProvider
            .filter { it.value > 0 }
            .map { (provider, count) ->
                Candidate(provider, effectiveRank(metric, provider), count)
            }
        if (candidates.isEmpty()) return null

        val sorted = candidates.sortedWith(
            compareBy<Candidate> { it.rank }
                .thenByDescending { it.count }
                .thenBy { it.provider }
        )
        val winner = sorted.first()
        val alsoRan = sorted.drop(1).map { RankedProvider(it.provider, it.rank) }
        return SourceResolution(winner.provider, winner.rank, alsoRan)
    }
}
