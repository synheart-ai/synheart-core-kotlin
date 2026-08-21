package ai.synheart.core.modules.behavior

/**
 * Typed behavior-event kinds that cross the FFI seam.
 *
 * Replaces flat `int` codes with an enum so SDK call sites can't drift out of
 * sync with the engine. Each variant carries:
 *  * [code] — the raw integer the legacy `synheart_core_push_behavior` FFI
 *    still expects.
 *  * [kind] — the canonical string used by the rich
 *    `synheart_core_push_behavior_event` FFI / `BehaviorEventInput.kind`.
 *
 * When migrating to the rich-event FFI only [kind] needs to change; the SDK
 * API stays put.
 */
enum class RuntimeBehaviorEvent(val code: Int, val kind: String) {
    SCREEN_ON(0, "screen_on"),
    SCREEN_OFF(1, "screen_off"),
    INPUT(2, "touch"),
    APP_SWITCH(3, "app_switch"),
    NOTIFICATION(4, "notification"),
    SCROLL(5, "scroll"),
    SWIPE(6, "swipe"),
    CALL(7, "call"),
    ;

    companion object {
        /** Decode a legacy integer code, or null when it maps to nothing. */
        fun fromCode(code: Int): RuntimeBehaviorEvent? = entries.firstOrNull { it.code == code }

        /** Decode a canonical kind string, or null when it maps to nothing. */
        fun fromKind(kind: String): RuntimeBehaviorEvent? = entries.firstOrNull { it.kind == kind }
    }
}
