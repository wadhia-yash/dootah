package com.pravah

import android.content.Context
import android.util.Log
import com.pravah.bridge.NativeBridge
import com.pravah.ota.PRAVAH_RUNTIME_VERSION
import com.pravah.ota.BUNDLED_PATCH_VERSION
import com.pravah.ota.PatchDownloader
import com.pravah.ota.PatchExecutionException
import com.pravah.ota.PatchStore
import com.pravah.ota.PatchUpdater
import com.pravah.runtime.JavaScriptRuntime
import com.pravah.runtime.PatchEngine
import com.pravah.ui.PatchUiNode
import com.pravah.ui.PatchUiParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The entry point for integrating Pravah into an Android app.
 *
 * Call [initialize] once, then drive updates and rendering through the suspend
 * functions here. A host app never touches the JavaScript sandbox, the patch
 * filesystem, manifest parsing, or hashing.
 *
 * A process-wide singleton because the JavaScript sandbox is an expensive,
 * process-scoped resource that should exist at most once per app.
 */
object Pravah {

    @Volatile
    private var client: PravahClient? = null

    /**
     * Prepares Pravah. Safe to call more than once; later calls are ignored so
     * an Activity recreation cannot discard a running sandbox.
     */
    fun initialize(context: Context, config: PravahConfig) {

        if (client != null) return

        synchronized(this) {
            if (client != null) return

            client = PravahClient(
                applicationContext = context.applicationContext,
                config = config,
            )

            Log.i(
                PRAVAH_LOG_TAG,
                "initialized: runtime version $PRAVAH_RUNTIME_VERSION, " +
                    "manifest ${config.manifestUrl}"
            )
        }
    }

    /** The Pravah runtime contract this build implements. */
    val runtimeVersion: String get() = PRAVAH_RUNTIME_VERSION

    val isInitialized: Boolean get() = client != null

    /** Performs one update check. Never throws; see [UpdateResult]. */
    suspend fun checkForUpdate(): UpdateResult {

        val active = client ?: return UpdateResult.Failed(
            reason = UpdateFailure.UNKNOWN,
            message = NOT_INITIALIZED_MESSAGE,
        )

        return active.checkForUpdate()
    }

    /** Loads the active patch and returns its UI. Never throws. */
    suspend fun loadPatch(): PatchLoadResult =
        client?.loadPatch() ?: notInitialized()

    /** Forwards a UI action to the running patch and returns the new UI. */
    suspend fun dispatchAction(action: String): PatchLoadResult =
        client?.dispatchAction(action) ?: notInitialized()

