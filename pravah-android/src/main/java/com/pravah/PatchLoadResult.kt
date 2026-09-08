package com.pravah

import com.pravah.ui.PatchUiNode

/** The outcome of loading and rendering the active patch. */
sealed interface PatchLoadResult {

    /** The patch produced a UI tree ready to render. */
    data class Loaded(
        val ui: PatchUiNode,
        val source: PatchSource,
    ) : PatchLoadResult

    /**
     * Pravah has no usable content, so the host app must show its own UI.
     *
     * This is an expected outcome, not an error: it is what happens when the
     * kill switch is on, the device has never downloaded a patch and has no
     * bundled one, or a patch failed to run.
     */
    data class Unavailable(
        val reason: FallbackReason,
        val message: String,
    ) : PatchLoadResult
}

/** Why Pravah fell back to the host app's own UI. */
enum class FallbackReason {

    /** A manifest switched Pravah off. */
    DISABLED,

    /** No patch is available on disk or in the APK. */
    NO_PATCH_AVAILABLE,

    /** The patch failed to evaluate, or exceeded its time budget. */
    EXECUTION_FAILED,

    /** The patch ran but produced UI Pravah could not parse. */
    INVALID_PATCH_UI,

    /** The device cannot run patches at all, e.g. no JavaScript sandbox. */
    RUNTIME_UNAVAILABLE,
}
