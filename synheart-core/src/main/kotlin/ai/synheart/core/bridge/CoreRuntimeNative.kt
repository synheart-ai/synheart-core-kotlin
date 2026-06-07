package ai.synheart.core.bridge

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer

/**
 * JNA interface to the native runtime shared library (C ABI).
 *
 * All 42 functions use an opaque handle pattern. Complex input/output types
 * are JSON strings. Returned strings must be freed with [synheart_core_free_string].
 */
interface CoreRuntimeNative : Library {

    companion object {
        /**
         * Lazily loaded native library instance. Returns null if the shared
         * library (libsynheart_core_runtime.so / .dylib / .dll) is not found
         * on the JNA library path.
         */
        val INSTANCE: CoreRuntimeNative? = try {
            Native.load("synheart_core_runtime", CoreRuntimeNative::class.java)
        } catch (e: UnsatisfiedLinkError) {
            null
        }
    }

    // ------------------------------------------------------------------ //
    // Lifecycle                                                          //
    // ------------------------------------------------------------------ //

    /** Create a new runtime handle from a JSON config string. Returns opaque handle or null on failure. */
    fun synheart_core_new(config_json: String?): Pointer?

    /** Release the runtime handle and all associated resources. */
    fun synheart_core_free(handle: Pointer?)

    /** Free a string that was returned by the runtime. */
    fun synheart_core_free_string(ptr: Pointer?)

    // ------------------------------------------------------------------ //
    // Session                                                            //
    // ------------------------------------------------------------------ //

    /** Start a new session. Returns a JSON string (SessionHandle) or null on failure. */
    fun synheart_core_start_session(handle: Pointer?): Pointer?

    /** Stop the current session. Returns 0 on success, non-zero on failure. */
    fun synheart_core_stop_session(handle: Pointer?): Int

    /** Get the current session as JSON, or null if none is active. */
    fun synheart_core_current_session(handle: Pointer?): Pointer?

    /** Returns 1 if a session is currently running, 0 otherwise. */
    fun synheart_core_is_running(handle: Pointer?): Int

    // ------------------------------------------------------------------ //
    // Sensor push                                                        //
    // ------------------------------------------------------------------ //

    /** Push an R-R interval sample. */
    fun synheart_core_push_rr(handle: Pointer?, ts_ms: Long, rr_ms: Double): Int

    /** Push a heart-rate sample. */
    fun synheart_core_push_hr(handle: Pointer?, ts_ms: Long, bpm: Double): Int

    /** Push a 3-axis accelerometer sample. */
    fun synheart_core_push_accel(handle: Pointer?, ts_ms: Long, x: Double, y: Double, z: Double): Int

    /** Push a behavioral event. */
    fun synheart_core_push_behavior(handle: Pointer?, ts_ms: Long, event_type: String?, value: Double): Int

    /** Push sleep-stage data as a JSON array. */
    fun synheart_core_push_sleep_stages(handle: Pointer?, json: String?): Int

    /** Ingest a pre-built batch (JSON). Returns result JSON or null. */
    fun synheart_core_ingest_batch(handle: Pointer?, batch_json: String?, now_ms: Long): Pointer?

    /** Enable/disable ambient capture mode. */
    fun synheart_core_set_ambient_capture(handle: Pointer?, enabled: Int)

    /** Read the ambient-capture flag. Returns 1 when enabled, 0 otherwise. */
    fun synheart_core_get_ambient_capture(handle: Pointer?): Int

    // ------------------------------------------------------------------ //
    // Consent                                                            //
    // ------------------------------------------------------------------ //

    /** Grant a consent type (string identifier). Returns 0 on success. */
    fun synheart_core_grant_consent(handle: Pointer?, consent_type: String?): Int

    /** Revoke a consent type. Returns 0 on success. */
    fun synheart_core_revoke_consent(handle: Pointer?, consent_type: String?): Int

    /** Check whether a consent type is currently granted. Returns 1 if granted, 0 otherwise. */
    fun synheart_core_has_consent(handle: Pointer?, consent_type: String?): Int

    /** Return current consent state as JSON. */
    fun synheart_core_current_consent(handle: Pointer?): Pointer?

    // ------------------------------------------------------------------ //
    // Research study                                                      //
    // ------------------------------------------------------------------ //

    /** Enrol the device in a research study by redeeming access + study codes. Returns JSON. */
    fun synheart_core_enrol_study(handle: Pointer?, access_code: String?, study_code: String?): Pointer?

    /** Preview an access + study code pair without redeeming the code. Returns JSON. */
    fun synheart_core_validate_study_codes(handle: Pointer?, access_code: String?, study_code: String?): Pointer?

    /** Withdraw from the device's active research study for this app. Returns JSON. */
    fun synheart_core_withdraw_study(handle: Pointer?): Pointer?

    // ------------------------------------------------------------------ //
    // Capability                                                         //
    // ------------------------------------------------------------------ //

