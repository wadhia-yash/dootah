package com.pravah.ota

import android.util.Log
import com.pravah.PRAVAH_LOG_TAG
import com.pravah.UpdateFailure
import com.pravah.UpdateResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Runs one update check: fetch the manifest, decide, download, verify, store.
 *
 * Every failure path returns an [UpdateResult] and leaves the installed patch
 * exactly as it was. Nothing here deletes a working patch, because an update
 * check that fails says nothing about whether the current patch is good.
 */
internal class PatchUpdater(
    private val manifestUrl: String,
    private val downloader: PatchDownloader,
    private val store: PatchStore,
    private val supportedRuntimeVersion: String,
    private val maxManifestSizeBytes: Int,
    private val maxPatchSizeBytes: Int,
) {

    suspend fun checkForUpdate(): UpdateResult = withContext(Dispatchers.IO) {
        try {
            runCheck()
        } catch (e: PatchManifestException) {
            failed(UpdateFailure.INVALID_MANIFEST, e)
        } catch (e: PatchDownloadException) {
            failed(UpdateFailure.NETWORK, e)
        } catch (e: PatchVerificationException) {
            failed(UpdateFailure.FAILED_VERIFICATION, e)
        }
    }

    private fun runCheck(): UpdateResult {

        val manifest = PatchManifestParser.parse(fetchManifestJson())
        val installedVersion = store.installedPatchVersion

        Log.i(
            PRAVAH_LOG_TAG,
            "current patch = $installedVersion, " +
                "remote patch = ${manifest.patchVersion}, " +
                "runtime version = $supportedRuntimeVersion, " +
                "enabled = ${manifest.enabled}"
        )

        return when (
            val decision = decideUpdate(
                manifest = manifest,
                installedPatchVersion = installedVersion,
                supportedRuntimeVersion = supportedRuntimeVersion,
            )
        ) {
            is UpdateDecision.Disabled -> {
                // Recorded so the switch keeps holding while offline, when a bad
                // patch would otherwise be hardest to escape.
                store.isRemotelyDisabled = true
                Log.w(PRAVAH_LOG_TAG, "Pravah disabled by manifest, using bundled fallback")
                UpdateResult.Disabled
            }

            UpdateDecision.UpToDate -> {
                clearDisabledFlag()
                Log.i(PRAVAH_LOG_TAG, "no update, staying on patch $installedVersion")
                UpdateResult.NoUpdate(installedVersion)
            }

            is UpdateDecision.Incompatible -> {
                clearDisabledFlag()
                Log.w(
                    PRAVAH_LOG_TAG,
                    "rejecting patch ${decision.manifest.patchVersion}: targets runtime " +
                        "'${decision.manifest.runtimeVersion}', this build implements " +
                        "'${decision.supportedRuntimeVersion}'. Keeping patch $installedVersion."
                )
                UpdateResult.IncompatibleRuntime(
                    patchRuntimeVersion = decision.manifest.runtimeVersion,
                    supportedRuntimeVersion = decision.supportedRuntimeVersion,
                )
            }

            is UpdateDecision.Download -> {
                clearDisabledFlag()
                install(decision.manifest, installedVersion)
            }
        }
    }

    private fun install(manifest: PatchManifest, installedVersion: Int): UpdateResult {

        Log.i(PRAVAH_LOG_TAG, "downloading patch ${manifest.patchVersion}")

        val payload = downloader.download(manifest.url, maxPatchSizeBytes)

        val actualDigest = sha256Hex(payload)

        if (actualDigest != manifest.sha256) {
            throw PatchVerificationException(
                "Digest mismatch for patch ${manifest.patchVersion}: manifest declared " +
                    "${manifest.sha256} but payload hashes to $actualDigest"
            )
        }

        Log.i(PRAVAH_LOG_TAG, "hash verified for patch ${manifest.patchVersion}")

        try {
            store.saveDownloadedPatch(payload, manifest.patchVersion)
        } catch (e: Exception) {
            Log.e(PRAVAH_LOG_TAG, "could not store patch ${manifest.patchVersion}", e)
            return UpdateResult.Failed(
                reason = UpdateFailure.STORAGE,
                message = e.message ?: e::class.java.simpleName,
            )
        }

        Log.i(PRAVAH_LOG_TAG, "stored patch ${manifest.patchVersion}")

        return UpdateResult.Updated(
            patchVersion = manifest.patchVersion,
            previousPatchVersion = installedVersion,
        )
    }

    /** A manifest that reports enabled=true lifts a previous kill switch. */
    private fun clearDisabledFlag() {

        if (store.isRemotelyDisabled) {
            Log.i(PRAVAH_LOG_TAG, "Pravah re-enabled by manifest")
            store.isRemotelyDisabled = false
        }
    }

    private fun fetchManifestJson(): String {

        // Adds context but keeps the exception type, so an unreachable manifest
        // is reported as NETWORK rather than as a malformed document.
        val payload = try {
            downloader.download(manifestUrl, maxManifestSizeBytes)
        } catch (cause: PatchDownloadException) {
            throw PatchDownloadException(
                "Could not fetch manifest from $manifestUrl: ${cause.message}",
                cause,
            )
        }

        return payload.decodeToString()
    }

    private fun failed(reason: UpdateFailure, cause: PatchException): UpdateResult {

        Log.w(PRAVAH_LOG_TAG, "update check failed ($reason)", cause)

        return UpdateResult.Failed(
            reason = reason,
            message = cause.message ?: cause::class.java.simpleName,
        )
    }
}
