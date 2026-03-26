package ai.synheart.core.modules.cloud

import ai.synheart.core.models.HsiSchema
import java.util.UUID

/**
 * Transforms raw HSI JSON maps produced by synheart-runtime into payloads
 * that conform to hsi-1.1.schema.json (additionalProperties: false).
 *
 * The Rust runtime may produce slightly non-conformant output (e.g. extra
 * fields on readings or windows). This class patches the map in-place,
 * stripping anything that would fail schema validation.
 */
class HsiSchemaTransformer {

    /**
     * Patch [hsi] in-place to conform to hsi-1.1.schema.json.
     *
     * @return the same map reference, patched
     */
    fun patch(hsi: MutableMap<String, Any?>): MutableMap<String, Any?> {
        hsi["hsi_version"] = HsiSchema.VERSION
        patchProducer(hsi)
        patchSources(hsi)
        patchAxes(hsi)
        patchWindows(hsi)
        patchPrivacy(hsi)
        return hsi
    }

    private fun patchProducer(hsi: MutableMap<String, Any?>) {
        val producer = hsi["producer"] as? MutableMap<String, Any?> ?: return
        producer.putIfAbsent("instance_id", UUID.randomUUID().toString())
        producer.keys.retainAll(HsiSchema.producerFields)
    }

    /**
     * Ensure `source_ids` + `sources` pair integrity at top level.
     * Both are optional, but if one is present the other must be too.
     */
    private fun patchSources(hsi: MutableMap<String, Any?>) {
        val sourceIds = hsi["source_ids"]
        val sources = hsi["sources"]

        val hasSourceIds = sourceIds is List<*> && sourceIds.isNotEmpty()
        @Suppress("UNCHECKED_CAST")
        val sourcesMap = sources as? MutableMap<String, Any?>
        val hasSources = sourcesMap != null && sourcesMap.isNotEmpty()

        if (hasSourceIds && !hasSources) {
            hsi.remove("source_ids")
        } else if (!hasSourceIds && hasSources) {
            hsi["source_ids"] = sourcesMap!!.keys.toList()
        }

        // Ensure source_ids is a List<String> if present
        val patchedSourceIds = hsi["source_ids"]
        if (patchedSourceIds is List<*>) {
            val strings = patchedSourceIds.filterIsInstance<String>()
            if (strings.isEmpty()) {
                hsi.remove("source_ids")
            } else {
                hsi["source_ids"] = strings
            }
        }

        @Suppress("UNCHECKED_CAST")
        val srcMap = hsi["sources"] as? MutableMap<String, Any?>
        if (srcMap != null) {
            for (entry in srcMap.values) {
                @Suppress("UNCHECKED_CAST")
                val entryMap = entry as? MutableMap<String, Any?> ?: continue
                entryMap.keys.retainAll(HsiSchema.sourceFields)
                if (entryMap["type"] !in HsiSchema.sourceTypes) {
                    entryMap["type"] = "derived"
                }
            }
        }
    }

    private fun patchAxes(hsi: MutableMap<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val axes = hsi["axes"] as? MutableMap<String, Any?> ?: return

        // Drop unknown domains (additionalProperties: false)
        axes.keys.retainAll(HsiSchema.axisDomains)

        for (domain in axes.values) {
            @Suppress("UNCHECKED_CAST")
            val domainMap = domain as? MutableMap<String, Any?> ?: continue
            val readings = domainMap["readings"] as? MutableList<*> ?: continue

            for (reading in readings) {
                @Suppress("UNCHECKED_CAST")
                val readingMap = reading as? MutableMap<String, Any?> ?: continue
                readingMap.keys.retainAll(HsiSchema.readingFields)

                val direction = readingMap["direction"] as? String
                if (direction != null && direction !in HsiSchema.directions) {
                    readingMap.remove("direction")
                }

                val evidence = readingMap["evidence_source_ids"]
                if (evidence is List<*>) {
                    val strings = evidence.filterIsInstance<String>()
                    if (strings.isEmpty()) {
                        readingMap.remove("evidence_source_ids")
                    } else {
                        readingMap["evidence_source_ids"] = strings
                    }
                }
            }
        }
    }

    private fun patchWindows(hsi: MutableMap<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val windows = hsi["windows"] as? MutableMap<String, Any?> ?: return

        for (window in windows.values) {
            @Suppress("UNCHECKED_CAST")
            val windowMap = window as? MutableMap<String, Any?> ?: continue
            windowMap.keys.retainAll(HsiSchema.windowFields)
        }
    }

    private fun patchPrivacy(hsi: MutableMap<String, Any?>) {
        @Suppress("UNCHECKED_CAST")
        val privacy = hsi["privacy"] as? MutableMap<String, Any?> ?: return

        val consent = privacy["consent"]
        when {
            consent is Map<*, *> -> {
                privacy["consent"] = consent["level"]?.toString() ?: "explicit"
            }
            consent is String -> {
                if (consent !in HsiSchema.consentLevels) {
                    privacy["consent"] = "explicit"
                }
            }
        }

        // Recheck after patching
        val patchedConsent = privacy["consent"]
        if (patchedConsent is String && patchedConsent !in HsiSchema.consentLevels) {
            privacy["consent"] = "explicit"
        }

        privacy["contains_pii"] = false
        privacy.putIfAbsent("raw_biosignals_allowed", false)
        privacy.putIfAbsent("derived_metrics_allowed", true)

        privacy.keys.retainAll(HsiSchema.privacyFields)
    }
}
