package ai.synheart.core.modules.cloud

import org.json.JSONObject
import java.time.Instant

/**
 * Delivery flavor the cloud stamped on an event, derived from the source
 * vendor's webhook capability.
 *
 * Wire values must match the streaming delivery-filter constants byte for
 * byte; renaming one requires a coordinated server change.
 */
enum class DeliveryHint(val wireName: String) {
    /** Full payload arrived inline; no follow-up pull needed (e.g. Whoop). */
    STREAM("stream"),

    /**
     * The vendor only sent a notification; the client should pull the full
     * record via REST when it wants the body (e.g. Garmin, Oura, Fitbit).
     */
    PING("ping"),

    /**
     * Flavor not in the cloud's capability registry — typically a REST-only
     * vendor that shouldn't be pushing events at all, or one the registry has
     * not seen yet. Treated as [STREAM] to avoid an unnecessary pull, but
     * worth logging.
     */
    UNKNOWN("unknown"),
    ;

    companion object {
        /**
         * Parse the cloud's wire string. Returns [UNKNOWN] for null, empty, or
         * unrecognized values so callers always get a usable enum without a
         * separate null branch.
         */
        fun fromWire(raw: String?): DeliveryHint {
            if (raw.isNullOrEmpty()) return UNKNOWN
            return entries.firstOrNull { it.wireName == raw } ?: UNKNOWN
        }
    }
}

/**
 * One decoded event from the runtime's streaming connection.
 *
 * Distinct from `ai.synheart.wear.ramen.RamenEvent`: that one is the wear
 * SDK's gRPC-level envelope, while this is what the *native runtime* hands
 * across the FFI boundary — and it carries the [deliveryHint] the ping-vs-
 * stream decision depends on.
 *
 * `appId` and `userId` are connection-level, not event-level: the runtime
 * envelope omits them, so `Synheart` stamps them from the config the stream
 * was started with.
 */
data class RuntimeStreamEvent(
    /** Unique event identifier. */
    val eventId: String,
    /** Monotonic per-user sequence number. */
    val seq: Long,
    val appId: String,
    val userId: String,
    /** Source vendor (e.g. `"whoop"`, `"oura"`, `"garmin"`, `"fitbit"`). */
    val provider: String,
    /** Vendor-specific event type (e.g. `"sleep.updated"`). */
    val eventType: String,
    /** How to interpret this event — see [DeliveryHint]. */
    val deliveryHint: DeliveryHint,
    /**
     * Reference to the raw payload in the Synheart Wear API backing store.
     * Ping-flavored consumers use this to fetch full detail over REST.
     */
    val rawId: String = "",
    /** Inline payload; empty on a ping delivery. */
    val payload: JSONObject? = null,
    val createdAt: Instant? = null,
    val attempt: Int = 0,
    val isReplay: Boolean = false,
) {
    /**
     * Whether the consumer must perform a follow-up REST pull to get the full
     * record. True only for ping-flavored deliveries.
     */
    val requiresPull: Boolean get() = deliveryHint == DeliveryHint.PING

    companion object {
        /**
         * Build from the runtime's JSON envelope, tolerating missing optional
         * fields and unknown delivery hints.
         */
        fun fromRuntimeJson(json: JSONObject, appId: String, userId: String): RuntimeStreamEvent {
            val payloadJson = json.optString("payload_json").takeIf { it.isNotEmpty() }
            return RuntimeStreamEvent(
                eventId = json.optString("event_id"),
                seq = json.optLong("seq", 0L),
                appId = appId,
                userId = userId,
                provider = json.optString("provider"),
                eventType = json.optString("event_type"),
                deliveryHint = DeliveryHint.fromWire(
                    json.optString("delivery_hint").takeIf { it.isNotEmpty() },
                ),
                rawId = json.optString("raw_id"),
                payload = payloadJson?.let { runCatching { JSONObject(it) }.getOrNull() },
                createdAt = json.optString("created_at").takeIf { it.isNotEmpty() }
                    ?.let { runCatching { Instant.parse(it) }.getOrNull() },
                attempt = json.optInt("attempt", 0),
                isReplay = json.optBoolean("is_replay", false),
            )
        }
    }
}
