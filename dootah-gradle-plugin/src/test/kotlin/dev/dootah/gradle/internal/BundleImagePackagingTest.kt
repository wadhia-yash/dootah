package dev.dootah.gradle.internal

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

class BundleImagePackagingTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun `normal drawable source becomes a content addressed painter without embedding bytes`() {
        val drawable = temporary.root.resolve("drawable-nodpi/new_photo.png").apply { parentFile.mkdirs() }
        ImageIO.write(BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB), "png", drawable)
        val source = temporary.newFile("Bundle.kt").apply { writeText("PainterResourceProp(\"drawable:new_photo\")") }
        val images = BundleImagePackaging.collect(listOf(source), listOf(drawable))
        assertEquals(1, images.size)
        assertArrayEquals(drawable.readBytes(), images.single().bytes)
        assertEquals("PainterResourceProp(\"image:${images.single().hash}\")", BundleImagePackaging.rewrite(source.readText(), images))
        assertEquals("https://example.test/updates/images/${images.single().hash}", BundleImagePackaging.imageUrl("https://example.test/updates/bundle.js", images.single().hash))
    }
    @Test fun `APK XML and qualified resources retain original painter references`() {
        val xml = temporary.root.resolve("drawable/icon.xml").apply { parentFile.mkdirs(); writeText("<vector/>") }
        val source = temporary.newFile("Bundle.kt").apply { writeText("PainterResourceProp(\"drawable:icon\")") }
        val images = BundleImagePackaging.collect(listOf(source), listOf(xml))
        assertTrue(images.isEmpty())
        assertEquals(source.readText(), BundleImagePackaging.rewrite(source.readText(), images))
    }
    @Test fun `rejects corrupt raster before publication`() {
        val image = temporary.root.resolve("drawable-nodpi/bad.png").apply { parentFile.mkdirs(); writeText("bad") }
        val source = temporary.newFile("Bundle.kt").apply { writeText("PainterResourceProp(\"drawable:bad\")") }
        try { BundleImagePackaging.collect(listOf(source), listOf(image)); fail("Accepted corruption") }
        catch (_: IllegalArgumentException) { }
    }
}