    /**
     * A snapshot of Pravah's state, or a fallback snapshot before initialization.
     */
    fun status(): PravahStatus = client?.status() ?: PravahStatus(
        patchVersion = BUNDLED_PATCH_VERSION,
        runtimeVersion = PRAVAH_RUNTIME_VERSION,
        source = PatchSource.NATIVE_FALLBACK,
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
    private fun notInitialized(): PatchLoadResult {

        Log.e(PRAVAH_LOG_TAG, NOT_INITIALIZED_MESSAGE)

        return PatchLoadResult.Unavailable(
            reason = FallbackReason.RUNTIME_UNAVAILABLE,
            message = NOT_INITIALIZED_MESSAGE,
        )
    }
}

private const val NOT_INITIALIZED_MESSAGE =
    "Pravah.initialize(context, config) was not called; falling back to native content"

/**
 * Holds the collaborators for one Pravah installation.
 *
 * Separate from the [Pravah] object so the wiring is explicit and the singleton
 * stays a thin facade.
 */
private class PravahClient(
    applicationContext: Context,
    private val config: PravahConfig,
) {

    private val store = PatchStore(
        context = applicationContext,
        bundledAssetName = config.bundledPatchAsset,
    )

    private val nativeBridge = NativeBridge(applicationContext)

    private val runtime = JavaScriptRuntime(
        context = applicationContext,
        executionTimeoutMillis = config.executionTimeoutMillis,
        onNativeMessage = nativeBridge::handle,
    )

    private val engine = PatchEngine(runtime)

    private val updater = PatchUpdater(
        manifestUrl = config.manifestUrl,
        downloader = PatchDownloader(
            connectTimeoutMillis = config.connectTimeoutMillis,
            readTimeoutMillis = config.readTimeoutMillis,
        ),
        store = store,
        supportedRuntimeVersion = PRAVAH_RUNTIME_VERSION,
        maxManifestSizeBytes = config.maxManifestSizeBytes,
        maxPatchSizeBytes = config.maxPatchSizeBytes,
    )

    /** Serialises patch loading so two loads cannot interleave in one isolate. */
    private val engineLock = Mutex()

    private var currentSource: PatchSource = PatchSource.NATIVE_FALLBACK

    suspend fun checkForUpdate(): UpdateResult = updater.checkForUpdate()

    suspend fun loadPatch(): PatchLoadResult = engineLock.withLock {

        if (store.isRemotelyDisabled) {
            Log.w(PRAVAH_LOG_TAG, "Pravah disabled, using fallback")
            currentSource = PatchSource.NATIVE_FALLBACK
            return@withLock PatchLoadResult.Unavailable(
                reason = FallbackReason.DISABLED,
                message = "Pravah is disabled by the published manifest",
            )
        }

        val isRemote = store.hasDownloadedPatch()

        val patchSource = try {
            if (isRemote) store.readDownloadedPatch() else store.readBundledPatch()
        } catch (e: Exception) {
            Log.w(PRAVAH_LOG_TAG, "no patch available, using fallback", e)
            currentSource = PatchSource.NATIVE_FALLBACK
            return@withLock PatchLoadResult.Unavailable(
                reason = FallbackReason.NO_PATCH_AVAILABLE,
                message = e.message ?: "No bundled or downloaded patch could be read",
            )
        }

        val origin = if (isRemote) PatchSource.REMOTE else PatchSource.BUNDLED

        renderInto(origin) { engine.load(patchSource) }
    }

    suspend fun dispatchAction(action: String): PatchLoadResult = engineLock.withLock {
        renderInto(currentSource) { engine.dispatch(action) }
    }

    /**
     * Runs a patch call and parses its UI, converting every failure into a
     * fallback outcome.
     *
     * The catch is deliberately broad. This is the boundary between remote code
     * and a real user's screen, and nothing a patch does may crash the host app.
     * Every failure is logged with its cause before being reported.
     */
    private suspend fun renderInto(
        origin: PatchSource,
        produceUiJson: suspend () -> String,
    ): PatchLoadResult {

        val uiJson = try {
            produceUiJson()
        } catch (e: PatchExecutionException) {
            return fallback(FallbackReason.EXECUTION_FAILED, "patch execution failed", e)
        } catch (e: Exception) {
            return fallback(FallbackReason.RUNTIME_UNAVAILABLE, "Pravah runtime unavailable", e)
        }

        val ui: PatchUiNode = try {
            PatchUiParser.parse(uiJson)
        } catch (e: Exception) {
            return fallback(FallbackReason.INVALID_PATCH_UI, "patch produced unusable UI", e)
        }

        currentSource = origin
        Log.i(PRAVAH_LOG_TAG, "patch loaded from $origin (version ${store.installedPatchVersion})")

        return PatchLoadResult.Loaded(ui = ui, source = origin)
    }

    private fun fallback(
        reason: FallbackReason,
        summary: String,
        cause: Exception,
    ): PatchLoadResult {

        Log.e(PRAVAH_LOG_TAG, "$summary, using fallback", cause)
        currentSource = PatchSource.NATIVE_FALLBACK

        return PatchLoadResult.Unavailable(
            reason = reason,
            message = cause.message ?: cause::class.java.simpleName,
        )
    }

    fun status(): PravahStatus = PravahStatus(
        patchVersion = store.installedPatchVersion,
        runtimeVersion = PRAVAH_RUNTIME_VERSION,
        source = currentSource,
        isRemotelyDisabled = store.isRemotelyDisabled,
    )

    suspend fun shutdown() = runtime.close()
}
