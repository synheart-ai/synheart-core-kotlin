package ai.synheart.core.bridge

import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.sun.jna.Memory
import com.sun.jna.Pointer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.Signature
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/**
 * On-device validation of the device-auth crypto + storage backing the runtime
 * callbacks. Runs against the real Android Keystore on a connected device.
 */
@RunWith(AndroidJUnit4::class)
class DeviceAuthInstrumentedTest {

    private val deviceId = "instr-test-${System.nanoTime()}"

    @After
    fun cleanup() {
        DeviceAuthCrypto.deleteKey(deviceId)
    }

    @Test
    fun generateSignAndVerify_roundTripsAgainstHardwareKey() {
        // 1. Generate (or reuse) a Keystore P-256 key; get its public coords.
        val json = DeviceAuthCrypto.generateKeyJson(deviceId)
        assertNotNull("generateKeyJson returned null", json)
        val obj = JSONObject(json!!)
        val pub = ecPublicKeyFrom(obj.getString("x"), obj.getString("y"))

        assertTrue("key should exist after generate", DeviceAuthCrypto.keyExists(deviceId))

        // 2. Sign a message; callback yields base64url raw r||s.
        val message = "device-auth proof payload".toByteArray()
        val sigB64 = DeviceAuthCrypto.signBase64Url(deviceId, message)
        assertNotNull("signBase64Url returned null", sigB64)
        val raw = Base64.decode(sigB64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        assertEquals("raw ES256 signature must be 64 bytes (r||s)", 64, raw.size)

        // 3. Verify with the public key — proves keygen + DER->raw are correct.
        val verifier = Signature.getInstance("SHA256withECDSA")
        verifier.initVerify(pub)
        verifier.update(message)
        assertTrue("signature must verify against the returned public key", verifier.verify(rawToDer(raw)))

        // 4. Delete clears the key.
        assertTrue(DeviceAuthCrypto.deleteKey(deviceId))
        assertFalse("key should be gone after delete", DeviceAuthCrypto.keyExists(deviceId))
    }

    @Test
    fun secureStorage_roundTripsThroughCallbacks() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        DeviceAuthCallbacks.attachContext(ctx)

        val svc = cstr("svc.test"); val key = cstr("token"); val value = cstr("blob-${System.nanoTime()}")
        try {
            val stored = DeviceAuthCallbacks.store.callback(svc, key, value)
            assertEquals(0, stored)

            val loaded = DeviceAuthCallbacks.load.callback(svc, key)
            assertNotNull("load returned null", loaded)
            assertEquals(value.getString(0), loaded!!.getString(0))

            assertEquals(0, DeviceAuthCallbacks.delete.callback(svc, key))
            assertNull("value should be gone after delete", DeviceAuthCallbacks.load.callback(svc, key))
        } finally {
            DeviceAuthCallbacks.delete.callback(svc, key)
        }
    }

    // ---- helpers --------------------------------------------------------- //

    private fun cstr(s: String): Memory {
        val b = s.toByteArray(Charsets.UTF_8)
        val m = Memory((b.size + 1).toLong())
        m.write(0, b, 0, b.size)
        m.setByte(b.size.toLong(), 0)
        return m
    }

    private fun ecPublicKeyFrom(xB64: String, yB64: String): java.security.PublicKey {
        val x = BigInteger(1, Base64.decode(xB64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        val y = BigInteger(1, Base64.decode(yB64, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
        val params = AlgorithmParameters.getInstance("EC").apply {
            init(java.security.spec.ECGenParameterSpec("secp256r1"))
        }.getParameterSpec(ECParameterSpec::class.java)
        val spec = ECPublicKeySpec(ECPoint(x, y), params)
        return KeyFactory.getInstance("EC").generatePublic(spec)
    }

    /** Raw 64-byte r||s → DER `SEQUENCE { INTEGER r, INTEGER s }` for Signature.verify. */
    private fun rawToDer(raw: ByteArray): ByteArray {
        fun derInt(b: ByteArray): ByteArray {
            var v = b.dropWhile { it == 0.toByte() }.toByteArray()
            if (v.isEmpty()) v = byteArrayOf(0)
            if (v[0].toInt() and 0x80 != 0) v = byteArrayOf(0) + v
            return byteArrayOf(0x02, v.size.toByte()) + v
        }
        val body = derInt(raw.copyOfRange(0, 32)) + derInt(raw.copyOfRange(32, 64))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}
