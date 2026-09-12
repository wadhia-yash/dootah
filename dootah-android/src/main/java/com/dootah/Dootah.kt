package com.dootah

import android.content.Context
import android.util.Log
import com.dootah.bridge.NativeBridge
import com.dootah.ota.ASSET_BUNDLE_VERSION
import com.dootah.ota.BundleDownloader
import com.dootah.ota.BundleExecutionException
import com.dootah.ota.BundleStore
import com.dootah.ota.BundleUpdater
import com.dootah.ota.DOOTAH_RUNTIME_VERSION
import com.dootah.runtime.BundleEngine
import com.dootah.runtime.JavaScriptRuntime
import com.dootah.ui.BundleCommand
import com.dootah.ui.BundleResponse
import com.dootah.ui.BundleUiParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The entry point for integrating Dootah into an Android app.
 *
 * Call [initialize] once; everything else is driven by the compiler plugin or,
 * for Dootah's own validation app, by the functions here. A host app never
 * touches the JavaScript sandbox, the bundle filesystem, manifest parsing, or
 * hashing.
 *
 * A process-wide singleton because the JavaScript sandbox is an expensive,
 * process-scoped resource that should exist at most once per app -- and because
 * every `@Bundlable` screen in the app shares one loaded bundle.
 */
object Dootah {

    @Volatile
    private var client: DootahClient? = null

    /**
     * Prepares Dootah. Safe to call more than once; later calls are ignored so
     * an Activity recreation cannot discard a running sandbox.
     */
    fun initialize(context: Context, config: DootahConfig) {

        if (client != null) return

        synchronized(this) {
            if (client != null) return

            client = DootahClient(
                applicationContext = context.applicationContext,
                config = config,
            )

            Log.i(
                DOOTAH_LOG_TAG,
                "initialized: runtime version $DOOTAH_RUNTIME_VERSION, " +
                    "manifest ${config.manifestUrl}"
            )
        }
    }

    /** The Dootah runtime contract this build implements. */
    val runtimeVersion: String get() = DOOTAH_RUNTIME_VERSION

    val isInitialized: Boolean get() = client != null

    /** Performs one update check. Never throws; see [UpdateResult]. */
    suspend fun checkForUpdate(): UpdateResult {

        val active = client ?: return UpdateResult.Failed(
            reason = UpdateFailure.UNKNOWN,
            message = NOT_INITIALIZED_MESSAGE,
        )

        return active.checkForUpdate()
    }

    /**
     * Renders one screen from the active bundle. Never throws.
     *
     * [argumentsJson] is what the screen's caller passed, as the bundle reads
     * it. An empty string means the screen takes no arguments.
     */
    suspend fun renderScreen(screenId: String, argumentsJson: String): BundleLoadResult =
        client?.renderScreen(screenId, argumentsJson) ?: notInitialized()

    /** Forwards a UI action to one screen and returns its new UI. Never throws. */
    suspend fun dispatchAction(
        screenId: String,
        action: String,
        argumentsJson: String,
    ): BundleLoadResult =
        client?.dispatchAction(screenId, action, argumentsJson) ?: notInitialized()

    /**
     * Runs one allowlisted native capability a bundle asked for.
     *
     * Routed through here rather than executed by the caller so that the set of
     * capabilities remote content can reach is decided in one place, against a
     * `Context` the caller does not have to hold.
     */
    fun runCapability(command: BundleCommand) {
        client?.runCapability(command)
    }

    /** A snapshot of Dootah's state, or a fallback snapshot before initialization. */
    fun status(): DootahStatus = client?.status() ?: DootahStatus(
        bundleVersion = ASSET_BUNDLE_VERSION,
        runtimeVersion = DOOTAH_RUNTIME_VERSION,
        source = BundleSource.NATIVE_FALLBACK,
        isRemotelyDisabled = false,
    )

    /** Releases the JavaScript sandbox. */
    suspend fun shutdown() {
        client?.shutdown()
    }

    /**
     * Forgetting to initialize is a programming error, but it must not take a
     * real user's screen down. It is reported loudly in the log and through the
     * ordinary fallback path, so the host app renders its native content.
     */
    private fun notInitialized(): BundleLoadResult {

        Log.e(DOOTAH_LOG_TAG, NOT_INITIALIZED_MESSAGE)

        return BundleLoadResult.Unavailable(
            reason = FallbackReason.RUNTIME_UNAVAILABLE,
            message = NOT_INITIALIZED_MESSAGE,
        )
    }
}

private const val NOT_INITIALIZED_MESSAGE =
    "Dootah.initialize(context, config) was not called; falling back to native content"

/**
 * Holds the collaborators for one Dootah installation.
 *
 * Separate from the [Dootah] object so the wiring is explicit and the singleton
 * stays a thin facade.
 */
