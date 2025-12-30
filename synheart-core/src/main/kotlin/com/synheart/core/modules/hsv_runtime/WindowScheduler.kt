package com.synheart.core.modules.hsv_runtime

import com.synheart.core.modules.interfaces.WindowType
import kotlinx.coroutines.delay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/// Callback for window events
typealias WindowCallback = (WindowType) -> Unit

/// Schedules window-based computation
class WindowScheduler(
    private val onWindow: WindowCallback
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var isScheduling = false

    /// Start scheduling windows
    fun start() {
        if (isScheduling) return
        isScheduling = true

        // 30-second window
        scope.launch {
            while (isActive && isScheduling) {
                delay(30000) // 30 seconds
                onWindow(WindowType.WINDOW_30S)
            }
        }

        // 5-minute window
        scope.launch {
            while (isActive && isScheduling) {
                delay(5 * 60 * 1000) // 5 minutes
                onWindow(WindowType.WINDOW_5M)
            }
        }

        // 1-hour window
        scope.launch {
            while (isActive && isScheduling) {
                delay(60 * 60 * 1000) // 1 hour
                onWindow(WindowType.WINDOW_1H)
            }
        }

        // 24-hour window
        scope.launch {
            while (isActive && isScheduling) {
                delay(24 * 60 * 60 * 1000) // 24 hours
                onWindow(WindowType.WINDOW_24H)
            }
        }

        // Trigger initial computation immediately
        scope.launch {
            onWindow(WindowType.WINDOW_30S)
            onWindow(WindowType.WINDOW_5M)
            onWindow(WindowType.WINDOW_1H)
            onWindow(WindowType.WINDOW_24H)
        }
    }

    /// Stop scheduling
    suspend fun stop() {
        isScheduling = false
        scope.cancel()
    }
}


