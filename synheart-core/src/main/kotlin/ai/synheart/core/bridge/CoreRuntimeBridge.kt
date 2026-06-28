package ai.synheart.core.bridge

import com.sun.jna.Pointer

/**
 * Safe Kotlin wrapper around [CoreRuntimeNative].
 *
 * Manages the opaque runtime handle lifetime and converts JNA pointer-based
 * strings into nullable Kotlin strings (with automatic free via
 * [CoreRuntimeNative.synheart_core_free_string]).
 *
 * Obtain an instance through [create]; call [close] when done to release
 * native resources.
 */
class CoreRuntimeBridge private constructor(private var handle: Pointer?) {

    private val lib: CoreRuntimeNative
        get() = CoreRuntimeNative.INSTANCE
            ?: throw IllegalStateException("synheart_core_runtime native library not loaded")

    companion object {
        /**
         * Create a new runtime instance from a JSON configuration string.
         * Returns null if the native library is unavailable or initialisation fails.
         */
        fun create(configJson: String): CoreRuntimeBridge? {
            val native = CoreRuntimeNative.INSTANCE ?: return null
            val ptr = native.synheart_core_new(configJson) ?: return null
            return CoreRuntimeBridge(ptr)
        }

        /** Returns true if the native shared library was loaded successfully. */
        fun isAvailable(): Boolean = CoreRuntimeNative.INSTANCE != null
    }

    // ------------------------------------------------------------------ //
    // Internal helpers                                                    //
    // ------------------------------------------------------------------ //

    /**
     * Read a UTF-8 string from a JNA [Pointer] returned by the runtime,
     * then free the native allocation. Returns null when [ptr] is null.
     */
    private fun readAndFreeString(ptr: Pointer?): String? {
        if (ptr == null) return null
        val str = ptr.getString(0, "UTF-8")
        lib.synheart_core_free_string(ptr)
        return str
    }

    private fun requireHandle(): Pointer =
        handle ?: throw IllegalStateException("CoreRuntimeBridge has been closed")

    // ------------------------------------------------------------------ //
    // Lifecycle                                                          //
    // ------------------------------------------------------------------ //

    /**
     * Release the native runtime handle. After this call the bridge instance
     * must not be used again.
     */
    fun close() {
        handle?.let { lib.synheart_core_free(it) }
        handle = null
    }

    // ------------------------------------------------------------------ //
    // Session                                                            //
    // ------------------------------------------------------------------ //

    /** Start a new session. Returns the SessionHandle JSON or null on failure. */
    fun startSession(): String? =
        readAndFreeString(lib.synheart_core_start_session(requireHandle()))

    /** Stop the current session. Returns true on success. */
    fun stopSession(): Boolean =
        lib.synheart_core_stop_session(requireHandle()) == 0

    /** Get the current session as JSON, or null if no session is active. */
    fun currentSession(): String? =
        readAndFreeString(lib.synheart_core_current_session(requireHandle()))

    /** Returns true if a session is currently running. */
    fun isRunning(): Boolean =
        lib.synheart_core_is_running(requireHandle()) == 1

    // ------------------------------------------------------------------ //
    // Sensor push                                                        //
    // ------------------------------------------------------------------ //

    /** Push an R-R interval sample (milliseconds). */
    fun pushRr(tsMs: Long, rrMs: Double) {
        lib.synheart_core_push_rr(requireHandle(), tsMs, rrMs)
    }

    /** Push a heart-rate sample (BPM). */
    fun pushHr(tsMs: Long, bpm: Double) {
        lib.synheart_core_push_hr(requireHandle(), tsMs, bpm)
    }

    /** Push a 3-axis accelerometer sample. */
    fun pushAccel(tsMs: Long, x: Double, y: Double, z: Double) {
        lib.synheart_core_push_accel(requireHandle(), tsMs, x, y, z)
    }

