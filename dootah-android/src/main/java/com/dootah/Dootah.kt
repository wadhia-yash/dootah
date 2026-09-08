package com.dootah

import android.content.Context
import android.util.Log
import com.dootah.bridge.NativeBridge
import com.dootah.ota.DOOTAH_RUNTIME_VERSION
import com.dootah.ota.ASSET_BUNDLE_VERSION
import com.dootah.ota.BundleDownloader
import com.dootah.ota.BundleExecutionException
import com.dootah.ota.BundleStore
import com.dootah.ota.BundleUpdater
import com.dootah.runtime.JavaScriptRuntime
import com.dootah.runtime.BundleEngine
import com.dootah.ui.BundleUiNode
import com.dootah.ui.BundleUiParser
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The entry point for integrating Dootah into an Android app.
 *
 * Call [initialize] once, then drive updates and rendering through the suspend
 * functions here. A host app never touches the JavaScript sandbox, the bundle
 * filesystem, manifest parsing, or hashing.
 *
 * A process-wide singleton because the JavaScript sandbox is an expensive,
 * process-scoped resource that should exist at most once per app.
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

    /** Loads the active bundle and returns its UI. Never throws. */
    suspend fun loadBundle(): BundleLoadResult =
        client?.loadBundle() ?: notInitialized()

    /** Forwards a UI action to the running bundle and returns the new UI. */
    suspend fun dispatchAction(action: String): BundleLoadResult =
        client?.dispatchAction(action) ?: notInitialized()

    /**
     * A snapshot of Dootah's state, or a fallback snapshot before initialization.
     */
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

    /** Serialises bundle loading so two loads cannot interleave in one isolate. */
    private val engineLock = Mutex()

    private var currentSource: BundleSource = BundleSource.NATIVE_FALLBACK

    suspend fun checkForUpdate(): UpdateResult = updater.checkForUpdate()

    suspend fun loadBundle(): BundleLoadResult = engineLock.withLock {

        if (store.isRemotelyDisabled) {
            Log.w(DOOTAH_LOG_TAG, "Dootah disabled, using fallback")
            currentSource = BundleSource.NATIVE_FALLBACK
            return@withLock BundleLoadResult.Unavailable(
                reason = FallbackReason.DISABLED,
                message = "Dootah is disabled by the published manifest",
            )
        }

        val isRemote = store.hasDownloadedBundle()

        val bundleSource = try {
            if (isRemote) store.readDownloadedBundle() else store.readAssetBundle()
        } catch (e: Exception) {
            Log.w(DOOTAH_LOG_TAG, "no bundle available, using fallback", e)
            currentSource = BundleSource.NATIVE_FALLBACK
            return@withLock BundleLoadResult.Unavailable(
                reason = FallbackReason.NO_BUNDLE_AVAILABLE,
                message = e.message ?: "No bundled or downloaded bundle could be read",
            )
        }

        val origin = if (isRemote) BundleSource.REMOTE else BundleSource.ASSET

        renderInto(origin) { engine.load(bundleSource) }
    }

    suspend fun dispatchAction(action: String): BundleLoadResult = engineLock.withLock {
        renderInto(currentSource) { engine.dispatch(action) }
    }

    /**
     * Runs a bundle call and parses its UI, converting every failure into a
     * fallback outcome.
     *
     * The catch is deliberately broad. This is the boundary between remote code
     * and a real user's screen, and nothing a bundle does may crash the host app.
     * Every failure is logged with its cause before being reported.
     */
    private suspend fun renderInto(
        origin: BundleSource,
        produceUiJson: suspend () -> String,
    ): BundleLoadResult {

        val uiJson = try {
            produceUiJson()
        } catch (e: BundleExecutionException) {
            return fallback(FallbackReason.EXECUTION_FAILED, "bundle execution failed", e)
        } catch (e: Exception) {
            return fallback(FallbackReason.RUNTIME_UNAVAILABLE, "Dootah runtime unavailable", e)
        }

        val ui: BundleUiNode = try {
            BundleUiParser.parse(uiJson)
        } catch (e: Exception) {
            return fallback(FallbackReason.INVALID_BUNDLE_UI, "bundle produced unusable UI", e)
        }

        currentSource = origin
        Log.i(DOOTAH_LOG_TAG, "bundle loaded from $origin (version ${store.installedBundleVersion})")

        return BundleLoadResult.Loaded(ui = ui, source = origin)
    }

    private fun fallback(
        reason: FallbackReason,
        summary: String,
        cause: Exception,
    ): BundleLoadResult {

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
