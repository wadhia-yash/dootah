package com.dootah.ui

import org.junit.Assert.*
import org.junit.Test

class ImagePainterRequirementsTest {
    private fun node(key: String) = BundleUiParser.parse("""{"ui":{"type":"component","adapter":"image","props":{"painter":{"k":"painterRes","key":"$key"}}},"commands":[]}""").ui
    @Test fun `APK drawable painters still require their native resource binding`() {
        assertEquals(listOf("drawable:icon"), node("drawable:icon").requirements().resources)
    }
    @Test fun `verified image references do not require a resource id in the APK`() {
        assertTrue(node("image:" + "a".repeat(64)).requirements().resources.isEmpty())
    }
    @Test fun `image reference cannot name an arbitrary file`() {
        assertThrows(IllegalArgumentException::class.java) { node("image:../file") }
    }
}
