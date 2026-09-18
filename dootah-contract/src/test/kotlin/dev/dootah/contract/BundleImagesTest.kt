package dev.dootah.contract

import org.junit.Assert.*
import org.junit.Test

class BundleImagesTest {
    @Test fun `dependencies are deterministic and distinct`() {
        val a = "a".repeat(64); val b = "b".repeat(64)
        assertEquals("// dootah-images:$a,$b\n", BundleImages.header(listOf(b, a, a)))
        assertEquals(setOf(a, b), BundleImages.required(BundleImages.header(listOf(b, a)) + "var x=1;"))
        assertTrue(BundleImages.required(BundleImages.header(emptyList())).isEmpty())
    }
    @Test fun `rejects missing dependencies and path shaped image ids`() {
        assertThrows(IllegalArgumentException::class.java) { BundleImages.required("var x=1;") }
        assertThrows(IllegalArgumentException::class.java) { BundleImages.required("// dootah-images:../file\n") }
        assertThrows(IllegalArgumentException::class.java) { BundleImages.hash("image:../file") }
        assertFalse(BundleImages.isImage("drawable:photo"))
    }
}
