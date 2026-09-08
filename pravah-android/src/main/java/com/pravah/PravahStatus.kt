package com.pravah

/** Where the currently rendered content came from. */
enum class PatchSource {

    /** The patch that shipped inside the APK. */
    BUNDLED,

    /** A patch downloaded over the air. */
    REMOTE,

    /** Pravah could not produce content; the host app's own UI is showing. */
    NATIVE_FALLBACK,
}

/**
 * A snapshot of Pravah's state, for diagnostics and for the validation screen.
 */
data class PravahStatus(
    val patchVersion: Int,
    val runtimeVersion: String,
    val source: PatchSource,

    /** True when a manifest has switched Pravah off. */
    val isRemotelyDisabled: Boolean,
)