    /** Push a behavioral event (e.g. "typing", "focus_shift"). */
    fun pushBehavior(tsMs: Long, eventType: String, value: Double) {
        lib.synheart_core_push_behavior(requireHandle(), tsMs, eventType, value)
    }

    /** Push sleep-stage data as a JSON array. */
    fun pushSleepStages(json: String) {
        lib.synheart_core_push_sleep_stages(requireHandle(), json)
    }

    /** Ingest a pre-built batch (JSON). Returns result JSON or null on failure. */
    fun ingestBatch(batchJson: String, nowMs: Long): String? =
        readAndFreeString(lib.synheart_core_ingest_batch(requireHandle(), batchJson, nowMs))

    // ------------------------------------------------------------------ //
    // Consent                                                            //
    // ------------------------------------------------------------------ //

    /** Grant a consent type. Returns true on success. */
    fun grantConsent(type: String): Boolean =
        lib.synheart_core_grant_consent(requireHandle(), type) == 0

    /** Revoke a consent type. Returns true on success. */
    fun revokeConsent(type: String): Boolean =
        lib.synheart_core_revoke_consent(requireHandle(), type) == 0

    /** Check whether a consent type is currently granted. */
    fun hasConsent(type: String): Boolean =
        lib.synheart_core_has_consent(requireHandle(), type) == 1

    /** Return the full consent state as JSON. */
    fun currentConsent(): String? =
        readAndFreeString(lib.synheart_core_current_consent(requireHandle()))

    // ------------------------------------------------------------------ //
    // Cloud consent (token mint + status)                                //
    // ------------------------------------------------------------------ //

    /** Configure the cloud consent service (base URL + app id). Returns true on success. */
    fun consentConfigureCloud(baseUrl: String, appId: String): Boolean =
        lib.synheart_core_consent_configure_cloud(requireHandle(), baseUrl, appId) == 0

    /** Return the editable consent form as JSON, or null. */
    fun consentGetEditableForm(): String? =
        readAndFreeString(lib.synheart_core_consent_get_editable_form(requireHandle()))

    /**
     * Submit a consent form to mint/refresh the cloud consent token under the
     * runtime's subject. Returns the JSON result (issued token, or `error`), or null.
     */
    fun consentSubmitForm(
        deviceId: String?,
        platform: String,
        userId: String?,
        formJson: String,
    ): String? =
        readAndFreeString(
            lib.synheart_core_consent_submit_form(
                requireHandle(),
                deviceId,
                platform,
                userId,
                formJson,
            ),
        )

    /** Cloud consent status as JSON (e.g. `{"status":"granted"|"pending"}`), or null. */
    fun consentStatus(): String? =
        readAndFreeString(lib.synheart_core_consent_status(requireHandle()))

    /** Effective consent state as JSON (token-authoritative when present), or null. */
    fun consentEffectiveState(): String? =
        readAndFreeString(lib.synheart_core_consent_effective_state(requireHandle()))

    /** True if the consent token should be refreshed soon. */
    fun consentNeedsTokenRefresh(): Boolean =
        lib.synheart_core_consent_needs_token_refresh(requireHandle()) == 1

    /** Clear the stored consent token + snapshot. Returns true on success. */
    fun consentClearStored(): Boolean =
        lib.synheart_core_consent_clear_stored(requireHandle()) == 0

    // ------------------------------------------------------------------ //
    // Research study                                                      //
    // ------------------------------------------------------------------ //

    /** Enrol the device in a research study. Returns the service response JSON. */
    fun enrolResearchStudy(accessCode: String, studyCode: String): String? =
        readAndFreeString(lib.synheart_core_enrol_study(requireHandle(), accessCode, studyCode))

    /** Preview an access + study code pair without redeeming the code. */
    fun validateResearchStudyCodes(accessCode: String, studyCode: String): String? =
        readAndFreeString(lib.synheart_core_validate_study_codes(requireHandle(), accessCode, studyCode))

