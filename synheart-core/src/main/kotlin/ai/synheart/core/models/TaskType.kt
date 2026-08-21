package ai.synheart.core.models

/**
 * Personalization task tags supplied by the host.
 *
 * Mirrors `synheart_personalization_runtime::TaskType` and the
 * `synheart_core_set_task_type` discriminant table:
 * `0=unknown, 1=focus, 2=recovery, 3=movement, 4=conversation`.
 *
 * Used by the engine's Stage 5 confidence modulation so e.g. mid-workout
 * [MOVEMENT] dampens cognitive head confidence per the personalization spec.
 */
enum class TaskType(
    /** Stable integer matching the C ABI in the native runtime. */
    val discriminant: Int,
) {
    UNKNOWN(0),
    FOCUS(1),
    RECOVERY(2),
    MOVEMENT(3),
    CONVERSATION(4),
    ;

    companion object {
        /** Decode an FFI-side discriminant. Unknown values fall back to [UNKNOWN]. */
        fun fromDiscriminant(value: Int): TaskType =
            entries.firstOrNull { it.discriminant == value } ?: UNKNOWN
    }
}
