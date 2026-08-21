package ai.synheart.core.baseline

/**
 * Discriminator for what kind of baseline a snapshot carries.
 *
 * Wire form is the stable lowercase dotted string used as the cloud +
 * on-device dispatch column (`session.hsi_axes`, `session.srm_metrics`,
 * `longitudinal.wear`).
 *
 * Adding a future kind: register a new variant + wire string here in lockstep
 * with the Rust enum. [fromWire] returns null for unknown wires so an older
 * SDK reading a snapshot produced by a newer runtime degrades gracefully
 * (log + skip rather than throw).
 */
enum class BaselineKind(val wire: String) {
    SESSION_HSI_AXES("session.hsi_axes"),
    SESSION_SRM_METRICS("session.srm_metrics"),
    LONGITUDINAL_WEAR("longitudinal.wear"),
    ;

    /**
     * True for kinds scoped to a single session (the `session.` prefix). The
     * envelope's `sessionId` is required when this is true and forbidden when
     * it is false.
     */
    val isSessionScoped: Boolean get() = wire.startsWith("session.")

    companion object {
        /**
         * Parse from the wire string. Returns null on an unknown kind — the
         * intended pattern is "log + skip", not "throw".
         */
        fun fromWire(s: String?): BaselineKind? =
            if (s == null) null else entries.firstOrNull { it.wire == s }
    }
}

/**
 * Maturity status of a session-level SRM metric. Distinct from the
 * longitudinal wearable status — session SRM doesn't decay within a session,
 * so there is no `STALE` variant here.
 */
enum class SrmMetricStatus(val wire: String) {
    EMPTY("EMPTY"),
    WARMING("WARMING"),
    READY("READY"),
    ;

    companion object {
        fun fromWire(s: String?): SrmMetricStatus? =
            if (s == null) null else entries.firstOrNull { it.wire == s }
    }
}
