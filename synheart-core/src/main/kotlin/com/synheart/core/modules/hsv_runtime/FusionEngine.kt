package com.synheart.core.modules.hsv_runtime

import com.synheart.core.modules.interfaces.WindowType
import com.synheart.core.models.BehaviorState
import com.synheart.core.models.ContextState
import com.synheart.core.models.ConversationContext
import com.synheart.core.models.DeviceStateContext
import com.synheart.core.models.HumanStateVector
import com.synheart.core.models.MetaState
import com.synheart.core.models.DeviceInfo
import com.synheart.core.models.UserPatternsContext
import android.os.Build

/// Fusion Engine
///
/// Combines features from all modules into a base HSV (internal state representation).
class FusionEngine {
    /// Fuse collected features into base HSV
    suspend fun fuse(
        features: CollectedFeatures,
        window: WindowType,
        timestamp: Long
    ): HumanStateVector {
        // Build fused feature vector
        val fusedVector = buildFusedVector(features)

        // Run embedding model (placeholder for now)
        val embedding = computeEmbedding(fusedVector)

        // Create behavior state
        val behavior = buildBehaviorState(features.behavior)

        // Create context state
        val context = buildContextState(features.phone)

        // Create meta state
        val meta = MetaState(
            device = DeviceInfo(
                platform = "Android",
                osVersion = Build.VERSION.RELEASE,
                model = Build.MODEL,
                manufacturer = Build.MANUFACTURER
            ),
            sessionId = "session_${timestamp}"
        )

        // Return HSV (internal representation)
        return HumanStateVector(
            timestamp = timestamp,
            meta = meta,
            heartRate = features.wear?.hrAverage?.toFloat(),
            hrv = features.wear?.hrvRmssd?.toFloat(),
            hrvSdnn = null,
            hsiEmbedding = embedding,
            behavior = behavior,
            context = context
        )
    }

    /// Build fused feature vector from available modalities
    private fun buildFusedVector(features: CollectedFeatures): List<Double> {
        val vector = mutableListOf<Double>()

        // Wear features
        if (features.wear != null) {
            vector.add(features.wear.hrAverage ?: 0.0)
            vector.add(features.wear.hrvRmssd ?: 0.0)
            vector.add(features.wear.motionIndex ?: 0.0)
        } else {
            vector.addAll(List(3) { 0.0 })
        }

        // Phone features
        if (features.phone != null) {
            vector.add(features.phone.motionLevel)
            vector.add(features.phone.screenOnRatio)
            vector.add(features.phone.appSwitchRate)
            vector.add(features.phone.notificationRate)
        } else {
            vector.addAll(List(4) { 0.0 })
        }

        // Behavior features
        if (features.behavior != null) {
            vector.add(features.behavior.keystrokeRateNorm)
            vector.add(features.behavior.scrollVelocityNorm)
            vector.add(features.behavior.idleRatio)
            vector.add(features.behavior.switchRateNorm)
            vector.add(features.behavior.burstiness)
            vector.add(features.behavior.sessionFragmentation)
            vector.add(features.behavior.notificationLoad)
        } else {
            vector.addAll(List(7) { 0.0 })
        }

        return vector
    }

    /// Compute embedding from fused vector (placeholder)
    private suspend fun computeEmbedding(fusedVector: List<Double>): List<Float> {
        // TODO: Implement actual embedding model (MLP/Tiny Transformer)
        // For now, return the fused vector padded/truncated to 64D
        return if (fusedVector.size >= 64) {
            fusedVector.take(64).map { it.toFloat() }
        } else {
            fusedVector.map { it.toFloat() } + List(64 - fusedVector.size) { 0.0f }
        }
    }

    /// Build behavior state from features
    private fun buildBehaviorState(features: com.synheart.core.modules.interfaces.BehaviorWindowFeatures?): BehaviorState? {
        if (features == null) {
            return BehaviorState()
        }

        return BehaviorState(
            typingSpeed = features.keystrokeRateNorm.toFloat(),
            typingBurstiness = features.burstiness.toFloat(),
            scrollingVelocity = features.scrollVelocityNorm.toFloat(),
            appSwitchFrequency = features.switchRateNorm.toFloat(),
            interactionIntensity = (1.0 - features.idleRatio).toFloat(),
            engagementLevel = (1.0 - features.distractionScore).toFloat()
        )
    }

    /// Build context state from phone features
    private fun buildContextState(features: com.synheart.core.modules.interfaces.PhoneWindowFeatures?): ContextState {
        // Placeholder context state
        return ContextState(
            conversation = ConversationContext(
                isActive = false,
                lastActivityTime = null,
                messageCount = 0,
                averageResponseTime = null
            ),
            device = DeviceStateContext(
                batteryLevel = 100, // TODO: Get actual battery level
                isCharging = false, // TODO: Get actual charging state
                screenOn = features != null && features.screenOnRatio > 0.5,
                networkType = null, // TODO: Get actual network type
                timeOfDay = System.currentTimeMillis()
            ),
            userPatterns = UserPatternsContext()
        )
    }
}


