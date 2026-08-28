package ai.synheart.core.bridge

import com.sun.jna.Callback
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer

/**
 * JNA interface to the native runtime shared library (C ABI).
 *
 * All functions use an opaque handle pattern. Complex input/output types
 * are JSON strings. Returned strings must be freed with [synheart_core_free_string].
 *
 * Declarations here are the Kotlin mirror of the runtime's `extern "C"`
 * surface. Argument counts and widths must match exactly — JNA does not
 * check them, so a wrong arity reads whatever happens to be in the next
 * register.
 *
 * Type mapping:
 *  - `*const c_char` (in)  → `String?`   (JNA owns the buffer for the call)
 *  - `*mut c_char`   (out) → `Pointer?`  (caller frees via `free_string`)
 *  - `c_int`/`i32`         → `Int`
 *  - `c_longlong`/`i64`    → `Long`
 *  - `u64`                 → `Long` (reinterpreted; values stay well under 2^63)
 *  - `usize`               → [NativeLong]
 *  - `bool`                → `Byte` (Rust `bool` is one byte, not a C `int`)
 *  - `c_double`            → `Double`
 *  - function pointers     → [Callback] subtypes
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

    /** Runtime semantic version. Handle-free. Caller frees. */
    fun synheart_core_version(): Pointer?

    /** Build metadata (profile, features, commit) as JSON. Handle-free. Caller frees. */
    fun synheart_core_build_info(): Pointer?

    /** Last global error message, or null. Handle-free. Caller frees. */
    fun synheart_core_last_error(): Pointer?

    // ------------------------------------------------------------------ //
    // Logging                                                            //
    // ------------------------------------------------------------------ //

    /**
     * Install the tracing subscriber and stream every line to [callback].
     * `env_filter` follows `RUST_LOG` syntax. Returns 0 on success.
     */
    fun synheart_core_init_logging(
        env_filter: String?,
        callback: HostLogCallbackNative?,
        user_data: Pointer?,
    ): Int

    /**
     * Install the tracing subscriber writing into an in-process ring buffer.
     * Drain it with [synheart_core_drain_logs]. Returns 0 on success.
     */
    fun synheart_core_init_logging_buffered(env_filter: String?): Int

    /** Attach or replace the log callback after init. Returns 0 on success. */
    fun synheart_core_set_log_callback(callback: HostLogCallbackNative?, user_data: Pointer?): Int

    /** Drain the buffered log ring as a JSON array of lines. Caller frees. */
    fun synheart_core_drain_logs(): Pointer?

    /** Count of log lines dropped because the ring buffer was full. */
    fun synheart_core_dropped_log_lines(): Long

    /** Tear the subscriber down. Returns 0 on success. */
    fun synheart_core_shutdown_logging(): Int

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

    /**
     * Close a session that was left open by a crash or a force-quit, without
     * touching the live session. Returns 0 on success.
     */
    fun synheart_core_close_orphan_session(handle: Pointer?, session_id: String?): Int

    /** Make sure the fusion pipeline is spun up even before the first sample. */
    fun synheart_core_ensure_pipeline(handle: Pointer?)

    /** Advance the pipeline clock. Returns an HSI window JSON when one closed. Caller frees. */
    fun synheart_core_tick(handle: Pointer?, now_ms: Long): Pointer?

    /** Number of frames the pipeline has produced this session. */
    fun synheart_core_frame_count(handle: Pointer?): Long

    /** Last computed feature vector as JSON, or null. Caller frees. */
    fun synheart_core_last_features(handle: Pointer?): Pointer?

    // ------------------------------------------------------------------ //
    // Sensor push                                                        //
    // ------------------------------------------------------------------ //

    /**
     * Push an R-R interval sample. `provider` tags the source for the
     * multi-source priority resolver; null falls back to `default_sensor`.
     */
    fun synheart_core_push_rr(handle: Pointer?, ts_ms: Long, rr_ms: Double, provider: String?)

    /**
     * Push a burst of R-R intervals that arrived in one sensor notification.
     * `rr` holds [len] doubles in milliseconds; `order` is 0 = oldest-first
     * (the BLE HRM convention), 1 = newest-first.
     */
    fun synheart_core_push_rr_batch(
        handle: Pointer?,
        anchor_ts_ms: Long,
        rr: DoubleArray?,
        len: NativeLong,
        order: Int,
        provider: String?,
    )

    /** Push a heart-rate sample. */
    fun synheart_core_push_hr(handle: Pointer?, ts_ms: Long, bpm: Double)

    /** Push a 3-axis accelerometer sample. */
    fun synheart_core_push_accel(handle: Pointer?, ts_ms: Long, x: Double, y: Double, z: Double)

    /**
     * Push a behavioral event. `event_type` is the numeric behavior code
     * (see `BehaviorCode`), not a name — the runtime takes a `c_int` here.
     */
    fun synheart_core_push_behavior(handle: Pointer?, ts_ms: Long, event_type: Int, value: Double)

    /** Push a fully-formed behavior event as JSON. Returns 0 on success. */
    fun synheart_core_push_behavior_event(handle: Pointer?, event_json: String?): Int

    /** Push sleep-stage data as a JSON array. */
    fun synheart_core_push_sleep_stages(handle: Pointer?, json: String?)

    /** Push vendor-derived HRV metrics (already computed by the wearable). */
    fun synheart_core_push_vendor_hrv(
        handle: Pointer?,
        ts_ms: Long,
        rmssd_ms: Double,
        sdnn_ms: Double,
        stress: Double,
        recovery: Double,
    )

    /** Push vendor-derived vitals (SpO2, respiration rate). */
    fun synheart_core_push_vendor_vitals(
        handle: Pointer?,
        ts_ms: Long,
        spo2: Double,
        respiration: Double,
    )

    /** Ingest a pre-built batch (JSON). Returns result JSON or null. */
    fun synheart_core_ingest_batch(handle: Pointer?, batch_json: String?, now_ms: Long): Pointer?

    /** Enable/disable ambient capture mode. */
    fun synheart_core_set_ambient_capture(handle: Pointer?, enabled: Int)

    /** Read the ambient-capture flag. Returns 1 when enabled, 0 otherwise. */
    fun synheart_core_get_ambient_capture(handle: Pointer?): Int

    // ------------------------------------------------------------------ //
    // Vendor event store                                                  //
    // ------------------------------------------------------------------ //

    /** Persist a canonical vendor event (JSON). Returns 0 on success. */
    fun synheart_core_ingest_vendor_event(handle: Pointer?, event_json: String?): Int

    /** Query stored vendor events. Returns a JSON array. Caller frees. */
    fun synheart_core_query_vendor_events(handle: Pointer?, query_json: String?): Pointer?

    /** Most recent stored event for `(provider, event_type)`, or null. Caller frees. */
    fun synheart_core_get_latest_vendor_event(
        handle: Pointer?,
        provider: String?,
        event_type: String?,
    ): Pointer?

    /** Drop every stored event for a provider (unlink). Returns rows deleted. */
    fun synheart_core_delete_vendor_events_for_provider(handle: Pointer?, provider: String?): Long

    // ------------------------------------------------------------------ //
    // Personalization — task / focus / workout                            //
    // ------------------------------------------------------------------ //

    /** Set the active task type (numeric `TaskType` discriminant). */
    fun synheart_core_set_task_type(handle: Pointer?, task_kind: Int)

    /** Read the active task type discriminant. */
    fun synheart_core_current_task_type(handle: Pointer?): Int

    /** Set the active focus kind (numeric `FocusKind` discriminant). */
    fun synheart_core_set_focus_kind(handle: Pointer?, focus_kind: Int)

    /** Read the active focus kind discriminant. */
    fun synheart_core_current_focus_kind(handle: Pointer?): Int

    /** Push a completed workout window with optional vendor strain/recovery. */
    fun synheart_core_push_workout_event(
        handle: Pointer?,
        start_ms: Long,
        end_ms: Long,
        workout_kind: Int,
        vendor_strain: Double,
        vendor_recovery: Double,
    )

    /** Read the active workout kind discriminant. */
    fun synheart_core_current_workout_kind(handle: Pointer?): Int

    /** Full personalization context as JSON. Caller frees. */
    fun synheart_core_personalization_context_json(handle: Pointer?): Pointer?

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

    /** Configure the cloud consent service (base URL + app id). Returns 0 on success. */
    fun synheart_core_consent_configure_cloud(
        handle: Pointer?,
        base_url: String?,
        app_id: String?,
    ): Int

    /** Return the editable consent form as JSON. */
    fun synheart_core_consent_get_editable_form(handle: Pointer?): Pointer?

    /**
     * Submit a consent form: mints (or refreshes) the cloud consent token under
     * the runtime's current subject. `user_id` is a legacy fallback only — the
     * runtime mints under its configured subject_id. Returns a JSON result that
     * includes the issued token (or an `error`). Caller frees the string.
     */
    fun synheart_core_consent_submit_form(
        handle: Pointer?,
        device_id: String?,
        platform: String?,
        user_id: String?,
        form_json: String?,
    ): Pointer?

    /** Cloud consent status as JSON. Caller frees. */
    fun synheart_core_consent_status(handle: Pointer?): Pointer?

    /** Effective consent state as JSON (token-authoritative when present). Caller frees. */
    fun synheart_core_consent_effective_state(handle: Pointer?): Pointer?

    /** Returns 1 when the consent token is close enough to expiry to re-mint. */
    fun synheart_core_consent_needs_token_refresh(handle: Pointer?): Int

    /** Clear the stored consent token + snapshot. Returns 0 on success. */
    fun synheart_core_consent_clear_stored(handle: Pointer?): Int

    // ------------------------------------------------------------------ //
    // Research study                                                      //
    // ------------------------------------------------------------------ //

    /** Enrol in a study with an access + study code pair. Caller frees. */
    fun synheart_core_enrol_study(
        handle: Pointer?,
        access_code: String?,
        study_code: String?,
    ): Pointer?

    /** Preview a code pair without redeeming it. Caller frees. */
    fun synheart_core_validate_study_codes(
        handle: Pointer?,
        access_code: String?,
        study_code: String?,
    ): Pointer?

    /** Withdraw from the active study. Idempotent. Caller frees. */
    fun synheart_core_withdraw_study(handle: Pointer?): Pointer?

    /** Current study enrolment status as JSON. Caller frees. */
    fun synheart_core_research_study_status(handle: Pointer?): Pointer?

    /** Record a signed study-consent affirmation payload. Caller frees. */
    fun synheart_core_record_study_consent(handle: Pointer?, payload_json: String?): Pointer?

    /** Request erasure of study-contributed data. `dry_run` returns an inventory. */
    fun synheart_core_request_study_data_deletion(handle: Pointer?, dry_run: Byte): Pointer?

    // ------------------------------------------------------------------ //
    // Customer data deletion (GDPR Art. 17)                               //
    // ------------------------------------------------------------------ //

    /** File an erasure request. `dry_run` previews the inventory. Caller frees. */
    fun synheart_core_request_data_deletion(
        handle: Pointer?,
        reason: String?,
        contact: String?,
        dry_run: Byte,
    ): Pointer?

    /** Status of one erasure request. Caller frees. */
    fun synheart_core_get_data_deletion(handle: Pointer?, request_id: String?): Pointer?

    /** Page through this subject's erasure requests. Caller frees. */
    fun synheart_core_list_data_deletions(handle: Pointer?, limit: Int, offset: Int): Pointer?

    // ------------------------------------------------------------------ //
    // Capability                                                         //
    // ------------------------------------------------------------------ //

    /** Load and verify a capability token. Returns 0 on success. */
    fun synheart_core_load_capability_token(
        handle: Pointer?,
        token_json: String?,
        secret: String?,
    ): Int

    // ------------------------------------------------------------------ //
    // Query / storage                                                     //
    // ------------------------------------------------------------------ //

    /** List all sessions as a JSON array. */
    fun synheart_core_list_sessions(handle: Pointer?): Pointer?

    /** Get a session summary by session ID. Returns JSON or null. */
    fun synheart_core_get_session_summary(handle: Pointer?, session_id: String?): Pointer?

    /** Get HSI windows for a session. Returns a JSON array. */
    fun synheart_core_get_hsi_windows(
        handle: Pointer?,
        session_id: String?,
        start_ms: Long,
        end_ms: Long,
        limit: Int,
    ): Pointer?

    /** Get storage usage statistics as JSON. */
    fun synheart_core_get_storage_usage(handle: Pointer?): Pointer?

    /** Record a metric event (JSON). Returns 0 on success. */
    fun synheart_core_record_metric(handle: Pointer?, metric_json: String?): Int

    /** Delete a local session by ID (creates a tombstone). Returns 0 on success. */
    fun synheart_core_delete_session(handle: Pointer?, session_id: String?): Int

    /** Wipe all local data (storage, keys, sync state). Returns 0 on success. */
    fun synheart_core_wipe_local_data(handle: Pointer?): Int

    /** Set the retention window in days. Returns the number of artifacts tombstoned. */
    fun synheart_core_set_retention_days(handle: Pointer?, days: Int): Long

    // ------------------------------------------------------------------ //
    // Account                                                            //
    // ------------------------------------------------------------------ //

    /** Request account deletion. Returns 0 on success. */
    fun synheart_core_request_account_deletion(handle: Pointer?): Int

    /** Cancel a pending account-deletion request. Returns 0 on success. */
    fun synheart_core_cancel_account_deletion(handle: Pointer?): Int

    // ------------------------------------------------------------------ //
    // Sync engine                                                         //
    // ------------------------------------------------------------------ //

    /** Enable or disable background sync. */
    fun synheart_core_set_sync_enabled(handle: Pointer?, enabled: Int)

    /** Trigger an immediate sync cycle. Returns result JSON or null. */
    fun synheart_core_sync_now(handle: Pointer?): Pointer?

    /** Create a new sync space owned by this device. Caller frees. */
    fun synheart_core_sync_create_space(handle: Pointer?, device_name: String?): Pointer?

    /** Mint a short-lived pairing token for another device. Caller frees. */
    fun synheart_core_sync_generate_pairing(handle: Pointer?): Pointer?

    /** Join an existing space with a pairing token. Caller frees. */
    fun synheart_core_sync_join_space(
        handle: Pointer?,
        pairing_token: String?,
        device_name: String?,
    ): Pointer?

    /** Sync engine status snapshot as JSON. Caller frees. */
    fun synheart_core_sync_status(handle: Pointer?): Pointer?

    /** Sync readiness snapshot (what still blocks a first sync). Caller frees. */
    fun synheart_core_sync_readiness(handle: Pointer?): Pointer?

    /** Recover space access from a recovery key. Caller frees. */
    fun synheart_core_sync_recover_space(
        handle: Pointer?,
        recovery_key: String?,
        space_id: String?,
    ): Pointer?

    /** Leave the current space (keeps the space alive for others). Caller frees. */
    fun synheart_core_sync_leave_space(handle: Pointer?): Pointer?

    /** List devices in the current space. Caller frees. */
    fun synheart_core_sync_list_devices(handle: Pointer?): Pointer?

    /** Revoke another device's membership. Caller frees. */
    fun synheart_core_sync_revoke_device(handle: Pointer?, device_id: String?): Pointer?

    /** Delete the space for everyone (owner only). Caller frees. */
    fun synheart_core_sync_delete_space(handle: Pointer?): Pointer?

    /** Forget local space state without touching the server. Caller frees. */
    fun synheart_core_sync_clear_local_space(handle: Pointer?): Pointer?

    // ------------------------------------------------------------------ //
    // SRM / baselines                                                     //
    // ------------------------------------------------------------------ //

    /** Get current baselines as JSON. */
    fun synheart_core_baselines_json(handle: Pointer?): Pointer?

    /** Export the SRM snapshot as JSON. */
    fun synheart_core_export_srm_snapshot(handle: Pointer?): Pointer?

    /** Load an SRM snapshot from JSON. Returns 0 on success. */
    fun synheart_core_load_srm_snapshot(handle: Pointer?, json: String?): Int

    /** Get overall SRM status as JSON. */
    fun synheart_core_srm_overall_status(handle: Pointer?): Pointer?

    /**
     * Feed one day of a wearable-derived dimension into the SRM.
     * `day_index` is days since the Unix epoch; `fidelity` is the
     * numeric `Fidelity` discriminant.
     */
    fun synheart_core_srm_push_wearable_daily(
        handle: Pointer?,
        dimension: String?,
        day_index: Int,
        value: Double,
        confidence: Double,
        fidelity: Int,
    )

    /** Recompute wearable-backed baselines after a bulk push. */
    fun synheart_core_srm_trigger_wearable_recompute(
        handle: Pointer?,
        trigger_type: Int,
        as_of_day: Int,
    )

    /** Export the longitudinal (multi-day) snapshot as JSON. Caller frees. */
    fun synheart_core_export_longitudinal_snapshot(handle: Pointer?): Pointer?

    /** Load a longitudinal snapshot. Returns 0 on success. */
    fun synheart_core_load_longitudinal_snapshot(handle: Pointer?, snapshot_json: String?): Int

    /** Hydrate baselines from local storage. Returns a JSON report. Caller frees. */
    fun synheart_core_baseline_hydrate_local(handle: Pointer?): Pointer?

    /** Export an encrypted offline baseline blob (base64). Caller frees. */
    fun synheart_core_baseline_export_offline(handle: Pointer?, passphrase: String?): Pointer?

    /** Import an encrypted offline baseline blob. Returns a JSON report. Caller frees. */
    fun synheart_core_baseline_import_offline(
        handle: Pointer?,
        passphrase: String?,
        blob_b64: String?,
    ): Pointer?

    /** Wearable reference view (per-dimension baseline reference). Caller frees. */
    fun synheart_core_wearable_reference_json(handle: Pointer?): Pointer?

    // ------------------------------------------------------------------ //
    // Scores — sleep / recovery / readiness                               //
    // ------------------------------------------------------------------ //

    /** Compute a sleep score from a typed input JSON. Caller frees. */
    fun synheart_core_sleep_score_compute_json(handle: Pointer?, input_json: String?): Pointer?

    /** As above, tagged with a correlation id for tracing. Caller frees. */
    fun synheart_core_sleep_score_compute_json_traced(
        handle: Pointer?,
        input_json: String?,
        correlation_id: String?,
    ): Pointer?

    /** Last computed sleep score as JSON, or null. Caller frees. */
    fun synheart_core_last_sleep_score_json(handle: Pointer?): Pointer?

    /** Attach a sleep-score result to today's longitudinal record. Returns 0 on success. */
    fun synheart_core_attach_sleep_score_json(handle: Pointer?, result_json: String?): Int

    /** Compute a recovery score from a typed input JSON. Caller frees. */
    fun synheart_core_recovery_score_compute_json(handle: Pointer?, input_json: String?): Pointer?

    /** As above, tagged with a correlation id for tracing. Caller frees. */
    fun synheart_core_recovery_score_compute_json_traced(
        handle: Pointer?,
        input_json: String?,
        correlation_id: String?,
    ): Pointer?

    /** Pin today's recovery score (0..100) for downstream readiness. Returns 0 on success. */
    fun synheart_core_attach_recovery_score_today(handle: Pointer?, score: Byte): Int

    /** Clear today's pinned recovery score. Returns 0 on success. */
    fun synheart_core_clear_recovery_score_today(handle: Pointer?): Int

    /** Compute a readiness score from a typed input JSON. Caller frees. */
    fun synheart_core_readiness_score_compute_json(handle: Pointer?, input_json: String?): Pointer?

    /** As above, tagged with a correlation id for tracing. Caller frees. */
    fun synheart_core_readiness_score_compute_json_traced(
        handle: Pointer?,
        input_json: String?,
        correlation_id: String?,
    ): Pointer?

    // ------------------------------------------------------------------ //
    // Cloud upload queue + HSI history                                    //
    // ------------------------------------------------------------------ //

    /** Enqueue an HSI snapshot for cloud upload. */
    fun synheart_core_enqueue_hsi(handle: Pointer?, hsi_json: String?, timestamp_ms: Long)

    /** Return the current upload queue length. */
    fun synheart_core_upload_queue_length(handle: Pointer?): Int

    /** Flush pending uploads. Returns result JSON or null. */
    fun synheart_core_flush_uploads(handle: Pointer?): Pointer?

    /** Wall-clock ms of the last successful ingest, or 0. */
    fun synheart_core_last_ingest_success_at_ms(handle: Pointer?): Long

    /** Upload platform metadata. Returns result JSON or null. */
    fun synheart_core_upload_metadata(handle: Pointer?): Pointer?

    /** Locally retained HSI windows as a JSON array. Caller frees. */
    fun synheart_core_hsi_history_list(handle: Pointer?, since_unix_ms: Long, limit: Long): Pointer?

    /** Number of locally retained HSI windows. */
    fun synheart_core_hsi_history_count(handle: Pointer?): Long

    /** Drop the local HSI history. Returns 0 on success. */
    fun synheart_core_hsi_history_clear(handle: Pointer?): Int

    /** Read HSI windows back from the cloud for a time range. Caller frees. */
    fun synheart_core_fetch_cloud_hsi(handle: Pointer?, from_unix_ms: Long, to_unix_ms: Long): Pointer?

    // ------------------------------------------------------------------ //
    // Wellness score                                                      //
    // ------------------------------------------------------------------ //

    /** Get the last Wellness Score as JSON, or null if baselines are not ready. */
    fun synheart_core_wellness_json(handle: Pointer?): Pointer?

    // ------------------------------------------------------------------ //
    // Diagnostics / status                                                //
    // ------------------------------------------------------------------ //

    /** Return full runtime diagnostics as JSON. */
    fun synheart_core_diagnostics(handle: Pointer?): Pointer?

    /** Return the last error code (0 = no error). */
    fun synheart_core_last_error_code(handle: Pointer?): Int

    /** Returns 1 if the native runtime reported itself as available. */
    fun synheart_core_is_runtime_available(handle: Pointer?): Int

    /** Returns 1 if the network is reachable (as reported by the runtime). */
    fun synheart_core_is_network_reachable(handle: Pointer?): Int

    // ------------------------------------------------------------------ //
    // Subject identity                                                    //
    // ------------------------------------------------------------------ //

    /** The runtime's canonical subject id (RFC-0008). Caller frees. */
    fun synheart_core_get_subject_id(handle: Pointer?): Pointer?

    /**
     * Atomically rebind the runtime subject id. Returns 1 when a re-mint is
     * required, 0 when an existing valid token was loaded, -1 on error.
     */
    fun synheart_core_rebind_subject_id(
        handle: Pointer?,
        subject_id: String?,
        invalidate_token: Int,
    ): Int

    // ------------------------------------------------------------------ //
    // Device auth (SDK-hosted crypto + storage)                           //
    // ------------------------------------------------------------------ //

    /** Hand the runtime a struct of Keystore-backed crypto callbacks. 0 on success. */
    fun synheart_core_sdk_set_crypto_callbacks(handle: Pointer?, callbacks: Pointer?): Int

    /** Hand the runtime its secure key-value storage callbacks. 0 on success. */
    fun synheart_core_set_storage_callbacks(
        handle: Pointer?,
        store: Callback?,
        load: Callback?,
        delete: Callback?,
    ): Int

    /** Register this device under `client_id` (the subject id). Caller frees. */
    fun synheart_core_sdk_register_device(handle: Pointer?, client_id: String?): Pointer?

    /** Device-auth status JSON. Caller frees. */
    fun synheart_core_sdk_device_auth_status(handle: Pointer?): Pointer?

    /**
     * Build a device-signed proof header for an outbound request.
     * Returns the header value, or null when no device key is registered.
     */
    fun synheart_core_sdk_build_proof_header(
        handle: Pointer?,
        method: String?,
        url: String?,
    ): Pointer?

    // ------------------------------------------------------------------ //
    // HSI + stream callbacks                                              //
    // ------------------------------------------------------------------ //

    /** Register a callback invoked on every closed HSI window. */
    fun synheart_core_set_hsi_callback(
        handle: Pointer?,
        callback: HsiCallbackNative?,
        user_data: Pointer?,
    )

    /** Unregister the HSI callback. */
    fun synheart_core_clear_hsi_callback(handle: Pointer?)

    /** Register a callback for streaming pipeline events. */
    fun synheart_core_set_stream_callback(
        handle: Pointer?,
        callback: StreamEventCallbackNative?,
        user_data: Pointer?,
    )

    /** Start the streaming pipeline with a JSON config. Returns 0 on success. */
    fun synheart_core_stream_start(handle: Pointer?, config_json: String?): Int

    /** Stop the streaming pipeline. Returns 0 on success. */
    fun synheart_core_stream_stop(handle: Pointer?): Int

    /** Current streaming pipeline state as JSON. Caller frees. */
    fun synheart_core_stream_state(handle: Pointer?): Pointer?

    // ------------------------------------------------------------------ //
    // Lab protocol                                                        //
    // ------------------------------------------------------------------ //

    /** Returns 1 if the lab module is compiled into the loaded runtime. */
    fun synheart_core_is_lab_available(handle: Pointer?): Int

    /** Start a lab protocol. Returns 0 on success. */
    fun synheart_core_lab_start(handle: Pointer?, protocol_json: String?, started_at_ms: Long): Int

    /** Open a lab window. Returns the window id. Caller frees. */
    fun synheart_core_lab_open_window(
        handle: Pointer?,
        parent_id: String?,
        window_type: String?,
        label: String?,
        started_at_ms: Long,
    ): Pointer?

    /** Close a lab window. Returns 0 on success. */
    fun synheart_core_lab_close_window(handle: Pointer?, window_id: String?, ended_at_ms: Long): Int

    /** Set values on a lab window. Returns 0 on success. */
    fun synheart_core_lab_set_window_values(
        handle: Pointer?,
        window_id: String?,
        values_json: String?,
    ): Int

    /** Merge a patch into the protocol's extra data. Returns 0 on success. */
    fun synheart_core_lab_merge_extra_data(handle: Pointer?, patch_json: String?): Int

    /** Set state overrides on a lab window. Returns 0 on success. */
    fun synheart_core_lab_set_state_overrides(
        handle: Pointer?,
        window_id: String?,
        overrides_json: String?,
    ): Int

    /** Finalize the protocol. Returns the session JSON. Caller frees. */
    fun synheart_core_lab_finalize(handle: Pointer?, ended_at_ms: Long): Pointer?

    /** Export the in-progress protocol as JSON. Caller frees. */
    fun synheart_core_lab_export_json(handle: Pointer?): Pointer?

    /** Ensure a lab metadata record exists for this device/user. Caller frees. */
    fun synheart_core_lab_ensure_metadata(
        handle: Pointer?,
        device_id: String?,
        platform: String?,
        os_version: String?,
        user_info_json: String?,
        device_extra_json: String?,
    ): Pointer?

    /** Id of the current lab metadata record, or null. Caller frees. */
    fun synheart_core_lab_current_metadata_id(handle: Pointer?): Pointer?

    /** Mark the metadata record dirty so it re-uploads. Returns 0 on success. */
    fun synheart_core_lab_mark_metadata_dirty(handle: Pointer?, reason: String?): Int

    /** Re-enqueue a finalized lab session for upload. Returns 0 on success. */
    fun synheart_core_reenqueue_lab_session(handle: Pointer?, session_json: String?): Int

    // ------------------------------------------------------------------ //
    // Multi-source priority (handle-free, process-global)                 //
    // ------------------------------------------------------------------ //

    /** Set the global rank for a provider. Returns 0 on success, -1 on bad input. */
    fun synheart_core_priority_set_provider(provider: String?, rank: Int): Int

    /**
     * Set or clear a per-metric rank override for `(metric, provider)`.
     * Pass `rank_present = 0` to clear the override; `1` to set to `rank`.
     */
    fun synheart_core_priority_set_metric_override(
        metric: String?,
        provider: String?,
        rank_present: Int,
        rank: Int,
    ): Int

    /**
     * Read the effective rank for `(metric, provider)`. Returns
     * `Int.MAX_VALUE` (the runtime's `ProviderRank::UNRANKED` sentinel)
     * for unknown providers, or -1 if `metric` is unparseable.
     */
    fun synheart_core_priority_effective_rank(metric: String?, provider: String?): Int

    /**
     * Resolve the winning source for `metric` given a `{provider: count}`
     * JSON map. Returns a JSON string pointer, or null on bad input.
     */
    fun synheart_core_priority_resolve(metric: String?, samples_json: String?): Pointer?

    // ------------------------------------------------------------------ //
    // HRV-CV resilience score (handle-free, stateless)                    //
    // ------------------------------------------------------------------ //

    /**
     * Compute a resilience score from samples + sleep windows + config.
     * Returns JSON string pointer (caller frees) or null on bad input.
     */
    fun synheart_core_resilience_compute_v1(
        samples_json: String?,
        windows_json: String?,
        config_json: String?,
    ): Pointer?

    // ------------------------------------------------------------------ //
    // Historical backfill (handle-free, SQLite ingest)                    //
    // ------------------------------------------------------------------ //

    /** Open (or create) a backfill import under `import_id`. Returns 0 on success. */
    fun synheart_core_backfill_open(db_path: String?, import_id: String?): Int

    /** Insert a batch of historical samples (JSON array). Returns a JSON report. Caller frees. */
    fun synheart_core_backfill_insert_batch(import_id: String?, samples_json: String?): Pointer?

    /** Finalize an import and return its summary JSON. Caller frees. */
    fun synheart_core_backfill_finalize(import_id: String?): Pointer?

    // ------------------------------------------------------------------ //
    // Breathing compliance                                                //
    // RR samples pushed via `synheart_core_push_rr` already feed the      //
    // detector. These configure the target / window / population and     //
    // read back JSON verdicts.                                            //
    // ------------------------------------------------------------------ //

    fun synheart_core_breathing_set_target_bpm(handle: Pointer?, bpm: Double)
    fun synheart_core_breathing_set_window_secs(handle: Pointer?, secs: Int)
    fun synheart_core_breathing_set_population(handle: Pointer?, profile: Int)

    /**
     * Evaluate breathing compliance over the current RR window. Returns a
     * JSON `ComplianceResult` string (caller frees) or null when the
     * detector has nothing to report.
     */
    fun synheart_core_breathing_evaluate(handle: Pointer?): Pointer?

    /** Clear the breathing detector's RR ring buffer. */
    fun synheart_core_breathing_reset(handle: Pointer?)
}

/** JNA callback interface for HSI window updates. */
interface HsiCallbackNative : Callback {
    fun invoke(hsiJson: Pointer?, userData: Pointer?)
}

/** JNA callback interface for streaming pipeline events. */
interface StreamEventCallbackNative : Callback {
    fun invoke(eventJson: Pointer?, userData: Pointer?)
}

/** JNA callback interface for runtime log lines. */
interface HostLogCallbackNative : Callback {
    fun invoke(line: Pointer?, userData: Pointer?)
}
