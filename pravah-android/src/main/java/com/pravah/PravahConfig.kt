package com.pravah

/** Shared log tag, so every Pravah line can be filtered with one adb command. */
internal const val PRAVAH_LOG_TAG = "Pravah"

/**
 * How Pravah should behave in a host app.
 *
 * Only [manifestUrl] has no sensible default. The limits are exposed so an app
 * can tighten them, not because they are expected to be tuned.
 */
data class PravahConfig(

    /** HTTPS URL of the patch manifest. */
    val manifestUrl: String,

    /** Asset in the host APK used when no remote patch is available. */
    val bundledPatchAsset: String = "patch.js",

    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 15_000,

    /** Wall-clock budget for a single patch evaluation. */
    val executionTimeoutMillis: Long = 5_000,

    val maxManifestSizeBytes: Int = 64 * 1024,
    val maxPatchSizeBytes: Int = 8 * 1024 * 1024,
)
