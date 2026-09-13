// Dootah's own runtime, which Dootah must never take over. Discovery
// finds every eligible composable in a compilation; an app that built
// this module from source rather than resolving it as a library would
// otherwise have these intercepted, including the one that renders a
// remote screen.
@file:dev.dootah.DootahNative

package com.dootah.ui

import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalWindowInfo
import com.dootah.BundleLoadResult
import com.dootah.DOOTAH_LOG_TAG
import com.dootah.Dootah
import com.dootah.DootahStatus
import com.dootah.FallbackReason
import com.dootah.UpdateResult
import com.dootah.ota.shouldReloadAfterCheck
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

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
 * One instance per screen, holding everything that screen needs and nothing
 * another screen could reach: its identity, the arguments its caller passed, its
 * callbacks, the native components and values it may use, and whatever the
 * bundle last drew for it. Two screens on display at once each keep their own,
 * so remote state, native handles and action routing cannot cross between them.
 */
class DootahScreenState internal constructor(
    internal val screenId: String,
    arguments: DootahArguments,
    callbacks: DootahCallbacks,
    bindings: DootahNativeBindings,
    private val scope: CoroutineScope,
) {

    /**
     * The inputs as of the latest composition, not the first.
     *
     * The state itself is remembered across recompositions, so holding the
     * arguments it was built with meant every re-render sent the values the
     * screen had when it first appeared. A remote screen then never reacted to
     * its own inputs: a toolbox went on showing a tool as selected after the
     * app had switched to the eraser, because the condition deciding that is
     * evaluated remotely from a value that never changed.
     *
     * Snapshot-backed because the render reads them during composition, and
     * because a change to them has to reach the screen already on display.
     */
    internal var arguments: DootahArguments by mutableStateOf(arguments)
        private set

    internal var bindings: DootahNativeBindings by mutableStateOf(bindings)
        private set

    private var callbacks: DootahCallbacks by mutableStateOf(callbacks)

    internal fun update(
        arguments: DootahArguments,
        callbacks: DootahCallbacks,
        bindings: DootahNativeBindings,
    ) {
        this.arguments = arguments
        this.callbacks = callbacks
        this.bindings = bindings
    }

    var content: DootahContent by mutableStateOf(DootahContent.Loading)
        private set

    var status: DootahStatus by mutableStateOf(Dootah.status())
        private set

    /** Result of the most recent update check, for diagnostics. */
    var lastUpdateResult: UpdateResult? by mutableStateOf(null)
        private set

    var isCheckingForUpdate: Boolean by mutableStateOf(false)
        private set

    /** The bundle version this screen last drew from. */
    private var loadedBundleVersion: Int = Dootah.status().bundleVersion

    suspend fun load() {
        apply(Dootah.renderScreen(screenId, arguments.toJson()))
        loadedBundleVersion = status.bundleVersion
    }

    /**
     * Runs an update check and reloads when the check changed what should be on
     * screen.
     *
     * The decision is [shouldReloadAfterCheck], which exists so that "the kill
     * switch must take effect now, not on the next launch" is a rule with tests
     * rather than a condition buried here.
     */
    fun checkForUpdate() {

        if (isCheckingForUpdate) return

        scope.launch {
            isCheckingForUpdate = true
            try {
                lastUpdateResult = Dootah.checkForUpdate()
                status = Dootah.status()

                val reload = shouldReloadAfterCheck(
                    isShowingRemote = content is DootahContent.Bundle,
                    currentFallbackReason = (content as? DootahContent.Fallback)?.reason,
                    isRemotelyDisabled = status.isRemotelyDisabled,
                    loadedBundleVersion = loadedBundleVersion,
                    installedBundleVersion = status.bundleVersion,
                )

                if (reload) load()
            } finally {
                isCheckingForUpdate = false
            }
        }
    }

    internal fun dispatch(action: String) {
        scope.launch {
            apply(Dootah.dispatchAction(screenId, action, arguments.toJson()))
        }
    }

    private fun apply(result: BundleLoadResult) {

        content = when (result) {

            is BundleLoadResult.Loaded -> {

                val shortfall = bindings.shortfall(result.ui.requirements())

                if (shortfall.isEmpty()) {
                    result.commands.forEach { command -> execute(command) }
                    DootahContent.Bundle(result.ui)
                } else {
                    // Reported loudly. A component that quietly stops appearing
                    // is a defect nobody notices until a user does, and the
                    // cause is impossible to guess from the symptom.
                    //
                    // This is the only structural reason a bundle is refused:
                    // it needs native code, an action, a value or a resource
                    // that this build genuinely has not got. How it arranges
                    // what this build *does* have is entirely its own business.
                    Log.e(
                        DOOTAH_LOG_TAG,
                        "the bundle for $screenId needs " +
                            shortfall.joinToString(", ") +
                            ", which this build of the app does not have; " +
                            "using the native implementation",
                    )

                    DootahContent.Fallback(
                        reason = FallbackReason.UNKNOWN_NATIVE_COMPONENT,
                        message = "This build has no " + shortfall.joinToString(", "),
                    )
                }
            }

            is BundleLoadResult.Unavailable ->
                DootahContent.Fallback(result.reason, result.message)
        }

        status = Dootah.status()
    }

    /**
     * Runs one command from the bundle.
     *
     * A callback is invoked through the screen's own map, so only names the
     * composable declares can reach anything. Everything else goes to the
     * allowlisted native capabilities. An unrecognised callback name is logged
     * and dropped rather than crashing: a bundle built against a newer version
     * of the screen must degrade, not take the app down.
     */
    private fun execute(command: BundleCommand) {

        when (command) {

            is BundleCommand.InvokeCallback ->
                if (!callbacks.invoke(command.name)) {
                    Log.w(
                        DOOTAH_LOG_TAG,
                        "bundle asked for callback '${command.name}' on $screenId, " +
                            "which declares ${callbacks.names()}",
                    )
                }

            is BundleCommand.Log -> Dootah.runCapability(command)
            is BundleCommand.Toast -> Dootah.runCapability(command)
        }
    }
}

