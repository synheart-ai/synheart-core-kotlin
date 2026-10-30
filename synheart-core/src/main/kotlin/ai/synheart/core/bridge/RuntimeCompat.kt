package ai.synheart.core.bridge

import ai.synheart.core.SYNHEART_CORE_VERSION
import org.json.JSONObject

/**
 * Runtime-version compatibility for the hand-written C ABI bindings.
 *
 * The runtime's C surface is additive and stable — a binding written against
 * one version still *links* against a much newer one — so nothing fails loudly
 * when the two drift apart. What moves between versions is semantics, JSON
 * shapes, error-code vocabulary and which symbols a build exports, none of
 * which the loader checks (JNA binds lazily, so even a missing symbol only
 * surfaces at the call site). This is the one place the SDK states which
 * runtime its bindings assume and compares it to what actually loaded.
 */
object RuntimeCompat {

    /**
     * The newest runtime release these bindings were verified against, and the
     * release every install hint recommends. Bump it when the bindings adopt a
     * new symbol or a moved shape, or once they are verified on a newer
     * release.
     */
    const val WRITTEN_AGAINST: String = "0.45.0"

    /**
     * Oldest runtime the bindings are known to load and behave on. Below this
     * the SDK refuses to initialise rather than run with entrypoints that no
     * longer mean what the code assumes. `0.45.0` is the first release on C
     * ABI 4.2, which these bindings require.
     */
    const val MINIMUM: String = "0.45.0"

    /**
     * The C ABI this SDK requires, as `MAJOR.MINOR`. The runtime bumps MAJOR on
     * any removal, rename or signature change and MINOR on additions only, so a
     * runtime is compatible when its MAJOR equals [REQUIRED_ABI_MAJOR] and its
     * MINOR is at least [REQUIRED_ABI_MINOR]. This, not the release version, is
     * the contract; the version checks only grade runtimes that satisfy it.
     *
     * 4.2 because the daily score inputs changed shape with it
     * (`baseline_maturity`, the Sleep Score's `baselines`): neither shape
     * parses on the other runtime, so there is no fallback to an older one.
     */
    const val REQUIRED_ABI: String = "4.2"
    const val REQUIRED_ABI_MAJOR: Int = 4
    const val REQUIRED_ABI_MINOR: Int = 2

    /**
     * Newest ABI major the bindings also accept. Only 4: ABI 4.0 re-signed every
     * sample push (each now takes `provider` and `utc_offset_min`) and the
     * personal-baseline day calls (calendar dates instead of day indexes). JNA
     * does not check a signature, so a binding for the other major would still
     * link and then pass the wrong arguments — a native crash, not an error.
     * Raise it only after checking a new major's removals and re-signings
     * against the bindings.
     */
    const val MAX_ABI_MAJOR: Int = 4

    /** The accepted ABI range, for messages. */
    val abiRange: String
        get() = if (MAX_ABI_MAJOR == REQUIRED_ABI_MAJOR) {
            "$REQUIRED_ABI_MAJOR.x"
        } else {
            "$REQUIRED_ABI to $MAX_ABI_MAJOR.x"
        }

    /** Installs the release the bindings were written against. */
    const val INSTALL_COMMAND: String = "synheart install runtime --version $WRITTEN_AGAINST"

    private const val FIX = "Install runtime $WRITTEN_AGAINST: $INSTALL_COMMAND"

    /** A parsed `MAJOR.MINOR` ABI version. */
    data class Abi(val major: Int, val minor: Int)

    /** Whether a runtime reporting [abi] can be used. */
    fun acceptsAbi(abi: Abi): Boolean {
        if (abi.major < REQUIRED_ABI_MAJOR || abi.major > MAX_ABI_MAJOR) return false
        return abi.major > REQUIRED_ABI_MAJOR || abi.minor >= REQUIRED_ABI_MINOR
    }

    /** Parse `MAJOR.MINOR` (extra components ignored); null if malformed. */
    fun parseAbi(abi: String): Abi? {
        val m = Regex("""^(\d+)\.(\d+)""").find(abi.trim()) ?: return null
        return Abi(m.groupValues[1].toInt(), m.groupValues[2].toInt())
    }

