// SPDX-License-Identifier: Apache-2.0
//
// Public types for the multi-source priority resolver (Loot #3).
//
// Mirror of `synheart-core-flutter/lib/src/priority/priority_metric.dart`
// and `synheart-core-swift/.../SynheartPriority.swift`. Wire names
// are persisted in the runtime SQLite schema — renaming requires a
// migration.

package ai.synheart.core.priority

/**
 * Metric types that can have per-metric priority overrides.
 *
 * [wireName] is the string passed across the C ABI; do not change
 * without a runtime migration.
 */
enum class PriorityMetric(val wireName: String) {
    HEART_RATE("heart_rate"),
    HRV("hrv"),
    STEPS("steps"),
    SLEEP("sleep"),
    CALORIES("calories"),
    SPO2("spo2"),
    TEMPERATURE("temperature"),
    STRESS("stress");

    companion object {
        fun fromWire(name: String?): PriorityMetric? =
            values().firstOrNull { it.wireName == name }
    }
}

/**
 * Outcome of a single priority resolution.
 *
 * @property winner the provider whose samples should be used.
 * @property rank the effective rank used to pick the winner (lower wins).
 * @property alsoRan other providers that submitted samples for this
 * metric, sorted ascending by rank then alphabetically.
 */
data class SourceResolution(
    val winner: String,
    val rank: Int,
    val alsoRan: List<RankedProvider>
)

data class RankedProvider(val provider: String, val rank: Int)

/**
 * Sentinel rank for unknown providers — matches `i32::MAX` in
 * `ProviderRank::UNRANKED` on the Rust side.
 */
const val PRIORITY_UNRANKED: Int = Int.MAX_VALUE