private class DootahClient(
    applicationContext: Context,
    private val config: DootahConfig,
) {

    private val store = BundleStore(
        context = applicationContext,
        bundledAssetName = config.assetBundleName,
    )

    private val nativeBridge = NativeBridge(applicationContext)

    private val runtime = JavaScriptRuntime(
        context = applicationContext,
        executionTimeoutMillis = config.executionTimeoutMillis,
        onNativeMessage = nativeBridge::handle,
    )

    private val engine = BundleEngine(runtime)

    private val updater = BundleUpdater(
        manifestUrl = config.manifestUrl,
        downloader = BundleDownloader(
            connectTimeoutMillis = config.connectTimeoutMillis,
            readTimeoutMillis = config.readTimeoutMillis,
        ),
        store = store,
        supportedRuntimeVersion = DOOTAH_RUNTIME_VERSION,
        maxManifestSizeBytes = config.maxManifestSizeBytes,
        maxBundleSizeBytes = config.maxBundleSizeBytes,
    )

    /**
     * Serialises everything that touches the isolate.
     *
     * With several screens on display at once, two of them can render at the
     * same moment. One isolate cannot serve two overlapping evaluations, and a
     * load must not interleave with a render.
     */
    private val engineLock = Mutex()

    /** Serialises update checks; see [checkForUpdate]. */
    private val updateLock = Mutex()

    /** The screens the loaded bundle implements, or null when nothing is loaded. */
    private var loadedScreenIds: List<String>? = null

    private var currentSource: BundleSource = BundleSource.NATIVE_FALLBACK

    /**
     * Runs one update check, and only one at a time.
     *
     * Every `@Bundlable` screen checks when it appears, so an app showing two of
     * them checks twice at once. Two concurrent checks each downloaded the same
     * bundle and each tried to move it into place; the second move failed,
     * because the first had already renamed the file out from under it. The
     * result was a logged failure for an update that had in fact succeeded, and
     * with two genuinely different versions in flight it could have installed
     * either one.
     *
     * Serialised here rather than deduplicated, so the second caller still gets
     * a truthful answer -- it simply finds there is nothing left to do.
     */
    suspend fun checkForUpdate(): UpdateResult = updateLock.withLock {

        val result = updater.checkForUpdate()

        // A new bundle on disk makes the loaded one stale. Dropped rather than
        // reloaded here: the next screen to render loads it, and reloading now
        // would restart the isolate under screens that are still using it.
        if (result is UpdateResult.Updated) {
            engineLock.withLock { loadedScreenIds = null }
        }

        result
    }

    suspend fun renderScreen(
        screenId: String,
        argumentsJson: String,
    ): BundleLoadResult = engineLock.withLock {

        when (val availability = ensureLoaded()) {
            is BundleAvailability.Unavailable -> availability.asResult()
            is BundleAvailability.Ready -> availability.requiring(screenId)
                ?: respond { engine.render(screenId, argumentsJson) }
        }
    }

    suspend fun dispatchAction(
        screenId: String,
        action: String,
        argumentsJson: String,
    ): BundleLoadResult = engineLock.withLock {

        when (val availability = ensureLoaded()) {
            is BundleAvailability.Unavailable -> availability.asResult()
            is BundleAvailability.Ready -> availability.requiring(screenId)
                ?: respond { engine.dispatch(screenId, action, argumentsJson) }
        }
    }

    fun runCapability(command: BundleCommand) {

        when (command) {
            is BundleCommand.Log -> nativeBridge.log(command.message)
            is BundleCommand.Toast -> nativeBridge.toast(command.message)

            // Callbacks belong to a screen, which is the only thing holding the
            // functions they name. Reaching one from here would mean a lookup by
            // name across the whole app, which is exactly what this design
            // avoids.
            is BundleCommand.InvokeCallback -> Log.w(
                DOOTAH_LOG_TAG,
                "callback '${command.name}' is not a native capability",
            )
        }
    }

    /**
     * Whether the loaded bundle can serve [screenId].
     *
     * A screen the bundle does not implement is an ordinary outcome, not a
     * failure: it is what a newly annotated screen looks like before the next
     * bundle is published. Returning the fallback here is also what stops one
     * screen from being served another's implementation.
     */
    private fun BundleAvailability.Ready.requiring(
        screenId: String,
    ): BundleLoadResult.Unavailable? =
        if (screenId in screenIds) null
        else BundleLoadResult.Unavailable(
            reason = FallbackReason.SCREEN_NOT_IN_BUNDLE,
            message = "The active bundle implements " +
                screenIds.joinToString().ifEmpty { "no screens" } +
                ", not '$screenId'",
        )

    /**
     * Makes sure a bundle is loaded, and reports which screens it has.
     *
     * The screen list is cached, so several screens rendering in one frame
     * evaluate the bundle once between them.
     */
    private suspend fun ensureLoaded(): BundleAvailability {

        if (store.isRemotelyDisabled) {
            Log.w(DOOTAH_LOG_TAG, "Dootah disabled, using fallback")
            currentSource = BundleSource.NATIVE_FALLBACK
            loadedScreenIds = null
            return BundleAvailability.Unavailable(
                reason = FallbackReason.DISABLED,
                message = "Dootah is disabled by the published manifest",
            )
        }

        loadedScreenIds?.let { return BundleAvailability.Ready(it) }

        val isRemote = store.hasDownloadedBundle()

        val bundleSource = try {
            if (isRemote) store.readDownloadedBundle() else store.readAssetBundle()
        } catch (e: Exception) {
            Log.w(DOOTAH_LOG_TAG, "no bundle available, using fallback", e)
            currentSource = BundleSource.NATIVE_FALLBACK
            return BundleAvailability.Unavailable(
                reason = FallbackReason.NO_BUNDLE_AVAILABLE,
                message = e.message ?: "No bundled or downloaded bundle could be read",
            )
        }

        return try {
            val screens = engine.load(bundleSource)

            currentSource = if (isRemote) BundleSource.REMOTE else BundleSource.ASSET
            loadedScreenIds = screens

            Log.i(
                DOOTAH_LOG_TAG,
                "bundle loaded from $currentSource " +
                    "(version ${store.installedBundleVersion}), " +
                    "screens: ${screens.joinToString()}"
            )

            BundleAvailability.Ready(screens)
        } catch (e: BundleExecutionException) {
            loadFailure(FallbackReason.EXECUTION_FAILED, "bundle execution failed", e)
        } catch (e: Exception) {
            loadFailure(FallbackReason.RUNTIME_UNAVAILABLE, "Dootah runtime unavailable", e)
        }
    }

    private fun loadFailure(
        reason: FallbackReason,
        summary: String,
        cause: Exception,
    ): BundleAvailability.Unavailable {

        Log.e(DOOTAH_LOG_TAG, "$summary, using fallback", cause)

        currentSource = BundleSource.NATIVE_FALLBACK
        loadedScreenIds = null

        return BundleAvailability.Unavailable(
            reason = reason,
            message = cause.message ?: cause::class.java.simpleName,
        )
    }

    /**
     * Runs a bundle call and parses its response, converting every failure into a
     * fallback outcome.
     *
     * The catch is deliberately broad. This is the boundary between remote code
     * and a real user's screen, and nothing a bundle does may crash the host app.
     * Every failure is logged with its cause before being reported.
     */
    private suspend fun respond(produce: suspend () -> String): BundleLoadResult {

        val payload = try {
            produce()
        } catch (e: BundleExecutionException) {
            return failure(FallbackReason.EXECUTION_FAILED, "bundle execution failed", e)
        } catch (e: Exception) {
            return failure(FallbackReason.RUNTIME_UNAVAILABLE, "Dootah runtime unavailable", e)
        }

        val response: BundleResponse = try {
            BundleUiParser.parse(payload)
        } catch (e: Exception) {
            return failure(FallbackReason.INVALID_BUNDLE_UI, "bundle produced unusable UI", e)
        }

        return BundleLoadResult.Loaded(
            ui = response.ui,
            source = currentSource,
            commands = response.commands,
            componentShapes = response.componentShapes,
        )
    }

    private fun failure(
        reason: FallbackReason,
        summary: String,
        cause: Exception,
    ): BundleLoadResult.Unavailable {

        Log.e(DOOTAH_LOG_TAG, "$summary, using fallback", cause)
        currentSource = BundleSource.NATIVE_FALLBACK

        return BundleLoadResult.Unavailable(
            reason = reason,
            message = cause.message ?: cause::class.java.simpleName,
        )
    }

    fun status(): DootahStatus = DootahStatus(
        bundleVersion = store.installedBundleVersion,
        runtimeVersion = DOOTAH_RUNTIME_VERSION,
        source = currentSource,
        isRemotelyDisabled = store.isRemotelyDisabled,
    )

    suspend fun shutdown() = runtime.close()
}

/**
 * Whether the active bundle can serve a screen right now.
 *
 * A result type rather than a nullable list plus remembered fields: the reason a
 * bundle is unavailable has to travel with the answer, and threading it through
 * mutable state is how a screen ends up reporting the wrong fallback reason.
 */
private sealed interface BundleAvailability {

    data class Ready(val screenIds: List<String>) : BundleAvailability

    data class Unavailable(
        val reason: FallbackReason,
        val message: String,
    ) : BundleAvailability {

        fun asResult(): BundleLoadResult.Unavailable =
            BundleLoadResult.Unavailable(reason = reason, message = message)
    }
}
