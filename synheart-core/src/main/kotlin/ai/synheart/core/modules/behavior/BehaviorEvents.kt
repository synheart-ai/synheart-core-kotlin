package ai.synheart.core.modules.behavior

enum class BehaviorEventType {
    TAP,
    SCROLL,
    KEY_DOWN,
    KEY_UP,
    APP_SWITCH,
    NOTIFICATION_RECEIVED,
    NOTIFICATION_OPENED
}

data class BehaviorEvent(
    val type: BehaviorEventType,
    val timestamp: Long,
    val metadata: Map<String, Any>? = null
) {
    companion object {
        fun tap(x: Double, y: Double) = BehaviorEvent(
            type = BehaviorEventType.TAP,
            timestamp = System.currentTimeMillis(),
            metadata = mapOf("x" to x, "y" to y)
        )

        fun scroll(delta: Double) = BehaviorEvent(
            type = BehaviorEventType.SCROLL,
            timestamp = System.currentTimeMillis(),
            metadata = mapOf("delta" to delta)
        )

        fun keyDown() = BehaviorEvent(
            type = BehaviorEventType.KEY_DOWN,
            timestamp = System.currentTimeMillis()
        )

        fun keyUp() = BehaviorEvent(
            type = BehaviorEventType.KEY_UP,
            timestamp = System.currentTimeMillis()
        )

        fun appSwitch() = BehaviorEvent(
            type = BehaviorEventType.APP_SWITCH,
            timestamp = System.currentTimeMillis()
        )

        fun notificationReceived() = BehaviorEvent(
            type = BehaviorEventType.NOTIFICATION_RECEIVED,
            timestamp = System.currentTimeMillis()
        )

        fun notificationOpened() = BehaviorEvent(
            type = BehaviorEventType.NOTIFICATION_OPENED,
            timestamp = System.currentTimeMillis()
        )
    }
}