    /**
     * Compare two dotted numeric versions (`0.31.1`). Non-numeric suffixes are
     * ignored; a missing component reads as `0`. Negative when [a] < [b].
     */
    fun compare(a: String, b: String): Int {
        fun parse(v: String): List<Int> = v.split('.').map { part ->
            Regex("""^\d+""").find(part)?.value?.toIntOrNull() ?: 0
        }
        val pa = parse(a)
        val pb = parse(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrElse(i) { 0 }
            val y = pb.getOrElse(i) { 0 }
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    /**
     * Evaluate the loaded runtime's `build_info`: its ABI against
     * [REQUIRED_ABI] when it reports one (runtime 0.33.0+), otherwise its
     * version against [MINIMUM]; then against [WRITTEN_AGAINST] for status.
     */
    fun check(buildInfo: JSONObject?): RuntimeCompatResult {
        val version = buildInfo?.optString("core_runtime", "")?.takeIf { it.isNotEmpty() }
        val rawAbi = buildInfo?.opt("abi") as? String
        val parsedAbi = rawAbi?.let { parseAbi(it) }
        val abi = if (parsedAbi == null) null else rawAbi
        if (parsedAbi != null && !acceptsAbi(parsedAbi)) {
            return RuntimeCompatResult(
                version = version,
                abi = abi,
                status = RuntimeCompatStatus.INCOMPATIBLE_ABI,
                message = "Core runtime ${version ?: "(unknown version)"} (ABI $abi) is " +
                    "incompatible with synheart-core $SYNHEART_CORE_VERSION (needs ABI " +
                    "$abiRange). $FIX",
            )
        }
        if (version == null) {
            return RuntimeCompatResult(
                version = null,
                abi = abi,
                status = RuntimeCompatStatus.UNKNOWN,
                message = "[Synheart] runtime version unknown — build_info carried no " +
                    "core_runtime; bindings assume $WRITTEN_AGAINST. Expect silent " +
                    "divergence if the vendored library is older.",
            )
        }
        if (compare(version, MINIMUM) < 0) {
            return RuntimeCompatResult(
                version = version,
                abi = abi,
                status = RuntimeCompatStatus.TOO_OLD,
                message = "Core runtime $version is incompatible with synheart-core " +
                    "$SYNHEART_CORE_VERSION (needs runtime $MINIMUM or newer). $FIX",
            )
        }
        if (compare(version, WRITTEN_AGAINST) < 0) {
            return RuntimeCompatResult(
                version = version,
                abi = abi,
                status = RuntimeCompatStatus.OLDER,
                message = "[Synheart] runtime $version is older than $WRITTEN_AGAINST, which " +
                    "these bindings were verified against. Symbols added since then " +
                    "fall back, and behaviour documented for $WRITTEN_AGAINST may not " +
                    "hold. Update the vendored runtime with `synheart install runtime`.",
            )
        }
        return RuntimeCompatResult(
            version = version,
            abi = abi,
            status = RuntimeCompatStatus.OK,
            message = "[Synheart] runtime $version (bindings: $WRITTEN_AGAINST)",
        )
    }
}

enum class RuntimeCompatStatus {
    /** At or above [RuntimeCompat.WRITTEN_AGAINST]. */
    OK,

    /** Loads and works, but predates the version the bindings assume. */
    OLDER,

    /**
     * Below [RuntimeCompat.MINIMUM] on a runtime that reports no ABI;
     * initialisation is refused.
     */
    TOO_OLD,

    /**
     * The runtime's ABI is outside [RuntimeCompat.abiRange]; initialisation is
     * refused.
     */
    INCOMPATIBLE_ABI,

    /** `build_info` did not report a version. */
    UNKNOWN,
}

data class RuntimeCompatResult(
    val version: String?,
    /** The runtime's ABI (`MAJOR.MINOR`); null for runtimes before 0.33.0. */
    val abi: String? = null,
    val status: RuntimeCompatStatus,
    val message: String,
) {
    val isAcceptable: Boolean
        get() = status != RuntimeCompatStatus.TOO_OLD &&
            status != RuntimeCompatStatus.INCOMPATIBLE_ABI
}
