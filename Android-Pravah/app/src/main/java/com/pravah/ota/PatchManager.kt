package com.pravah.ota

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.URL
import java.security.MessageDigest

class PatchManager(
    private val context: Context,
    private val manifestUrl: String
) {

    private val patchFile =
        File(context.filesDir, "pravah/patch.js")

    private val prefs =
        context.getSharedPreferences(
            "pravah",
            Context.MODE_PRIVATE
        )

    suspend fun checkForUpdate(): Boolean =
        withContext(Dispatchers.IO) {

            val manifest =
                JSONObject(URL(manifestUrl).readText())

            val remoteVersion =
                manifest.getInt("version")

            val currentVersion =
                prefs.getInt("patch_version", 1)

            if (remoteVersion <= currentVersion) {
                return@withContext false
            }

            val patch =
                URL(manifest.getString("url"))
                    .readText()

            val expectedHash =
                manifest.getString("sha256")

            if (sha256(patch) != expectedHash) {
                error("Patch hash verification failed")
            }

            patchFile.parentFile?.mkdirs()
            patchFile.writeText(patch)

            prefs.edit()
                .putInt("patch_version", remoteVersion)
                .apply()

            true
        }

    fun getActivePatch(): String {

        return if (patchFile.exists()) {
            patchFile.readText()
        } else {
            context.assets
                .open("patch.js")
                .bufferedReader()
                .use { it.readText() }
        }
    }

    fun rollback() {
        patchFile.delete()

        prefs.edit()
            .putInt("patch_version", 1)
            .apply()
    }

    private fun sha256(value: String): String {

        return MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") {
                "%02x".format(it)
            }
    }
}
