package ai.synheart.core.bridge

import ai.synheart.core.config.unwrapSyncEnvelope
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Safe Kotlin wrapper around [CoreRuntimeNative].
 *
 * Manages the opaque runtime handle lifetime and converts JNA pointer-based
 * strings into nullable Kotlin strings (with automatic free via
 * [CoreRuntimeNative.synheart_core_free_string]).
 *
 * Obtain an instance through [create]; call [close] when done to release
 * native resources.
 *
 * Every method that reaches a symbol a lagging vendored runtime may not export
 * is wrapped so an [UnsatisfiedLinkError] degrades to a null/false/-1 result
 * rather than taking the app down. Check [missingSymbols] to see which ones
 * the loaded library actually lacks.
 */
class CoreRuntimeBridge private constructor(private var handle: Pointer?) {

    private val lib: CoreRuntimeNative
        get() = CoreRuntimeNative.INSTANCE
            ?: throw IllegalStateException("synheart_core_runtime native library not loaded")

    /**
     * Symbols the loaded runtime turned out not to export, recorded the first
     * time a call fell through. Surfaced in [diagnosticsMap] so a host can tell
     * "the feature is off" apart from "this runtime build is too old".
     */
    private val _missingSymbols = linkedSetOf<String>()
    val missingSymbols: Set<String> get() = _missingSymbols.toSet()

    companion object {
        /** Default `env_filter` when [initLogging] is called with a null/empty filter. */
        var defaultLogEnvFilter: String = "info"

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

        // -------------------------------------------------------------- //
        // Handle-free entry points                                        //
        // -------------------------------------------------------------- //

        private fun staticReadAndFree(ptr: Pointer?): String? {
            val native = CoreRuntimeNative.INSTANCE ?: return null
            if (ptr == null) return null
            return try {
                ptr.getString(0, "UTF-8")
            } finally {
                native.synheart_core_free_string(ptr)
            }
        }

        /** Runtime semantic version, or null when the library is absent. */
        fun runtimeVersion(): String? = try {
            staticReadAndFree(CoreRuntimeNative.INSTANCE?.synheart_core_version())
        } catch (e: UnsatisfiedLinkError) {
            null
        }

        /** Build metadata (profile, features, commit), or null. */
        fun buildInfo(): JSONObject? = try {
            staticReadAndFree(CoreRuntimeNative.INSTANCE?.synheart_core_build_info())
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
        } catch (e: UnsatisfiedLinkError) {
            null
        }

        /** The runtime's last global error message, or null. */
        fun lastError(): String? = try {
            staticReadAndFree(CoreRuntimeNative.INSTANCE?.synheart_core_last_error())
        } catch (e: UnsatisfiedLinkError) {
            null
        }

        // -------------------------------------------------------------- //
        // Logging (process-global, install once)                          //
        // -------------------------------------------------------------- //

        private var logCallback: HostLogCallbackNative? = null

        @Volatile
        private var loggingInstalled = false

        /**
         * Install the runtime's `tracing` subscriber once per process.
         *
         * When [onLine] is given, lines are pushed to it from a native thread —
         * do not touch UI state directly from the callback. When it is null the
         * runtime logs through its own default sink.
         *
         * Returns 0 on success, a negative code on failure, and 0 (a no-op) if
         * logging was already installed.
         */
        @Synchronized
        fun initLogging(envFilter: String? = null, onLine: ((String) -> Unit)? = null): Int {
            if (loggingInstalled) return 0
            val native = CoreRuntimeNative.INSTANCE ?: return -1
            val filter = envFilter?.takeIf { it.isNotBlank() } ?: defaultLogEnvFilter
            return try {
                val rc = if (onLine == null) {
                    native.synheart_core_init_logging(filter, null, null)
                } else {
                    // Held in a field for the process lifetime: the runtime keeps
                    // the function pointer, so letting JNA collect the peer would
                    // leave it calling into freed memory.
                    val cb = object : HostLogCallbackNative {
                        override fun invoke(line: Pointer?, userData: Pointer?) {
                            val text = line?.getString(0, "UTF-8") ?: return
                            runCatching { onLine(text) }
                        }
                    }
                    logCallback = cb
                    native.synheart_core_init_logging(filter, cb, null)
                }
                if (rc == 0) loggingInstalled = true
                rc
            } catch (e: UnsatisfiedLinkError) {
                -2
            }
        }

        /**
         * Install logging in buffered (pull-based) mode: the runtime writes into
         * an in-process ring buffer that the host drains with [drainLogs].
         * No callback pointer is handed across the boundary, so nothing can
         * dangle. Returns 0 on success.
         */
        @Synchronized
        fun initLoggingBuffered(envFilter: String? = null): Int {
            if (loggingInstalled) return 0
            val native = CoreRuntimeNative.INSTANCE ?: return -1
            val filter = envFilter?.takeIf { it.isNotBlank() } ?: defaultLogEnvFilter
            return try {
                val rc = native.synheart_core_init_logging_buffered(filter)
                if (rc == 0) loggingInstalled = true
                rc
            } catch (e: UnsatisfiedLinkError) {
                -2
            }
        }

        /** Drain buffered log lines. Empty when buffered mode is not installed. */
        fun drainLogs(): List<String> = try {
            val json = staticReadAndFree(CoreRuntimeNative.INSTANCE?.synheart_core_drain_logs())
                ?: return emptyList()
            val arr = JSONArray(json)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }
        } catch (e: UnsatisfiedLinkError) {
            emptyList()
        } catch (e: Exception) {
            emptyList()
        }

