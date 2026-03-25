package ai.synheart.core.modules.cloud

/**
 * Base exception for Cloud Connector operations
 */
open class CloudConnectorException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/**
 * Thrown when cloudUpload consent is required but not granted
 */
class ConsentRequiredError(message: String) : CloudConnectorException(message)

/**
 * Thrown when an app capability is required but not granted
 */
class CapabilityRequiredError(message: String) : CloudConnectorException(message)

/**
 * Thrown when HMAC signature validation fails (401 invalid_signature)
 */
class InvalidSignatureError : CloudConnectorException("HMAC signature validation failed")

/**
 * Thrown when rate limit is exceeded (429)
 */
class RateLimitExceededError(val retryAfter: Int) :
    CloudConnectorException("Rate limit exceeded, retry after $retryAfter seconds")

/**
 * Thrown when tenant ID is invalid or not found (403 invalid_tenant)
 */
class InvalidTenantError : CloudConnectorException("Tenant ID not found or invalid")

/**
 * Thrown when HSI 1.1 schema validation fails (400 schema_validation_failed)
 */
class SchemaValidationError : CloudConnectorException("HSI 1.1 schema validation failed")

/**
 * Thrown on network errors or failed uploads after max retries
 */
class NetworkError(message: String, cause: Throwable? = null) :
    CloudConnectorException(message, cause)
