package ai.synheart.core.models

/**
 * Sub-classification of a [TaskType.FOCUS] task — symmetric to `WorkoutKind`
 * for [TaskType.MOVEMENT]. Lets the host signal cognitive-load grading so the
 * engine's explanation trace can surface "user just played Hard Stroop" as
 * session context.
 *
 * Mirrors `synheart_personalization_runtime::FocusKind` and the
 * `synheart_core_set_focus_kind` discriminant table:
 * `0=unknown, 1=easy, 2=medium, 3=hard`.
 *
 * **Behaviour note:** the engine records the focus kind on
 * `PersonalizationContext.explanation.entries` for trace observability but
 * does not fold it into multipliers. A rule-pack update can wire actual
 * modulation without changing this enum or the FFI.
 */
enum class FocusKind(
    /** Stable integer matching the C ABI in the native runtime. */
    val discriminant: Int,
) {
    UNKNOWN(0),
    EASY(1),
    MEDIUM(2),
    HARD(3),
    ;

    companion object {
        /** Decode an FFI-side discriminant. Unknown values fall back to [UNKNOWN]. */
        fun fromDiscriminant(value: Int): FocusKind =
            entries.firstOrNull { it.discriminant == value } ?: UNKNOWN
    }
}
