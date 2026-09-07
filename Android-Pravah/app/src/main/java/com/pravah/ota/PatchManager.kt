package com.pravah.ota

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

private const val LOG_TAG = "Pravah"

private const val CONNECT_TIMEOUT_MS = 10_000
private const val READ_TIMEOUT_MS = 15_000

private const val MAX_MANIFEST_SIZE_BYTES = 64 * 1024
private const val MAX_PATCH_SIZE_BYTES = 8 * 1024 * 1024
private const val DOWNLOAD_CHUNK_BYTES = 16 * 1024

private const val REQUIRED_URL_SCHEME = "https://"

/**
 * The patch version attributed to the bundle shipped inside the APK.
 *
 * This is an assumption, not a fact: the bundled asset carries no metadata, so a
 * developer who refreshes it from a later patch will have it reported as version
 * 1. Recording real metadata alongside the bundled asset is part of the storage
 * milestone.
 */
private const val BUNDLED_PATCH_VERSION = 1

private const val BUNDLED_PATCH_ASSET = "patch.js"
private const val PATCH_DIRECTORY_NAME = "pravah"
private const val PATCH_FILE_NAME = "patch.js"

private const val PREFERENCES_NAME = "pravah"
private const val KEY_PATCH_VERSION = "patch_version"

/**
 * Fetches, verifies and installs patches for the installed app.
 *
 * The decision-making is delegated to [PatchManifestParser] and [decideUpdate],
 * both pure and unit tested; what remains here is the I/O that carries those
 * decisions out.
 *
 * Known limitation: [installPatch] still replaces the running patch in place, so
 * a payload that verifies but fails to execute has already displaced its
 * predecessor. Candidate staging, health checks and last-known-good rollback are
 * the next milestone and will move that write behind a proving step.
 */