    /** Withdraw from the device's active research study for this app. Idempotent. */
    fun withdrawResearchStudy(): String? =
        readAndFreeString(lib.synheart_core_withdraw_study(requireHandle()))

    /**
     * Request erasure of the data the participant contributed to their study.
     * [dryRun] returns an inventory preview without deleting; a real request is
     * accepted asynchronously and carries a `request_id`. Idempotent.
     */
    fun requestStudyDataDeletion(dryRun: Boolean): String? =
        readAndFreeString(
            lib.synheart_core_request_study_data_deletion(
                requireHandle(),
                if (dryRun) 1.toByte() else 0.toByte(),
            ),
        )

    // ------------------------------------------------------------------ //
    // Capability                                                         //
    // ------------------------------------------------------------------ //

    /** Load and verify a capability token. Returns true on success. */
    fun loadCapabilityToken(tokenJson: String, secret: String): Boolean =
        lib.synheart_core_load_capability_token(requireHandle(), tokenJson, secret) == 0

    // ------------------------------------------------------------------ //
    // Query                                                              //
    // ------------------------------------------------------------------ //

    /** List all sessions as a JSON array. */
    fun listSessions(): String? =
        readAndFreeString(lib.synheart_core_list_sessions(requireHandle()))

    /** Get a session summary by session ID. Returns JSON or null. */
    fun getSessionSummary(sessionId: String): String? =
        readAndFreeString(lib.synheart_core_get_session_summary(requireHandle(), sessionId))

    /**
     * Get HSI windows for a session within an optional time range.
     * Pass 0 for [startMs]/[endMs] to omit bounds. Pass 0 for [limit] for no limit.
     */
    fun getHsiWindows(sessionId: String, startMs: Long = 0, endMs: Long = 0, limit: Int = 0): String? =
        readAndFreeString(
            lib.synheart_core_get_hsi_windows(requireHandle(), sessionId, startMs, endMs, limit)
        )

    /** Get storage usage statistics as JSON. */
    fun getStorageUsage(): String? =
        readAndFreeString(lib.synheart_core_get_storage_usage(requireHandle()))

    // ------------------------------------------------------------------ //
    // Metrics                                                            //
    // ------------------------------------------------------------------ //

    /** Record a metric event (JSON). Returns true on success. */
    fun recordMetric(json: String): Boolean =
        lib.synheart_core_record_metric(requireHandle(), json) == 0

    // ------------------------------------------------------------------ //
    // Data management                                                    //
    // ------------------------------------------------------------------ //

    /** Delete a local session by ID (creates tombstone). Returns true on success. */
    fun deleteSession(sessionId: String): Boolean =
        lib.synheart_core_delete_session(requireHandle(), sessionId) == 0

    /** Wipe all local data (storage, keys, sync state). Returns true on success. */
    fun wipeLocalData(): Boolean =
        lib.synheart_core_wipe_local_data(requireHandle()) == 0

    /**
     * Set the retention period in days. Artifacts older than this are
     * tombstoned. Returns the number of artifacts affected.
     */
    fun setRetentionDays(days: Int): Long =
        lib.synheart_core_set_retention_days(requireHandle(), days)

    // ------------------------------------------------------------------ //
    // Sync                                                               //
    // ------------------------------------------------------------------ //

    /** Enable or disable background sync. */
    fun setSyncEnabled(enabled: Boolean) {
        lib.synheart_core_set_sync_enabled(requireHandle(), if (enabled) 1 else 0)
    }

    /** Trigger an immediate sync cycle. Returns result JSON or null. */
    fun syncNow(): String? =
        readAndFreeString(lib.synheart_core_sync_now(requireHandle()))

    // ------------------------------------------------------------------ //
    // SRM / Baselines                                                    //
    // ------------------------------------------------------------------ //

    /** Get current baselines as JSON. */
    fun baselinesJson(): String? =
        readAndFreeString(lib.synheart_core_baselines_json(requireHandle()))

