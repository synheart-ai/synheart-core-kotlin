package ai.synheart.core.example.sdk

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence for the one runtime snapshot a mobile host still owns (mobile
 * host guide §7), plus the two scalars that go with it.
 *
 * ## One, not three
 *
 * | Snapshot | Carries | Who persists it |
 * |---|---|---|
 * | session state | Capacity, Mental Fatigue, Stress, Valence, context engine | **the host** — skip it and every relaunch starts the stateful heads cold |
 * | personal baselines | every per-window reference, the daily values, the 7-night sleep ring | **the runtime** (0.42+): it seals one snapshot to its own data directory at session end, after every reference refresh and every 15 minutes while windows close, and restores it at startup |
 *
 * A host that keeps the runtime's data directory, as this one does, needs no
 * baseline snapshot calls at all. `Synheart.exportBaselineSnapshot` /
 * `loadBaselineSnapshot` exist for a host that persists somewhere else.
 *
 * ## `SharedPreferences` is the wrong backing store for a real app
 *
 * It is used here because the example already depends on it and the point is
 * the call sequence, not the storage. A production host writes the session
 * state to a file under `context.filesDir`, where a partial write can be made
 * atomic with a write-and-rename.
 */
class HostSnapshotStore(context: Context, private val subjectId: String) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Every key is subject-scoped. The runtime scopes storage, baselines and
    // device identity to the subject, so a snapshot restored under a different
    // one would attribute one person's state to another.
    private val sessionStateKey get() = "host.session_state.$subjectId"
    private val configIdKey get() = "host.config_id.$subjectId"
    private val strainDateKey get() = "host.closed_day_strain.$subjectId"

    fun readSessionState(): String? = prefs.getString(sessionStateKey, null)
    fun writeSessionState(json: String) = prefs.edit().putString(sessionStateKey, json).apply()

    /**
     * The `config_id` the stored snapshot was produced under (§9.5).
     *
     * Persisted beside it because a score computed under a different
     * `config_id` is not comparable to a new one, and `config_id` changes
     * whenever anything value-affecting changes — including the `sensing` and
     * `mask_profile` declarations.
     */
    fun readConfigId(): String? = prefs.getString(configIdKey, null)
    fun writeConfigId(id: String) = prefs.edit().putString(configIdKey, id).apply()

    /**
     * The local date (`YYYY-MM-DD`) whose closed-day Strain was last attached
     * (§8).
     *
     * The engine closes each local day itself and keeps the closed day until
     * the next one closes, across a restart too, so attaching it at every
     * launch would send the same day's Strain again. This cursor makes it once
     * per date.
     */
    fun readLastStrainDate(): String? = prefs.getString(strainDateKey, null)
    fun writeLastStrainDate(date: String) = prefs.edit().putString(strainDateKey, date).apply()

    /** Drop everything for this subject. */
    fun clear() {
        prefs.edit()
            .remove(sessionStateKey)
            .remove(configIdKey)
            .remove(strainDateKey)
            .apply()
    }

    /**
     * What is on disk, for a UI that has to show whether persistence is
     * actually working: a host that exports every window and reloads nothing
     * on launch looks identical to one that persists correctly until the
     * stateful heads never warm.
     */
    fun storedSizes(): Map<String, Int> = linkedMapOf(
        "session_state" to (readSessionState()?.length ?: 0),
    )

    private companion object {
        const val PREFS = "synheart.example.host"
    }
}