    /** Load a capability token (JSON) verified against the given secret. Returns 0 on success. */
    fun synheart_core_load_capability_token(handle: Pointer?, token_json: String?, secret: String?): Int

    // ------------------------------------------------------------------ //
    // Query                                                              //
    // ------------------------------------------------------------------ //

    /** List all sessions as a JSON array. */
    fun synheart_core_list_sessions(handle: Pointer?): Pointer?

    /** Get a session summary by session ID (JSON). */
    fun synheart_core_get_session_summary(handle: Pointer?, session_id: String?): Pointer?

    /** Get HSI windows for a session, with optional time range and limit. Returns JSON. */
    fun synheart_core_get_hsi_windows(handle: Pointer?, session_id: String?, start_ms: Long, end_ms: Long, limit: Int): Pointer?

    /** Get storage usage statistics as JSON. */
    fun synheart_core_get_storage_usage(handle: Pointer?): Pointer?

    // ------------------------------------------------------------------ //
    // Metrics                                                            //
    // ------------------------------------------------------------------ //

    /** Record a metric event (JSON). Returns 0 on success. */
    fun synheart_core_record_metric(handle: Pointer?, json: String?): Int

    // ------------------------------------------------------------------ //
    // Data management                                                    //
    // ------------------------------------------------------------------ //

    /** Delete a local session by ID. Returns 0 on success. */
    fun synheart_core_delete_session(handle: Pointer?, session_id: String?): Int

    /** Wipe all local data. Returns 0 on success. */
    fun synheart_core_wipe_local_data(handle: Pointer?): Int

    /** Set retention days, returning the number of artifacts tombstoned. */
    fun synheart_core_set_retention_days(handle: Pointer?, days: Int): Long

    // ------------------------------------------------------------------ //
    // Sync                                                               //
    // ------------------------------------------------------------------ //

    /** Enable or disable sync. */
    fun synheart_core_set_sync_enabled(handle: Pointer?, enabled: Int): Int

    /** Trigger an immediate sync cycle. Returns result JSON or null. */
    fun synheart_core_sync_now(handle: Pointer?): Pointer?

    // ------------------------------------------------------------------ //
    // SRM / Baselines                                                    //
    // ------------------------------------------------------------------ //

    /** Get current baselines as JSON. */
    fun synheart_core_baselines_json(handle: Pointer?): Pointer?

    /** Export SRM snapshot as JSON. */
    fun synheart_core_export_srm_snapshot(handle: Pointer?): Pointer?

    /** Load an SRM snapshot from JSON. Returns 0 on success. */
    fun synheart_core_load_srm_snapshot(handle: Pointer?, json: String?): Int

    /** Get overall SRM status as a JSON string. */
    fun synheart_core_srm_overall_status(handle: Pointer?): Pointer?

    // ------------------------------------------------------------------ //
    // Cloud upload queue                                                 //
    // ------------------------------------------------------------------ //

    /** Enqueue an HSI snapshot (JSON) for cloud upload. */
    fun synheart_core_enqueue_hsi(handle: Pointer?, json: String?, timestamp_ms: Long): Int

    /** Return the current upload queue length. */
    fun synheart_core_upload_queue_length(handle: Pointer?): Int

    /** Flush pending uploads. Returns result JSON or null. */
    fun synheart_core_flush_uploads(handle: Pointer?): Pointer?

    /** Upload platform metadata. Returns result JSON or null. */
    fun synheart_core_upload_metadata(handle: Pointer?): Pointer?

    // ------------------------------------------------------------------ //
    // Diagnostics / status                                               //
    // ------------------------------------------------------------------ //

    /** Get the last Wellness Score as JSON, or null if baselines are not ready. */
    fun synheart_core_wellness_json(handle: Pointer?): Pointer?

    /** Return full runtime diagnostics as JSON. */
    fun synheart_core_diagnostics(handle: Pointer?): Pointer?

    /** Return the last error code (0 = no error). */
    fun synheart_core_last_error_code(handle: Pointer?): Int

    /** Returns 1 if the native runtime is available, 0 otherwise. */
    fun synheart_core_is_runtime_available(handle: Pointer?): Int

    /** Returns 1 if the network is reachable, 0 otherwise. */
    fun synheart_core_is_network_reachable(handle: Pointer?): Int

    // ------------------------------------------------------------------ //
    // Account                                                            //
    // ------------------------------------------------------------------ //

    /** Request account deletion. Returns 0 on success. */
    fun synheart_core_request_account_deletion(handle: Pointer?): Int

    /** Cancel a pending account deletion request. Returns 0 on success. */
    fun synheart_core_cancel_account_deletion(handle: Pointer?): Int

    // ------------------------------------------------------------------ //
    // HSI Callback                                                       //
    // ------------------------------------------------------------------ //

    /** Register a callback for HSI state updates. */
    fun synheart_core_set_hsi_callback(handle: Pointer?, callback: HsiCallbackNative?, userData: Pointer?)

