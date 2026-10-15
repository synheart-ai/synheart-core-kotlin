package ai.synheart.core.bridge

/** Why the native runtime could not be used. */
enum class SynheartRuntimeErrorKind {
    /** No runtime library was found for this device. */
    NOT_INSTALLED,

    /** A library was found but was built for a different CPU architecture. */
    WRONG_ARCHITECTURE,

    /** The runtime loaded, but its ABI or version does not fit this SDK. */
    INCOMPATIBLE,

    /** The library exists but the loader rejected it, e.g. a damaged file. */
    LOAD_FAILED,
}

/**
 * The native runtime is missing, unusable, or incompatible with this SDK.
 *
 * `Synheart.initialize` throws it for [SynheartRuntimeErrorKind.INCOMPATIBLE]
 * only. The other kinds leave initialisation degraded (no HSI) and are
 * readable from `Synheart.runtimeError`.
 */
class SynheartRuntimeException(
    val kind: SynheartRuntimeErrorKind,
    /** One plain sentence naming the versions involved and the fix. */
    override val message: String,
    /** ABI this SDK requires (`MAJOR.MINOR`; the runtime must match MAJOR and be at least this MINOR). */
    val requiredAbi: String = RuntimeCompat.REQUIRED_ABI,
    /** Runtime release to install. */
    val recommendedVersion: String = RuntimeCompat.WRITTEN_AGAINST,
    /** Exact shell command that installs [recommendedVersion]. */
    val installCommand: String = RuntimeCompat.INSTALL_COMMAND,
    /** Loaded runtime version, when one loaded and reported it. */
    val runtimeVersion: String? = null,
    /** Loaded runtime ABI (`MAJOR.MINOR`), when it reported one. */
    val runtimeAbi: String? = null,
    /**
     * Raw loader text, for support. Not meant for end users. Named apart from
     * [Throwable.cause], which is the wrapped exception.
     */
    val rawCause: String? = null,
) : Exception(message) {

    override fun toString(): String = "SynheartRuntimeException(${kind.name}): $message"

    companion object {
        private const val FIX =
            "Install runtime ${RuntimeCompat.WRITTEN_AGAINST}: ${RuntimeCompat.INSTALL_COMMAND}"

        /** Build the exception for a runtime that failed to load. */
        fun loadFailure(kind: SynheartRuntimeErrorKind, cause: String? = null): SynheartRuntimeException {
            val message = when (kind) {
                SynheartRuntimeErrorKind.NOT_INSTALLED ->
                    "Core runtime not found. synheart-core needs runtime ABI " +
                        "${RuntimeCompat.abiRange}. $FIX"
                SynheartRuntimeErrorKind.WRONG_ARCHITECTURE ->
                    "Core runtime was built for a different CPU architecture than this " +
                        "device. $FIX"
                else ->
                    "Core runtime file is damaged or not built for this device. " +
                        "Reinstall it. $FIX"
            }
            return SynheartRuntimeException(kind = kind, message = message, rawCause = cause)
        }

        /**
         * Build the exception for a library that loaded but does not report its
         * build info: not a Synheart Core runtime, or a build without its C ABI.
         */
        fun unidentified(cause: String? = null): SynheartRuntimeException =
            SynheartRuntimeException(
                kind = SynheartRuntimeErrorKind.INCOMPATIBLE,
                message = "The Core runtime library does not report its version, so it is " +
                    "not a usable runtime build for synheart-core. $FIX",
                rawCause = cause,
            )

        /** Build the exception for a loaded runtime that [compat] refused. */
        fun incompatible(compat: RuntimeCompatResult): SynheartRuntimeException =
            SynheartRuntimeException(
                kind = SynheartRuntimeErrorKind.INCOMPATIBLE,
                message = compat.message,
                runtimeVersion = compat.version,
                runtimeAbi = compat.abi,
                rawCause = compat.version?.let { v ->
                    "runtime $v" + (compat.abi?.let { " abi $it" } ?: "")
                },
            )

        // Loader wording differs per OS (dlopen on Bionic, glibc, Apple).
        // Windows error 193 is deliberately absent: it is raised for a corrupt
        // file as well as a foreign architecture, so it falls through to
        // LOAD_FAILED, whose message covers both.
        private val WRONG_ARCHITECTURE = Regex(
            "incompatible architecture|wrong ELF class|" +
                "mach-o, but wrong architecture|wrong architecture|is 32-bit instead of 64-bit|" +
                "is 64-bit instead of 32-bit|has unexpected e_machine",
            RegexOption.IGNORE_CASE,
        )

        private val NOT_FOUND = Regex(
            "no such file|cannot open shared object|image not found|" +
                "library .* not found|unable to load library|couldn't find|" +
                "specified module could not be found",
            RegexOption.IGNORE_CASE,
        )

        /** Map loader text to a [SynheartRuntimeErrorKind]. */
        fun classifyLoadFailure(loaderMessage: String): SynheartRuntimeErrorKind = when {
            WRONG_ARCHITECTURE.containsMatchIn(loaderMessage) -> SynheartRuntimeErrorKind.WRONG_ARCHITECTURE
            NOT_FOUND.containsMatchIn(loaderMessage) -> SynheartRuntimeErrorKind.NOT_INSTALLED
            else -> SynheartRuntimeErrorKind.LOAD_FAILED
        }
    }
}
