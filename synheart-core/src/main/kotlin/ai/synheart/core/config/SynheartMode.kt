package ai.synheart.core.config

/** Operational mode for the Synheart SDK. */
enum class SynheartMode {
    /** HSI only — no raw bio streams persisted, no app metrics. */
    PERSONAL,
    /** HSI + app-level metrics. */
    INSIGHT,
    /** Full data collection including raw bio streams (requires explicit opt-in). */
    RESEARCH;

    val value: String get() = name.lowercase()
}
