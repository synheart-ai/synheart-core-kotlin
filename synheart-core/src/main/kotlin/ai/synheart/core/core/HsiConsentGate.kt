package ai.synheart.core.core

import ai.synheart.core.modules.consent.ConsentEffectiveState
import ai.synheart.core.modules.interfaces.ConsentSnapshot

/**
 * Whether a completed HSI window may reach the host's streams.
 *
 * A window is delivered when the person has granted any collection category
 * (biosignals, behavior, phone context) or research. Each axis in the frame
 * already comes only from inputs the SDK was allowed to collect, and the
 * runtime reports the rest as withheld, so a behavior-only app gets its
 * digital axes and an empty biosignal side. Gating on biosignals alone threw
 * away every window for an app with no wearable, the one case the digital
 * axes exist for.
 *
 * [effective] is the runtime's effective state and [local] the SDK-side
 * snapshot; either granting is enough. The SDK snapshot can lag a native
 * consent write, and the runtime's view is absent before it loads, so neither
 * alone is trusted to refuse.
 */
object HsiConsentGate {
    fun allows(effective: ConsentEffectiveState?, local: ConsentSnapshot?): Boolean {
        if (effective != null &&
            (effective.biosignals || effective.behavior || effective.phoneContext || effective.research)
        ) {
            return true
        }
        if (local == null) return false
        return local.biosignals || local.behavior || local.phoneContext || local.research
    }
}
