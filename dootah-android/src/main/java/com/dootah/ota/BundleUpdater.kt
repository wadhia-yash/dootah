package com.dootah.ota

import android.util.Log
import com.dootah.DOOTAH_LOG_TAG
import com.dootah.UpdateFailure
import com.dootah.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one update check: fetch the manifest, decide, download, verify, store.
 *
 * Every failure path returns an [UpdateResult] and leaves the installed bundle
 * exactly as it was. Nothing here deletes a working bundle, because an update
 * check that fails says nothing about whether the current bundle is good.
 */
internal class BundleUpdater(
    private val manifestUrl: String,
    private val downloader: BundleDownloader,
    private val store: BundleStore,
    private val supportedRuntimeVersion: String,
    private val maxManifestSizeBytes: Int,
    private val maxBundleSizeBytes: Int,
) {

    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            runCheck()
        } catch (e: BundleManifestException) {
            failed(UpdateFailure.INVALID_MANIFEST, e)
        } catch (e: BundleDownloadException) {
            failed(UpdateFailure.NETWORK, e)
        } catch (e: BundleVerificationException) {
            failed(UpdateFailure.FAILED_VERIFICATION, e)
        }
    }

    private fun runCheck(): UpdateResult {

        val manifest = BundleManifestParser.parse(fetchManifestJson())
        val installedVersion = store.installedBundleVersion

        Log.i(
            DOOTAH_LOG_TAG,
            "current bundle = $installedVersion, " +
                "remote bundle = ${manifest.bundleVersion}, " +
                "runtime version = $supportedRuntimeVersion, " +
                "enabled = ${manifest.enabled}"
        )

        return when (
            val decision = decideUpdate(
                manifest = manifest,
                installedBundleVersion = installedVersion,
                supportedRuntimeVersion = supportedRuntimeVersion,
            )
        ) {
            is UpdateDecision.Disabled -> {
                // Recorded so the switch keeps holding while offline, when a bad
                // bundle would otherwise be hardest to escape.
                store.isRemotelyDisabled = true
                Log.w(DOOTAH_LOG_TAG, "Dootah disabled by manifest, using the bundled asset")
                UpdateResult.Disabled
            }

            UpdateDecision.UpToDate -> {
                clearDisabledFlag()
                Log.i(DOOTAH_LOG_TAG, "no update, staying on bundle $installedVersion")
                UpdateResult.NoUpdate(installedVersion)
            }

            is UpdateDecision.Incompatible -> {
                clearDisabledFlag()
                Log.w(
                    DOOTAH_LOG_TAG,
                    "rejecting bundle ${decision.manifest.bundleVersion}: targets runtime " +
                        "'${decision.manifest.runtimeVersion}', this build implements " +
                        "'${decision.supportedRuntimeVersion}'. Keeping bundle $installedVersion."
                )
                UpdateResult.IncompatibleRuntime(
                    bundleRuntimeVersion = decision.manifest.runtimeVersion,
                    supportedRuntimeVersion = decision.supportedRuntimeVersion,
                )
            }

            is UpdateDecision.Download -> {
                clearDisabledFlag()
                install(decision.manifest, installedVersion)
            }
        }
    }

    private fun install(manifest: BundleManifest, installedVersion: Int): UpdateResult {

        Log.i(DOOTAH_LOG_TAG, "downloading bundle ${manifest.bundleVersion}")

        val payload = downloader.download(manifest.url, maxBundleSizeBytes)

        val actualDigest = sha256Hex(payload)

        if (actualDigest != manifest.sha256) {
            throw BundleVerificationException(
                "Digest mismatch for bundle ${manifest.bundleVersion}: manifest declared " +
                    "${manifest.sha256} but payload hashes to $actualDigest"
            )
        }

        Log.i(DOOTAH_LOG_TAG, "hash verified for bundle ${manifest.bundleVersion}")

        try {
            store.saveDownloadedBundle(payload, manifest.bundleVersion)
        } catch (e: Exception) {
            Log.e(DOOTAH_LOG_TAG, "could not store bundle ${manifest.bundleVersion}", e)
            return UpdateResult.Failed(
                reason = UpdateFailure.STORAGE,
                message = e.message ?: e::class.java.simpleName,
            )
        }

        Log.i(DOOTAH_LOG_TAG, "stored bundle ${manifest.bundleVersion}")

        return UpdateResult.Updated(
            bundleVersion = manifest.bundleVersion,
            previousBundleVersion = installedVersion,
        )
    }

    /** A manifest that reports enabled=true lifts a previous kill switch. */
    private fun clearDisabledFlag() {

        if (store.isRemotelyDisabled) {
            Log.i(DOOTAH_LOG_TAG, "Dootah re-enabled by manifest")
            store.isRemotelyDisabled = false
        }
    }

    private fun fetchManifestJson(): String {

        // Adds context but keeps the exception type, so an unreachable manifest
        // is reported as NETWORK rather than as a malformed document.
        val payload = try {
            downloader.download(manifestUrl, maxManifestSizeBytes)
        } catch (cause: BundleDownloadException) {
            throw BundleDownloadException(
                "Could not fetch manifest from $manifestUrl: ${cause.message}",
                cause,
            )
        }

        return payload.decodeToString()
    }

    private fun failed(reason: UpdateFailure, cause: BundleException): UpdateResult {

        Log.w(DOOTAH_LOG_TAG, "update check failed ($reason)", cause)

        return UpdateResult.Failed(
            reason = reason,
            message = cause.message ?: cause::class.java.simpleName,
        )
    }
}
