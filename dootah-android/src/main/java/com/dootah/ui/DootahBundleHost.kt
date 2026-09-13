// Dootah's own runtime, which Dootah must never take over. Discovery
// finds every eligible composable in a compilation; an app that built
// this module from source rather than resolving it as a library would
// otherwise have these intercepted, including the one that renders a
// remote screen.
@file:dev.dootah.DootahNative

package com.dootah.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.dootah.FallbackReason

/** What a Dootah-backed screen is currently showing. */
sealed interface DootahContent {

    data object Loading : DootahContent

    data class Bundle(val ui: BundleUiNode) : DootahContent

    /** Dootah has nothing usable; the host app's fallback is showing. */
    data class Fallback(
        val reason: FallbackReason,
        val message: String,
    ) : DootahContent
}

/**
 * Prepares Dootah for a screen wired by hand.
 *
 * The compiler plugin is the supported route -- a `@Bundlable` annotation and
 * nothing else -- and this exists for Dootah's own validation app, which needs
 * to drive update checks and read status directly. It takes a screen id because
 * the bundle protocol addresses screens: there is no "the screen" any more.
 */
@OptIn(DootahGeneratedApi::class)
@Composable
fun rememberDootahHostState(screenId: String): DootahScreenState =
    rememberDootahScreen(
        screenId = screenId,
        arguments = DootahArguments.EMPTY,
        callbacks = DootahCallbacks.EMPTY,
        bindings = DootahNativeBindings.EMPTY,
    )

/**
 * Renders bundle-provided UI, or [fallback] when Dootah has nothing to show.
 *
 * The fallback is a required parameter rather than an optional one: a screen
 * backed by remote content must always have something native to fall back to,
 * and making it optional would allow a blank screen in production.
 */
@Composable
fun DootahBundleHost(
    state: DootahScreenState,
    loading: @Composable () -> Unit = {},
    fallback: @Composable (reason: FallbackReason, message: String) -> Unit,
) {

    when (val current = state.content) {

        DootahContent.Loading -> loading()

        is DootahContent.Bundle -> BundleRenderer(
            node = current.ui,
            bindings = state.bindings,
            inherited = Modifier,
            onAction = state::dispatch,
        )

        is DootahContent.Fallback -> fallback(current.reason, current.message)
    }
}
