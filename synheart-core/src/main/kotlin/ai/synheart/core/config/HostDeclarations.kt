package ai.synheart.core.config

import org.json.JSONObject

/**
 * Host declarations that change engine output (engine 0.16.0, blockers B-0,
 * B-2, B-5, B-6).
 *
 * All four are **opt-in rather than derived from `platform`**, and the default
 * — declaring nothing — reproduces pre-0.16.0 behaviour exactly. That is
 * deliberate: each one changes the output of a host already in the field.
 *
 * | Declaration | What changes the moment you declare it |
 * |---|---|
 * | [HostDeclarations.deviceClass] | Folds into the SRM `config_hash`. **Every persisted baseline snapshot stops loading** (`ERR_SRM_CONFIG_MISMATCH`) and the person re-warms 30 observations across 3 distinct days. |
 * | [HostDeclarations.sensing] | Appended to `config_id`. `episodic` additionally **withholds Capacity and Mental Fatigue** from every frame. |
 * | [HostDeclarations.maskProfile] `mobile` | Admits the hesitation bit in the `Communication` and `WritingEditing` context rows — moves Focus and Cognitive Load. |
 * | [HostDeclarations.cfiStructuralComponents] `4` | **Lowers** `conf_CFI` for identical evidence: it widens the coverage denominator. That direction surprises people. |
 *
 * Declare them once at first launch and keep them stable. Changing
 * [HostDeclarations.deviceClass] mid-life is a baseline reset, not a config
 * tweak.
 *
 * `"auto"` resolves through core-runtime's `engine::host_declarations` tables
 * from the config's `platform` string, and is the recommended setting for a
 * new mobile integration — the tables are the maintained answer.
 */

/**
 * Whether the host senses continuously or in episodes. There is no default:
 * continuous-vs-episodic is the whole claim, and guessing it is what ruling
 * B-0 forbids.
 */
enum class SensingMode(val wire: String) {
    CONTINUOUS("continuous"),
    EPISODIC("episodic"),
}

/**
 * The closed, versioned stream roster.
 *
 * A stream you do not name is declared **unavailable**, not merely absent —
 * that is what lets a consumer distinguish "iOS structurally cannot see
 * notifications" from "this host predates the field". The roster id rides on
 * the wire as `meta.synheart.sensing.roster_version`.
 *
 * Omit the whole roster to take the platform default: Android → `ANDROID`
 * continuous, desktop → `DESKTOP`, watch → `WATCH`, iOS → `IOS_EPISODIC`.
 *
 * Every field is nullable so a host can name only the streams it has a
 * position on; a null is not emitted.
 */
data class SensingStreams(
    val cardiac: Boolean? = null,
    val accelerometer: Boolean? = null,
    val keystrokes: Boolean? = null,
    val pointer: Boolean? = null,
    val appFocus: Boolean? = null,
    val notificationArrivals: Boolean? = null,
    val notificationResponses: Boolean? = null,
    val screenState: Boolean? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        cardiac?.let { put("cardiac", it) }
        accelerometer?.let { put("accelerometer", it) }
        keystrokes?.let { put("keystrokes", it) }
        pointer?.let { put("pointer", it) }
        appFocus?.let { put("app_focus", it) }
        notificationArrivals?.let { put("notification_arrivals", it) }
        notificationResponses?.let { put("notification_responses", it) }
        screenState?.let { put("screen_state", it) }
    }
}

/**
 * An explicit sensing declaration.
 *
 * ## Android `continuous` is a claim the host has to back
 *
 * An Android app holding a `dataSync` foreground service stays alive and
 * streams all day; the same app without one gets whatever the scheduler
 * grants once it is backgrounded. Declare [SensingMode.CONTINUOUS] only when
 * something actually keeps the process alive for the session. The honest
 * failure mode of `episodic` is the point: it withholds the two stateful heads
 * with a reason rather than publishing torn session clocks as trajectories.
 */
data class SensingProfile(
    /**
     * Required. An object with no valid mode is dropped entirely (with a log
     * line), leaving you undeclared rather than partially declared.
     */
    val mode: SensingMode,
    /**
     * How long the engine holds a completed window before emitting it, so a
     * retroactive source flushing less often than once per window still lands
     * inside its own window. The frame's content is unchanged; only its
     * emission tick moves. The hold is pipeline-wide, not per-channel.
     */
    val latenessBudgetMs: Long? = null,
    val streams: SensingStreams? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("mode", mode.wire)
        latenessBudgetMs?.let { put("lateness_budget_ms", it) }
        streams?.let { put("streams", it.toJson()) }
    }
}

