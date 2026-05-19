// SPDX-License-Identifier: Apache-2.0
//
// Default user-facing copy for [NonComplianceReason] enum values.
//
// The native engine emits structured reasons (frequency mismatch,
// shallow breathing, etc.). Wording belongs to the consumer; this file
// ships a neutral default so most apps can stay one-liner. Apps wanting
// a different tone can either replace [BreathingGuidanceCopy.localize]
// (single replacement) or build their own switch over
// [NonComplianceReason] in their UI layer.

package ai.synheart.core.modules.breathing

/** Single-line coaching string for a [NonComplianceReason]. Pure function; no engine call. */
typealias BreathingGuidanceLocalizer = (NonComplianceReason) -> String

object BreathingGuidanceCopy {
    /**
     * Default-tone English. Override via [localize] to swap in your own
     * copy globally without forking call sites.
     */
    var localize: BreathingGuidanceLocalizer = ::defaultEn

    /** Convenience equivalent of `localize(reason)`. */
    fun copyFor(reason: NonComplianceReason): String = localize(reason)

    private fun defaultEn(reason: NonComplianceReason): String = when (reason) {
        is NonComplianceReason.WrongFrequency ->
            if (reason.detectedBpm > reason.targetBpm)
                "Slow down - you're breathing faster than the target."
            else
                "Speed up slightly - you're breathing slower than the target."
        is NonComplianceReason.ShallowBreathing ->
            "Good rhythm - now breathe deeper, take fuller breaths."
        is NonComplianceReason.IrregularPattern ->
            "Try to keep a steady, even rhythm with the pacer."
        is NonComplianceReason.NoBreathingSignature ->
            "We can't detect a breathing pattern yet - follow the pacer."
    }
}