    /** Export the SRM snapshot as JSON. */
    fun exportSrmSnapshot(): String? =
        readAndFreeString(lib.synheart_core_export_srm_snapshot(requireHandle()))

    /** Load an SRM snapshot from JSON. Returns true on success. */
    fun loadSrmSnapshot(json: String): Boolean =
        lib.synheart_core_load_srm_snapshot(requireHandle(), json) == 0

    /** Get overall SRM status as JSON. */
    fun srmOverallStatus(): String? =
        readAndFreeString(lib.synheart_core_srm_overall_status(requireHandle()))

    // ------------------------------------------------------------------ //
    // Cloud upload queue                                                 //
    // ------------------------------------------------------------------ //

    /** Enqueue an HSI snapshot for cloud upload. */
    fun enqueueHsi(json: String, timestampMs: Long) {
        lib.synheart_core_enqueue_hsi(requireHandle(), json, timestampMs)
    }

    /** Return the current upload queue length. */
    fun uploadQueueLength(): Int =
        lib.synheart_core_upload_queue_length(requireHandle())

    /** Flush pending uploads. Returns result JSON or null. */
    fun flushUploads(): String? =
        readAndFreeString(lib.synheart_core_flush_uploads(requireHandle()))

    // ------------------------------------------------------------------ //
    // Ambient capture                                                    //
    // ------------------------------------------------------------------ //

    /** Enable/disable ambient capture (HSI windows forwarded regardless of session state). */
    fun setAmbientCapture(enabled: Boolean) {
        lib.synheart_core_set_ambient_capture(requireHandle(), if (enabled) 1 else 0)
    }

    /** Read the ambient-capture flag. */
    fun getAmbientCapture(): Boolean =
        lib.synheart_core_get_ambient_capture(requireHandle()) != 0

    /** Upload platform metadata. Returns result JSON or null. */
    fun uploadMetadata(): String? =
        readAndFreeString(lib.synheart_core_upload_metadata(requireHandle()))

    // ------------------------------------------------------------------ //
    // Wellness Score                                                     //
    // ------------------------------------------------------------------ //

    /** Get the last Wellness Score as JSON, or null if baselines are not ready. */
    fun wellnessJson(): String? =
        readAndFreeString(lib.synheart_core_wellness_json(requireHandle()))

    // ------------------------------------------------------------------ //
    // Diagnostics / status                                               //
    // ------------------------------------------------------------------ //

    /** Return full runtime diagnostics as JSON. */
    fun diagnostics(): String? =
        readAndFreeString(lib.synheart_core_diagnostics(requireHandle()))

    /** Return the last error code (0 = no error). */
    fun lastErrorCode(): Int =
        lib.synheart_core_last_error_code(requireHandle())

    /** Returns true if the native runtime reported itself as available. */
    fun isRuntimeAvailable(): Boolean =
        lib.synheart_core_is_runtime_available(requireHandle()) == 1

    /** Returns true if the network is reachable (as reported by the runtime). */
    fun isNetworkReachable(): Boolean =
        lib.synheart_core_is_network_reachable(requireHandle()) == 1

    // ------------------------------------------------------------------ //
    // Account                                                            //
    // ------------------------------------------------------------------ //

    /** Request account deletion. Returns true on success. */
    fun requestAccountDeletion(): Boolean =
        lib.synheart_core_request_account_deletion(requireHandle()) == 0

    /** Cancel a pending account deletion request. Returns true on success. */
    fun cancelAccountDeletion(): Boolean =
        lib.synheart_core_cancel_account_deletion(requireHandle()) == 0

    // ------------------------------------------------------------------ //
    // HSI State Callback                                                 //
    // ------------------------------------------------------------------ //

    private var hsiCallback: HsiCallbackNative? = null