/**
 * SRM baseline partition. Folds into the SRM `config_hash` — see the table on
 * [HostDeclarations].
 */
enum class DeviceClass(val wire: String) {
    DESKTOP("desktop"),
    PHONE("phone"),
    TABLET("tablet"),
    WATCH("watch"),
}

/** Which interpretation-mask table the engine reads. */
enum class MaskProfile(val wire: String) {
    DESKTOP("desktop"),
    MOBILE("mobile"),
}

/**
 * A declaration that is either `"auto"` — resolved by core-runtime's own
 * tables from the `platform` string — or an explicit value.
 *
 * Covariant so [Auto] is a [Declared] of anything, which is what lets one
 * object serve every field.
 */
sealed class Declared<out T> {
    /** Let the runtime resolve it from `platform`. */
    object Auto : Declared<Nothing>()

    /** An explicit value the host is asserting. */
    data class Value<T>(val value: T) : Declared<T>()
}

/**
 * The four declarations, plus the `"auto"` escape hatch for each.
 *
 * Every field is null by default, which sends nothing and leaves the runtime
 * in its pre-0.16.0 behaviour. [HostDeclarations.auto] declares all four as
 * `"auto"`, which is a valid first cut for a new mobile integration.
 */
data class HostDeclarations(
    /** `"auto"`, or an explicit [SensingProfile]. Null sends nothing. */
    val sensing: Declared<SensingProfile>? = null,
    /**
     * `"auto"` or a [DeviceClass]. Null sends nothing.
     *
     * **Declaring this invalidates every persisted SRM baseline.**
     */
    val deviceClass: Declared<DeviceClass>? = null,
    /** `"auto"` or a [MaskProfile]. Null sends nothing. */
    val maskProfile: Declared<MaskProfile>? = null,
    /** Android + mobile mask only. `4` is the documented mobile value. */
    val cfiStructuralComponents: Int? = null,
) {
    val isEmpty: Boolean
        get() = sensing == null &&
            deviceClass == null &&
            maskProfile == null &&
            cfiStructuralComponents == null

    /**
     * Keys to merge into the config JSON handed to `synheart_core_new`. Absent
     * keys mean "undeclared", which is not the same as a declared default.
     */
    fun toJson(): JSONObject = JSONObject().apply {
        sensing?.let {
            put(
                "sensing",
                when (it) {
                    Declared.Auto -> AUTO
                    is Declared.Value -> it.value.toJson()
                },
            )
        }
        deviceClass?.let {
            put(
                "device_class",
                when (it) {
                    Declared.Auto -> AUTO
                    is Declared.Value -> it.value.wire
                },
            )
        }
        maskProfile?.let {
            put(
                "mask_profile",
                when (it) {
                    Declared.Auto -> AUTO
                    is Declared.Value -> it.value.wire
                },
            )
        }
        cfiStructuralComponents?.let { put("cfi_structural_components", it) }
    }

    companion object {
        const val AUTO = "auto"

        /**
         * All four resolved from the config's `platform` string by core-runtime's
         * own tables. The maintained answer — prefer it to re-deriving per app.
         *
         * Note this includes [deviceClass], so the first launch after adopting
         * it resets persisted SRM baselines.
         */
        val auto: HostDeclarations = HostDeclarations(
            sensing = Declared.Auto,
            deviceClass = Declared.Auto,
            maskProfile = Declared.Auto,
            cfiStructuralComponents = 4,
        )
    }
}

/**
 * Opt-in kinematic heads.
 *
 * These do not appear in HSI frames unless requested, and they withhold even
 * when requested until a body-worn accelerometer placement is declared — see
 * [ai.synheart.core.models.AccelPlacement]. Enabling a head is necessary but
 * not sufficient.
 *
 * Unrecognised names are ignored by the runtime with a warning, so this is a
 * closed set rather than a free string list.
 */
enum class ExtraHead(val wire: String) {
    MOVEMENT_REGULARITY("movement_regularity"),
    POSTURAL_STATE("postural_state"),
    ACTIVITY_STATE("activity_state"),
    LOCOMOTION_STATE("locomotion_state"),
}
