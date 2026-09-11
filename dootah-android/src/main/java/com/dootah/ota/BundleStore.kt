package com.dootah.ota

import android.content.Context
import com.dootah.DOOTAH_LOG_TAG
import android.util.Log
import java.io.File

private const val BUNDLE_DIRECTORY_NAME = "dootah"
private const val BUNDLE_FILE_NAME = "bundle.js"
private const val TEMP_BUNDLE_PREFIX = "bundle-"

private const val PREFERENCES_NAME = "dootah"
private const val KEY_BUNDLE_VERSION = "bundle_version"
private const val KEY_REMOTELY_DISABLED = "remotely_disabled"

/**
 * The bundle version attributed to the bundle shipped inside the APK.
 *
 * An assumption, not a fact: the bundled asset carries no metadata, so a
 * developer who refreshes it from a later bundle will still see version 1
 * reported until a bundle is downloaded again.
 */
const val ASSET_BUNDLE_VERSION: Int = 1

/**
 * Owns everything Dootah persists: the downloaded bundle, the version it belongs
 * to, and whether the publisher has switched Dootah off.
 *
 * Kept separate from downloading and verifying so that the rules about what is
 * on disk live in one place.
 */
internal class BundleStore(
    private val context: Context,
    private val bundledAssetName: String,
) {

    private val bundleDirectory = File(context.filesDir, BUNDLE_DIRECTORY_NAME)
    private val bundleFile = File(bundleDirectory, BUNDLE_FILE_NAME)

    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val installedBundleVersion: Int
        get() = preferences.getInt(KEY_BUNDLE_VERSION, ASSET_BUNDLE_VERSION)

    fun hasDownloadedBundle(): Boolean = bundleFile.exists()

    fun readDownloadedBundle(): String = bundleFile.readText()

    fun readAssetBundle(): String =
        context.assets
            .open(bundledAssetName)
            .bufferedReader()
            .use { reader -> reader.readText() }

    /**
     * Writes the bundle to a temporary file and renames it into place.
     *
     * The rename is atomic on the same filesystem, so a process death during the
     * write cannot leave a half-written bundle where a valid one used to be.
     */
    fun saveDownloadedBundle(payload: ByteArray, bundleVersion: Int) {

        bundleDirectory.mkdirs()

        // A name of its own per write. A shared scratch file means two writers
        // clobber each other's bytes and then race to rename, and the survivor
        // could be a mixture of two bundles rather than either one.
        val temporaryFile = File.createTempFile(TEMP_BUNDLE_PREFIX, ".tmp", bundleDirectory)
        temporaryFile.writeBytes(payload)

        if (!temporaryFile.renameTo(bundleFile)) {
            temporaryFile.delete()
            throw BundleDownloadException(
                "Could not move the downloaded bundle into ${bundleFile.path}"
            )
        }

        // commit() rather than apply(): the recorded version must not disagree
        // with the file on disk if the process dies immediately afterwards.
        preferences.edit()
            .putInt(KEY_BUNDLE_VERSION, bundleVersion)
            .commit()
    }

    fun deleteDownloadedBundle() {

        if (bundleFile.exists() && !bundleFile.delete()) {
            Log.w(DOOTAH_LOG_TAG, "could not delete bundle file ${bundleFile.path}")
        }

        preferences.edit()
            .putInt(KEY_BUNDLE_VERSION, ASSET_BUNDLE_VERSION)
            .commit()
    }

    /**
     * Whether the publisher has switched Dootah off.
     *
     * Persisted deliberately. A kill switch that lived only in memory would stop
     * working the moment the device went offline, which is exactly when a bad
     * bundle is hardest to recover from. Once a manifest reports enabled=false,
     * the app keeps honouring it across launches until a manifest says otherwise.
     */
    var isRemotelyDisabled: Boolean
        get() = preferences.getBoolean(KEY_REMOTELY_DISABLED, false)
        set(value) {
            preferences.edit()
                .putBoolean(KEY_REMOTELY_DISABLED, value)
                .commit()
        }
}
