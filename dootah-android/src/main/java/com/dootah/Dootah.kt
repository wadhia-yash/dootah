package com.dootah

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
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
import kotlinx.coroutines.CancellationException
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
 * every intercepted Compose screen in the app shares one loaded bundle.
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

    internal var recoveryGeneration by mutableStateOf(0)
        private set
    internal fun recovered() { recoveryGeneration++ }

    internal fun imageBytes(hash: String): ByteArray? = client?.imageBytes(hash)

    /** Native operator API: verified retained healthy versions only; pauses automatic upgrades. */
    suspend fun rollbackTo(bundleVersion: Int, reason: String): ManualRollbackResult =
        client?.rollbackTo(bundleVersion, reason) ?: ManualRollbackResult.Rejected(NOT_INITIALIZED_MESSAGE)

    /** Clears the manual pause. Does not clear quarantine or perform a download. */
    suspend fun resumeUpdates(): Boolean = client?.resumeUpdates() ?: false

    fun updateHistory(): DootahUpdateHistory = client?.history()
        ?: DootahUpdateHistory(emptyList(), emptyList(), false)

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

    private val verifier = com.dootah.ota.ManifestVerifier(
        config.appId ?: applicationContext.packageName, config.trustedPublicKey,
    )

    private val checkRequest = config.channel?.let { channel ->
        @Suppress("DEPRECATION")
        val info = applicationContext.packageManager.getPackageInfo(applicationContext.packageName, 0)
        val appVersion = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        com.dootah.ota.UpdateCheckRequest(config.appId ?: applicationContext.packageName,
            DOOTAH_RUNTIME_VERSION, appVersion, channel) {
            com.dootah.ota.InstallationIdentity.readOrCreate(java.io.File(applicationContext.noBackupFilesDir, "dootah"))
        }
    }

    private val store = BundleStore(
        context = applicationContext,
        bundledAssetName = config.assetBundleName,
        verifier = verifier,
        selectionScope = checkRequest?.cacheScope,
    )

    fun imageBytes(hash: String): ByteArray? = try { store.readImage(hash) }
        catch (e: Exception) { Log.w(DOOTAH_LOG_TAG, "Image unavailable: $hash", e); null }

    private val nativeBridge = NativeBridge(applicationContext)

    private val runtime = JavaScriptRuntime(
        context = applicationContext,
        executionTimeoutMillis = config.executionTimeoutMillis,
        onNativeMessage = nativeBridge::handle,
    )

    private val engine = BundleEngine(runtime)

    private val downloader = BundleDownloader(config.connectTimeoutMillis, config.readTimeoutMillis)
    private val updater = BundleUpdater(
        manifestUrl = config.manifestUrl,
        download = downloader::download,
        store = store,
        supportedRuntimeVersion = DOOTAH_RUNTIME_VERSION,
        maxManifestSizeBytes = config.maxManifestSizeBytes,
        maxBundleSizeBytes = config.maxBundleSizeBytes,
        verifier = verifier,
        checkRequest = checkRequest,
        downloadCheck = downloader::downloadCheck,
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

    private var loadedIdentity: String? = null

    private var health: com.dootah.ota.RemoteHealth? = null

    private var currentSource: BundleSource = BundleSource.NATIVE_FALLBACK

    /**
     * Runs one update check, and only one at a time.
     *
     * Every intercepted Compose screen checks when it appears, so an app showing two of
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
        if (result is UpdateResult.Updated || result is UpdateResult.Disabled) {
            engineLock.withLock { health?.failed(); loadedScreenIds = null }
        }

        result
    }

    suspend fun rollbackTo(version: Int, reason: String): ManualRollbackResult = updateLock.withLock {
        engineLock.withLock {
            try {
                val target = store.rollbackTo(version, reason)
                health?.failed()
                health = null
                loadedIdentity = null
                loadedScreenIds = null
                currentSource = BundleSource.NATIVE_FALLBACK
                Dootah.recovered()
                ManualRollbackResult.Applied(target.version)
            } catch (e: Exception) {
                Log.w(DOOTAH_LOG_TAG, "Manual rollback rejected", e)
                ManualRollbackResult.Rejected(e.message ?: "Rollback rejected")
            }
        }
    }

    suspend fun resumeUpdates(): Boolean = updateLock.withLock {
        try { store.resumeUpdates(); true }
        catch (e: Exception) { Log.w(DOOTAH_LOG_TAG, "Resume rejected", e); false }
    }

    fun history(): DootahUpdateHistory = store.state().let { state ->
        DootahUpdateHistory(state.history, state.retained.map {
            RetainedHealthyUpdate(it.version, it.identity, it.hash)
        }, state.paused)
    }

    suspend fun renderScreen(screenId: String, argumentsJson: String): BundleLoadResult = engineLock.withLock {
        renderLocked(screenId, argumentsJson)
    }

    private suspend fun renderLocked(screenId: String, argumentsJson: String): BundleLoadResult {
        val before = Dootah.recoveryGeneration
        val result = when (val availability = ensureLoaded()) {
            is BundleAvailability.Unavailable -> availability.asResult()
            is BundleAvailability.Ready -> availability.requiring(screenId)
                ?: respond { engine.render(screenId, argumentsJson) }
        }
        // One recovery render; never replay a user action or its native commands.
        return if (result is BundleLoadResult.Unavailable && Dootah.recoveryGeneration != before)
            renderLocked(screenId, argumentsJson) else result
    }

    suspend fun dispatchAction(screenId: String, action: String, argumentsJson: String): BundleLoadResult = engineLock.withLock {
        val before = Dootah.recoveryGeneration
        val result = when (val availability = ensureLoaded()) {
            is BundleAvailability.Unavailable -> availability.asResult()
            is BundleAvailability.Ready -> availability.requiring(screenId)
                ?: respond { engine.dispatch(screenId, action, argumentsJson) }
        }
        if (result is BundleLoadResult.Unavailable && Dootah.recoveryGeneration != before)
            renderLocked(screenId, argumentsJson) else result
    }

    private fun recover(identity: String?, reason: String): Boolean {
        if (identity == null) return false
        return try {
            if (!store.failUnconfirmed(identity, reason)) return false
            health?.failed()
            health = null
            loadedIdentity = null
            loadedScreenIds = null
            currentSource = BundleSource.NATIVE_FALLBACK
            Dootah.recovered()
            true
        } catch (e: Exception) {
            // No unrecorded retry if storage cannot commit recovery. The durable attempt
            // remains for the next process; this process stays on native fallback.
            Log.e(DOOTAH_LOG_TAG, "Recovery could not be persisted; using native fallback", e)
            health?.failed()
            false
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
            health?.failed()
            loadedScreenIds = null
            return BundleAvailability.Unavailable(
                reason = FallbackReason.DISABLED,
                message = "Dootah is disabled by the published manifest",
            )
        }

        loadedScreenIds?.let { return BundleAvailability.Ready(it) }

        val isRemote = store.hasDownloadedBundle()

        var remoteIdentity: String? = null
        health?.failed()
        health = null
        val bundleSource = try {
            if (isRemote) {
                val selected = store.prepareForLoad()
                    ?: return loadFailure(FallbackReason.NO_BUNDLE_AVAILABLE, "No remote recovery", IllegalStateException("Using APK fallback"))
                remoteIdentity = selected.identity
            }
            loadedIdentity = remoteIdentity
            if (isRemote) store.readDownloadedBundle() else store.readAssetBundle()
        } catch (e: Exception) {
            recover(remoteIdentity, "payload")
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
            remoteIdentity?.let { identity ->
                health = com.dootah.ota.RemoteHealth { store.confirmHealthy(identity) }.also { it.initialized() }
            }

            Log.i(
                DOOTAH_LOG_TAG,
                "bundle loaded from $currentSource " +
                    "(version ${store.installedBundleVersion}), " +
                    "screens: ${screens.joinToString()}"
            )

            BundleAvailability.Ready(screens)
        } catch (e: CancellationException) {
            throw e
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
        recover(loadedIdentity, "initialization")

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
        } catch (e: CancellationException) {
            throw e
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
        ).also { result ->
            if (currentSource == BundleSource.REMOTE) {
                val session = health
                val identity = loadedIdentity
                val ready = session?.executed()
                result.nativeReady = {
                    try { ready?.invoke() }
                    catch (e: Exception) { Log.w(DOOTAH_LOG_TAG, "Health confirmation not persisted", e) }
                }
                result.nativeFailed = {
                    engineLock.withLock {
                        session?.failed()
                        recover(identity, "readiness")
                    }
                }
            }
        }
    }

    private fun failure(
        reason: FallbackReason,
        summary: String,
        cause: Exception,
    ): BundleLoadResult.Unavailable {

        Log.e(DOOTAH_LOG_TAG, "$summary, using fallback", cause)
        health?.failed()
        recover(loadedIdentity, "execution")
        currentSource = BundleSource.NATIVE_FALLBACK

        return BundleLoadResult.Unavailable(
            reason = reason,
            message = cause.message ?: cause::class.java.simpleName,
        )
    }

    fun status(): DootahStatus {
        val state = store.state()
        return DootahStatus(
            bundleVersion = store.installedBundleVersion,
            runtimeVersion = DOOTAH_RUNTIME_VERSION,
            source = currentSource,
            isRemotelyDisabled = store.isRemotelyDisabled,
            candidateVersion = state.candidate?.version,
            activeVersion = state.active?.version,
            lastKnownGoodVersion = state.lastKnownGood?.version,
            activeConfirmedHealthy = state.confirmedHealthy,
            updatesPaused = state.paused,
        )
    }

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
