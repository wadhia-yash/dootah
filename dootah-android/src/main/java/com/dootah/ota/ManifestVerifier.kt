package com.dootah.ota

import com.google.crypto.tink.subtle.Ed25519Verify
import dev.dootah.contract.SignedImage
import dev.dootah.contract.SignedUpdate
import java.util.Base64

internal fun BundleManifest.signedUpdate() = SignedUpdate(
    schemaVersion, appId ?: throw BundleVerificationException("Missing app identity"),
    runtimeVersion, bundleVersion, enabled, url, sha256,
    images.map { SignedImage(it.id, it.url, it.sha256) },
)

/** No unsigned OTA mode. Invalid configuration disables OTA without breaking native startup. */
internal class ManifestVerifier(private val appId: String, private val publicKey: String?) {
    // A key/app change must not revive a cache or kill switch authenticated under another policy.
    val cacheScope = sha256Hex("$appId\n${publicKey.orEmpty()}".toByteArray())

    fun verify(manifest: BundleManifest) {
        try {
            require(appId.isNotBlank() && manifest.appId == appId)
            val key = Base64.getDecoder().decode(requireNotNull(publicKey))
            require(key.size == 32)
            val signature = Base64.getDecoder().decode(requireNotNull(manifest.signature))
            require(signature.size == 64)
            Ed25519Verify(key).verify(signature, manifest.signedUpdate().signingBytes())
        } catch (e: Exception) {
            throw BundleVerificationException("Publisher signature verification failed", e)
        }
    }
}
