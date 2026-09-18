package com.dootah.ota

/**
 * A bundle manifest as published by the update endpoint.
 *
 * This is the wire contract between the publishing tooling and the installed
 * app, so every field is immutable and validation lives in [BundleManifestParser]
 * rather than in the callers. A [BundleManifest] instance is only ever created by
 * a successful parse, which means holding one is proof that the manifest was
 * structurally valid.
 */
data class BundleManifest(
    val schemaVersion: Int,
    val bundleVersion: Int,
    val runtimeVersion: String,

    /**
     * Remote kill switch. When false, Dootah must stop fetching and executing
     * remote bundles and fall back to what shipped in the APK.
     *
     * Required rather than defaulted: a manifest that forgot the field would
     * otherwise silently default to enabled, which is the wrong direction to
     * fail for a switch whose entire purpose is turning remote code off.
     */
    val enabled: Boolean,

    val url: String,

    /** Lowercase hex SHA-256 of the bundle payload, normalised by the parser. */
    val sha256: String,

    /** Base64 Ed25519 signature over the canonical manifest values. */
    val signature: String? = null,
    val images: List<BundleImage> = emptyList(),
    val appId: String? = null,
)

/** id is the immutable image hash used by the bundle's image:<id> painter. */
data class BundleImage(val id: String, val url: String, val sha256: String)
