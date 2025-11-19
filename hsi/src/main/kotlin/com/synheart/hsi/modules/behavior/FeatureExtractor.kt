package com.synheart.hsi.modules.behavior

import com.synheart.hsi.modules.interfaces.BehaviorWindowFeatures

/// Extracts behavioral features from events
class BehaviorFeatureExtractor {
    /// Extract features from a list of events
    fun extract(events: List<BehaviorEvent>): BehaviorWindowFeatures {
        if (events.isEmpty()) {
            return BehaviorWindowFeatures(
                tapRateNorm = 0.0,
                keystrokeRateNorm = 0.0,
                scrollVelocityNorm = 0.0,
                idleRatio = 1.0,
                switchRateNorm = 0.0,
                burstiness = 0.0,
                sessionFragmentation = 0.0,
                notificationLoad = 0.0,
                distractionScore = 0.0,
                focusHint = 1.0
            )
        }
        
        val tapRate = calculateTapRate(events)
        val keystrokeRate = calculateKeystrokeRate(events)
        val scrollVelocity = calculateScrollVelocity(events)
        val idleRatio = calculateIdleRatio(events)
        val switchRate = calculateSwitchRate(events)
        val burstiness = calculateBurstiness(events)
        val fragmentation = calculateFragmentation(events)
        val notificationLoad = calculateNotificationLoad(events)
        
        // Simple heuristic for distraction/focus (will be replaced by MLP)
        val distractionScore = estimateDistraction(
            switchRate = switchRate,
            burstiness = burstiness,
            fragmentation = fragmentation,
            notificationLoad = notificationLoad
        )
        val focusHint = 1.0 - distractionScore
        
        return BehaviorWindowFeatures(
            tapRateNorm = tapRate,
            keystrokeRateNorm = keystrokeRate,
            scrollVelocityNorm = scrollVelocity,
            idleRatio = idleRatio,
            switchRateNorm = switchRate,
            burstiness = burstiness,
            sessionFragmentation = fragmentation,
            notificationLoad = notificationLoad,
            distractionScore = distractionScore,
            focusHint = focusHint
        )
    }
    
    private fun calculateTapRate(events: List<BehaviorEvent>): Double {
        val taps = events.count { it.type == BehaviorEventType.TAP }
        val duration = getDuration(events)
        if (duration == 0.0) return 0.0
        return (taps / duration).coerceIn(0.0, 1.0)
    }
    
    private fun calculateKeystrokeRate(events: List<BehaviorEvent>): Double {
        val keystrokes = events.count { 
            it.type == BehaviorEventType.KEY_DOWN || it.type == BehaviorEventType.KEY_UP 
        }
        val duration = getDuration(events)
        if (duration == 0.0) return 0.0
        return (keystrokes / duration / 2.0).coerceIn(0.0, 1.0) // Normalize to reasonable rate
    }
    
    private fun calculateScrollVelocity(events: List<BehaviorEvent>): Double {
        val scrollEvents = events.filter { it.type == BehaviorEventType.SCROLL }
        if (scrollEvents.isEmpty()) return 0.0
        
        val totalDelta = scrollEvents.sumOf { event ->
            val delta = event.metadata?.get("delta") as? Double ?: 0.0
            kotlin.math.abs(delta)
        }
        
        val duration = getDuration(events)
        if (duration == 0.0) return 0.0
        
        return (totalDelta / duration).coerceIn(0.0, 1.0)
    }
    
    private fun calculateIdleRatio(events: List<BehaviorEvent>): Double {
        val duration = getDuration(events)
        if (duration == 0.0) return 1.0
        
        // Simple heuristic: idle if no events for > 5 seconds
        val idleThreshold: Long = 5000
        var idleTime: Long = 0
        var lastEventTime: Long? = null
        
        events.sortedBy { it.timestamp }.forEach { event ->
            lastEventTime?.let { lastTime ->
                val gap = event.timestamp - lastTime
                if (gap > idleThreshold) {
                    idleTime += gap - idleThreshold
                }
            }
            lastEventTime = event.timestamp
        }
        
        return (idleTime.toDouble() / duration).coerceIn(0.0, 1.0)
    }
    
    private fun calculateSwitchRate(events: List<BehaviorEvent>): Double {
        val switches = events.count { it.type == BehaviorEventType.APP_SWITCH }
        val duration = getDuration(events)
        if (duration == 0.0) return 0.0
        return (switches / duration).coerceIn(0.0, 1.0)
    }
    
    private fun calculateBurstiness(events: List<BehaviorEvent>): Double {
        // Simple burstiness: variance in inter-event intervals
        if (events.size < 2) return 0.0
        
        val sortedEvents = events.sortedBy { it.timestamp }
        val intervals = mutableListOf<Long>()
        
        for (i in 1 until sortedEvents.size) {
            val interval = sortedEvents[i].timestamp - sortedEvents[i-1].timestamp
            intervals.add(interval)
        }
        
        if (intervals.isEmpty()) return 0.0
        
        val mean = intervals.average()
        val variance = intervals.map { kotlin.math.pow(it - mean, 2.0) }.average()
        
        return (kotlin.math.sqrt(variance) / (mean + 0.001)).coerceIn(0.0, 1.0) // Normalize
    }
    
    private fun calculateFragmentation(events: List<BehaviorEvent>): Double {
        // Simple fragmentation: number of idle gaps
        val idleThreshold: Long = 10000
        var gaps = 0
        
        val sortedEvents = events.sortedBy { it.timestamp }
        for (i in 1 until sortedEvents.size) {
            val gap = sortedEvents[i].timestamp - sortedEvents[i-1].timestamp
            if (gap > idleThreshold) {
                gaps++
            }
        }
        
        val duration = getDuration(events)
        if (duration == 0.0) return 0.0
        
        return (gaps / (duration / 60000.0)).coerceIn(0.0, 1.0) // Gaps per minute
    }
    
    private fun calculateNotificationLoad(events: List<BehaviorEvent>): Double {
        val notifications = events.count {
            it.type == BehaviorEventType.NOTIFICATION_RECEIVED || 
            it.type == BehaviorEventType.NOTIFICATION_OPENED
        }
        
        val duration = getDuration(events)
        if (duration == 0.0) return 0.0
        
        return (notifications / duration).coerceIn(0.0, 1.0)
    }
    
    private fun estimateDistraction(
        switchRate: Double,
        burstiness: Double,
        fragmentation: Double,
        notificationLoad: Double
    ): Double {
        // Simple weighted combination (will be replaced by MLP)
        val distraction = (switchRate * 0.3) + (burstiness * 0.2) + (fragmentation * 0.3) + (notificationLoad * 0.2)
        return distraction.coerceIn(0.0, 1.0)
    }
    
    private fun getDuration(events: List<BehaviorEvent>): Double {
        if (events.isEmpty()) return 0.0
        val sorted = events.sortedBy { it.timestamp }
        return (sorted.last().timestamp - sorted.first().timestamp) / 1000.0 // Convert to seconds
    }
}

