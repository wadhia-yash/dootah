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
    val bundleVersion: Int,
    val runtimeVersion: String,
    val source: BundleSource,

    /** True when a manifest has switched Dootah off. */
    val isRemotelyDisabled: Boolean,
)
