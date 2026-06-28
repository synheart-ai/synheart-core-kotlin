package ai.synheart.core.bridge

import ai.synheart.auth.crypto.HardwareKeyManager
import ai.synheart.auth.crypto.KeyManaging
import android.util.Base64
import java.math.BigInteger

/**
 * Device-auth crypto backed by the Android Keystore (via synheart-auth's
 * [HardwareKeyManager]), keyed by the runtime's `device_id`. Produces exactly
 * what the native runtime's crypto callbacks expect:
 *
 * - `generateKeyJson` → `{"x":"<b64url>","y":"<b64url>"}` P-256 public-key coords.
 * - `signBase64Url`   → base64url of the raw 64-byte `r||s` ES256 signature.
 *
 * The keystore key is reused across calls within a registration flow (load or
 * create), matching the iOS Secure Enclave behaviour.
 */
internal object DeviceAuthCrypto {

    private val keyManager: KeyManaging by lazy { HardwareKeyManager.create() }

    /** Reuse-or-create the key and return its public-key coordinates JSON, or null. */
    fun generateKeyJson(deviceId: String): String? = try {
        val pub = if (keyManager.hasKey(deviceId)) {
            keyManager.getPublicKey(deviceId) ?: keyManager.generateKeyPair(deviceId)
        } else {
            keyManager.generateKeyPair(deviceId)
        }
        val coords = splitUncompressedPoint(pub)
        coords?.let { (x, y) -> "{\"x\":\"$x\",\"y\":\"$y\"}" }
    } catch (e: Exception) {
        null
    }

    /** Sign `data` (SHA-256 then ES256) and return base64url of raw 64-byte `r||s`, or null. */
    fun signBase64Url(deviceId: String, data: ByteArray): String? = try {
        // HardwareKeyManager.sign uses SHA256withECDSA (hash-then-sign) and
        // returns a DER signature; the runtime wants the raw compact form.
        val der = keyManager.sign(data, deviceId)
        base64Url(derToRawRS(der))
    } catch (e: Exception) {
        null
    }

    fun keyExists(deviceId: String): Boolean = try {
        keyManager.hasKey(deviceId)
    } catch (e: Exception) {
        false
    }

    fun deleteKey(deviceId: String): Boolean = try {
        keyManager.deleteKey(deviceId)
        true
    } catch (e: Exception) {
        false
    }

    // ---- helpers --------------------------------------------------------- //

    /** X9.62 uncompressed point (0x04 || X32 || Y32) → (x, y) base64url. */
    private fun splitUncompressedPoint(point: ByteArray): Pair<String, String>? {
        if (point.size != 65 || point[0] != 0x04.toByte()) return null
        val x = point.copyOfRange(1, 33)
        val y = point.copyOfRange(33, 65)
        return base64Url(x) to base64Url(y)
    }

    /** DER `SEQUENCE { INTEGER r, INTEGER s }` → raw 64-byte `r||s` (32 each). */
    private fun derToRawRS(der: ByteArray): ByteArray {
        var i = 0
        require(der[i].toInt() and 0xff == 0x30) { "bad DER: no SEQUENCE" }; i++
        // Sequence length (P-256 sigs are always short-form, < 128).
        i++
        require(der[i].toInt() and 0xff == 0x02) { "bad DER: no INTEGER r" }; i++
        val rLen = der[i].toInt() and 0xff; i++
        val r = der.copyOfRange(i, i + rLen); i += rLen
        require(der[i].toInt() and 0xff == 0x02) { "bad DER: no INTEGER s" }; i++
        val sLen = der[i].toInt() and 0xff; i++
        val s = der.copyOfRange(i, i + sLen)
        return toFixed32(r) + toFixed32(s)
    }

    /** Big-endian magnitude → fixed 32-byte big-endian (strip sign byte / left-pad). */
    private fun toFixed32(b: ByteArray): ByteArray {
        val mag = BigInteger(1, b).toByteArray().let {
            if (it.size > 1 && it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it
        }
        require(mag.size <= 32) { "integer too large for P-256" }
        val out = ByteArray(32)
        System.arraycopy(mag, 0, out, 32 - mag.size, mag.size)
        return out
    }

    private fun base64Url(data: ByteArray): String =
        Base64.encodeToString(data, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
}
