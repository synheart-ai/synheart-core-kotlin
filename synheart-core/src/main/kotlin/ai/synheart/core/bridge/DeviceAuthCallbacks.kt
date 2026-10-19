package ai.synheart.core.bridge

import ai.synheart.core.SynheartLogger
import android.content.Context
import android.content.SharedPreferences
import android.os.SystemClock
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

    /**
     * The secure store, opened on first successful use and reused afterwards.
     *
     * Deliberately NOT a `lazy` that caches a failure: `EncryptedSharedPreferences`
     * creation goes through the Android Keystore, which is not ready for a
     * moment after boot and faults on some OEM builds, and a `lazy` that
     * memoised that first null made secure storage unavailable for the rest
     * of the process — every `load` then read as "absent".
     */
    @Volatile private var prefsInstance: SharedPreferences? = null
    private val prefsLock = Any()

    private fun openPrefs(): SharedPreferences? {
        prefsInstance?.let { return it }
        val ctx = appContext ?: return null
        synchronized(prefsLock) {
            prefsInstance?.let { return it }
            return try {
                createPrefs(ctx).also { prefsInstance = it }
            } catch (e: Exception) {
                if (!isSealedWithAnotherKey(e)) {
                    // Transient (Keystore not ready, user not authenticated,
                    // OEM fault): keep the file, the caller retries.
                    SynheartLogger.log(
                        "[DeviceAuthCallbacks] secure store unavailable: " +
                            "${e::class.java.name}: ${e.message}",
                    )
                    return null
                }
                // The store's keyset was sealed by a master key this install
                // does not have - typically app data restored from a backup
                // or a device transfer, since Keystore keys are never backed
                // up. Every value in it is permanently unreadable, and keeping
                // it fails every secure read forever. Start an empty store.
                SynheartLogger.log(
                    "[DeviceAuthCallbacks] secure store was sealed by another " +
                        "Keystore key (restored backup?) - discarding it",
                )
                try {
                    ctx.deleteSharedPreferences(SECURE_PREFS_FILE)
                    clearSmkProvisionedMarkers(ctx)
                    createPrefs(ctx).also { prefsInstance = it }
                } catch (e2: Exception) {
                    SynheartLogger.log(
                        "[DeviceAuthCallbacks] secure store re-create failed: " +
                            "${e2::class.java.name}: ${e2.message}",
                    )
                    null
                }
            }
        }
    }

    private const val SECURE_PREFS_FILE = "synheart_core_secure_store"

    // Runtime file-name convention (synheart_crypto::smk); keep in sync.
    private const val SMK_PROVISIONED_MARKER_PREFIX = ".smk-provisioned-"

    private fun createPrefs(ctx: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(ctx)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            ctx,
            SECURE_PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * True only for the definitive "wrong key" failure: the GCM tag check of
     * the stored keyset failed. Transient Keystore states throw other types,
     * and must never discard the store - that would orphan everything in it.
     */
    private fun isSealedWithAnotherKey(e: Throwable): Boolean {
        var cause: Throwable? = e
        var depth = 0
        while (cause != null && depth < 8) {
            if (cause is javax.crypto.AEADBadTagException) return true
            cause = cause.cause
            depth++
        }
        return false
    }

    /**
     * Removes the runtime's `.smk-provisioned-<subject>` markers.
     *
     * The runtime writes one beside its data the first time it stores a
     * subject's storage master key in this store, and afterwards refuses to
     * mint a replacement when the key reads back absent (it assumes a locked
     * Keystore and waits). Once the store is discarded, every key the markers
     * vouch for is gone for good, so they would block the subject forever
     * (ERR_SECURE_STORAGE_UNAVAILABLE). Markers live in plain app storage, so
     * a restore brings them back with the unreadable store. Searched across
     * the app's data dir because each runtime instance has its own data dir.
     */
    private fun clearSmkProvisionedMarkers(ctx: Context) {
        val root = ctx.filesDir.parentFile ?: ctx.filesDir
        var removed = 0
        root.walkTopDown()
            .maxDepth(6)
            .filter { it.isFile && it.name.startsWith(SMK_PROVISIONED_MARKER_PREFIX) }
            .forEach { if (it.delete()) removed++ }
        SynheartLogger.log(
            "[DeviceAuthCallbacks] removed $removed SMK provisioning marker(s)",
        )
    }

    /**
     * Bounded retry budget for a transiently unavailable secure store
     * (Keystore not ready right after boot, `UserNotAuthenticatedException`,
     * OEM Keystore hiccups). ~1 s total (150 + 300 + 600 ms). The runtime
     * holds its mutex across the storage callbacks, so this stays short on
     * purpose.
     */
    private const val SECURE_LOAD_ATTEMPTS = 4
    private const val SECURE_LOAD_INITIAL_BACKOFF_MS = 150L

    private fun storeKey(service: String, key: String) = "$service::$key"

    val store = StoreCb { svc, key, value ->
        val p = openPrefs() ?: return@StoreCb 1
        val s = svc?.getString(0); val k = key?.getString(0); val v = value?.getString(0)
        if (s == null || k == null || v == null) return@StoreCb 1
        try {
            if (p.edit().putString(storeKey(s, k), v).commit()) 0 else 1
        } catch (e: Exception) {
            SynheartLogger.log("[DeviceAuthCallbacks] secure_store($s, $k) failed: ${e.message}")
            1
        }
    }

    /**
     * `secure_load`: returns NULL ONLY when the key is genuinely absent.
     *
     * The C signature has no error channel, so a storage failure — the store
     * could not be opened, or the read threw — is retried with a short bounded
     * backoff and, if it still fails, ALSO returns NULL. On a runtime ≥ 0.31.1
     * the provisioning marker turns that NULL into
     * `ERR_SECURE_STORAGE_UNAVAILABLE` (retryable) instead of a re-minted
     * storage master key; on an older runtime the re-mint — which orphans
     * every blob sealed so far — remains (SDK-CONTRACT-CHANGES §4.4).
     * Previously any failure returned NULL on the first try and read as
     * "fresh install".
     */
    val load = LoadCb { svc, key ->
        val s = svc?.getString(0); val k = key?.getString(0)
        if (s == null || k == null) return@LoadCb null
        val storageKey = storeKey(s, k)
        var lastError: Throwable? = null
        var backoffMs = SECURE_LOAD_INITIAL_BACKOFF_MS
        for (attempt in 1..SECURE_LOAD_ATTEMPTS) {
            val p = openPrefs()
            if (p != null) {
                try {
                    // `contains` is the absent/present question; a null value
                    // for a present key is treated as absent too.
                    if (!p.contains(storageKey)) return@LoadCb null
                    return@LoadCb p.getString(storageKey, null)?.let(::cString)
                } catch (e: Exception) {
                    lastError = e
                }
            }
            if (attempt < SECURE_LOAD_ATTEMPTS) {
                SynheartLogger.log(
                    "[DeviceAuthCallbacks] secure_load($s, $k): secure storage unavailable " +
                        "(${lastError?.message ?: "store could not be opened"}), " +
                        "retry $attempt/${SECURE_LOAD_ATTEMPTS - 1}",
                )
                SystemClock.sleep(backoffMs)
                backoffMs *= 2
            }
        }
        SynheartLogger.log(
            "[DeviceAuthCallbacks] secure_load($s, $k): secure storage unavailable after " +
                "$SECURE_LOAD_ATTEMPTS attempts — returning NULL, which the runtime " +
                "cannot tell from absent" + (lastError?.let { ": ${it.message}" } ?: ""),
        )
        null
    }

    val delete = DeleteStoreCb { svc, key ->
        val p = openPrefs() ?: return@DeleteStoreCb 1
        val s = svc?.getString(0); val k = key?.getString(0)
        if (s == null || k == null) return@DeleteStoreCb 1
        try {
            if (p.edit().remove(storeKey(s, k)).commit()) 0 else 1
        } catch (e: Exception) {
            SynheartLogger.log("[DeviceAuthCallbacks] secure_delete($s, $k) failed: ${e.message}")
            1
        }
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
