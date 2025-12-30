package com.synheart.core.modules.hsv_runtime

import com.synheart.core.modules.interfaces.BehaviorFeatureProvider
import com.synheart.core.modules.interfaces.PhoneFeatureProvider
import com.synheart.core.modules.interfaces.WearFeatureProvider
import com.synheart.core.modules.interfaces.WindowType
import com.synheart.core.modules.interfaces.BehaviorWindowFeatures
import com.synheart.core.modules.interfaces.PhoneWindowFeatures
import com.synheart.core.modules.interfaces.WearWindowFeatures

/// Collected features from all modules
data class CollectedFeatures(
    val wear: WearWindowFeatures? = null,
    val phone: PhoneWindowFeatures? = null,
    val behavior: BehaviorWindowFeatures? = null
) {
    /// Check if we have any features
    val hasAnyFeatures: Boolean
        get() = wear != null || phone != null || behavior != null
}

/// Collects features from all data modules
class ChannelCollector(
    private val wear: WearFeatureProvider? = null,
    private val phone: PhoneFeatureProvider? = null,
    private val behavior: BehaviorFeatureProvider? = null
) {
    /// Collect features for a specific window
    fun collect(window: WindowType): CollectedFeatures {
        return CollectedFeatures(
            wear = wear?.features(window),
            phone = phone?.features(window),
            behavior = behavior?.features(window)
        )
    }
}


