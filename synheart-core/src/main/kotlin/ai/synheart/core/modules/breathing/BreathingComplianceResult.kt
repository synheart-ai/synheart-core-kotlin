// SPDX-License-Identifier: Apache-2.0
//
// Result of evaluating breathing compliance over the current RR window.
// Mirrors the breathing runtime's `ComplianceResult` JSON shape and
// the Flutter reference at
// `lib/src/modules/breathing/breathing_compliance_result.dart`.
//
// See RFC-Breathing-001 for verdict semantics.

package ai.synheart.core.modules.breathing

import org.json.JSONObject

/** Numeric metrics computed by the 4-pillar detector. */
data class BreathingMetrics(
    /** Detected breathing peak frequency in Hz. */
    val peakHz: Double,
    /** Detected breathing rate in breaths/minute (= peakHz * 60). */
    val peakBpm: Double,
    /** Coherence score (peak power / power in 0.04–0.26 Hz band), 0..1. */
    val coherence: Double,
    /** RSA amplitude (mean per-cycle HR_max − HR_min), in BPM. */
    val rsaBpm: Double,
    /** Peak power as a fraction of total breathing-band power, 0..1. */
    val relativePower: Double,
    /** Number of compliance pillars met (0..4). */
    val criteriaMet: Int,
    /** Mean of normalized pillar scores, 0..1. */
    val confidence: Double,
) {
    companion object {
        fun fromJson(json: JSONObject): BreathingMetrics = BreathingMetrics(
            peakHz = json.optDouble("peak_hz", 0.0),
            peakBpm = json.optDouble("peak_bpm", 0.0),
            coherence = json.optDouble("coherence", 0.0),
            rsaBpm = json.optDouble("rsa_bpm", 0.0),
            relativePower = json.optDouble("relative_power", 0.0),
            criteriaMet = json.optInt("criteria_met", 0),
            confidence = json.optDouble("confidence", 0.0),
        )
    }
}

/** Why the user is not following instructions (when verdict is NotCompliant). */
sealed class NonComplianceReason {
    data class WrongFrequency(val detectedBpm: Double, val targetBpm: Double) : NonComplianceReason()
    data class ShallowBreathing(val rsaBpm: Double) : NonComplianceReason()
    data class IrregularPattern(val coherence: Double) : NonComplianceReason()
    data object NoBreathingSignature : NonComplianceReason()

    companion object {
        fun fromJson(json: JSONObject): NonComplianceReason = when (json.optString("type")) {
            "WrongFrequency" -> WrongFrequency(
                detectedBpm = json.optDouble("detected_bpm", 0.0),
                targetBpm = json.optDouble("target_bpm", 0.0),
            )
            "ShallowBreathing" -> ShallowBreathing(rsaBpm = json.optDouble("rsa_bpm", 0.0))
            "IrregularPattern" -> IrregularPattern(coherence = json.optDouble("coherence", 0.0))
            else -> NoBreathingSignature
        }
    }
}

/** Why the detector cannot produce a verdict yet. */
sealed class InsufficientReason {
    data class NotEnoughBeats(val have: Int, val need: Int) : InsufficientReason()
    data class WindowTooShort(val haveSecs: Int, val needSecs: Int) : InsufficientReason()
    data class VendorOnlyTier(val device: String) : InsufficientReason()
    data class ExcessiveArtifacts(val rejectedPct: Double) : InsufficientReason()

    companion object {
        fun fromJson(json: JSONObject): InsufficientReason = when (json.optString("type")) {
            "NotEnoughBeats" -> NotEnoughBeats(
                have = json.optInt("have", 0),
                need = json.optInt("need", 50),
            )
            "WindowTooShort" -> WindowTooShort(
                haveSecs = json.optInt("have_secs", 0),
                needSecs = json.optInt("need_secs", 0),
            )
            "VendorOnlyTier" -> VendorOnlyTier(device = json.optString("device", ""))
            "ExcessiveArtifacts" -> ExcessiveArtifacts(
                rejectedPct = json.optDouble("rejected_pct", 0.0),
            )
            else -> NotEnoughBeats(have = 0, need = 50)
        }
    }
}

/** Top-level breathing compliance verdict. */
sealed class BreathingComplianceResult {
    /** Returns the metrics if available (null for [Insufficient]). */
    abstract val metrics: BreathingMetrics?

    /** True iff the user is meeting the compliance threshold. */
    val isCompliant: Boolean get() = this is Compliant

    data class Compliant(override val metrics: BreathingMetrics) : BreathingComplianceResult()

    data class NotCompliant(
        override val metrics: BreathingMetrics,
        val reason: NonComplianceReason,
    ) : BreathingComplianceResult()

    data class Insufficient(val reason: InsufficientReason) : BreathingComplianceResult() {
        override val metrics: BreathingMetrics? get() = null
    }

    companion object {
        fun fromJson(json: JSONObject): BreathingComplianceResult =
            when (json.optString("verdict")) {
                "Compliant" -> Compliant(
                    metrics = BreathingMetrics.fromJson(
                        json.optJSONObject("metrics") ?: JSONObject()
                    ),
                )
                "NotCompliant" -> NotCompliant(
                    metrics = BreathingMetrics.fromJson(
                        json.optJSONObject("metrics") ?: JSONObject()
                    ),
                    reason = NonComplianceReason.fromJson(
                        json.optJSONObject("reason") ?: JSONObject()
                    ),
                )
                else -> Insufficient(
                    reason = InsufficientReason.fromJson(
                        json.optJSONObject("reason") ?: JSONObject()
                    ),
                )
            }

        fun fromJsonString(s: String): BreathingComplianceResult = fromJson(JSONObject(s))
    }
}

/** Population threshold profiles. Match the native runtime's enum ordering. */
enum class BreathingPopulation { BEGINNER, EXPERIENCED, CLINICAL }
