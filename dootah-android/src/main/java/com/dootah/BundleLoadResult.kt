package com.dootah

import com.dootah.ui.BundleUiNode

/** The outcome of loading and rendering the active bundle. */
sealed interface BundleLoadResult {

    /** The bundle produced a UI tree ready to render. */
    data class Loaded(
        val ui: BundleUiNode,
        val source: BundleSource,
    ) : BundleLoadResult

    /**
     * Dootah has no usable content, so the host app must show its own UI.
     *
     * This is an expected outcome, not an error: it is what happens when the
     * kill switch is on, the device has never downloaded a bundle and has no
     * bundled one, or a bundle failed to run.
     */
    data class Unavailable(
        val reason: FallbackReason,
        val message: String,
    ) : BundleLoadResult
}

/** Why Dootah fell back to the host app's own UI. */
enum class FallbackReason {

    /** A manifest switched Dootah off. */
    DISABLED,

    /** No bundle is available on disk or in the APK. */
    NO_BUNDLE_AVAILABLE,

    /** The bundle failed to evaluate, or exceeded its time budget. */
    EXECUTION_FAILED,

    /** The bundle ran but produced UI Dootah could not parse. */
    INVALID_BUNDLE_UI,

    /** The device cannot run bundles at all, e.g. no JavaScript sandbox. */
    RUNTIME_UNAVAILABLE,
}
