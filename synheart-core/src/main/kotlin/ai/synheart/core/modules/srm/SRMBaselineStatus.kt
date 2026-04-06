package ai.synheart.core.modules.srm

/**
 * SRM baseline status per stratum.
 *
 * - [EMPTY]: fewer than M_min accepted windows.
 * - [WARMING]: between M_min and M_ready accepted windows.
 * - [READY]: at least M_ready windows spanning at least D_min distinct days.
 */
enum class SRMBaselineStatus {
    EMPTY,
    WARMING,
    READY
}
