package ai.synheart.core.modules.syni

import ai.synheart.core.Synheart
import ai.synheart.core.SynheartLogger
import ai.synheart.core.models.HSIAxisValue
import ai.synheart.core.models.HSIState
import ai.synheart.core.storage.SessionRecord
import org.json.JSONObject
import java.time.Instant

/**
 * Builds the HSI-context payload that conditions Syni inference.
 *
 * This is the SDK's half of the Syni HSI contract: it gathers live HSI plus
 * stored session history and projects them into the reduced shape the Syni
 * runtime expects (`current` = a state snapshot, `history` = state history).
 *
 * Layering: the SDK *gathers* state; the runtime's prompt builder *renders* it
 * into conditioning text. Keep the keys here in sync with the runtime structs.
 *
 * **Axis-agnostic.** The builder privileges no axis — it projects whatever HSI
 * axes and session means exist, generically. Which axis matters is the active
 * persona's concern, not the SDK's.
 *
 * Every section degrades gracefully: when a data source is empty the
 * corresponding key is omitted and the runtime renders nothing for it. A fresh
 * app with no sessions and no live HSI yields null — no context at all.
 */
class SyniContextBuilder(
    private val liveState: () -> HSIState? = { Synheart.currentHSIState },
    private val listSessions: () -> List<SessionRecord> = { Synheart.listSessions() },
    private val sessionSummary: (String) -> JSONObject? = { Synheart.getSessionSummary(it) },
    private val hsiWindows: (String) -> List<JSONObject> = { Synheart.getHSIWindows(it) },
) {

    /**
     * Build the HSI context. Returns null when there is genuinely nothing to
     * contribute (no live HSI, no stored sessions).
     *
     * When [message] looks like a trivial utterance — a short greeting or
     * one-word reply that doesn't ask about the user's state — the heavy
     * `current` + `history` blocks are skipped. The persona's authored system
     * prompt is enough conditioning there, and the saved prefill is
     * significant on a CPU on-device runtime.
     */
    fun build(surface: String = "coach", message: String = ""): JSONObject? {
        if (isTrivialMessage(message)) {
            SynheartLogger.log(
                "[syni] context builder: trivial message — skipping HSI + history",
            )
            // Return null so the runtime ships only the persona + safety
            // prefix. Surface alone is not worth a round trip.
            return null
        }

        val ctx = JSONObject().put("surface", surface)

        snapshotFromHsi(liveState())?.let { ctx.put("current", it) }
        buildHistory()?.let { ctx.put("history", it) }

        // `surface` alone isn't worth shipping — only return context when
        // there is real state to condition on.
        if (!ctx.has("current") && !ctx.has("history")) {
            SynheartLogger.log(
                "[syni] context builder: no live HSI, no session history — empty context",
            )
            return null
        }

        val sessions = ctx.optJSONObject("history")?.optJSONArray("recent_sessions")
        val withAxes = (0 until (sessions?.length() ?: 0)).count {
            sessions?.optJSONObject(it)?.has("axis_means") == true
        }
        SynheartLogger.log(
            "[syni] context builder: current=${ctx.has("current")} " +
                "history_sessions=${sessions?.length() ?: 0} with_axes=$withAxes",
        )
        return ctx
    }

    // ------------------------------------------------------------------ //
    // Current snapshot                                                    //
    // ------------------------------------------------------------------ //

    /**
     * Project a live [HSIState] into the runtime's state-snapshot shape.
     * Returns null when no axis carries a reading.
     */
    private fun snapshotFromHsi(state: HSIState?): JSONObject? {
        if (state == null) return null
        val axes = state.hsi
        val snapshot = JSONObject()

        fun put(key: String, axis: HSIAxisValue?) {
            if (axis == null) return
            snapshot.put(
                key,
                JSONObject().put("value", axis.value).put("confidence", axis.confidence),
            )
        }

        put("focus", axes.focus)
        put("capacity", axes.capacity)
        put("arousal", axes.arousal)

        if (snapshot.length() == 0) return null
        snapshot.put(
            "observed_at_utc",
            Instant.ofEpochMilli(state.timestampMs).toString(),
        )
        return snapshot
    }

    // ------------------------------------------------------------------ //
    // Historical digest                                                   //
    // ------------------------------------------------------------------ //

    /**
     * Build the state-history block from stored sessions. Returns null when
     * there are no usable digests.
     *
     * Ships raw per-session digests (time + duration + per-axis means). The
     * runtime and model do any time-of-day or trend reasoning — the SDK does
     * not pre-compute axis-specific peaks or trends.
     */
    private fun buildHistory(): JSONObject? {
        val sessions = runCatching { listSessions() }.getOrNull() ?: return null
        if (sessions.isEmpty()) return null

        val recent = sessions.sortedByDescending { it.startUtc }.take(HISTORY_DEPTH)
        val digests = recent.map { digestSession(it) }
        if (digests.isEmpty()) return null
        return JSONObject().put("recent_sessions", org.json.JSONArray(digests))
    }

    /**
     * Digest one session into `{started_at_utc, duration_min?, axis_means?}`.
     * Falls back to stored HSI windows when a session summary is unavailable,
     * and degrades to a timestamp-only digest on parse failure.
     */
    private fun digestSession(s: SessionRecord): JSONObject {
        val digest = JSONObject()
            .put("started_at_utc", Instant.ofEpochMilli(s.startUtc).toString())

        try {
            val raw = sessionSummary(s.sessionId)
            if (raw != null) {
                val session = raw.optJSONObject("session")
                val durMs = (session?.optLong("end_ms", 0) ?: 0) -
                    (session?.optLong("start_ms", 0) ?: 0)
                if (durMs > 0) {
                    digest.put("duration_min", Math.round(durMs / 60_000.0).toInt())
                }

                // Skip `sleep` (daily, not per-session), absent axes, and any
                // axis whose mean is 0 — a zero would mislead the model into
                // reading "measured as nothing" for "not measured".
                val agg = raw.optJSONObject("aggregates")
                val means = JSONObject()
                agg?.keys()?.forEach { axis ->
                    if (axis == "sleep") return@forEach
                    val mean = agg.optJSONObject(axis)?.optDouble("mean", 0.0) ?: 0.0
                    if (mean != 0.0) means.put(axis, mean)
                }
                if (means.length() > 0) digest.put("axis_means", means)
                SynheartLogger.log(
                    "[syni] session ${s.sessionId}: summary path " +
                        "(axes=${means.length()}, dur=${digest.optInt("duration_min", 0)}min)",
                )
                return digest
            }

            // Fallback when the summary isn't persisted: aggregate axes from
            // the per-window HSI payloads instead.
            val windows = hsiWindows(s.sessionId)
            if (windows.isEmpty()) {
                SynheartLogger.log(
                    "[syni] session ${s.sessionId}: no summary, no HSI windows",
                )
                return digest
            }

            val means = aggregateAxesFromWindows(windows)
            if (means.isNotEmpty()) {
                digest.put("axis_means", JSONObject(means as Map<*, *>))
            }
            // HSI windows are 60s each by convention — the count is ≈ minutes.
            digest.put("duration_min", windows.size)
            SynheartLogger.log(
                "[syni] session ${s.sessionId}: aggregated ${windows.size} HSI " +
                    "windows → ${means.size} axes (no summary path)",
            )
        } catch (e: Exception) {
            SynheartLogger.log("[syni] session ${s.sessionId}: digest failed: $e")
        }

        return digest
    }

    companion object {
        /** Where each projected channel lives in the HSI payload. */
        private val CHANNELS = mapOf(
            "focus" to ("cognitive" to "focus"),
            "capacity" to ("cognitive" to "capacity"),
            "arousal" to ("affective" to "arousal"),
            "recovery" to ("physiological" to "recovery"),
        )

        /**
         * How many recent sessions to digest into the history block.
         *
         * Trade-off: more is richer reasoning, but roughly 30 tokens of prompt
         * prefill per session on every turn. At a few tokens/sec of prefill on
         * a phone CPU, each session costs seconds of "Syni is thinking…". Four
         * is the sweet spot — enough trend signal, bounded prefill.
         */
        private const val HISTORY_DEPTH = 4

        /**
         * True when [message] is a short greeting or acknowledgement that
         * doesn't reference the user's state.
         *
         * Conservative on purpose: anything mentioning a state-related keyword
         * is not trivial even if short. That avoids the failure mode where the
         * persona answers "how was my last session?" without the digest it
         * needs to summarize.
         */
        internal fun isTrivialMessage(message: String): Boolean {
            val m = message.trim().lowercase()
            if (m.isEmpty()) return true
            if (m.length > 24) return false
            if (m in GREETINGS) return true
            // A state keyword keeps the rich context even in a short message.
            if (STATE_KEYWORDS.any { m.contains(it) }) return false
            // Short with no state hook — treat as trivial.
            return true
        }

        private val GREETINGS = setOf(
            "hi", "hello", "hey", "yo", "sup", "howdy", "hola",
            "thanks", "thank you", "thx", "ty",
            "ok", "okay", "cool", "nice", "great", "sounds good", "got it",
            "bye", "goodbye", "gn", "gm", "good morning", "good night",
        )

        private val STATE_KEYWORDS = listOf(
            "focus", "capacity", "arousal", "recovery", "sleep", "stress",
            "state", "session", "last", "recent", "today", "week", "summary",
            "how am i", "how was", "how is", "how are",
        )

        /**
         * Mean per-channel score across the HSI frames buffered inside the
         * given windows. Each frame is weighted equally.
         */
        internal fun aggregateAxesFromWindows(windows: List<JSONObject>): Map<String, Double> {
            val sums = mutableMapOf<String, Double>()
            val counts = mutableMapOf<String, Int>()
            for (w in windows) {
                for (frame in findFrames(w)) {
                    val axes = findAxes(frame) ?: continue
                    for ((outName, loc) in CHANNELS) {
                        val readings = axes.optJSONArray(loc.first) ?: continue
                        for (i in 0 until readings.length()) {
                            val r = readings.optJSONObject(i) ?: continue
                            if (r.optString("name") != loc.second) continue
                            val score = (r.opt("score") as? Number)?.toDouble() ?: break
                            sums[outName] = (sums[outName] ?: 0.0) + score
                            counts[outName] = (counts[outName] ?: 0) + 1
                            break
                        }
                    }
                }
            }
            return sums.mapValues { (k, v) -> v / (counts[k] ?: 1) }
        }

        /**
         * Extract HSI frame payloads from one window envelope, normalizing the
         * several shapes the runtime can emit into a flat frame list.
         */
        internal fun findFrames(w: JSONObject): List<JSONObject> {
            w.optJSONObject("window")?.let { win ->
                win.optJSONArray("hsi")?.let { arr ->
                    return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
                }
                win.optJSONObject("hsi")?.let { return listOf(it) }
            }
            if (w.optJSONObject("axes") != null) return listOf(w)
            w.optJSONArray("hsi")?.let { arr ->
                return (0 until arr.length()).mapNotNull { arr.optJSONObject(it) }
            }
            w.optJSONObject("hsi")?.let { return listOf(it) }
            return emptyList()
        }

        /** Locate the `axes` object inside a single HSI frame. */
        internal fun findAxes(frame: JSONObject): JSONObject? {
            frame.optJSONObject("axes")?.let { return it }
            val hsi = frame.optJSONObject("hsi") ?: return null
            hsi.optJSONObject("axes")?.let { return it }
            return hsi
        }
    }
}