    /**
     * Register a callback for real-time HSI state updates.
     *
     * The callback fires on a native background thread. Use
     * `Dispatchers.Main` to post to the UI thread.
     */
    fun setHsiCallback(onHsi: (String) -> Unit) {
        clearHsiCallback()
        hsiCallback = object : HsiCallbackNative {
            override fun invoke(hsiJson: Pointer?, userData: Pointer?) {
                if (hsiJson != null) {
                    onHsi(hsiJson.getString(0))
                }
            }
        }
        lib.synheart_core_set_hsi_callback(requireHandle(), hsiCallback, null)
    }

    /** Unregister the HSI callback. */
    fun clearHsiCallback() {
        if (hsiCallback != null) {
            lib.synheart_core_clear_hsi_callback(requireHandle())
            hsiCallback = null
        }
    }

    // ------------------------------------------------------------------ //
    // Lab Protocol                                                       //
    // ------------------------------------------------------------------ //

    /** Returns true if the lab module is available in the native runtime. */
    fun isLabAvailable(): Boolean =
        lib.synheart_core_lab_is_available(requireHandle()) == 1

    /** Start a lab protocol. Returns true on success. */
    fun labStart(protocolJson: String, startedAtMs: Long): Boolean =
        lib.synheart_core_lab_start(requireHandle(), protocolJson, startedAtMs) == 0

    /** Open a new lab window. Returns the window ID string, or null on failure. */
    fun labOpenWindow(parentId: String?, windowType: String, label: String?, startedAtMs: Long): String? =
        readAndFreeString(
            lib.synheart_core_lab_open_window(requireHandle(), parentId, windowType, label, startedAtMs)
        )

    /** Close a lab window. Returns true on success. */
    fun labCloseWindow(windowId: String, endedAtMs: Long): Boolean =
        lib.synheart_core_lab_close_window(requireHandle(), windowId, endedAtMs) == 0

    /** Set values on a lab window. Returns true on success. */
    fun labSetWindowValues(windowId: String, valuesJson: String): Boolean =
        lib.synheart_core_lab_set_window_values(requireHandle(), windowId, valuesJson) == 0

    /** Merge extra data into the lab protocol. Returns true on success. */
    fun labMergeExtraData(patchJson: String): Boolean =
        lib.synheart_core_lab_merge_extra_data(requireHandle(), patchJson) == 0

    /** Set state overrides on a lab window. Returns true on success. */
    fun labSetStateOverrides(windowId: String, overridesJson: String): Boolean =
        lib.synheart_core_lab_set_state_overrides(requireHandle(), windowId, overridesJson) == 0

    /** Finalize the lab protocol. Returns the result JSON string, or null on failure. */
    fun labFinalize(endedAtMs: Long): String? =
        readAndFreeString(lib.synheart_core_lab_finalize(requireHandle(), endedAtMs))

    // ------------------------------------------------------------------ //
    // Breathing compliance                                                //
    // ------------------------------------------------------------------ //

    /** Set the target breathing rate in breaths per minute (e.g. 6.0 for resonance). */
    fun breathingSetTargetBpm(bpm: Double) {
        lib.synheart_core_breathing_set_target_bpm(requireHandle(), bpm)
    }

    /** Set the rolling-window length in seconds. Native side clamps to `[30, 120]`. */
    fun breathingSetWindowSecs(secs: Int) {
        lib.synheart_core_breathing_set_window_secs(requireHandle(), secs)
    }

    /**
     * Set the population threshold profile.
     * `0 = Beginner`, `1 = Experienced`, `2 = Clinical`.
     */
    fun breathingSetPopulation(profile: Int) {
        lib.synheart_core_breathing_set_population(requireHandle(), profile)
    }

    /**
     * Evaluate breathing compliance over the current RR window. Returns a
     * JSON `ComplianceResult` string or null when there isn't enough Tier-1
     * data yet.
     */
    fun breathingEvaluateJson(): String? =
        readAndFreeString(lib.synheart_core_breathing_evaluate(requireHandle()))

    /** Clear the breathing detector's RR ring buffer. */
    fun breathingReset() {
        lib.synheart_core_breathing_reset(requireHandle())
    }
}
