package ai.synheart.core.bridge

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
     * The runtime release these bindings were written and tested against.
     * Bump it in the same change that adopts a new symbol or a moved shape.
     */
    const val WRITTEN_AGAINST: String = "0.31.1"

    /**
     * Oldest runtime the bindings are known to load and behave on. Below this
     * the SDK refuses to initialise rather than run with entrypoints that no
     * longer mean what the code assumes. `0.20.0` is the baseline of the
     * runtime's `SDK-CONTRACT-CHANGES.md`.
     */
    const val MINIMUM: String = "0.20.0"

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
     * Evaluate the loaded runtime's `build_info` against [MINIMUM] and
     * [WRITTEN_AGAINST].
     */
    fun check(buildInfo: JSONObject?): RuntimeCompatResult {
        val version = buildInfo?.optString("core_runtime", "")?.takeIf { it.isNotEmpty() }
            ?: return RuntimeCompatResult(
                version = null,
                status = RuntimeCompatStatus.UNKNOWN,
                message = "[Synheart] runtime version unknown — build_info carried no " +
                    "core_runtime; bindings assume $WRITTEN_AGAINST. Expect silent " +
                    "divergence if the vendored library is older.",
            )
        if (compare(version, MINIMUM) < 0) {
            return RuntimeCompatResult(
                version = version,
                status = RuntimeCompatStatus.TOO_OLD,
                message = "[Synheart] runtime $version is below the minimum $MINIMUM these " +
                    "bindings support — refusing to initialise. Update the vendored " +
                    "runtime with `synheart install runtime`.",
            )
        }
        if (compare(version, WRITTEN_AGAINST) < 0) {
            return RuntimeCompatResult(
                version = version,
                status = RuntimeCompatStatus.OLDER,
                message = "[Synheart] runtime $version is older than $WRITTEN_AGAINST, which " +
                    "these bindings were written against. Newer symbols fall back " +
                    "(buffered HSI, context fan-in, secure-storage marker …) and " +
                    "behaviour documented for $WRITTEN_AGAINST may not hold. Update " +
                    "the vendored runtime with `synheart install runtime`.",
            )
        }
        return RuntimeCompatResult(
            version = version,
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

    /** Below [RuntimeCompat.MINIMUM]; initialisation is refused. */
    TOO_OLD,

    /** `build_info` did not report a version. */
    UNKNOWN,
}

data class RuntimeCompatResult(
    val version: String?,
    val status: RuntimeCompatStatus,
    val message: String,
) {
    val isAcceptable: Boolean get() = status != RuntimeCompatStatus.TOO_OLD
}
