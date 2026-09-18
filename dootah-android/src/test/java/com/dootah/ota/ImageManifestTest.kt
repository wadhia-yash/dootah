package com.dootah.ota

import org.junit.Assert.*
import org.junit.Test

class ImageManifestTest {
    private val hash = "a".repeat(64)
    private fun json(images: String = "[]") = """{"schemaVersion":1,"bundleVersion":2,"runtimeVersion":"8","enabled":true,"url":"https://example.test/bundle.js","sha256":"$hash","images":$images}"""
    @Test fun `reads image information and normalizes digest`() {
        val manifest = BundleManifestParser.parse(json("""[{"id":"$hash","url":"https://example.test/photo","sha256":"${hash.uppercase()}"}]"""))
        assertEquals(listOf(BundleImage(hash, "https://example.test/photo", hash)), manifest.images)
    }
    @Test fun `rejects malformed duplicate insecure and mismatched images`() {
        val entry = """{"id":"$hash","url":"https://example.test/photo","sha256":"$hash"}"""
        listOf("{}", "null", "[{}]", "[$entry,$entry]", "[${entry.replace("https:", "http:")}]", "[${entry.replace("\"id\":\"$hash\"", "\"id\":\"../bad\"")}]").forEach {
            try { BundleManifestParser.parse(json(it)); fail("Accepted $it") } catch (_: BundleManifestException) { }
        }
    }
    @Test fun `manifest may contain zero images`() { assertTrue(BundleManifestParser.parse(json()).images.isEmpty()) }
}
