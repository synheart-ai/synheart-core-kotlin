package ai.synheart.core.bridge

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sun.jna.Callback
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.Structure

/**
 * Host-provided device-auth callback tables the native runtime drives:
 *   1. Crypto callbacks  — Android Keystore P-256 keygen / ES256 signing via
 *      [DeviceAuthCrypto].
 *   2. Secure-storage callbacks — EncryptedSharedPreferences, used by the
 *      runtime to persist consent tokens + device records.
 *
 * Strong references to every [Callback] and the [Structure] are held here for
 * the process lifetime so JNA's native trampolines outlive the runtime's use.
 */
internal object DeviceAuthCallbacks {

    // ---- C signatures (arm64: usize == NativeLong, 8 bytes) -------------- //

    fun interface GenerateKeyCb : Callback { fun callback(deviceId: Pointer?): Pointer? }
    fun interface SignBytesCb : Callback { fun callback(deviceId: Pointer?, data: Pointer?, len: NativeLong): Pointer? }
    fun interface GetAttestationCb : Callback { fun callback(deviceId: Pointer?, hash: Pointer?, len: NativeLong): Pointer? }
    fun interface KeyExistsCb : Callback { fun callback(deviceId: Pointer?): Int }
    fun interface DeleteKeyCb : Callback { fun callback(deviceId: Pointer?): Int }

    fun interface StoreCb : Callback { fun callback(service: Pointer?, key: Pointer?, value: Pointer?): Int }
    fun interface LoadCb : Callback { fun callback(service: Pointer?, key: Pointer?): Pointer? }
    fun interface DeleteStoreCb : Callback { fun callback(service: Pointer?, key: Pointer?): Int }

    @Suppress("unused")
    class CryptoCallbacksStruct : Structure() {
        @JvmField var generate_key: GenerateKeyCb? = null
        @JvmField var sign_bytes: SignBytesCb? = null
        @JvmField var get_attestation: GetAttestationCb? = null
        @JvmField var key_exists: KeyExistsCb? = null
        @JvmField var delete_key: DeleteKeyCb? = null
        override fun getFieldOrder() =
            listOf("generate_key", "sign_bytes", "get_attestation", "key_exists", "delete_key")
    }

    @Volatile private var appContext: Context? = null

    /** Provide the application context used by the secure-storage callbacks. */
    fun attachContext(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    // ---- Crypto callbacks (strong refs) ---------------------------------- //

    private val generateKey = GenerateKeyCb { devId ->
        devId?.getString(0)?.let { DeviceAuthCrypto.generateKeyJson(it)?.let(::cString) }
    }
    private val signBytes = SignBytesCb { devId, data, len ->
        val id = devId?.getString(0) ?: return@SignBytesCb null
        val n = len.toInt()
        if (data == null || n <= 0) return@SignBytesCb null
        DeviceAuthCrypto.signBase64Url(id, data.getByteArray(0, n))?.let(::cString)
    }
    /**
     * Platform attestation material for a registration challenge.
     *
     * The runtime's FFI contract is a JSON object `{"format":…,"blob":…}`; it
     * reads the returned pointer and treats a NULL as a hard
     * `PlatformCrypto("null callback result")` failure. This used to be a
     * hardcoded `null`, which made that failure unconditional: registration
     * could never get past step 4/7 on Android, whatever the configuration.
     *
     * It also made [DeviceAuthConfig.allowUnattestedDevRegistration]
     * unreachable. That flag's documented behaviour — "sends the registration
     * carrying `format:"none"` and an empty blob, and the server decides" —
     * requires the callback to *answer*. A null is not "no material available",
     * it is "the callback is broken", and the runtime cannot tell the difference
     * between a device that cannot attest and a host that never wired this up.
     *
     * So: report no material, in the shape the contract expects. An empty blob
     * is the runtime's documented signal for "Play Integrity unavailable"
     * (emulator, de-Googled ROM, no Play services), which it maps onto either
     * an unattested registration or a local-only fallback.
     *
     * This deliberately does NOT mint a real Play Integrity token.
     * `synheart-auth` ships [ai.synheart.auth.registration.PlayIntegrityAttestationProvider]
     * for that, but its `generateProof` is `suspend` and this callback is a
     * synchronous FFI trampoline — bridging them means blocking a runtime thread
     * on an Integrity API round-trip, which needs its own timeout and
     * cancellation design rather than a `runBlocking` bolted on here. A host
     * that needs attested provenance today should supply the token through that
     * provider path; nothing fake is ever sent from here.
     */
    private val getAttestation = GetAttestationCb { _, _, _ ->
        cString("""{"format":"none","blob":""}""")
    }
    private val keyExists = KeyExistsCb { devId ->
        if (devId?.getString(0)?.let(DeviceAuthCrypto::keyExists) == true) 1 else 0
    }
    private val deleteKey = DeleteKeyCb { devId ->
        if (devId?.getString(0)?.let(DeviceAuthCrypto::deleteKey) == true) 0 else 1
    }

    /** Built once and retained; pass its pointer to `set_crypto_callbacks`. */
    val cryptoStruct: CryptoCallbacksStruct by lazy {
        CryptoCallbacksStruct().apply {
            generate_key = generateKey
            sign_bytes = signBytes
            get_attestation = getAttestation
            key_exists = keyExists
            delete_key = deleteKey
            write()
        }
    }

    // ---- Secure-storage callbacks (strong refs) -------------------------- //

    private val prefs: SharedPreferences? by lazy {
        val ctx = appContext ?: return@lazy null
        runCatching {
            val masterKey = MasterKey.Builder(ctx)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                ctx,
                "synheart_core_secure_store",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrNull()
    }

    private fun storeKey(service: String, key: String) = "$service::$key"

    val store = StoreCb { svc, key, value ->
        val p = prefs ?: return@StoreCb 1
        val s = svc?.getString(0); val k = key?.getString(0); val v = value?.getString(0)
        if (s == null || k == null || v == null) return@StoreCb 1
        if (p.edit().putString(storeKey(s, k), v).commit()) 0 else 1
    }
    val load = LoadCb { svc, key ->
        val p = prefs ?: return@LoadCb null
        val s = svc?.getString(0); val k = key?.getString(0)
        if (s == null || k == null) return@LoadCb null
        p.getString(storeKey(s, k), null)?.let(::cString)
    }
    val delete = DeleteStoreCb { svc, key ->
        val p = prefs ?: return@DeleteStoreCb 1
        val s = svc?.getString(0); val k = key?.getString(0)
        if (s == null || k == null) return@DeleteStoreCb 1
        if (p.edit().remove(storeKey(s, k)).commit()) 0 else 1
    }

    /** Allocate a NUL-terminated C string with the system allocator. The runtime
     * frees it (matching `malloc`/`free`). */
    private fun cString(s: String): Pointer {
        val bytes = s.toByteArray(Charsets.UTF_8)
        val addr = Native.malloc((bytes.size + 1).toLong())
        val p = Pointer(addr)
        p.write(0, bytes, 0, bytes.size)
        p.setByte(bytes.size.toLong(), 0)
        return p
    }
}
