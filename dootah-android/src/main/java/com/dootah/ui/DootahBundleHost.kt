package com.dootah.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.dootah.FallbackReason
import com.dootah.BundleLoadResult
import com.dootah.Dootah
import com.dootah.DootahStatus
import com.dootah.UpdateResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What [DootahBundleHost] is currently showing. */
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
 * Observable state for a Dootah-backed screen.
 *
 * Owns the load/reload lifecycle and action dispatch so a host app only has to
 * decide what to draw. Actions are forwarded to the bundle and the resulting UI
 * replaces the current one; a bundle that fails mid-interaction degrades to the
 * fallback rather than leaving a stale screen.
 */
class DootahHostState internal constructor(
    private val scope: CoroutineScope,
) {

    var content: DootahContent by mutableStateOf(DootahContent.Loading)
        private set

    var status: DootahStatus by mutableStateOf(Dootah.status())
        private set

    /** Result of the most recent manual update check, for display. */
    var lastUpdateResult: UpdateResult? by mutableStateOf(null)
        private set

    var isCheckingForUpdate: Boolean by mutableStateOf(false)
        private set

    suspend fun load() {
        apply(Dootah.loadBundle())
    }

    /**
     * Runs an update check and reloads the bundle when one was installed.
     *
     * Reloading only on [UpdateResult.Updated] keeps a no-op check from
     * restarting a perfectly good isolate.
     */
    fun checkForUpdate() {

        if (isCheckingForUpdate) return

        scope.launch {
            isCheckingForUpdate = true
            try {
                val result = Dootah.checkForUpdate()
                lastUpdateResult = result

                if (result is UpdateResult.Updated) {
                    load()
                } else {
                    status = Dootah.status()
                }
            } finally {
                isCheckingForUpdate = false
            }
        }
    }

    internal fun dispatch(action: String) {
        scope.launch { apply(Dootah.dispatchAction(action)) }
    }

    private fun apply(result: BundleLoadResult) {

        content = when (result) {
            is BundleLoadResult.Loaded -> DootahContent.Bundle(result.ui)
            is BundleLoadResult.Unavailable ->
                DootahContent.Fallback(result.reason, result.message)
        }

        status = Dootah.status()
    }
}

@Composable
fun rememberDootahHostState(): DootahHostState {

    val scope = rememberCoroutineScope()
    val state = remember(scope) { DootahHostState(scope) }

    LaunchedEffect(state) { state.load() }

    return state
}

/**
 * Renders bundle-provided UI, or [fallback] when Dootah has nothing to show.
 *
 * The fallback is a required parameter rather than an optional one: a screen
 * backed by remote content must always have something native to fall back to,
 * and making it optional would allow a blank screen in production.
 */
@Composable
fun DootahBundleHost(
    state: DootahHostState = rememberDootahHostState(),
    loading: @Composable () -> Unit = {},
    fallback: @Composable (reason: FallbackReason, message: String) -> Unit,
) {

    when (val current = state.content) {

        DootahContent.Loading -> loading()

        is DootahContent.Bundle -> BundleRenderer(
            node = current.ui,
            onAction = state::dispatch,
        )

        is DootahContent.Fallback -> fallback(current.reason, current.message)
    }
}
