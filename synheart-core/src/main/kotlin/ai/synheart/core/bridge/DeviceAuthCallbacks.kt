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
    private val getAttestation = GetAttestationCb { _, _, _ -> null }
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
