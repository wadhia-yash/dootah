package dev.dootah.runtime

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Test
import org.w3c.dom.Element

/** Phase 8: inherited Expo/RN manifest entries must not add permissions or providers to host apps. */
class ManifestSurfaceTest {
    private val android = "http://schemas.android.com/apk/res/android"
    private val tools = "http://schemas.android.com/tools"
    private val manifest = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(File("src/main/AndroidManifest.xml"))

    private fun elements(tag: String) = manifest.getElementsByTagName(tag).let { list ->
        (0 until list.length).map { list.item(it) as Element }
    }

    @Test fun onlyInternetIsRequestedAndInheritedSurfaceIsRemoved() {
        val permissions = elements("uses-permission").groupBy({ it.getAttributeNS(tools, "node") }, { it.getAttributeNS(android, "name") })
        assertEquals(listOf("android.permission.INTERNET"), permissions[""])
        assertEquals(setOf("android.permission.READ_EXTERNAL_STORAGE", "android.permission.WRITE_EXTERNAL_STORAGE",
            "android.permission.SYSTEM_ALERT_WINDOW"), permissions["remove"]!!.toSet())
        val providers = elements("provider").associate { it.getAttributeNS(android, "name") to it.getAttributeNS(tools, "node") }
        assertEquals(mapOf("expo.modules.filesystem.FileSystemFileProvider" to "remove"), providers)
    }
}
