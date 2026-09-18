package com.dootah.ota

import android.content.Context
import java.io.File

const val ASSET_BUNDLE_VERSION: Int = 1

/** Persists complete image updates separately from the APK fallback and kill switch. */
internal class BundleStore(private val context: Context, private val bundledAssetName: String, verifier: ManifestVerifier, selectionScope: String? = null) : UpdateStore {
    private val cacheScope = verifier.cacheScope + (selectionScope?.let { "-$it" } ?: "")
    private val updates = ImageUpdateStore(
        File(context.filesDir, "dootah/signed/${cacheScope}"),
        authenticate = {
            verifier.verify(it)
            if (it.runtimeVersion != DOOTAH_RUNTIME_VERSION || !it.enabled)
                throw BundleVerificationException("Stored update is incompatible or disabled")
        },
        validateImage = { decodeNativeImage(it).recycle() },
        report = { android.util.Log.i(com.dootah.DOOTAH_LOG_TAG, it) },
    )
    private val preferences = context.getSharedPreferences("dootah-signed-${cacheScope}", Context.MODE_PRIVATE)

    // A bad persisted proof must not throw from public status() or revive an unsigned update.
    override val installedBundleVersion: Int get() = try {
        if (updates.exists) updates.version else ASSET_BUNDLE_VERSION
    } catch (_: Exception) { ASSET_BUNDLE_VERSION }
    fun state() = try { updates.state() } catch (_: Exception) { ImageUpdateStore.State() }
    fun hasDownloadedBundle(): Boolean = updates.exists
    fun readDownloadedBundle(): String = updates.readBundle()
    fun readAssetBundle(): String = context.assets.open(bundledAssetName).bufferedReader().use { it.readText() }
    override fun install(manifest: BundleManifest, payload: ByteArray, download: (String, Int) -> ByteArray) =
        updates.stage(manifest, payload, download)
    fun activateCandidate() = updates.activateCandidate()
    fun prepareForLoad() = updates.prepareForLoad()
    fun failUnconfirmed(identity: String, reason: String) = updates.failUnconfirmed(identity, reason)
    override fun isBlocked(manifest: BundleManifest) = updates.isBlocked(manifest)
    fun rollbackTo(version: Int, reason: String): ImageUpdateStore.Update {
        check(!isRemotelyDisabled) { "Dootah is disabled by the publisher" }
        return updates.rollbackTo(version, reason)
    }
    fun resumeUpdates() = updates.resumeUpdates()
    fun confirmHealthy(identity: String) = updates.confirmHealthy(identity)
    fun readImage(hash: String): ByteArray = updates.readImage(hash)
    fun deleteDownloadedBundle() = updates.delete()

    override var isRemotelyDisabled: Boolean
        get() = preferences.getBoolean("remotely_disabled", false)
        set(value) { preferences.edit().putBoolean("remotely_disabled", value).commit() }
}
