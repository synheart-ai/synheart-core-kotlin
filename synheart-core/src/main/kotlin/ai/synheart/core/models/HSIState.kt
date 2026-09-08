package ai.synheart.core.models

import org.json.JSONArray
import org.json.JSONObject

/** A single HSI axis reading with value and confidence. */
data class HSIAxisValue(
    val value: Double,
    val confidence: Double
)

/** The canonical HSI axes surfaced to hosts. */
data class HSIAxes(
    val focus: HSIAxisValue? = null,
    val arousal: HSIAxisValue? = null,
    val capacity: HSIAxisValue? = null,
    val sleep: HSIAxisValue? = null,
    /**
     * Multimodal stress reading (motion-gated autonomic primary fused with a
     * behavioral corroborator). New in engine v0.10.0; null on the legacy/1.2
     * path that never carried it.
     */
    val stress: HSIAxisValue? = null,

    // ── The digital domain ──────────────────────────────────────────────
    // Produced from interaction events alone, so these resolve on hardware
    // that can supply no biosignal at all.

    val focusQuality: HSIAxisValue? = null,
    val interruptionPressure: HSIAxisValue? = null,
    val interactionMode: HSIAxisValue? = null
) {
    /** True when at least one digital reading resolved. */
    val hasDigital: Boolean
        get() = focusQuality != null ||
            interruptionPressure != null ||
            interactionMode != null
}

/**
 * Modality availability derived from `meta.provenance.sources[*].signals`, per
 * the runtime's signal-modality mapping.
 *
 * Worth rendering rather than inferring from the axes: the five canonical axes
 * are physiology-derived, so on hardware with no wearable they all sit at zero
 * confidence while behavior and motion are in fact arriving. Without this a
 * host reasonably concludes nothing is working.
 */
data class Modalities(
    val physiological: Boolean = false,
    val kinematic: Boolean = false,
    val digital: Boolean = false,
) {
    val isEmpty: Boolean get() = !physiological && !kinematic && !digital
}

/**
 * Per-modality fidelity bundle. Lower number = higher fidelity.
 *
 * Pre-canonical: HSI 1.3 moves this to per-axis `tiers`. Physiological comes
 * from `source.source_tier`; kinematic and digital from the residual
 * `meta.synheart.tiers` shim.
 */
data class Tiers(
    val physiological: Int? = null,
    val kinematic: Int? = null,
    val digital: Int? = null,
) {
    val isEmpty: Boolean
        get() = physiological == null && kinematic == null && digital == null
}

/**
 * Typed HSI state emitted by `Synheart.onStateUpdate`.
 *
 * [modalities] and [tiers] are derived from `meta.provenance` and
 * `meta.synheart.tiers`; [rawJson] is retained for diagnostics.
 */
