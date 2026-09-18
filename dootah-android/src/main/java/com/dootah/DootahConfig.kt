package com.dootah

/** Shared log tag, so every Dootah line can be filtered with one adb command. */
internal const val DOOTAH_LOG_TAG = "Dootah"

/**
 * How Dootah should behave in a host app.
 *
 * [manifestUrl] selects the update endpoint; [trustedPublicKey] authorizes its
 * publisher. Without a key the app can use its APK content but cannot accept OTA.
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

    /** Base64 of the trusted 32-byte Ed25519 public key. Missing key rejects OTA. */
    val trustedPublicKey: String? = null,

    /** Defaults to the installed application package name. */
    val appId: String? = null,

    /** Null keeps the static-manifest flow. Otherwise manifestUrl is /updates/check.
     * Explicit native choice: development, staging or production. Fixed for this process.
     */
    val channel: String? = null,
)
