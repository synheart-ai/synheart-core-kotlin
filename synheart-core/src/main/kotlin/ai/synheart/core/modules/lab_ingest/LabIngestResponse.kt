package ai.synheart.core.modules.lab_ingest

/**
 * Response from lab ingestion API calls.
 */
data class LabIngestResponse(
    val success: Boolean,
    val statusCode: Int,
    val body: Map<String, Any?>? = null,
    val errorMessage: String? = null
) {
    override fun toString(): String {
        val base = "LabIngestResponse(success=$success, statusCode=$statusCode"
        return if (errorMessage != null) "$base, error=$errorMessage)" else "$base)"
    }
}