class PatchManager(
    private val context: Context,
    private val manifestUrl: String,
    private val supportedRuntimeVersion: String = PRAVAH_RUNTIME_VERSION,
) {

    private val patchFile =
        File(File(context.filesDir, PATCH_DIRECTORY_NAME), PATCH_FILE_NAME)

    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val installedPatchVersion: Int
        get() = preferences.getInt(KEY_PATCH_VERSION, BUNDLED_PATCH_VERSION)

    /**
     * Returns true when a newer patch was downloaded, verified and installed.
     *
     * Throws [PatchException] on failure. Callers must treat a failure here as
     * "no update this time" and keep running the installed patch: an update
     * check that cannot reach the network is not a reason to discard working
     * code.
     */
    suspend fun checkForUpdate(): Boolean = withContext(Dispatchers.IO) {

        val manifest = PatchManifestParser.parse(fetchManifestJson())
        val installedVersion = installedPatchVersion

        Log.i(
            LOG_TAG,
            "current patch = $installedVersion, " +
                "remote patch = ${manifest.patchVersion}, " +
                "runtime version = $supportedRuntimeVersion"
        )

        when (
            val decision = decideUpdate(
                manifest = manifest,
                installedPatchVersion = installedVersion,
                supportedRuntimeVersion = supportedRuntimeVersion,
            )
        ) {
            UpdateDecision.UpToDate -> {
                Log.i(LOG_TAG, "already up to date")
                false
            }

            is UpdateDecision.Incompatible -> {
                Log.w(
                    LOG_TAG,
                    "rejecting patch ${decision.manifest.patchVersion}: it targets " +
                        "runtime '${decision.manifest.runtimeVersion}' but this build " +
                        "implements '${decision.supportedRuntimeVersion}'. " +
                        "Keeping patch $installedVersion."
                )
                false
            }

            is UpdateDecision.Download -> {
                installPatch(decision.manifest)
                true
            }
        }
    }

    /** The patch source to execute: the downloaded patch, else the bundled one. */
    suspend fun getActivePatch(): String = withContext(Dispatchers.IO) {

        if (patchFile.exists()) {
            patchFile.readText()
        } else {
            readBundledPatch()
        }
    }

    /**
     * Deletes the downloaded patch so the app falls back to the bundled asset.
     *
     * Named for what it actually does. This is not a rollback: it discards the
     * only downloaded patch rather than reinstating the last known good one, so
     * a device running patch 7 drops all the way back to whatever shipped in the
     * APK. Genuine last-known-good rollback arrives with candidate staging.
     */
    suspend fun discardDownloadedPatch() = withContext(Dispatchers.IO) {

        if (patchFile.exists() && !patchFile.delete()) {
            Log.w(LOG_TAG, "could not delete patch file ${patchFile.path}")
        }

        // commit() rather than apply(): the version on disk and the patch file
        // must not disagree if the process dies immediately afterwards.
        preferences.edit()
            .putInt(KEY_PATCH_VERSION, BUNDLED_PATCH_VERSION)
            .commit()

        Log.w(
            LOG_TAG,
            "discarded downloaded patch, reverted to bundled patch $BUNDLED_PATCH_VERSION"
        )
    }

    private fun installPatch(manifest: PatchManifest) {

        Log.i(LOG_TAG, "downloading patch ${manifest.patchVersion}")

        val payload = download(manifest.url, MAX_PATCH_SIZE_BYTES)

        verifyDigest(payload, manifest)
        Log.i(LOG_TAG, "SHA-256 verified")

        patchFile.parentFile?.mkdirs()
        patchFile.writeBytes(payload)

        preferences.edit()
            .putInt(KEY_PATCH_VERSION, manifest.patchVersion)
            .commit()

        Log.i(LOG_TAG, "activated patch ${manifest.patchVersion}")
    }

    /**
     * Compares the digest of the bytes exactly as received.
     *
     * The previous implementation decoded the response to a String and then
     * re-encoded it to hash, so any BOM or non-UTF-8 byte produced a digest that
     * disagreed with the publisher's for reasons no log would explain.
     */
    private fun verifyDigest(payload: ByteArray, manifest: PatchManifest) {

        val actualDigest = sha256Hex(payload)

        if (actualDigest != manifest.sha256) {
            throw PatchVerificationException(
                "Digest mismatch for patch ${manifest.patchVersion}: " +
                    "manifest declared ${manifest.sha256} but payload hashes to $actualDigest"
            )
        }
    }

    private fun fetchManifestJson(): String {

        requireHttps(manifestUrl, "manifest URL")

        val payload = try {
            download(manifestUrl, MAX_MANIFEST_SIZE_BYTES)
        } catch (cause: PatchDownloadException) {
            throw PatchManifestException(
                "Could not fetch manifest from $manifestUrl",
                cause,
            )
        }

        return payload.decodeToString()
    }

    private fun download(url: String, maxBytes: Int): ByteArray {

        val connection = try {
            (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
            }
        } catch (cause: IOException) {
            throw PatchDownloadException("Could not open connection to $url", cause)
        }

        try {
            val status = connection.responseCode

            if (status != HttpURLConnection.HTTP_OK) {
                throw PatchDownloadException("Request for $url returned HTTP $status")
            }

            // Trust the declared length only to reject early; the read below is
            // capped regardless, since the header is attacker-controlled.
            val declaredLength = connection.contentLength

            if (declaredLength > maxBytes) {
                throw PatchDownloadException(
                    "$url declares $declaredLength bytes, over the $maxBytes byte limit"
                )
            }

            return connection.inputStream.use { stream ->
                stream.readAtMost(maxBytes, url)
            }
        } catch (cause: IOException) {
            throw PatchDownloadException("Could not read $url", cause)
        } finally {
            connection.disconnect()
        }
    }

    private fun readBundledPatch(): String =
        context.assets
            .open(BUNDLED_PATCH_ASSET)
            .bufferedReader()
            .use { it.readText() }
}

private fun requireHttps(url: String, description: String) {

    if (!url.startsWith(REQUIRED_URL_SCHEME)) {
        throw PatchManifestException("$description must use HTTPS but was '$url'")
    }
}

/**
 * Reads the stream, refusing to buffer more than [maxBytes].
 *
 * A response with no Content-Length, or a dishonest one, would otherwise be read
 * into memory until the process died.
 */
private fun InputStream.readAtMost(maxBytes: Int, url: String): ByteArray {

    val buffer = ByteArrayOutputStream()
    val chunk = ByteArray(DOWNLOAD_CHUNK_BYTES)

    while (true) {
        val bytesRead = read(chunk)

        if (bytesRead == -1) break

        if (buffer.size() + bytesRead > maxBytes) {
            throw PatchDownloadException(
                "Payload from $url exceeds the $maxBytes byte limit"
            )
        }

        buffer.write(chunk, 0, bytesRead)
    }

    return buffer.toByteArray()
}

private fun sha256Hex(payload: ByteArray): String =
    MessageDigest.getInstance("SHA-256")
        .digest(payload)
        .joinToString("") { byte -> "%02x".format(byte) }
