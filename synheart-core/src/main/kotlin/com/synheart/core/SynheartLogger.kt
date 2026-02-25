package com.synheart.core

/**
 * Centralized logging for the Synheart SDK.
 *
 * All library-internal logging goes through this object. Consumers can disable
 * SDK logs by setting [enabled] to `false`.
 *
 * On Android, delegates to `android.util.Log`. On JVM (tests), falls back to
 * `println`.
 */
object SynheartLogger {
    private const val TAG = "SynheartCore"

    /** When `false`, all log output is suppressed. Defaults to `true`. */
    @JvmStatic
    var enabled: Boolean = true

    /** Log a debug-level message. No-op when [enabled] is `false`. */
    @JvmStatic
    fun log(message: String) {
        if (!enabled) return
        try {
            // Try Android Log first
            val logClass = Class.forName("android.util.Log")
            val method = logClass.getMethod("d", String::class.java, String::class.java)
            method.invoke(null, TAG, message)
        } catch (_: Exception) {
            // Fallback for JVM tests
            println("[$TAG] $message")
        }
    }
}
