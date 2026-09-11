package com.dootah

import com.dootah.ui.BundleCommand
import com.dootah.ui.BundleUiNode

/** The outcome of rendering one screen from the active bundle. */
sealed interface BundleLoadResult {

    /** The bundle produced a UI tree, and possibly work for the app to do. */
    data class Loaded(
        val ui: BundleUiNode,
        val source: BundleSource,
        val commands: List<BundleCommand> = emptyList(),
    ) : BundleLoadResult

    /**
     * Dootah has no usable content, so the host app must show its own UI.
     *
     * This is an expected outcome, not an error: it is what happens when the
     * kill switch is on, the device has never downloaded a bundle and has no
     * bundled one, the bundle does not implement this screen, or a bundle
     * failed to run.
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

    /**
     * The bundle loaded, but has no implementation for this screen.
     *
     * Ordinary and expected: a screen marked `@Bundlable` after the installed
     * bundle was published simply has no remote version yet.
     */
    SCREEN_NOT_IN_BUNDLE,

    /**
     * The bundle draws a native component this build of the app does not have.
     *
     * What an edit to a component Dootah keeps native looks like from the other
     * side. Falling back is deliberate: drawing the rest of the screen with a
     * gap where that component belongs is a defect nobody can see, and a screen
     * missing a button is worse than one that is simply a version behind.
     */
    UNKNOWN_NATIVE_COMPONENT,

    /** The bundle failed to evaluate, or exceeded its time budget. */
    EXECUTION_FAILED,

    /** The bundle ran but produced UI Dootah could not parse. */
    INVALID_BUNDLE_UI,

    /** The device cannot run bundles at all, e.g. no JavaScript sandbox. */
    RUNTIME_UNAVAILABLE,
}