data class HSIState(
    val subjectId: String,
    val timestampMs: Long,
    val hsi: HSIAxes,
    val rawJson: String,
    val modalities: Modalities = Modalities(),
    val tiers: Tiers = Tiers(),
    /**
     * Axes the engine omitted, mapped to why — `meta.synheart.state_withheld`.
     *
     * Empty when nothing was withheld. Read this alongside [hsi]: an axis
     * missing from both is genuinely unsupported by the build, whereas one
     * listed here was deliberately not produced and must be rendered as
     * unavailable rather than as a neutral default. A canonical member is
     * complete over `axes.<domain>` ∪ this map.
     *
     * Common mobile reasons: `episodic_sensing` withholds `capacity` and
     * `mental_fatigue` on every frame; `cold_start_confidence_exhausted` marks
     * a Capacity reading whose confidence chain was consumed by the cold-start
     * penalty, which is unavailable rather than a score of zero.
     */
    val stateWithheld: Map<String, String> = emptyMap(),
    /**
     * `meta.synheart.sensing` — the sensing profile this window was produced
     * under, or null when the host declared none.
     *
     * Present only for a host that passed `HostDeclarations.sensing`. Consumers
     * comparing across platforms should **stratify on this block rather than
     * pooling**: a Cognitive Load built without notification observation and
     * without a context layer measures a structurally thinner subset of the
     * same construct. `rest_declared` also rides here.
     */
    val sensing: JSONObject? = null,
    /**
     * Why the payload could not be parsed, or null on success.
     *
     * A failed parse yields all-null axes, which is indistinguishable from
     * "the engine had no basis for any axis" unless the reason is carried
     * alongside. Check [hasParseError] before reading an axis as absent.
     */
    val parseError: String? = null,
) {
    val hasParseError: Boolean get() = parseError != null

    companion object {
        fun fromJson(json: String, subjectId: String = ""): HSIState {
            return try {
                val map = JSONObject(json)
                val timestampMs = when {
                    map.has("timestamp_ms") -> map.getLong("timestamp_ms")
                    map.has("observed_at_ms") -> map.getLong("observed_at_ms")
                    else -> System.currentTimeMillis()
                }
                val hsiObj = if (map.has("hsi")) map.getJSONObject("hsi") else map
                val sid = if (map.has("subject_id")) map.getString("subject_id") else subjectId

                // HSI 1.3 nests readings under `axes.<domain>[]`; anything
                // older used a flat `{ focus: {...} }` map. Dispatch on which
                // is present rather than assuming — the flat parser silently
                // returns all-null axes against a 1.3 payload.
                val axesObj = hsiObj.optJSONObject("axes")
                HSIState(
                    subjectId = sid,
                    timestampMs = timestampMs,
                    hsi = if (axesObj != null) parseAxesV13(axesObj) else parseAxesFlat(hsiObj),
                    rawJson = json,
                    modalities = deriveModalities(map),
                    tiers = deriveTiers(map),
                    stateWithheld = deriveStateWithheld(map),
                    sensing = synheartMeta(map)?.optJSONObject("sensing"),
                )
            } catch (e: Exception) {
                HSIState(subjectId = subjectId, timestampMs = System.currentTimeMillis(),
                    hsi = HSIAxes(), rawJson = json, parseError = e.toString())
            }
        }

        /**
         * Map one signal name to its modality.
         *
         * Unknown names return null rather than being bucketed, so a future
         * signal does not silently light up the wrong modality chip.
         */
        private fun signalToModality(signal: String): String? = when (signal) {
            "hr", "hrv", "rr", "ecg", "ppg", "spo2", "resp", "eda", "gsr", "temp" ->
                "physiological"
            "accel", "gyro", "mag" -> "kinematic"
            "touch", "scroll", "app_switch", "typing", "notification", "clipboard" ->
                "digital"
            else -> null
        }

        /**
         * `meta.synheart` off a parsed payload, or null when absent.
         *
         * Legitimately missing: `sensing` appears only when the host declared a
         * profile, and a runtime that predates the block emits neither it nor
         * `state_withheld`.
         */
        private fun synheartMeta(payload: JSONObject): JSONObject? =
            payload.optJSONObject("meta")?.optJSONObject("synheart")

        /**
         * `meta.synheart.state_withheld` — axis name to the reason it was
         * omitted. Non-string values are skipped rather than coerced: a reason
         * is a reason, and a stringified number in front of a developer trying
         * to explain a missing axis is worse than nothing.
         */
        private fun deriveStateWithheld(payload: JSONObject): Map<String, String> {
            val withheld = synheartMeta(payload)?.optJSONObject("state_withheld")
                ?: return emptyMap()
            val out = linkedMapOf<String, String>()
            for (key in withheld.keys()) {
                (withheld.opt(key) as? String)?.let { out[key] = it }
            }
            return out
        }

        /** `meta.provenance.sources` as an object, or null when absent. */
        private fun provenanceSources(payload: JSONObject): JSONObject? =
            payload.optJSONObject("meta")
                ?.optJSONObject("provenance")
                ?.optJSONObject("sources")

        private fun deriveModalities(payload: JSONObject): Modalities {
            var physio = false
            var kin = false
            var dig = false
            val sources = provenanceSources(payload) ?: return Modalities()
            for (key in sources.keys()) {
                val signals = sources.optJSONObject(key)?.optJSONArray("signals") ?: continue
                for (i in 0 until signals.length()) {
                    when (signalToModality(signals.optString(i))) {
                        "physiological" -> physio = true
                        "kinematic" -> kin = true
                        "digital" -> dig = true
                    }
                }
            }
            return Modalities(physiological = physio, kinematic = kin, digital = dig)
        }

        private fun deriveTiers(payload: JSONObject): Tiers {
            // Physiological: the WORST tier (highest number) among sources that
            // emitted a physiological signal. Reporting the best would overstate
            // the fidelity the window was actually built from.
            var physio: Int? = null
            val sources = provenanceSources(payload)
            if (sources != null) {
                for (key in sources.keys()) {
                    val entry = sources.optJSONObject(key) ?: continue
                    val signals = entry.optJSONArray("signals") ?: continue
                    val isPhysio = (0 until signals.length()).any {
                        signalToModality(signals.optString(it)) == "physiological"
                    }
                    if (!isPhysio) continue
                    val tier = (entry.opt("source_tier") as? Number)?.toInt() ?: continue
                    physio = physio?.coerceAtLeast(tier) ?: tier
                }
            }

            // Kinematic / digital have no per-source tier yet; the runtime
            // reports them through a residual shim.
            val shim = payload.optJSONObject("meta")
                ?.optJSONObject("synheart")
                ?.optJSONObject("tiers")
            return Tiers(
                physiological = physio,
                kinematic = (shim?.opt("kinematic") as? Number)?.toInt(),
                digital = (shim?.opt("digital") as? Number)?.toInt(),
            )
        }

        /**
         * Pull one named reading out of an HSI 1.3 axis domain array.
         *
         * A reading with a null / non-numeric `score` is skipped rather than
         * coerced: the engine emits that for a categorical axis or one it
         * could not compute, and a consumer must not read it as zero.
         */
        private fun findAxisReading(domain: JSONArray?, name: String): HSIAxisValue? {
            if (domain == null) return null
            for (i in 0 until domain.length()) {
                val r = domain.optJSONObject(i) ?: continue
                if (r.optString("name") != name) continue
                val score = r.opt("score") as? Number ?: return null
                return HSIAxisValue(
                    value = score.toDouble(),
                    confidence = (r.opt("confidence") as? Number)?.toDouble() ?: 0.0,
                )
            }
            return null
        }

        /**
         * HSI 1.3 parse path (the canonical five-domain set).
         *
         * `focus` / `capacity` come from `axes.cognitive[]`, `arousal` /
         * `stress` from `axes.affective[]`, `sleep_score` from
         * `axes.physiological[]`, and the interaction axes from
         * `axes.digital[]`. We surface `arousal` rather than `valence` to keep
         * parity with the legacy four-axis model. `sleep_score` is canonical;
         * `sleep` is tolerated for forward-compat producers.
         */
        private fun parseAxesV13(axes: JSONObject): HSIAxes = HSIAxes(
            focus = findAxisReading(axes.optJSONArray("cognitive"), "focus"),
            capacity = findAxisReading(axes.optJSONArray("cognitive"), "capacity"),
            arousal = findAxisReading(axes.optJSONArray("affective"), "arousal"),
            stress = findAxisReading(axes.optJSONArray("affective"), "stress"),
            sleep = findAxisReading(axes.optJSONArray("physiological"), "sleep_score")
                ?: findAxisReading(axes.optJSONArray("physiological"), "sleep"),
            focusQuality = findAxisReading(axes.optJSONArray("digital"), "focus_quality"),
            interruptionPressure = findAxisReading(
                axes.optJSONArray("digital"),
                "interruption_pressure",
            ),
            interactionMode = findAxisReading(axes.optJSONArray("digital"), "interaction_mode"),
        )

        /**
         * Legacy flat shape: `{ focus: { value, confidence }, … }`.
         *
         * Kept for producers that predate HSI 1.3. The runtime has not emitted
         * this since 1.2, so it is a fallback, not the main path.
         */
        private fun parseAxesFlat(obj: JSONObject): HSIAxes {
            fun parseAxis(key: String): HSIAxisValue? {
                val a = obj.optJSONObject(key) ?: return null
                return HSIAxisValue(
                    value = a.optDouble("value", 0.0),
                    confidence = a.optDouble("confidence", 0.0)
                )
            }
            return HSIAxes(
                focus = parseAxis("focus"),
                arousal = parseAxis("arousal"),
                capacity = parseAxis("capacity"),
                sleep = parseAxis("sleep"),
                stress = parseAxis("stress")
            )
        }
    }
}
