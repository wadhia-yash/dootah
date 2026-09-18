package com.dootah.ota

import android.graphics.Bitmap
import org.junit.Test
import org.junit.Assert.*
import androidx.test.platform.app.InstrumentationRegistry
import java.io.ByteArrayOutputStream

class NativeImageTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun png(): ByteArray = ByteArrayOutputStream().also { output ->
        Bitmap.createBitmap(3, 2, Bitmap.Config.ARGB_8888).also {
            it.eraseColor(android.graphics.Color.RED)
            it.compress(Bitmap.CompressFormat.PNG, 100, output)
            it.recycle()
        }
    }.toByteArray()

    @Test fun testNativeDecode() {
        val bitmap = decodeNativeImage(png())
        assertEquals(3, bitmap.width)
        assertEquals(2, bitmap.height)
        assertEquals(android.graphics.Color.RED, bitmap.getPixel(1, 1))
        bitmap.recycle()
    }
    @Test fun testCorruptAndTruncatedPngRejected() {
        val bytes = png()
        val corrupt = bytes.clone().apply { this[40] = (this[40].toInt() xor 1).toByte() }
        for (invalid in listOf(corrupt, bytes.copyOf(bytes.size - 12), "garbage".toByteArray())) {
            try { decodeNativeImage(invalid); fail("Corrupt image accepted") }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun testNativeStoreOfflineRestart() {
        val directory = java.io.File(context.cacheDir, "image-test-${System.nanoTime()}")
        try {
            val bytes = png()
            val hash = sha256Hex(bytes)
            val bundle = (dev.dootah.contract.BundleImages.header(listOf(hash)) + "var test = 1;").toByteArray()
            val manifest = BundleManifest(1, 2, "8", true, "https://example.test/bundle.js", sha256Hex(bundle),
                images = listOf(BundleImage(hash, "https://example.test/image", hash)))
            ImageUpdateStore(directory, validateImage = { decodeNativeImage(it).recycle() }).apply {
                stage(manifest, bundle) { _, _ -> bytes }
                activateCandidate()
            }
            val restarted = ImageUpdateStore(directory, validateImage = { decodeNativeImage(it).recycle() })
            assertEquals(bundle.decodeToString(), restarted.readBundle())
            assertTrue(bytes.contentEquals(restarted.readImage(hash)))
            restarted.stage(manifest.copy(bundleVersion = 3), bundle) { _, _ -> error("Network used offline") }
            restarted.activateCandidate()
            assertEquals(3, restarted.version)
        } finally { directory.deleteRecursively() }
    }
}
