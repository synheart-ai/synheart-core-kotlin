package ai.synheart.core.example.sdk

import android.content.Context
import android.content.SharedPreferences

/**
 * Persistence for the three runtime snapshots a mobile host owns (mobile host
 * guide §7), plus the two scalars that go with them.
 *
 * ## Three, not one
 *
 * The runtime's own SQLite covers session *state* only. Everything the engine
 * accumulates about the person lives in snapshots the host has to export and
 * re-load, and each one loses something different if you skip it:
 *
 * | Snapshot | Carries | Cost of skipping |
 * |---|---|---|
 * | session state | Capacity, Mental Fatigue, Stress, Valence, context engine | every relaunch starts the stateful heads cold |
 * | SRM | the personal baseline | baselines report `Warming` forever across launches |
 * | longitudinal | wearable reference, 7-night sleep ring, today's partial daily accumulator | a mid-day relaunch discards the morning's cardiovascular load |
 *
 * ## One SRM file per device class
 *
 * The SRM key includes the declared `device_class`, because a cross-class load
 * is rejected with `ERR_SRM_CONFIG_MISMATCH` — the baseline partition working
 * as designed, not a bug. Storing one file for all classes means a phone and a
 * tablet take turns invalidating each other's baseline and neither ever
 * matures.
 *
 * ## `SharedPreferences` is the wrong backing store for a real app
 *
 * It is used here because the example already depends on it and the point is
 * the call sequence, not the storage. These payloads are JSON blobs that grow
 * with history — a production host writes them to files under
 * `context.filesDir`, where a partial write can be made atomic with a
 * write-and-rename and a corrupt blob can be replaced without taking the rest
 * of the preferences file with it.
 */
class HostSnapshotStore(context: Context, private val subjectId: String) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // Every key is subject-scoped. The runtime scopes storage, baselines and
    // device identity to the subject, so a snapshot restored under a different
    // one would attribute one person's baseline to another.
    private val sessionStateKey get() = "host.session_state.$subjectId"
    private fun srmKey(deviceClass: String) = "host.srm.$subjectId.$deviceClass"
    private val longitudinalKey get() = "host.longitudinal.$subjectId"
    private val configIdKey get() = "host.config_id.$subjectId"
    private val dayIndexKey get() = "host.day_index.$subjectId"

    fun readSessionState(): String? = prefs.getString(sessionStateKey, null)
    fun writeSessionState(json: String) = prefs.edit().putString(sessionStateKey, json).apply()

    fun readSrm(deviceClass: String): String? = prefs.getString(srmKey(deviceClass), null)
    fun writeSrm(deviceClass: String, json: String) =
        prefs.edit().putString(srmKey(deviceClass), json).apply()

    fun readLongitudinal(): String? = prefs.getString(longitudinalKey, null)
    fun writeLongitudinal(json: String) = prefs.edit().putString(longitudinalKey, json).apply()

    /**
     * The `config_id` the stored snapshots were produced under (§9.5).
     *
     * Persisted beside them because a score computed under a different
     * `config_id` is not comparable to a new one, and `config_id` changes
     * whenever anything value-affecting changes — including the `sensing` and
     * `mask_profile` declarations.
     */
    fun readConfigId(): String? = prefs.getString(configIdKey, null)
    fun writeConfigId(id: String) = prefs.edit().putString(configIdKey, id).apply()

    /**
     * The last `day_index` handed to `roll_day` (§8). Kept because the index
     * must **strictly advance** — a repeat returns `ERR_DAILY_DAY_NOT_ADVANCING`
     * — and after a relaunch the host has no other way to know whether today
     * has already been rolled.
     */
    fun readLastDayIndex(): Int? =
        if (prefs.contains(dayIndexKey)) prefs.getInt(dayIndexKey, 0) else null
    fun writeLastDayIndex(dayIndex: Int) = prefs.edit().putInt(dayIndexKey, dayIndex).apply()

    /**
     * Drop everything for this subject. [deviceClasses] must name every class
     * whose SRM file might exist — they are separate keys by design.
     */
    fun clear(deviceClasses: Iterable<String> = listOf("phone", "tablet", "watch")) {
        val e = prefs.edit()
            .remove(sessionStateKey)
            .remove(longitudinalKey)
            .remove(configIdKey)
            .remove(dayIndexKey)
        for (c in deviceClasses) e.remove(srmKey(c))
        e.apply()
    }

    /**
     * Which snapshots are on disk, for a UI that has to show whether
     * persistence is actually working. All three writes fail quietly, and a
     * host that exports every window and reloads nothing looks identical to
     * one that is persisting correctly right up until the baselines never
     * mature.
     */
    fun storedSizes(deviceClass: String): Map<String, Int> = linkedMapOf(
        "session_state" to (readSessionState()?.length ?: 0),
        "srm ($deviceClass)" to (readSrm(deviceClass)?.length ?: 0),
        "longitudinal" to (readLongitudinal()?.length ?: 0),
    )

    private companion object {
        const val PREFS = "synheart.example.host"
    }
}
