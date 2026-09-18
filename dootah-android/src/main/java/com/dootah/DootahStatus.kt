package com.dootah

/** Where the currently rendered content came from. */
enum class BundleSource {

    /** The bundle that shipped inside the APK. */
    ASSET,

    /** A bundle downloaded over the air. */
    REMOTE,

    /** Dootah could not produce content; the host app's own UI is showing. */
    NATIVE_FALLBACK,
}

/**
 * A snapshot of Dootah's state, for diagnostics and for the validation screen.
 */
data class DootahStatus(
    /** Newest staged or active version; retained for update/reload decisions. */
    val bundleVersion: Int,
    val runtimeVersion: String,
    val source: BundleSource,

    /** True when a manifest has switched Dootah off. */
    val isRemotelyDisabled: Boolean,
    val candidateVersion: Int? = null,
    val activeVersion: Int? = null,
    val lastKnownGoodVersion: Int? = null,
    val activeConfirmedHealthy: Boolean = false,
    val updatesPaused: Boolean = false,
)
