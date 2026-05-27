package ai.synheart.core.models

/**
 * Constants derived from hsi-1.1.schema.json.
 *
 * When the HSI schema is upgraded, update [VERSION], add/modify the
 * domain and field sets, and [HsiSchemaTransformer] adapts automatically.
 *
 * See: https://github.com/synheart-ai/hsi/tree/main/schema
 */
object HsiSchema {
    const val VERSION = "1.1"

    /** Valid top-level axis domain names (additionalProperties: false on `axes`). */
    val axisDomains = setOf(
        "physiological",
        "behavior",
        "engagement",
        "context"
    )

    /** Valid source types. */
    val sourceTypes = setOf(
        "sensor",
        "app",
        "self_report",
        "observer",
        "derived",
        "other"
    )

    /** Valid direction values for axis readings. */
    val directions = setOf(
        "higher_is_more",
        "higher_is_less",
        "bidirectional"
    )

    /** Valid embedding encoding formats. */
    val encodings = setOf(
        "float32",
        "float64",
        "fp16",
        "int8"
    )

    /** Valid privacy consent level values. */
    val consentLevels = setOf(
        "none",
        "implicit",
        "explicit"
    )

    /** Allowed fields on `axis_reading` (additionalProperties: false). */
    val readingFields = setOf(
        "axis",
        "score",
        "confidence",
        "window_id",
        "direction",
        "unit",
        "evidence_source_ids",
        "notes"
    )

    /** Allowed fields on `window` (additionalProperties: false). */
    val windowFields = setOf("start", "end", "label")

    /** Allowed fields on `producer` (additionalProperties: false). */
    val producerFields = setOf("name", "version", "instance_id")

    /** Allowed fields on `privacy` (additionalProperties: false). */
    val privacyFields = setOf(
        "contains_pii",
        "raw_biosignals_allowed",
        "derived_metrics_allowed",
        "embedding_allowed",
        "consent",
        "purposes",
        "notes"
    )

    /** Allowed fields on `source` (additionalProperties: false). */
    val sourceFields = setOf(
        "type",
        "quality",
        "degraded",
        "notes"
    )
}
