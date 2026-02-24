package com.synheart.core.modules.interfaces

import com.synheart.core.modules.behavior.BehaviorEvent
import com.synheart.core.modules.phone.PhoneDataPoint
import com.synheart.core.modules.wear.WearSample

/**
 * Protocols for modules to expose raw buffered data to the runtime pipeline.
 *
 * RFC-CORE-0007: Core caches buffer raw data only. Feature computation,
 * fusion, embedding, and HSV construction are delegated to synheart-runtime.
 */

/** Provides raw wear samples for a given window. */
interface RawWearDataProvider {
    fun rawSamples(window: WindowType): List<WearSample>
}

/** Provides raw phone data points for a given window. */
interface RawPhoneDataProvider {
    fun rawDataPoints(window: WindowType): List<PhoneDataPoint>
}

/** Provides raw behavior events for a given window. */
interface RawBehaviorDataProvider {
    fun rawEvents(window: WindowType): List<BehaviorEvent>
}
