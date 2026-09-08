package com.dootah

/** Shared log tag, so every Dootah line can be filtered with one adb command. */
internal const val DOOTAH_LOG_TAG = "Dootah"

/**
 * How Dootah should behave in a host app.
 *
 * Only [manifestUrl] has no sensible default. The limits are exposed so an app
 * can tighten them, not because they are expected to be tuned.
 */
data class DootahConfig(

    /** HTTPS URL of the bundle manifest. */
    val manifestUrl: String,

    /** Asset in the host APK used when no remote bundle is available. */
    val assetBundleName: String = "bundle.js",

    val connectTimeoutMillis: Int = 10_000,
    val readTimeoutMillis: Int = 15_000,

    /** Wall-clock budget for a single bundle evaluation. */
    val executionTimeoutMillis: Long = 5_000,

    val maxManifestSizeBytes: Int = 64 * 1024,
    val maxBundleSizeBytes: Int = 8 * 1024 * 1024,
)
