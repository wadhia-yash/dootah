package com.dootah.ui

import androidx.compose.runtime.Composable

/**
 * Marks the surface Dootah's compiler plugin generates calls to.
 *
 * Public because generated code lives in the host app's module and cannot reach
 * an internal declaration, but not part of the API a host app writes against.
 * Nothing here is a supported hand-written entry point, and its shape may change
 * with the compiler.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is generated-code surface for Dootah's compiler plugin, not an API to call directly.",
)
@Retention(AnnotationRetention.BINARY)
annotation class DootahGeneratedApi

/**
 * The Dootah state backing one `@Bundlable` screen.
 *
 * Deliberately opaque: the generated interception only needs to ask whether a
 * remote implementation exists and to render it.
 */
class DootahScreenState internal constructor(
    internal val screenId: String,
    internal val host: DootahHostState,
)

/**
 * Prepares Dootah for the screen identified by [screenId].
 *
 * Called once per composition of an intercepted function, so the availability
 * check and the render below it observe the same state rather than two
 * independent lookups.
 *
 * Milestone 1: [screenId] is carried through and reported but not yet used to
 * select an implementation, because the bundle protocol is still single-screen.
 * The screen-addressed protocol replaces the lookup without changing this
 * signature.
 */
@DootahGeneratedApi
@Composable
fun rememberDootahScreen(screenId: String): DootahScreenState {

    val host = rememberDootahHostState()

    return androidx.compose.runtime.remember(screenId, host) {
        DootahScreenState(screenId = screenId, host = host)
    }
}

/**
 * Whether Dootah has a remote implementation ready for this screen.
 *
 * False while loading and false on every failure path, so the native body -- the
 * implementation that shipped in the APK -- is what renders unless Dootah has
 * something usable to put in its place.
 */
@DootahGeneratedApi
fun hasRemoteImplementation(state: DootahScreenState): Boolean =
    state.host.content is DootahContent.Bundle

/**
 * Renders the remote implementation for a screen.
 *
 * Only valid where [hasRemoteImplementation] just returned true. If the state
 * changed in between, nothing is drawn rather than crashing: a remote screen
 * disappearing mid-composition must not take the host app down.
 */
@DootahGeneratedApi
@Composable
fun DootahRemoteContent(state: DootahScreenState) {

    val content = state.host.content

    if (content is DootahContent.Bundle) {
        BundleRenderer(
            node = content.ui,
            onAction = state.host::dispatch,
        )
    }
}