    /** Unregister the HSI callback. */
    fun synheart_core_clear_hsi_callback(handle: Pointer?)

    // ------------------------------------------------------------------ //
    // Lab Protocol                                                       //
    // ------------------------------------------------------------------ //

    /** Returns 1 if the lab module is available, 0 otherwise. */
    fun synheart_core_lab_is_available(handle: Pointer?): Int

    /** Start a lab protocol. Returns 0 on success. */
    fun synheart_core_lab_start(handle: Pointer?, protocol_json: String?, started_at_ms: Long): Int

    /** Open a new lab window. Returns a pointer to the window ID string, or null on failure. */
    fun synheart_core_lab_open_window(handle: Pointer?, parent_id: String?, window_type: String?, label: String?, started_at_ms: Long): Pointer?

    /** Close a lab window. Returns 0 on success. */
    fun synheart_core_lab_close_window(handle: Pointer?, window_id: String?, ended_at_ms: Long): Int

    /** Set values on a lab window. Returns 0 on success. */
    fun synheart_core_lab_set_window_values(handle: Pointer?, window_id: String?, values_json: String?): Int

    /** Merge extra data into the lab protocol. Returns 0 on success. */
    fun synheart_core_lab_merge_extra_data(handle: Pointer?, patch_json: String?): Int

    /** Set state overrides on a lab window. Returns 0 on success. */
    fun synheart_core_lab_set_state_overrides(handle: Pointer?, window_id: String?, overrides_json: String?): Int

    /** Finalize the lab protocol. Returns a pointer to the result JSON string, or null on failure. */
    fun synheart_core_lab_finalize(handle: Pointer?, ended_at_ms: Long): Pointer?

    // ------------------------------------------------------------------ //
    // Multi-source priority resolver                                     //
    // Process-global store; no handle. Lower rank wins.                  //
    // ------------------------------------------------------------------ //

    /** Set the global rank for a provider. Returns 0 on success, -1 on bad input. */
    fun synheart_core_priority_set_provider(provider: String?, rank: Int): Int

    /**
     * Set or clear a per-metric rank override for `(metric, provider)`.
     * Pass `rankPresent = 0` to clear the override; `1` to set to `rank`.
     * Returns 0 on success, -1 on bad input.
     */
    fun synheart_core_priority_set_metric_override(
        metric: String?, provider: String?, rank_present: Int, rank: Int
    ): Int

    /**
     * Read the effective rank for `(metric, provider)`. Returns
     * `Int.MAX_VALUE` (the runtime's `ProviderRank::UNRANKED` sentinel)
     * for unknown providers, or -1 if `metric` is unparseable.
     */
    fun synheart_core_priority_effective_rank(metric: String?, provider: String?): Int

    /**
     * Resolve the winning source for `metric` given a `{provider:
     * count}` JSON map. Returns JSON string pointer (caller frees via
     * [synheart_core_free_string]) or null on bad input.
     */
    fun synheart_core_priority_resolve(metric: String?, samples_json: String?): Pointer?

    // ------------------------------------------------------------------ //
    // HRV-CV resilience score                                               //
    // Stateless. Three JSON inputs, one JSON output.                     //
    // ------------------------------------------------------------------ //

    /**
     * Compute a resilience score from samples + sleep windows + config.
     * Returns JSON string pointer (caller frees) or null on bad input.
     */
    fun synheart_core_resilience_compute_v1(
        samples_json: String?, windows_json: String?, config_json: String?
    ): Pointer?

    // Apple Health XML backfill is intentionally NOT bound
    // on Android — `export.zip` is iOS-only. When Android Health
    // Connect backfill ships, it will get its own JNA decls here
    // pointing at format-agnostic runtime symbols (the underlying
    // `synheart_core_backfill_*` symbols are reusable; we just need
    // a different sample-payload variant on the runtime side.

    // ------------------------------------------------------------------ //
    // Breathing compliance (RFC-Breathing-001)                            //
    // RR samples pushed via `synheart_core_push_rr` already feed the     //
    // detector. These configure the target / window / population and    //
    // read back JSON verdicts.                                           //
    // ------------------------------------------------------------------ //

    fun synheart_core_breathing_set_target_bpm(handle: Pointer, bpm: Double)
    fun synheart_core_breathing_set_window_secs(handle: Pointer, secs: Int)
    fun synheart_core_breathing_set_population(handle: Pointer, profile: Int)

    /**
     * Evaluate breathing compliance over the current RR window. Returns a
     * JSON `ComplianceResult` string (caller frees via
     * [synheart_core_free_string]) or null when the detector has nothing
     * to report.
     */
    fun synheart_core_breathing_evaluate(handle: Pointer): Pointer?

    /** Clear the breathing detector's RR ring buffer. */
    fun synheart_core_breathing_reset(handle: Pointer)
}

/** JNA callback interface for HSI updates. */
interface HsiCallbackNative : com.sun.jna.Callback {
    fun invoke(hsiJson: Pointer?, userData: Pointer?)
}
