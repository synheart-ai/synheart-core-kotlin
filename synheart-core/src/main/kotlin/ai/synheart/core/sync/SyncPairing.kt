package ai.synheart.core.sync

import org.json.JSONObject
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeParseException

/** The server's verdict on a pairing token this device minted. */
enum class SyncPairingState(val wire: String) {
    /** The code is valid and nobody has used it yet. Keep polling. */
    WAITING("waiting"),

    /** Another device joined with the code. Stop polling. */
    JOINED("joined"),

    /** The code expired unused. Stop polling and offer a new code. */
    EXPIRED("expired"),

    /**
     * The server sent a state this SDK does not know. Treat it as still
     * pending: keep the code on screen, keep polling until `expiresAt`, and
     * claim neither success nor failure.
     */
    UNKNOWN("unknown");

    /** Polling can stop: the pairing reached a final state. */
    val isTerminal: Boolean get() = this == JOINED || this == EXPIRED

    companion object {
        fun fromWire(s: String?): SyncPairingState =
            entries.firstOrNull { it.wire == s && it != UNKNOWN } ?: UNKNOWN
    }
}

/** The device that joined with a pairing code. */
data class SyncPairedDevice(
    val deviceId: String,
    val deviceName: String? = null,
)

/**
 * Pairing status for a token from `Synheart.syncGeneratePairing()`.
 *
 * Read it with `Synheart.syncPairingStatus(token)` while the pairing code is
 * on screen, every 2–3 seconds, and stop when [state] is terminal
 * ([SyncPairingState.JOINED] or [SyncPairingState.EXPIRED]) or [expiresAt]
 * has passed. Never infer pairing from the device list instead.
 */
data class SyncPairingStatus(
    val state: SyncPairingState,
    /** The space the code invites into. */
    val syncSpaceId: String? = null,
    /** The device that joined, once [state] is [SyncPairingState.JOINED]. */
    val joinedDevice: SyncPairedDevice? = null,
    /** When the other device joined. */
    val joinedAt: Instant? = null,
    /** When the code stops working. Bound the polling loop on it. */
    val expiresAt: Instant? = null,
) {
    /** The code is past its expiry, whatever the server last said. */
    fun isExpiredAt(now: Instant): Boolean =
        state == SyncPairingState.EXPIRED || (expiresAt != null && !now.isBefore(expiresAt))

    companion object {
        /** Parse the runtime's `sync_pairing_status` payload. */
        fun fromJson(json: JSONObject): SyncPairingStatus {
            val device = json.optJSONObject("joined_device")
            val deviceId = device?.opt("device_id") as? String
            return SyncPairingStatus(
                state = SyncPairingState.fromWire(json.opt("status") as? String),
                syncSpaceId = (json.opt("sync_space_id") as? String)?.takeIf { it.isNotEmpty() },
                joinedDevice = deviceId?.let {
                    SyncPairedDevice(it, device?.opt("device_name") as? String)
                },
                joinedAt = instantOrNull(json.opt("joined_at")),
                expiresAt = instantOrNull(json.opt("expires_at")),
            )
        }

        /** RFC 3339 timestamp, with or without fractional seconds, or `null`. */
        private fun instantOrNull(v: Any?): Instant? {
            val s = v as? String ?: return null
            return try {
                OffsetDateTime.parse(s).toInstant()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }
}
