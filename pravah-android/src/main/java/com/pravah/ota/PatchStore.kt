package com.pravah.ota

import android.content.Context
import com.pravah.PRAVAH_LOG_TAG
import android.util.Log
import java.io.File

private const val PATCH_DIRECTORY_NAME = "pravah"
private const val PATCH_FILE_NAME = "patch.js"
private const val TEMP_PATCH_FILE_NAME = "patch.js.tmp"

private const val PREFERENCES_NAME = "pravah"
private const val KEY_PATCH_VERSION = "patch_version"
private const val KEY_REMOTELY_DISABLED = "remotely_disabled"

/**
 * The patch version attributed to the bundle shipped inside the APK.
 *
 * An assumption, not a fact: the bundled asset carries no metadata, so a
 * developer who refreshes it from a later patch will still see version 1
 * reported until the patch is downloaded again.
 */
const val BUNDLED_PATCH_VERSION: Int = 1

/**
 * Owns everything Pravah persists: the downloaded patch, the version it belongs
 * to, and whether the publisher has switched Pravah off.
 *
 * Kept separate from downloading and verifying so that the rules about what is
 * on disk live in one place.
 */
internal class PatchStore(
    private val context: Context,
    private val bundledAssetName: String,
) {

    private val patchDirectory = File(context.filesDir, PATCH_DIRECTORY_NAME)
    private val patchFile = File(patchDirectory, PATCH_FILE_NAME)

    private val preferences =
        context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    val installedPatchVersion: Int
        get() = preferences.getInt(KEY_PATCH_VERSION, BUNDLED_PATCH_VERSION)

    fun hasDownloadedPatch(): Boolean = patchFile.exists()

    fun readDownloadedPatch(): String = patchFile.readText()

    fun readBundledPatch(): String =
        context.assets
            .open(bundledAssetName)
            .bufferedReader()
            .use { reader -> reader.readText() }

    /**
     * Writes the patch to a temporary file and renames it into place.
     *
     * The rename is atomic on the same filesystem, so a process death during the
     * write cannot leave a half-written bundle where a valid one used to be.
     */
    fun saveDownloadedPatch(payload: ByteArray, patchVersion: Int) {

        patchDirectory.mkdirs()

        val temporaryFile = File(patchDirectory, TEMP_PATCH_FILE_NAME)
        temporaryFile.writeBytes(payload)

        if (!temporaryFile.renameTo(patchFile)) {
            temporaryFile.delete()
            throw PatchDownloadException(
                "Could not move the downloaded patch into ${patchFile.path}"
            )
        }

        // commit() rather than apply(): the recorded version must not disagree
        // with the file on disk if the process dies immediately afterwards.
        preferences.edit()
            .putInt(KEY_PATCH_VERSION, patchVersion)
            .commit()
    }

    fun deleteDownloadedPatch() {

        if (patchFile.exists() && !patchFile.delete()) {
            Log.w(PRAVAH_LOG_TAG, "could not delete patch file ${patchFile.path}")
        }

        preferences.edit()
            .putInt(KEY_PATCH_VERSION, BUNDLED_PATCH_VERSION)
            .commit()
    }

    /**
     * Whether the publisher has switched Pravah off.
     *
     * Persisted deliberately. A kill switch that lived only in memory would stop
     * working the moment the device went offline, which is exactly when a bad
     * patch is hardest to recover from. Once a manifest reports enabled=false,
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
