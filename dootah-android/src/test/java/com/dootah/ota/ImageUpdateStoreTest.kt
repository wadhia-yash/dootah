package com.dootah.ota

import dev.dootah.contract.BundleImages
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

class ImageUpdateStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val image = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", it)
    }.toByteArray()
    private val hash get() = sha256Hex(image)
    private fun store() = ImageUpdateStore(temporary.root, validateImage = { bytes ->
        requireNotNull(ImageIO.read(bytes.inputStream()))
    })
    private fun payload(vararg hashes: String) = (BundleImages.header(hashes.toList()) + "var bundle = 1;").toByteArray()
    private fun manifest(bytes: ByteArray, version: Int = 2, images: List<BundleImage> = listOf(
        BundleImage(hash, "https://example.test/image", hash)
    )) = testManifest(bundleVersion = version, runtimeVersion = DOOTAH_RUNTIME_VERSION)
        .copy(sha256 = sha256Hex(bytes), images = images)

    private fun baseline(store: ImageUpdateStore) {
        val bytes = payload()
        store.install(manifest(bytes, images = emptyList()), bytes) { _, _ -> error("No download expected") }
    }
    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected verification failure") } catch (_: BundleVerificationException) { }
    }

    @Test fun `downloads verifies and activates bundle and image together`() {
        val store = store()
        val bytes = payload(hash)
        var downloads = 0
        store.install(manifest(bytes), bytes) { url, limit ->
            assertEquals("https://example.test/image", url)
            assertEquals(8 * 1024 * 1024, limit)
            assertFalse(store.exists)
            downloads++; image
        }
        assertEquals(1, downloads)
        assertEquals(2, store.version)
        assertEquals(bytes.decodeToString(), store.readBundle())
        assertArrayEquals(image, store.readImage(hash))
    }

    @Test fun `wrong hash keeps previous update`() {
        val store = store(); baseline(store)
        val bytes = payload(hash)
        rejects { store.install(manifest(bytes, 3), bytes) { _, _ -> "bad".toByteArray() } }
        assertEquals(2, store.version)
        assertEquals(payload().decodeToString(), store.readBundle())
    }

    @Test fun `correct hash but corrupt image keeps previous update`() {
        val store = store(); baseline(store)
        val corrupt = "not an image".toByteArray()
        val digest = sha256Hex(corrupt)
        val bytes = payload(digest)
        rejects {
            store.install(manifest(bytes, 3, listOf(BundleImage(digest, "https://example.test/bad", digest))), bytes) { _, _ -> corrupt }
        }
        assertEquals(2, store.version)
    }

    @Test fun `cached image reused across updates and offline process restart`() {
        val bytes = payload(hash)
        store().install(manifest(bytes), bytes) { _, _ -> image }
        val restarted = store()
        assertEquals(bytes.decodeToString(), restarted.readBundle())
        assertArrayEquals(image, restarted.readImage(hash))
        restarted.install(manifest(bytes, 3), bytes) { _, _ -> error("Offline - cache must be reused") }
        assertEquals(3, store().version)
    }

    @Test fun `missing required image prevents activation`() {
        val store = store(); baseline(store)
        val bytes = payload(hash)
        rejects { store.install(manifest(bytes, 3, emptyList()), bytes) { _, _ -> image } }
        assertEquals(2, store.version)
    }

    @Test fun `failed image download preserves previous update`() {
        val store = store(); baseline(store)
        val bytes = payload(hash)
        try {
            store.install(manifest(bytes, 3), bytes) { _, _ -> throw BundleDownloadException("Unavailable") }
            fail("Expected download failure")
        } catch (_: BundleDownloadException) { }
        assertEquals(2, store.version)
        assertEquals(payload().decodeToString(), store.readBundle())
    }

    @Test fun `tampered offline cache is never used`() {
        val bytes = payload(hash)
        store().install(manifest(bytes), bytes) { _, _ -> image }
        temporary.root.resolve("images/$hash").writeText("corrupted")
        rejects { store().readImage(hash) }
        rejects { store().readBundle() }
    }

    @Test fun `zero image update activates without download`() {
        val store = store(); baseline(store)
        assertEquals(payload().decodeToString(), store.readBundle())
    }

    @Test fun `failed second dependency cannot activate partially staged update`() {
        val store = store(); baseline(store)
        val other = "missing".toByteArray(); val second = sha256Hex(other)
        val bytes = payload(hash, second)
        val images = listOf(BundleImage(hash, "https://example.test/ok", hash), BundleImage(second, "https://example.test/fail", second))
        rejects { store.install(manifest(bytes, 3, images), bytes) { url, _ -> if (url.endsWith("ok")) image else image } }
        assertEquals(2, store.version)
        assertEquals(payload().decodeToString(), store.readBundle())
    }
}

/** Existing payload tests explicitly select the staged update before reading it. */
internal fun ImageUpdateStore.install(manifest: BundleManifest, payload: ByteArray, download: (String, Int) -> ByteArray) {
    stage(manifest, payload, download)
    activateCandidate()
}