        /** Log lines dropped because the ring buffer was full. */
        fun droppedLogLines(): Long = try {
            CoreRuntimeNative.INSTANCE?.synheart_core_dropped_log_lines() ?: 0L
        } catch (e: UnsatisfiedLinkError) {
            0L
        }

        /** Tear the subscriber down. Returns 0 on success. */
        @Synchronized
        fun shutdownLogging(): Int = try {
            val rc = CoreRuntimeNative.INSTANCE?.synheart_core_shutdown_logging() ?: -1
            if (rc == 0) {
                loggingInstalled = false
                logCallback = null
            }
            rc
        } catch (e: UnsatisfiedLinkError) {
            -2
        }

        // -------------------------------------------------------------- //
        // Multi-source priority (process-global, handle-free)             //
        // -------------------------------------------------------------- //

        /** Set the global rank for a provider. Returns true on success. */
        fun prioritySetProvider(provider: String, rank: Int): Boolean = try {
            CoreRuntimeNative.INSTANCE?.synheart_core_priority_set_provider(provider, rank) == 0
        } catch (e: UnsatisfiedLinkError) {
            false
        }

        /**
         * Set or clear a per-metric rank override for `(metric, provider)`.
         * Pass a null [rank] to clear the override.
         */
        fun prioritySetMetricOverride(metric: String, provider: String, rank: Int?): Boolean = try {
            CoreRuntimeNative.INSTANCE?.synheart_core_priority_set_metric_override(
                metric,
                provider,
                if (rank == null) 0 else 1,
                rank ?: 0,
            ) == 0
        } catch (e: UnsatisfiedLinkError) {
            false
        }

        /**
         * Effective rank for `(metric, provider)`. Returns [Int.MAX_VALUE] (the
         * runtime's `UNRANKED` sentinel) for unknown providers, or -1 when
         * `metric` is unparseable or the symbol is absent.
         */
        fun priorityEffectiveRank(metric: String, provider: String): Int = try {
            CoreRuntimeNative.INSTANCE?.synheart_core_priority_effective_rank(metric, provider) ?: -1
        } catch (e: UnsatisfiedLinkError) {
            -1
        }

        /**
         * Resolve the winning source for `metric` given a `{provider: count}`
         * JSON map. Returns the resolution JSON, or null on bad input.
         */
        fun priorityResolve(metric: String, samplesJson: String): JSONObject? = try {
            staticReadAndFree(
                CoreRuntimeNative.INSTANCE?.synheart_core_priority_resolve(metric, samplesJson),
            )?.let { runCatching { JSONObject(it) }.getOrNull() }
        } catch (e: UnsatisfiedLinkError) {
            null
        }

        // -------------------------------------------------------------- //
        // HRV-CV resilience (stateless, handle-free)                      //
        // -------------------------------------------------------------- //

        /**
         * Compute a resilience score from samples + sleep windows + config.
         * Returns the result JSON, or null on bad input / absent symbol.
         */
        fun resilienceComputeV1(
            samplesJson: String,
            windowsJson: String,
            configJson: String,
        ): String? = try {
            staticReadAndFree(
                CoreRuntimeNative.INSTANCE?.synheart_core_resilience_compute_v1(
                    samplesJson,
                    windowsJson,
                    configJson,
                ),
            )
        } catch (e: UnsatisfiedLinkError) {
            null
        }

        // -------------------------------------------------------------- //
        // Historical backfill (handle-free SQLite ingest)                 //
        // -------------------------------------------------------------- //

        /** Open (or create) a backfill import under `importId`. Returns true on success. */
        fun backfillOpen(dbPath: String, importId: String): Boolean = try {
            CoreRuntimeNative.INSTANCE?.synheart_core_backfill_open(dbPath, importId) == 0
        } catch (e: UnsatisfiedLinkError) {
            false
        }

        /** Insert a batch of historical samples (JSON array). Returns the report JSON. */
        fun backfillInsertBatch(importId: String, samplesJson: String): String? = try {
            staticReadAndFree(
                CoreRuntimeNative.INSTANCE?.synheart_core_backfill_insert_batch(
                    importId,
                    samplesJson,
                ),
            )
        } catch (e: UnsatisfiedLinkError) {
            null
        }

