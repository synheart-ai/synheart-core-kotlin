// SPDX-License-Identifier: Apache-2.0
//
// High-level Kotlin wrapper around the native breathing compliance
// detector. Mirrors the Flutter reference at
// `lib/src/modules/breathing/breathing_module.dart`.
//
// All RR samples pushed via [CoreRuntimeBridge.pushRr] (i.e. Tier-1
// BLE chest-strap data) automatically feed the breathing detector.
// This module configures the target rate / population profile and
// reads back the current verdict. See RFC-Breathing-001 for the
// algorithm.

package ai.synheart.core.modules.breathing

import ai.synheart.core.bridge.CoreRuntimeBridge
import org.json.JSONObject

class BreathingModule(private val bridge: CoreRuntimeBridge) {

    /**
     * Set the target breathing rate in breaths per minute
     * (e.g. `6.0` for resonance breathing).
     */
    fun setTargetBpm(bpm: Double) {
        bridge.breathingSetTargetBpm(bpm)
    }

    /** Set the rolling-window length in seconds. Native side clamps to `[30, 120]`. */
    fun setWindowSecs(secs: Int) {
        bridge.breathingSetWindowSecs(secs)
    }

    /** Choose threshold profile (defaults to [BreathingPopulation.BEGINNER]). */
    fun setPopulation(profile: BreathingPopulation) {
        bridge.breathingSetPopulation(profile.ordinal)
    }

    /**
     * Compute compliance for the current RR window. Returns
     * [BreathingComplianceResult.Insufficient] when there isn't
     * enough Tier-1 data yet.
     */
    fun evaluate(): BreathingComplianceResult {
        val json = bridge.breathingEvaluateJson()
            ?: return BreathingComplianceResult.Insufficient(
                reason = InsufficientReason.NotEnoughBeats(have = 0, need = 50)
            )
        return BreathingComplianceResult.fromJson(JSONObject(json))
    }

    /** Clear the RR ring buffer. Call when starting a new breathing exercise. */
    fun reset() {
        bridge.breathingReset()
    }
}
