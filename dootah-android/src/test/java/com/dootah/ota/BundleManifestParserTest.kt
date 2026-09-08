package com.dootah.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val VALID_DIGEST =
    "ba409aa79ea36249d61490b613164cc202365ea5e796037f1ed8766ef54d62e4"

private fun manifestJson(
    schemaVersion: String = "1",
    bundleVersion: String = "4",
    runtimeVersion: String = "\"1\"",
    enabled: String = "true",
    url: String = "\"https://example.test/bundle.js\"",
    sha256: String = "\"$VALID_DIGEST\"",
): String = """
    {
      "schemaVersion": $schemaVersion,
      "bundleVersion": $bundleVersion,
      "runtimeVersion": $runtimeVersion,
      "enabled": $enabled,
      "url": $url,
      "sha256": $sha256
    }
""".trimIndent()

/** Asserts the parse fails and that the message names the offending field. */
private fun assertRejected(json: String, vararg expectedInMessage: String) {

    val failure = try {
        BundleManifestParser.parse(json)
        null
    } catch (e: BundleManifestException) {
        e
    }

    requireNotNull(failure) { "Expected manifest to be rejected but it parsed" }

    for (fragment in expectedInMessage) {
        assertTrue(
            "Message did not mention '$fragment': ${failure.message}",
            failure.message!!.contains(fragment),
        )
    }
}

class BundleManifestParserTest {

    @Test
    fun `parses a well formed manifest`() {

        val manifest = BundleManifestParser.parse(manifestJson())

        assertEquals(1, manifest.schemaVersion)
        assertEquals(4, manifest.bundleVersion)
        assertEquals("1", manifest.runtimeVersion)
        assertEquals("https://example.test/bundle.js", manifest.url)
        assertEquals(VALID_DIGEST, manifest.sha256)
        assertTrue(manifest.enabled)
        assertNull(manifest.signature)
    }

    @Test
    fun `reads an optional signature when present`() {

        val json = """
            {
              "schemaVersion": 1,
              "bundleVersion": 4,
              "runtimeVersion": "1",
              "enabled": true,
              "url": "https://example.test/bundle.js",
              "sha256": "$VALID_DIGEST",
              "signature": "abc123"
            }
        """.trimIndent()

        assertEquals("abc123", BundleManifestParser.parse(json).signature)
    }

    // --- fail closed on the legacy schema -------------------------------------

    @Test
    fun `rejects the legacy manifest schema with actionable guidance`() {

        val legacy = """
            {
              "version": 3,
              "url": "https://example.test/bundle.js",
              "sha256": "$VALID_DIGEST"
            }
        """.trimIndent()

        assertRejected(legacy, "Legacy manifest schema", "bundleVersion", "runtimeVersion")
    }

    @Test
    fun `rejects an unknown newer schema version rather than guessing`() {
        assertRejected(manifestJson(schemaVersion = "2"), "schemaVersion")
    }

    // --- structural validation ------------------------------------------------

    @Test
    fun `rejects malformed json`() {
        assertRejected("{ not json", "valid JSON")
    }

    @Test
    fun `rejects a non object root`() {
        assertRejected("[1, 2, 3]", "JSON object")
    }

    @Test
    fun `rejects a missing runtimeVersion`() {

        val json = """
            {
              "schemaVersion": 1,
              "bundleVersion": 4,
              "enabled": true,
              "url": "https://example.test/bundle.js",
              "sha256": "$VALID_DIGEST"
            }
        """.trimIndent()

        assertRejected(json, "runtimeVersion", "missing")
    }

    // --- kill switch ----------------------------------------------------------

    @Test
    fun `reads the kill switch when it is off`() {

        val manifest = BundleManifestParser.parse(manifestJson(enabled = "false"))

        assertFalse(manifest.enabled)
    }

    /**
     * A defaulted kill switch would fail open, which is the wrong direction for
     * a switch whose purpose is turning remote code off.
     */
    @Test
    fun `rejects a manifest with no kill switch field`() {

        val json = """
            {
              "schemaVersion": 1,
              "bundleVersion": 4,
              "runtimeVersion": "1",
              "url": "https://example.test/bundle.js",
              "sha256": "$VALID_DIGEST"
            }
        """.trimIndent()

        assertRejected(json, "enabled", "missing")
    }

    @Test
    fun `rejects a quoted kill switch value`() {
        assertRejected(manifestJson(enabled = "\"true\""), "enabled", "quoted string")
    }

    @Test
    fun `rejects a non boolean kill switch value`() {
        assertRejected(manifestJson(enabled = "7"), "enabled", "true or false")
    }

    @Test
    fun `rejects a blank runtimeVersion`() {
        assertRejected(manifestJson(runtimeVersion = "\"  \""), "runtimeVersion", "blank")
    }

    @Test
    fun `rejects a quoted bundleVersion`() {
        assertRejected(manifestJson(bundleVersion = "\"4\""), "bundleVersion", "number")
    }

    @Test
    fun `rejects a non positive bundleVersion`() {
        assertRejected(manifestJson(bundleVersion = "0"), "bundleVersion", "positive")
    }

    @Test
    fun `rejects a non scalar field`() {
        assertRejected(manifestJson(runtimeVersion = "{}"), "runtimeVersion", "scalar")
    }

    // --- url and digest -------------------------------------------------------

    @Test
    fun `rejects a plaintext http url`() {
        assertRejected(manifestJson(url = "\"http://example.test/bundle.js\""), "HTTPS")
    }

    @Test
    fun `rejects a digest of the wrong length`() {
        assertRejected(manifestJson(sha256 = "\"abc123\""), "sha256", "64 hex")
    }

    @Test
    fun `rejects a digest containing non hex characters`() {

        val notHex = "z".repeat(64)

        assertRejected(manifestJson(sha256 = "\"$notHex\""), "sha256")
    }

    @Test
    fun `normalises an uppercase digest so a valid bundle is not refused`() {

        val manifest = BundleManifestParser.parse(
            manifestJson(sha256 = "\"${VALID_DIGEST.uppercase()}\"")
        )

        assertEquals(VALID_DIGEST, manifest.sha256)
    }
}
