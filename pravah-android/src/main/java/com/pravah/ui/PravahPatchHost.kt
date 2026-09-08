package com.pravah.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.pravah.FallbackReason
import com.pravah.PatchLoadResult
import com.pravah.Pravah
import com.pravah.PravahStatus
import com.pravah.UpdateResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** What [PravahPatchHost] is currently showing. */
sealed interface PravahContent {

    data object Loading : PravahContent

    data class Patch(val ui: PatchUiNode) : PravahContent

    /** Pravah has nothing usable; the host app's fallback is showing. */
    data class Fallback(
        val reason: FallbackReason,
        val message: String,
    ) : PravahContent
}

/**
 * Observable state for a Pravah-backed screen.
 *
 * Owns the load/reload lifecycle and action dispatch so a host app only has to
 * decide what to draw. Actions are forwarded to the patch and the resulting UI
 * replaces the current one; a patch that fails mid-interaction degrades to the
 * fallback rather than leaving a stale screen.
 */
class PravahHostState internal constructor(
    private val scope: CoroutineScope,
) {

    var content: PravahContent by mutableStateOf(PravahContent.Loading)
        private set

    var status: PravahStatus by mutableStateOf(Pravah.status())
        private set

    /** Result of the most recent manual update check, for display. */
    var lastUpdateResult: UpdateResult? by mutableStateOf(null)
        private set

    var isCheckingForUpdate: Boolean by mutableStateOf(false)
        private set

    suspend fun load() {
        apply(Pravah.loadPatch())
    }

    /**
     * Runs an update check and reloads the patch when one was installed.
     *
     * Reloading only on [UpdateResult.Updated] keeps a no-op check from
     * restarting a perfectly good isolate.
     */
    fun checkForUpdate() {

        if (isCheckingForUpdate) return

        scope.launch {
            isCheckingForUpdate = true
            try {
                val result = Pravah.checkForUpdate()
                lastUpdateResult = result

                if (result is UpdateResult.Updated) {
                    load()
                } else {
                    status = Pravah.status()
                }
            } finally {
                isCheckingForUpdate = false
            }
        }
    }

    internal fun dispatch(action: String) {
        scope.launch { apply(Pravah.dispatchAction(action)) }
    }

    private fun apply(result: PatchLoadResult) {

        content = when (result) {
            is PatchLoadResult.Loaded -> PravahContent.Patch(result.ui)
            is PatchLoadResult.Unavailable ->
                PravahContent.Fallback(result.reason, result.message)
        }

        status = Pravah.status()
    }
}

@Composable
fun rememberPravahHostState(): PravahHostState {

    val scope = rememberCoroutineScope()
    val state = remember(scope) { PravahHostState(scope) }

    LaunchedEffect(state) { state.load() }

    return state
}

/**
 * Renders patch-provided UI, or [fallback] when Pravah has nothing to show.
 *
 * The fallback is a required parameter rather than an optional one: a screen
 * backed by remote content must always have something native to fall back to,
 * and making it optional would allow a blank screen in production.
 */
@Composable
fun PravahPatchHost(
    state: PravahHostState = rememberPravahHostState(),
    loading: @Composable () -> Unit = {},
    fallback: @Composable (reason: FallbackReason, message: String) -> Unit,
) {

    when (val current = state.content) {

        PravahContent.Loading -> loading()

        is PravahContent.Patch -> PatchRenderer(
            node = current.ui,
            onAction = state::dispatch,
        )

        is PravahContent.Fallback -> fallback(current.reason, current.message)
    }
}
