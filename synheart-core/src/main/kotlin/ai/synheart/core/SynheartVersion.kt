package ai.synheart.core

/**
 * Version of the `synheart-core` Android SDK.
 *
 * Kept in sync with `VERSION_NAME` in `gradle.properties`. Surfaced so host
 * apps can display the SDK version on About / diagnostics screens without
 * having to parse build metadata at runtime.
 *
 * Distinct from the native runtime's own version, which is read through
 * `Synheart.runtimeVersion`.
 */
const val SYNHEART_CORE_VERSION: String = "0.3.0"
