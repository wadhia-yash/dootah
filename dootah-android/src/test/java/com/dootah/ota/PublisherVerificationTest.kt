package com.dootah.ota

import com.dootah.UpdateFailure
import com.dootah.UpdateResult
import dev.dootah.contract.BundleImages
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.Base64

class PublisherVerificationTest {
    @get:Rule val temporary = TemporaryFolder()
    private val keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
    private val publicKey = Base64.getEncoder().encodeToString(keys.public.encoded.takeLast(32).toByteArray())
    private val verifier = ManifestVerifier("example.app", publicKey)
    private val payload = (BundleImages.header(emptyList()) + "var bundle=1;").toByteArray()
    private fun manifest(version: Int = 2) = testManifest(bundleVersion = version, runtimeVersion = DOOTAH_RUNTIME_VERSION)
        .copy(appId = "example.app", sha256 = sha256Hex(payload))
    private fun sign(m: BundleManifest): BundleManifest {
        val signer = Signature.getInstance("Ed25519")
        signer.initSign(keys.private); signer.update(m.signedUpdate().signingBytes())
        return m.copy(signature = Base64.getEncoder().encodeToString(signer.sign()))
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected rejection") } catch (_: BundleVerificationException) { }
    }
    private fun store() = ImageUpdateStore(temporary.root, {}, authenticate = verifier::verify)