        /** Finalize an import and return its summary JSON. */
        fun backfillFinalize(importId: String): String? = try {
            staticReadAndFree(CoreRuntimeNative.INSTANCE?.synheart_core_backfill_finalize(importId))
        } catch (e: UnsatisfiedLinkError) {
            null
        }
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
        return try {
            ptr.getString(0, "UTF-8")
        } finally {
            lib.synheart_core_free_string(ptr)
        }
    }

    private fun requireHandle(): Pointer =
        handle ?: throw IllegalStateException("CoreRuntimeBridge has been closed")

    /**
     * Run [body], degrading to [fallback] when the loaded runtime does not
     * export the symbol. Records [symbol] in [missingSymbols] so a host can
     * distinguish an old runtime from a disabled feature.
     */
    private inline fun <T> soft(symbol: String, fallback: T, body: () -> T): T = try {
        body()
    } catch (e: UnsatisfiedLinkError) {
        _missingSymbols.add(symbol)
        fallback
    }

    /** Parse a runtime JSON-object return, tolerating malformed payloads. */
    private fun asObject(json: String?): JSONObject? =
        json?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Parse a runtime JSON-array return, tolerating malformed payloads. */
    private fun asArray(json: String?): JSONArray? =
        json?.let { runCatching { JSONArray(it) }.getOrNull() }

    /** Read a sync-envelope call, unwrapping `{"ok":…}` into its payload. */
    private fun syncEnvelope(json: String?): JSONObject? = unwrapSyncEnvelope(asObject(json))

    // ------------------------------------------------------------------ //
    // Lifecycle                                                          //
    // ------------------------------------------------------------------ //

    /** True while the handle is live. */
    val isOpen: Boolean get() = handle != null

    /**
     * Release the native runtime handle. After this call the bridge instance
     * must not be used again.
     *
     * Registered callbacks are handed back to the runtime first, but the Kotlin
     * peers are only released here — see [clearHsiCallback] for why.
     */
    fun close() {
        val h = handle ?: return
        runCatching { clearStreamCallback() }
        runCatching { clearHsiCallback() }
        lib.synheart_core_free(h)
        handle = null
        // Safe now: `synheart_core_free` drops the embedded tokio runtime and
        // its workers, so nothing can reach a trampoline any more.
        retiredCallbacks.clear()
        hsiCallback = null
        streamCallback = null
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
        handle != null && lib.synheart_core_is_running(requireHandle()) == 1

    /**
     * Close a session left open by a crash or force-quit without touching the
     * live session. Returns true on success.
     */
    fun closeOrphanSession(sessionId: String): Boolean = soft("close_orphan_session", false) {
        lib.synheart_core_close_orphan_session(requireHandle(), sessionId) == 0
    }

    /** Spin the fusion pipeline up before the first sample arrives. */
    fun ensurePipeline() = soft("ensure_pipeline", Unit) {
        lib.synheart_core_ensure_pipeline(requireHandle())
    }

    /** Advance the pipeline clock. Returns an HSI window JSON when one closed. */
    fun tick(nowMs: Long): String? = soft("tick", null) {
        readAndFreeString(lib.synheart_core_tick(requireHandle(), nowMs))
    }

    /** Number of frames the pipeline produced this session. */
    fun frameCount(): Long = soft("frame_count", 0L) {
        lib.synheart_core_frame_count(requireHandle())
    }

    /** Last computed feature vector as JSON, or null. */
    fun lastFeatures(): String? = soft("last_features", null) {
        readAndFreeString(lib.synheart_core_last_features(requireHandle()))
    }

    // ------------------------------------------------------------------ //
    // Sensor push                                                        //
    // ------------------------------------------------------------------ //

    /**
     * Push an R-R interval sample (milliseconds). [provider] tags the source
     * for the multi-source priority resolver.
     */
    fun pushRr(tsMs: Long, rrMs: Double, provider: String = "default_sensor") {
        lib.synheart_core_push_rr(requireHandle(), tsMs, rrMs, provider)
    }

    /**
     * Push a burst of R-R intervals that arrived in one sensor notification.
     *
     * [order] is 0 for oldest-first (the BLE Heart Rate Measurement
     * convention) and 1 for newest-first. Pushing the burst in one call keeps
     * the runtime's inter-beat timing intact, which per-sample pushes lose.
     */
    fun pushRrBatch(
        anchorTsMs: Long,
        rrMs: DoubleArray,
        order: Int = 0,
        provider: String = "default_sensor",
    ) = soft("push_rr_batch", Unit) {
        if (rrMs.isEmpty()) return@soft
        lib.synheart_core_push_rr_batch(
            requireHandle(),
            anchorTsMs,
            rrMs,
            NativeLong(rrMs.size.toLong()),
            order,
            provider,
        )
    }

    /** Push a heart-rate sample (BPM). */
    fun pushHr(tsMs: Long, bpm: Double) {
        lib.synheart_core_push_hr(requireHandle(), tsMs, bpm)
    }

    /** Push a 3-axis accelerometer sample. */
    fun pushAccel(tsMs: Long, x: Double, y: Double, z: Double) {
        lib.synheart_core_push_accel(requireHandle(), tsMs, x, y, z)
    }

    /**
     * Push a behavioral event. [eventType] is the numeric behavior code — see
     * `BehaviorCode` for the mapping from names.
     */
    fun pushBehavior(tsMs: Long, eventType: Int, value: Double) {
        lib.synheart_core_push_behavior(requireHandle(), tsMs, eventType, value)
    }

    /** Push a fully-formed behavior event as JSON. Returns true on success. */
    fun pushBehaviorEvent(eventJson: String): Boolean = soft("push_behavior_event", false) {
        lib.synheart_core_push_behavior_event(requireHandle(), eventJson) == 0
    }

    /** Push sleep-stage data as a JSON array. */
    fun pushSleepStages(json: String) {
        lib.synheart_core_push_sleep_stages(requireHandle(), json)
    }

    /** Push vendor-derived HRV metrics (already computed by the wearable). */
    fun pushVendorHrv(
        tsMs: Long,
        rmssdMs: Double,
        sdnnMs: Double,
        stress: Double,
        recovery: Double,
    ) = soft("push_vendor_hrv", Unit) {
        lib.synheart_core_push_vendor_hrv(requireHandle(), tsMs, rmssdMs, sdnnMs, stress, recovery)
    }

    /** Push vendor-derived vitals (SpO2 %, respiration rate). */
    fun pushVendorVitals(tsMs: Long, spo2: Double, respiration: Double) =
        soft("push_vendor_vitals", Unit) {
            lib.synheart_core_push_vendor_vitals(requireHandle(), tsMs, spo2, respiration)
        }

    /** Ingest a pre-built batch (JSON). Returns result JSON or null on failure. */
    fun ingestBatch(batchJson: String, nowMs: Long): String? =
        readAndFreeString(lib.synheart_core_ingest_batch(requireHandle(), batchJson, nowMs))

    // ------------------------------------------------------------------ //
    // Vendor event store                                                  //
    // ------------------------------------------------------------------ //

    /** Persist a canonical vendor event (JSON). Returns true on success. */
    fun ingestVendorEvent(eventJson: String): Boolean = soft("ingest_vendor_event", false) {
        lib.synheart_core_ingest_vendor_event(requireHandle(), eventJson) == 0
    }

    /** Query stored vendor events. Returns the result array, or null. */
    fun queryVendorEvents(queryJson: String): JSONArray? = soft("query_vendor_events", null) {
        asArray(readAndFreeString(lib.synheart_core_query_vendor_events(requireHandle(), queryJson)))
    }

    /** Most recent stored event for `(provider, eventType)`, or null. */
    fun getLatestVendorEvent(provider: String, eventType: String): JSONObject? =
        soft("get_latest_vendor_event", null) {
            asObject(
                readAndFreeString(
                    lib.synheart_core_get_latest_vendor_event(requireHandle(), provider, eventType),
                ),
            )
        }

    /** Drop every stored event for a provider (unlink). Returns rows deleted. */
    fun deleteVendorEventsForProvider(provider: String): Long =
        soft("delete_vendor_events_for_provider", 0L) {
            lib.synheart_core_delete_vendor_events_for_provider(requireHandle(), provider)
        }

    // ------------------------------------------------------------------ //
    // Personalization — task / focus / workout                            //
    // ------------------------------------------------------------------ //

    /** Set the active task type by its numeric discriminant. */
    fun setTaskType(taskKind: Int) = soft("set_task_type", Unit) {
        lib.synheart_core_set_task_type(requireHandle(), taskKind)
    }

    /** Read the active task-type discriminant, or -1 when unavailable. */
    fun currentTaskType(): Int = soft("current_task_type", -1) {
        lib.synheart_core_current_task_type(requireHandle())
    }

    /** Set the active focus kind by its numeric discriminant. */
    fun setFocusKind(focusKind: Int) = soft("set_focus_kind", Unit) {
        lib.synheart_core_set_focus_kind(requireHandle(), focusKind)
    }

    /** Read the active focus-kind discriminant, or -1 when unavailable. */
    fun currentFocusKind(): Int = soft("current_focus_kind", -1) {
        lib.synheart_core_current_focus_kind(requireHandle())
    }

    /** Push a completed workout window with optional vendor strain/recovery. */
    fun pushWorkoutEvent(
        startMs: Long,
        endMs: Long,
        workoutKind: Int,
        vendorStrain: Double = Double.NaN,
        vendorRecovery: Double = Double.NaN,
    ) = soft("push_workout_event", Unit) {
        lib.synheart_core_push_workout_event(
            requireHandle(),
            startMs,
            endMs,
            workoutKind,
            vendorStrain,
            vendorRecovery,
        )
    }

    /** Read the active workout-kind discriminant, or -1 when unavailable. */
    fun currentWorkoutKind(): Int = soft("current_workout_kind", -1) {
        lib.synheart_core_current_workout_kind(requireHandle())
    }

    /** Full personalization context as JSON, or null. */
    fun personalizationContextJson(): String? = soft("personalization_context_json", null) {
        readAndFreeString(lib.synheart_core_personalization_context_json(requireHandle()))
    }

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
    // Device auth (crypto + storage callbacks + registration)            //
    // ------------------------------------------------------------------ //

    /** Hand the runtime its Keystore-backed crypto callbacks. 0 on success, -2 if absent. */
    fun setSdkCryptoCallbacks(): Int = soft("sdk_set_crypto_callbacks", -2) {
        lib.synheart_core_sdk_set_crypto_callbacks(
            requireHandle(),
            DeviceAuthCallbacks.cryptoStruct.pointer,
        )
    }

    /** Hand the runtime its EncryptedSharedPreferences-backed storage callbacks. 0 ok, -2 absent. */
    fun setStorageCallbacks(): Int = soft("set_storage_callbacks", -2) {
        lib.synheart_core_set_storage_callbacks(
            requireHandle(),
            DeviceAuthCallbacks.store,
            DeviceAuthCallbacks.load,
            DeviceAuthCallbacks.delete,
        )
    }

    /** Register this device for [clientId] (the subject id). Returns result JSON, or null. */
    fun registerDevice(clientId: String): String? =
        readAndFreeString(lib.synheart_core_sdk_register_device(requireHandle(), clientId))

    /** Device-auth status JSON, or null. */
    fun deviceAuthStatus(): String? =
        readAndFreeString(lib.synheart_core_sdk_device_auth_status(requireHandle()))

    /**
     * Build a device-signed proof header for an outbound request. Returns the
     * header value, or null when no device key is registered.
     */
    fun buildProofHeader(method: String, absoluteUrl: String): String? =
        soft("sdk_build_proof_header", null) {
            readAndFreeString(
                lib.synheart_core_sdk_build_proof_header(requireHandle(), method, absoluteUrl),
            )
        }

    // ------------------------------------------------------------------ //
    // Subject identity                                                    //
    // ------------------------------------------------------------------ //

    /**
     * The runtime's canonical subject id (RFC-0008), or null (including on a
     * runtime build that predates the symbol — degrades softly).
     */
    fun runtimeSubjectId(): String? = soft("get_subject_id", null) {
        readAndFreeString(lib.synheart_core_get_subject_id(requireHandle()))
    }

    /**
     * Atomically rebind the runtime subject id, re-pointing consent + the cloud
     * connector without a full reinit. Returns 1 (re-mint required), 0 (valid
     * token loaded), or -1 (error / symbol absent).
     */
    fun rebindSubjectId(subjectId: String, invalidateToken: Boolean = true): Int =
        soft("rebind_subject_id", -1) {
            lib.synheart_core_rebind_subject_id(
                requireHandle(),
                subjectId,
                if (invalidateToken) 1 else 0,
            )
        }

    // ------------------------------------------------------------------ //
    // Research study                                                      //
    // ------------------------------------------------------------------ //

    /** Enrol the device in a research study. Returns the service response JSON. */
    fun enrolResearchStudy(accessCode: String, studyCode: String): String? =
        readAndFreeString(lib.synheart_core_enrol_study(requireHandle(), accessCode, studyCode))

    /** Preview an access + study code pair without redeeming the code. */
    fun validateResearchStudyCodes(accessCode: String, studyCode: String): String? =
        readAndFreeString(
            lib.synheart_core_validate_study_codes(requireHandle(), accessCode, studyCode),
        )

    /** Withdraw from the device's active research study for this app. Idempotent. */
    fun withdrawResearchStudy(): String? =
        readAndFreeString(lib.synheart_core_withdraw_study(requireHandle()))

    /** Current study enrolment status as JSON, or null. */
    fun researchStudyStatus(): String? = soft("research_study_status", null) {
        readAndFreeString(lib.synheart_core_research_study_status(requireHandle()))
    }

    /** Record a signed study-consent affirmation payload. Returns the result JSON. */
    fun recordStudyConsent(payloadJson: String): String? = soft("record_study_consent", null) {
        readAndFreeString(lib.synheart_core_record_study_consent(requireHandle(), payloadJson))
    }

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
    // Customer data deletion (GDPR Art. 17)                               //
    // ------------------------------------------------------------------ //

    /** File an erasure request. [dryRun] previews the inventory instead. */
    fun requestDataDeletion(reason: String?, contact: String?, dryRun: Boolean): String? =
        soft("request_data_deletion", null) {
            readAndFreeString(
                lib.synheart_core_request_data_deletion(
                    requireHandle(),
                    reason,
                    contact,
                    if (dryRun) 1.toByte() else 0.toByte(),
                ),
            )
        }

    /** Status of one erasure request, or null. */
    fun getDataDeletion(requestId: String): String? = soft("get_data_deletion", null) {
        readAndFreeString(lib.synheart_core_get_data_deletion(requireHandle(), requestId))
    }

    /** Page through this subject's erasure requests. */
    fun listDataDeletions(limit: Int = 20, offset: Int = 0): String? =
        soft("list_data_deletions", null) {
            readAndFreeString(lib.synheart_core_list_data_deletions(requireHandle(), limit, offset))
        }

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
            lib.synheart_core_get_hsi_windows(requireHandle(), sessionId, startMs, endMs, limit),
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

    /**
     * Trigger an immediate sync cycle.
     *
     * @throws SyncNativeException when the runtime reports a failure envelope.
     */
    fun syncNow(): JSONObject? =
        syncEnvelope(readAndFreeString(lib.synheart_core_sync_now(requireHandle())))

    /** Create a new sync space owned by this device. */
    fun syncCreateSpace(deviceName: String? = null): JSONObject? = soft("sync_create_space", null) {
        syncEnvelope(
            readAndFreeString(lib.synheart_core_sync_create_space(requireHandle(), deviceName)),
        )
    }

    /** Mint a short-lived pairing token for another device. */
    fun syncGeneratePairing(): JSONObject? = soft("sync_generate_pairing", null) {
        syncEnvelope(readAndFreeString(lib.synheart_core_sync_generate_pairing(requireHandle())))
    }

    /** Join an existing space with a pairing token. */
    fun syncJoinSpace(pairingToken: String, deviceName: String? = null): JSONObject? =
        soft("sync_join_space", null) {
            syncEnvelope(
                readAndFreeString(
                    lib.synheart_core_sync_join_space(requireHandle(), pairingToken, deviceName),
                ),
            )
        }

    /** Sync engine status snapshot. */
    fun syncStatus(): JSONObject? = soft("sync_status", null) {
        syncEnvelope(readAndFreeString(lib.synheart_core_sync_status(requireHandle())))
    }

    /** Sync readiness snapshot — what still blocks a first sync. */
    fun syncReadiness(): JSONObject? = soft("sync_readiness", null) {
        syncEnvelope(readAndFreeString(lib.synheart_core_sync_readiness(requireHandle())))
    }

    /** Recover space access from a recovery key. */
    fun syncRecoverSpace(recoveryKey: String, spaceId: String): JSONObject? =
        soft("sync_recover_space", null) {
            syncEnvelope(
                readAndFreeString(
                    lib.synheart_core_sync_recover_space(requireHandle(), recoveryKey, spaceId),
                ),
            )
        }

    /** Leave the current space (the space stays alive for other devices). */
    fun syncLeaveSpace(): JSONObject? = soft("sync_leave_space", null) {
        syncEnvelope(readAndFreeString(lib.synheart_core_sync_leave_space(requireHandle())))
    }

    /** List devices in the current space. */
    fun syncListDevices(): JSONObject? = soft("sync_list_devices", null) {
        syncEnvelope(readAndFreeString(lib.synheart_core_sync_list_devices(requireHandle())))
    }

    /** Revoke another device's membership. */
    fun syncRevokeDevice(deviceId: String): JSONObject? = soft("sync_revoke_device", null) {
        syncEnvelope(
            readAndFreeString(lib.synheart_core_sync_revoke_device(requireHandle(), deviceId)),
        )
    }

    /** Delete the space for everyone (owner only). */
    fun syncDeleteSpace(): JSONObject? = soft("sync_delete_space", null) {
        syncEnvelope(readAndFreeString(lib.synheart_core_sync_delete_space(requireHandle())))
    }

    /** Forget local space state without touching the server. */
    fun syncClearLocalSpace(): JSONObject? = soft("sync_clear_local_space", null) {
        syncEnvelope(readAndFreeString(lib.synheart_core_sync_clear_local_space(requireHandle())))
    }

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

    /**
     * Feed one day of a wearable-derived dimension into the SRM.
     *
     * [dayIndex] is days since the Unix epoch; [fidelity] is the numeric
     * `Fidelity` discriminant. Call [srmTriggerWearableRecompute] once after a
     * bulk push rather than after every day.
     */
    fun srmPushWearableDaily(
        dimension: String,
        dayIndex: Int,
        value: Double,
        confidence: Double,
        fidelity: Int,
    ) = soft("srm_push_wearable_daily", Unit) {
        lib.synheart_core_srm_push_wearable_daily(
            requireHandle(),
            dimension,
            dayIndex,
            value,
            confidence,
            fidelity,
        )
    }

    /** Recompute wearable-backed baselines after a bulk push. */
    fun srmTriggerWearableRecompute(triggerType: Int, asOfDay: Int) =
        soft("srm_trigger_wearable_recompute", Unit) {
            lib.synheart_core_srm_trigger_wearable_recompute(requireHandle(), triggerType, asOfDay)
        }

    /** Export the longitudinal (multi-day) snapshot as JSON. */
    fun exportLongitudinalSnapshot(): String? = soft("export_longitudinal_snapshot", null) {
        readAndFreeString(lib.synheart_core_export_longitudinal_snapshot(requireHandle()))
    }

    /** Load a longitudinal snapshot. Returns true on success. */
    fun loadLongitudinalSnapshot(json: String): Boolean =
        soft("load_longitudinal_snapshot", false) {
            lib.synheart_core_load_longitudinal_snapshot(requireHandle(), json) == 0
        }

    /** Hydrate baselines from local storage. Returns the report JSON. */
    fun baselineHydrateLocal(): String? = soft("baseline_hydrate_local", null) {
        readAndFreeString(lib.synheart_core_baseline_hydrate_local(requireHandle()))
    }

    /** Export an encrypted offline baseline blob (base64). */
    fun baselineExportOffline(passphrase: String): String? = soft("baseline_export_offline", null) {
        readAndFreeString(lib.synheart_core_baseline_export_offline(requireHandle(), passphrase))
    }

    /** Import an encrypted offline baseline blob. Returns the report JSON. */
    fun baselineImportOffline(passphrase: String, blobB64: String): String? =
        soft("baseline_import_offline", null) {
            readAndFreeString(
                lib.synheart_core_baseline_import_offline(requireHandle(), passphrase, blobB64),
            )
        }

    /** Wearable reference view (per-dimension baseline reference) as JSON. */
    fun wearableReferenceJson(): String? = soft("wearable_reference_json", null) {
        readAndFreeString(lib.synheart_core_wearable_reference_json(requireHandle()))
    }

    // ------------------------------------------------------------------ //
    // Scores — sleep / recovery / readiness                               //
    // ------------------------------------------------------------------ //

    /** Compute a sleep score from a typed input JSON. */
    fun sleepScoreComputeJson(inputJson: String): String? = soft("sleep_score_compute_json", null) {
        readAndFreeString(lib.synheart_core_sleep_score_compute_json(requireHandle(), inputJson))
    }

    /** Compute a sleep score, tagged with a correlation id for tracing. */
    fun sleepScoreComputeJsonTraced(inputJson: String, correlationId: String): String? =
        soft("sleep_score_compute_json_traced", null) {
            readAndFreeString(
                lib.synheart_core_sleep_score_compute_json_traced(
                    requireHandle(),
                    inputJson,
                    correlationId,
                ),
            )
        }

    /** Last computed sleep score as JSON, or null. */
    fun lastSleepScoreJson(): String? = soft("last_sleep_score_json", null) {
        readAndFreeString(lib.synheart_core_last_sleep_score_json(requireHandle()))
    }

    /** Attach a sleep-score result to today's longitudinal record. Returns the raw code. */
    fun attachSleepScoreJson(resultJson: String): Int = soft("attach_sleep_score_json", -2) {
        lib.synheart_core_attach_sleep_score_json(requireHandle(), resultJson)
    }

    /** Compute a recovery score from a typed input JSON. */
    fun recoveryScoreComputeJson(inputJson: String): String? =
        soft("recovery_score_compute_json", null) {
            readAndFreeString(
                lib.synheart_core_recovery_score_compute_json(requireHandle(), inputJson),
            )
        }

    /** Compute a recovery score, tagged with a correlation id for tracing. */
    fun recoveryScoreComputeJsonTraced(inputJson: String, correlationId: String): String? =
        soft("recovery_score_compute_json_traced", null) {
            readAndFreeString(
                lib.synheart_core_recovery_score_compute_json_traced(
                    requireHandle(),
                    inputJson,
                    correlationId,
                ),
            )
        }

    /**
     * Pin today's recovery score (0..100) so downstream readiness picks it up.
     * Returns the raw code (0 on success).
     */
    fun attachRecoveryScoreToday(score: Int): Int = soft("attach_recovery_score_today", -2) {
        lib.synheart_core_attach_recovery_score_today(
            requireHandle(),
            score.coerceIn(0, 100).toByte(),
        )
    }

    /** Clear today's pinned recovery score. Returns the raw code (0 on success). */
    fun clearRecoveryScoreToday(): Int = soft("clear_recovery_score_today", -2) {
        lib.synheart_core_clear_recovery_score_today(requireHandle())
    }

    /** Compute a readiness score from a typed input JSON. */
    fun readinessScoreComputeJson(inputJson: String): String? =
        soft("readiness_score_compute_json", null) {
            readAndFreeString(
                lib.synheart_core_readiness_score_compute_json(requireHandle(), inputJson),
            )
        }

    /** Compute a readiness score, tagged with a correlation id for tracing. */
    fun readinessScoreComputeJsonTraced(inputJson: String, correlationId: String): String? =
        soft("readiness_score_compute_json_traced", null) {
            readAndFreeString(
                lib.synheart_core_readiness_score_compute_json_traced(
                    requireHandle(),
                    inputJson,
                    correlationId,
                ),
            )
        }

    // ------------------------------------------------------------------ //
    // Cloud upload queue + HSI history                                    //
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

    /** Wall-clock ms of the last successful ingest, or null when there has been none. */
    fun lastIngestSuccessAtMs(): Long? = soft("last_ingest_success_at_ms", null) {
        lib.synheart_core_last_ingest_success_at_ms(requireHandle()).takeIf { it > 0 }
    }

    /** Upload platform metadata. Returns result JSON or null. */
    fun uploadMetadata(): String? =
        readAndFreeString(lib.synheart_core_upload_metadata(requireHandle()))

    /**
     * Locally retained HSI windows, newest first. [sinceUnixMs] of 0 means no
     * lower bound; [limit] of 0 means no cap.
     */
    fun hsiHistoryList(sinceUnixMs: Long = 0, limit: Long = 0): JSONArray? =
        soft("hsi_history_list", null) {
            asArray(
                readAndFreeString(
                    lib.synheart_core_hsi_history_list(requireHandle(), sinceUnixMs, limit),
                ),
            )
        }

    /** Number of locally retained HSI windows. */
    fun hsiHistoryCount(): Long = soft("hsi_history_count", 0L) {
        lib.synheart_core_hsi_history_count(requireHandle())
    }

    /** Drop the local HSI history. Returns true on success. */
    fun hsiHistoryClear(): Boolean = soft("hsi_history_clear", false) {
        lib.synheart_core_hsi_history_clear(requireHandle()) == 0
    }

    /** Read HSI windows back from the cloud for a time range. */
    fun fetchCloudHsi(fromUnixMs: Long, toUnixMs: Long): JSONArray? = soft("fetch_cloud_hsi", null) {
        asArray(
            readAndFreeString(
                lib.synheart_core_fetch_cloud_hsi(requireHandle(), fromUnixMs, toUnixMs),
            ),
        )
    }

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

    /**
     * Runtime diagnostics as a parsed object, annotated with the symbols this
     * loaded library turned out to be missing.
     */
    fun diagnosticsMap(): JSONObject {
        val obj = asObject(diagnostics()) ?: JSONObject()
        // Always present, whatever shape the runtime's own diagnostics take —
        // a host needs a stable key to check that the library actually loaded.
        obj.put("isAvailable", isOpen && CoreRuntimeNative.INSTANCE != null)
        obj.put("missingSymbols", JSONArray(_missingSymbols.toList()))
        return obj
    }

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
    // HSI / stream callbacks                                              //
    // ------------------------------------------------------------------ //

    private var hsiCallback: HsiCallbackNative? = null
    private var streamCallback: StreamEventCallbackNative? = null

    /**
     * Callbacks unregistered by [clearHsiCallback] / [clearStreamCallback] but
     * deliberately kept reachable until [close].
     *
     * Releasing the Kotlin peer at clear time is a use-after-free. The runtime's
     * `synheart_core_clear_hsi_callback` only calls `JoinHandle::abort()` on the
     * tokio listener task; `abort()` requests cancellation and returns without
     * joining, so a worker can still be inside the dispatch path when the FFI
     * call returns. If JNA has collected the peer by then, the native side calls
     * a freed trampoline and the process takes SIGSEGV/SIGABRT on a
     * `tokio-rt-worker` thread.
     *
     * So we hand the registration back to the runtime immediately but keep the
     * peer alive, and only drop it in [close] once `synheart_core_free` has
     * dropped the handle — and with it the embedded tokio runtime and its
     * workers. After that nothing can reach the pointer.
     *
     * The cost is a handful of live callbacks per handle (one per
     * re-registration, which is rare), all released on close. That is a trivial
     * amount of memory next to a hard crash.
     */
    private val retiredCallbacks = mutableListOf<com.sun.jna.Callback>()

    /**
     * Register a callback for real-time HSI state updates.
     *
     * The callback fires on a native background thread. Post to
     * `Dispatchers.Main` before touching UI state.
     */
    fun setHsiCallback(onHsi: (String) -> Unit) {
        clearHsiCallback()
        val cb = object : HsiCallbackNative {
            override fun invoke(hsiJson: Pointer?, userData: Pointer?) {
                val json = hsiJson?.getString(0, "UTF-8") ?: return
                runCatching { onHsi(json) }
            }
        }
        hsiCallback = cb
        lib.synheart_core_set_hsi_callback(requireHandle(), cb, null)
    }

    /**
     * Unregister the HSI callback. The Kotlin peer stays reachable until
     * [close] — see [retiredCallbacks].
     */
    fun clearHsiCallback() {
        val cb = hsiCallback ?: return
        hsiCallback = null
        retiredCallbacks.add(cb)
        handle?.let { lib.synheart_core_clear_hsi_callback(it) }
    }

    /**
     * Register a callback for streaming pipeline events. Fires on a native
     * background thread, same as [setHsiCallback].
     */
    fun setStreamCallback(onEvent: (String) -> Unit) = soft("set_stream_callback", Unit) {
        clearStreamCallback()
        val cb = object : StreamEventCallbackNative {
            override fun invoke(eventJson: Pointer?, userData: Pointer?) {
                val json = eventJson?.getString(0, "UTF-8") ?: return
                runCatching { onEvent(json) }
            }
        }
        streamCallback = cb
        lib.synheart_core_set_stream_callback(requireHandle(), cb, null)
    }

    /** Unregister the stream callback, retaining the peer until [close]. */
    fun clearStreamCallback() = soft("set_stream_callback", Unit) {
        val cb = streamCallback ?: return@soft
        streamCallback = null
        retiredCallbacks.add(cb)
        handle?.let { lib.synheart_core_set_stream_callback(it, null, null) }
    }

    /** Start the streaming pipeline with a JSON config. Returns the raw code. */
    fun startStream(configJson: String): Int = soft("stream_start", -2) {
        lib.synheart_core_stream_start(requireHandle(), configJson)
    }

    /** Stop the streaming pipeline. Returns the raw code. */
    fun stopStream(): Int = soft("stream_stop", -2) {
        lib.synheart_core_stream_stop(requireHandle())
    }

    /** Current streaming pipeline state as JSON. */
    fun streamState(): String? = soft("stream_state", null) {
        readAndFreeString(lib.synheart_core_stream_state(requireHandle()))
    }

    // ------------------------------------------------------------------ //
    // Lab Protocol                                                       //
    // ------------------------------------------------------------------ //

    /** Returns true if the lab module is available in the native runtime. */
    fun isLabAvailable(): Boolean = soft("is_lab_available", false) {
        lib.synheart_core_is_lab_available(requireHandle()) == 1
    }

    /** Start a lab protocol. Returns true on success. */
    fun labStart(protocolJson: String, startedAtMs: Long): Boolean =
        lib.synheart_core_lab_start(requireHandle(), protocolJson, startedAtMs) == 0

    /** Open a new lab window. Returns the window ID string, or null on failure. */
    fun labOpenWindow(parentId: String?, windowType: String, label: String?, startedAtMs: Long): String? =
        readAndFreeString(
            lib.synheart_core_lab_open_window(
                requireHandle(),
                parentId,
                windowType,
                label,
                startedAtMs,
            ),
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

    /** Export the in-progress lab protocol as JSON. */
    fun labExportJson(): String? = soft("lab_export_json", null) {
        readAndFreeString(lib.synheart_core_lab_export_json(requireHandle()))
    }

    /** Ensure a lab metadata record exists for this device/user. Returns its JSON. */
    fun labEnsureMetadata(
        deviceId: String,
        platform: String,
        osVersion: String,
        userInfoJson: String?,
        deviceExtraJson: String?,
    ): String? = soft("lab_ensure_metadata", null) {
        readAndFreeString(
            lib.synheart_core_lab_ensure_metadata(
                requireHandle(),
                deviceId,
                platform,
                osVersion,
                userInfoJson,
                deviceExtraJson,
            ),
        )
    }

    /** Id of the current lab metadata record, or null. */
    fun labCurrentMetadataId(): String? = soft("lab_current_metadata_id", null) {
        readAndFreeString(lib.synheart_core_lab_current_metadata_id(requireHandle()))
    }

    /** Mark the metadata record dirty so it re-uploads. Returns true on success. */
    fun labMarkMetadataDirty(reason: String): Boolean = soft("lab_mark_metadata_dirty", false) {
        lib.synheart_core_lab_mark_metadata_dirty(requireHandle(), reason) == 0
    }

    /** Re-enqueue a finalized lab session for upload. Returns the raw code. */
    fun reenqueueLabSession(sessionJson: String): Int = soft("reenqueue_lab_session", -2) {
        lib.synheart_core_reenqueue_lab_session(requireHandle(), sessionJson)
    }

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
