package com.synheart.core.modules.runtime

import com.synheart.core.SynheartDefaults
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import org.json.JSONObject

/**
 * JNA interface to the synheart-runtime native library.
 *
 * All methods returning [Pointer] allocate heap memory that MUST be freed
 * with [synheart_runtime_free_string]. Returns null on error.
 */
interface RuntimeNative : Library {
    companion object {
        /**
         * Lazily loaded native library instance. Returns null if the library
         * cannot be found or loaded (e.g., not bundled in APK).
         */
        val INSTANCE: RuntimeNative? = try {
            Native.load("synheart_runtime", RuntimeNative::class.java)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    /** Create a new runtime handle from a JSON config string. */
    fun synheart_runtime_new(config_json: String?): Pointer?

    /** Free a runtime handle. */
    fun synheart_runtime_free(handle: Pointer?)

    /** Push an RR-interval sample (timestamp in ms, interval in ms). */
    fun synheart_runtime_push_rr(handle: Pointer?, ts_ms: Long, rr_ms: Double)

    /** Push a heart-rate sample (timestamp in ms, rate in bpm). */
    fun synheart_runtime_push_hr(handle: Pointer?, ts_ms: Long, bpm: Double)

    /** Push a 3-axis accelerometer sample. */
    fun synheart_runtime_push_accel(handle: Pointer?, ts_ms: Long, x: Double, y: Double, z: Double)

    /** Push a behavioral event. */
    fun synheart_runtime_push_behavior(handle: Pointer?, ts_ms: Long, event_type: Int, value: Double)

    /** Advance the runtime clock; returns HSI JSON pointer or null. */
    fun synheart_runtime_tick(handle: Pointer?, now_ms: Long): Pointer?

    /** Return the latest quality-assessment JSON, or null. */
    fun synheart_runtime_last_quality(handle: Pointer?): Pointer?

    /** Return the latest HSV values as JSON, or null. Caller MUST free with [synheart_runtime_free_string]. */
    fun synheart_runtime_last_hsv(handle: Pointer?): Pointer?

    /** Return the latest pre-processed window as JSON (internal use only), or null. Caller MUST free with [synheart_runtime_free_string]. */
    fun synheart_runtime_last_preprocessed(handle: Pointer?): Pointer?

    /** Number of HSI frames produced so far. */
    fun synheart_runtime_frame_count(handle: Pointer?): Long

    /** Reset the runtime state (clears all internal buffers). */
    fun synheart_runtime_reset(handle: Pointer?)

    /** Free a string returned by runtime functions. Caller MUST call this. */
    fun synheart_runtime_free_string(ptr: Pointer?)

    /** Get runtime library version. Caller MUST free with [synheart_runtime_free_string]. */
    fun synheart_runtime_version(): Pointer?

    /** Return all SRM baselines as JSON. Caller MUST free with [synheart_runtime_free_string]. */
    fun synheart_runtime_baselines_json(handle: Pointer?): Pointer?

    /** Return baseline summary as JSON. Caller MUST free with [synheart_runtime_free_string]. */
    fun synheart_runtime_baseline_summary(handle: Pointer?): Pointer?

    /** Export the SRM snapshot as JSON. Caller MUST free with [synheart_runtime_free_string]. */
    fun synheart_runtime_export_srm_snapshot(handle: Pointer?): Pointer?

    /** Load an SRM snapshot from JSON. Returns 0 on success, non-zero on failure. */
    fun synheart_runtime_load_srm_snapshot(handle: Pointer?, json: String?): Int
}

/**
 * Configuration passed to `synheart_runtime_new` as JSON.
 *
 * @param behaviorEnabled `true` for phone (default), `false` for watch/edge.
 */
data class RuntimeConfig(
    val windowMs: Long = SynheartDefaults.RUNTIME_WINDOW_MS,
    val stepMs: Long = SynheartDefaults.RUNTIME_STEP_MS,
    val subjectId: String,
    val sessionId: String,
    val behaviorEnabled: Boolean = true,
)

/**
 * Kotlin wrapper around the `synheart_runtime_*` C ABI functions.
 *
 * Use [createIfAvailable] to attempt loading the native library. If the
 * library is not present on this platform the factory returns `null` and the
 * pipeline is gracefully inert.
 */
class RuntimeBridge private constructor(private val handle: Pointer) {

    private val native: RuntimeNative = RuntimeNative.INSTANCE!!

    companion object {
        /**
         * Create a [RuntimeBridge] if the native library is available, otherwise null.
         */
        fun createIfAvailable(config: RuntimeConfig): RuntimeBridge? {
            val lib = RuntimeNative.INSTANCE ?: return null
            val configJson = JSONObject().apply {
                put("window_ms", config.windowMs)
                put("step_ms", config.stepMs)
                put("subject_id", config.subjectId)
                put("session_id", config.sessionId)
                put("behavior_enabled", config.behaviorEnabled)
            }.toString()
            val handle = lib.synheart_runtime_new(configJson) ?: return null
            return RuntimeBridge(handle)
        }

        /**
         * Return the native library version string.
         *
         * Returns `null` if the library is unavailable.
         */
        fun version(): String? {
            val lib = RuntimeNative.INSTANCE ?: return null
            val ptr = lib.synheart_runtime_version() ?: return null
            val v = ptr.getString(0)
            lib.synheart_runtime_free_string(ptr)
            return v
        }
    }

    /** Push an RR-interval sample into the runtime. */
    fun pushRr(tsMs: Long, rrMs: Double) {
        native.synheart_runtime_push_rr(handle, tsMs, rrMs)
    }

    /** Push a heart-rate sample into the runtime. */
    fun pushHr(tsMs: Long, bpm: Double) {
        native.synheart_runtime_push_hr(handle, tsMs, bpm)
    }

    /** Push a 3-axis accelerometer sample into the runtime. */
    fun pushAccel(tsMs: Long, x: Double, y: Double, z: Double) {
        native.synheart_runtime_push_accel(handle, tsMs, x, y, z)
    }

    /** Push a behavioral event into the runtime. */
    fun pushBehavior(tsMs: Long, eventType: Int, value: Double) {
        native.synheart_runtime_push_behavior(handle, tsMs, eventType, value)
    }

    /**
     * Advance the runtime clock and return HSI JSON if a new frame was produced.
     *
     * Returns `null` when the runtime has not accumulated enough data to emit
     * a new frame since the last tick.
     */
    fun tick(nowMs: Long): String? {
        val ptr = native.synheart_runtime_tick(handle, nowMs) ?: return null
        val json = ptr.getString(0)
        native.synheart_runtime_free_string(ptr)
        return json
    }

    /** Return the latest quality-assessment JSON, or `null` if unavailable. */
    fun lastQuality(): String? {
        val ptr = native.synheart_runtime_last_quality(handle) ?: return null
        val json = ptr.getString(0)
        native.synheart_runtime_free_string(ptr)
        return json
    }

    /**
     * Return the latest HSV (Human State Vector) values as JSON, or `null`
     * if no window has completed yet.
     *
     * The JSON contains per-head values (emotion, focus, capacity, recovery,
     * strain, sleep) with confidence and inference metadata. This is the
     * canonical source for all HSV data.
     */
    fun lastHsv(): String? {
        val ptr = native.synheart_runtime_last_hsv(handle) ?: return null
        val json = ptr.getString(0)
        native.synheart_runtime_free_string(ptr)
        return json
    }

    /**
     * Return the latest pre-processed window as JSON (internal use only), or `null`
     * if no window has completed yet.
     *
     * The JSON contains quality metrics, derived features (HRV, motion, artifact),
     * behavior features, SRM baseline context with Z-score deviations, and 64D
     * signal embeddings. Used for on-device model training, R&D, and diagnostics.
     */
    fun lastPreprocessed(): String? {
        val ptr = native.synheart_runtime_last_preprocessed(handle) ?: return null
        val json = ptr.getString(0)
        native.synheart_runtime_free_string(ptr)
        return json
    }

    /** Number of HSI frames produced so far. */
    fun frameCount(): Long {
        return native.synheart_runtime_frame_count(handle)
    }

    /** Reset the runtime state (clears all internal buffers). */
    fun reset() {
        native.synheart_runtime_reset(handle)
    }

    // -- SRM Baselines --

    /** Return all SRM baselines as JSON, or `null`. */
    fun baselinesJson(): String? {
        val ptr = native.synheart_runtime_baselines_json(handle) ?: return null
        val json = ptr.getString(0)
        native.synheart_runtime_free_string(ptr)
        return json
    }

    /** Return baseline summary as JSON: `{"total":14,"ready":0,"warming":5,"empty":9}`. */
    fun baselineSummary(): String? {
        val ptr = native.synheart_runtime_baseline_summary(handle) ?: return null
        val json = ptr.getString(0)
        native.synheart_runtime_free_string(ptr)
        return json
    }

    /** Export the SRM snapshot as JSON for persistence, or `null`.
     *  Internal: used by RuntimeModule for auto-save/load lifecycle. */
    internal fun exportSrmSnapshot(): String? {
        val ptr = native.synheart_runtime_export_srm_snapshot(handle) ?: return null
        val json = ptr.getString(0)
        native.synheart_runtime_free_string(ptr)
        return json
    }

    /** Load an SRM snapshot from JSON. Returns 0 on success, error code on failure.
     *  Internal: used by RuntimeModule for auto-save/load lifecycle. */
    internal fun loadSrmSnapshot(json: String): Int {
        return native.synheart_runtime_load_srm_snapshot(handle, json)
    }

    /**
     * Release the native runtime handle. Must be called when the bridge
     * is no longer needed to avoid leaking native memory.
     */
    fun close() {
        native.synheart_runtime_free(handle)
    }
}
