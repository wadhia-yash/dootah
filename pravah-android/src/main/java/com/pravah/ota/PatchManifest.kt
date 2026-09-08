package com.pravah.ota

/**
 * A patch manifest as published by the update endpoint.
 *
 * This is the wire contract between the publishing tooling and the installed
 * app, so every field is immutable and validation lives in [PatchManifestParser]
 * rather than in the callers. A [PatchManifest] instance is only ever created by
 * a successful parse, which means holding one is proof that the manifest was
 * structurally valid.
 */
data class PatchManifest(
    val schemaVersion: Int,
    val patchVersion: Int,
    val runtimeVersion: String,

    /**
     * Remote kill switch. When false, Pravah must stop fetching and executing
     * remote patches and fall back to what shipped in the APK.
     *
     * Required rather than defaulted: a manifest that forgot the field would
     * otherwise silently default to enabled, which is the wrong direction to
     * fail for a switch whose entire purpose is turning remote code off.
     */
    val enabled: Boolean,

    val url: String,

    /** Lowercase hex SHA-256 of the patch payload, normalised by the parser. */
    val sha256: String,

    /**
     * Detached publisher signature over the manifest.
     *
     * Always null today. SHA-256 proves the payload matches the manifest but
     * proves nothing about who wrote the manifest, so authenticity arrives with
     * Ed25519 signing in a later milestone. The field exists now so that adding
     * signatures does not change the manifest schema version.
     */
    val signature: String? = null,
)
