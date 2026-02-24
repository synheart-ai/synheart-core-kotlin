package com.synheart.core.modules.srm

/// SRM context strata for baseline partitioning.
///
/// Each stratum maintains an independent reference buffer to prevent
/// distributional contamination across contexts (SRM.pdf §3.2).
enum class SRMStratum {
    SLEEP,
    REST,
    BREATHING,
    MORNING,
    OTHER;

    companion object {
        fun fromString(value: String): SRMStratum? {
            return entries.find { it.name.equals(value, ignoreCase = true) }
        }
    }
}