/**
 * Prepares Dootah for one `@Bundlable` screen.
 *
 * Called once per composition of an intercepted function, so the availability
 * check and the render below it observe the same state rather than two
 * independent lookups.
 *
 * The screen reloads when the window regains focus. That is what makes the kill
 * switch a live control: a manifest published while the app is in the background
 * takes effect when the user comes back, with no restart. Window focus rather
 * than a timer, because Dootah should not be checking for updates while nobody
 * is looking at the screen.
 */
@DootahGeneratedApi
@Composable
fun rememberDootahScreen(
    screenId: String,
    arguments: DootahArguments,
    callbacks: DootahCallbacks,
    bindings: DootahNativeBindings,
): DootahScreenState {

    val scope = rememberCoroutineScope()

    val state = remember(screenId, scope) {
        DootahScreenState(
            screenId = screenId,
            arguments = arguments,
            callbacks = callbacks,
            bindings = bindings,
            scope = scope,
        )
    }

    // The state outlives a recomposition; its inputs do not. Publishing them on
    // every composition is what makes the re-render below send the values the
    // screen has now rather than the ones it opened with -- and what keeps a
    // handle pointing at the object the caller is holding today.
    SideEffect { state.update(arguments, callbacks, bindings) }

    // Re-rendered whenever the caller's arguments change, so a remote screen
    // reacts to its inputs the way the native one it replaced would.
    val argumentsJson = arguments.toJson()
    LaunchedEffect(state, argumentsJson) { state.load() }

    val isFocused = LocalWindowInfo.current.isWindowFocused

    LaunchedEffect(state, isFocused) {
        if (isFocused) state.checkForUpdate()
    }

    return state
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
    state.content is DootahContent.Bundle

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

    val content = state.content

    if (content is DootahContent.Bundle) {
        BundleRenderer(
            node = content.ui,
            bindings = state.bindings,
            inherited = state.arguments.modifier,
            onAction = state::dispatch,
        )
    }
}