    @Test fun `valid publisher interoperates with Android verifier and signed offline cache`() {
        val m = sign(manifest()); verifier.verify(m)
        store().install(m, payload) { _, _ -> error("No images") }
        assertEquals(2, store().version)
        assertEquals(payload.decodeToString(), store().readBundle())
    }
    @Test fun `wrong public key is rejected`() {
        val wrong = KeyPairGenerator.getInstance("Ed25519").generateKeyPair()
        rejects { ManifestVerifier("example.app", Base64.getEncoder().encodeToString(wrong.public.encoded.takeLast(32).toByteArray())).verify(sign(manifest())) }
    }
    @Test fun `every control field and identity is authenticated`() {
        val hash = "a".repeat(64)
        val m = sign(manifest().copy(images = listOf(BundleImage(hash, "https://example.test/image", hash))))
        listOf(
            m.copy(schemaVersion = 2), m.copy(bundleVersion = 20), m.copy(runtimeVersion = "future"),
            m.copy(appId = "other.app"), m.copy(enabled = false), m.copy(url = "https://evil.test/bundle"),
            m.copy(sha256 = "b".repeat(64)), m.copy(images = emptyList()),
            m.copy(images = listOf(m.images.single().copy(id = "b".repeat(64)))),
            m.copy(images = listOf(m.images.single().copy(sha256 = "b".repeat(64)))),
            m.copy(images = listOf(m.images.single().copy(url = "https://evil.test/image"))),
        ).forEach { changed -> rejects { verifier.verify(changed) } }
    }
    @Test fun `malformed missing signatures and unconfigured keys fail closed`() {
        val m = sign(manifest())
        listOf(null, "not base64!", "", Base64.getEncoder().encodeToString(ByteArray(64)), "AAAA")
            .forEach { rejects { verifier.verify(m.copy(signature = it)) } }
        rejects { verifier.verify(m.copy(appId = null)) }
        rejects { ManifestVerifier("example.app", null).verify(m) }
        rejects { ManifestVerifier("example.app", "bad").verify(m) }
    }
    @Test fun `JSON formatting member order and image order do not affect verification`() {
        val a = "a".repeat(64); val b = "b".repeat(64)
        val m = sign(manifest().copy(images = listOf(BundleImage(b, "https://e.test/b", b), BundleImage(a, "https://e.test/a", a))))
        val json = m.signedUpdate().manifestJson(m.signature)
        val reversedFields = JsonObject(Json.parseToJsonElement(json).jsonObject.entries.reversed().associate { it.key to it.value })
        val parsed = BundleManifestParser.parse(reversedFields.toString().replace(",", ",\n  "))
        verifier.verify(parsed.copy(images = parsed.images.reversed()))
        assertArrayEquals(m.signedUpdate().signingBytes(), parsed.signedUpdate().signingBytes())
        assertEquals(m.signature, sign(m).signature)
    }
    @Test fun `rejection cannot mutate working update kill switch or download payload`() = runBlocking {
        val disk = store(); disk.install(sign(manifest()), payload) { _, _ -> error("Unexpected") }
        val before = File(temporary.root, "active.properties").readBytes()
        val state = object : UpdateStore {
            override val installedBundleVersion get() = disk.version
            override var isRemotelyDisabled = true
            override fun install(manifest: BundleManifest, payload: ByteArray, download: (String, Int) -> ByteArray) = disk.install(manifest, payload, download)
        }
        val signed = sign(manifest(3))
        for (bad in listOf(signed.copy(signature = null), signed.copy(enabled = false), signed.copy(bundleVersion = 4), signed.copy(sha256 = "a".repeat(64)))) {
            var requests = 0
            val updater = BundleUpdater("https://e.test/manifest", { url, _ ->
                assertEquals("https://e.test/manifest", url); requests++
                bad.signedUpdate().manifestJson(bad.signature).toByteArray()
            }, state, DOOTAH_RUNTIME_VERSION, 65536, 8000000, verifier)
            val result = updater.checkForUpdate() as UpdateResult.Failed
            assertEquals(UpdateFailure.FAILED_VERIFICATION, result.reason)
            assertEquals(1, requests); assertTrue(state.isRemotelyDisabled)
            assertArrayEquals(before, File(temporary.root, "active.properties").readBytes())
            assertEquals(payload.decodeToString(), disk.readBundle())
        }
    }
    @Test fun `valid signed update passes full updater transaction`() = runBlocking {
        val disk = store()
        val state = object : UpdateStore {
            override val installedBundleVersion get() = if (disk.exists) disk.version else 1
            override var isRemotelyDisabled = true
            override fun install(manifest: BundleManifest, payload: ByteArray, download: (String, Int) -> ByteArray) = disk.install(manifest, payload, download)
        }
        val m = sign(manifest())
        val updater = BundleUpdater("https://e.test/manifest", { url, _ ->
            if (url.endsWith("manifest")) m.signedUpdate().manifestJson(m.signature).toByteArray() else payload
        }, state, DOOTAH_RUNTIME_VERSION, 65536, 8000000, verifier)
        assertTrue(updater.checkForUpdate() is UpdateResult.Updated)
        assertFalse(state.isRemotelyDisabled); assertEquals(2, disk.version)
    }
    @Test fun `missing persisted proof or altered activation record is rejected`() {
        store().install(sign(manifest()), payload) { _, _ -> error("Unexpected") }
        val file = File(temporary.root, "active.properties"); val original = file.readText()
        file.writeText(original.replace("version=2", "version=3"))
        rejects { store().readBundle() }
        file.writeText(original.lineSequence().filterNot { it.startsWith("manifest=") }.joinToString("\n"))
        rejects { store().readBundle() }
    }
    @Test fun `signed metadata still requires actual payload hash`() {
        rejects { store().install(sign(manifest()), "changed".toByteArray()) { _, _ -> error("Unexpected") } }
        assertFalse(store().exists)
    }
    @Test fun `trusted signed disable changes only the kill switch`() = runBlocking {
        val disk = store(); disk.install(sign(manifest()), payload) { _, _ -> error("Unexpected") }
        val before = File(temporary.root, "active.properties").readBytes()
        val state = object : UpdateStore {
            override val installedBundleVersion get() = disk.version
            override var isRemotelyDisabled = false
            override fun install(manifest: BundleManifest, payload: ByteArray, download: (String, Int) -> ByteArray) = error("Disabled updates never install")
        }
        val m = sign(manifest(3).copy(enabled = false))
        val updater = BundleUpdater("https://e.test/manifest", { url, _ ->
            assertEquals("https://e.test/manifest", url)
            m.signedUpdate().manifestJson(m.signature).toByteArray()
        }, state, DOOTAH_RUNTIME_VERSION, 65536, 8000000, verifier)
        assertEquals(UpdateResult.Disabled, updater.checkForUpdate())
        assertTrue(state.isRemotelyDisabled)
        assertArrayEquals(before, File(temporary.root, "active.properties").readBytes())
    }
    @Test fun `signed image identities survive complete activation and cached proof verification`() {
        val image = "image payload for hash verification".toByteArray()
        val hash = sha256Hex(image)
        val bytes = (BundleImages.header(listOf(hash)) + "var bundle=2;").toByteArray()
        val m = sign(manifest().copy(sha256 = sha256Hex(bytes), images = listOf(BundleImage(hash, "https://e.test/image", hash))))
        store().install(m, bytes) { _, _ -> image }
        assertEquals(bytes.decodeToString(), store().readBundle())
        assertArrayEquals(image, store().readImage(hash))
    }

}
