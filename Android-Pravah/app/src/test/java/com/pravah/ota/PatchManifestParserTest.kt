package com.pravah.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val VALID_DIGEST =
    "ba409aa79ea36249d61490b613164cc202365ea5e796037f1ed8766ef54d62e4"

private fun manifestJson(
    schemaVersion: String = "1",
    patchVersion: String = "4",
    runtimeVersion: String = "\"1\"",
    url: String = "\"https://example.test/patch.js\"",
    sha256: String = "\"$VALID_DIGEST\"",
): String = """
    {
      "schemaVersion": $schemaVersion,
      "patchVersion": $patchVersion,
      "runtimeVersion": $runtimeVersion,
      "url": $url,
      "sha256": $sha256
    }
""".trimIndent()

/** Asserts the parse fails and that the message names the offending field. */
private fun assertRejected(json: String, vararg expectedInMessage: String) {

    val failure = try {
        PatchManifestParser.parse(json)
        null
    } catch (e: PatchManifestException) {
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

class PatchManifestParserTest {

    @Test
    fun `parses a well formed manifest`() {

        val manifest = PatchManifestParser.parse(manifestJson())

        assertEquals(1, manifest.schemaVersion)
        assertEquals(4, manifest.patchVersion)
        assertEquals("1", manifest.runtimeVersion)
        assertEquals("https://example.test/patch.js", manifest.url)
        assertEquals(VALID_DIGEST, manifest.sha256)
        assertNull(manifest.signature)
    }

    @Test
    fun `reads an optional signature when present`() {

        val json = """
            {
              "schemaVersion": 1,
              "patchVersion": 4,
              "runtimeVersion": "1",
              "url": "https://example.test/patch.js",
              "sha256": "$VALID_DIGEST",
              "signature": "abc123"
            }
        """.trimIndent()

        assertEquals("abc123", PatchManifestParser.parse(json).signature)
    }

    // --- fail closed on the legacy schema -------------------------------------

    @Test
    fun `rejects the legacy manifest schema with actionable guidance`() {

        val legacy = """
            {
              "version": 3,
              "url": "https://example.test/patch.js",
              "sha256": "$VALID_DIGEST"
            }
        """.trimIndent()

        assertRejected(legacy, "Legacy manifest schema", "patchVersion", "runtimeVersion")
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
              "patchVersion": 4,
              "url": "https://example.test/patch.js",
              "sha256": "$VALID_DIGEST"
            }
        """.trimIndent()

        assertRejected(json, "runtimeVersion", "missing")
    }

    @Test
    fun `rejects a blank runtimeVersion`() {
        assertRejected(manifestJson(runtimeVersion = "\"  \""), "runtimeVersion", "blank")
    }

    @Test
    fun `rejects a quoted patchVersion`() {
        assertRejected(manifestJson(patchVersion = "\"4\""), "patchVersion", "number")
    }

    @Test
    fun `rejects a non positive patchVersion`() {
        assertRejected(manifestJson(patchVersion = "0"), "patchVersion", "positive")
    }

    @Test
    fun `rejects a non scalar field`() {
        assertRejected(manifestJson(runtimeVersion = "{}"), "runtimeVersion", "scalar")
    }

    // --- url and digest -------------------------------------------------------

    @Test
    fun `rejects a plaintext http url`() {
        assertRejected(manifestJson(url = "\"http://example.test/patch.js\""), "HTTPS")
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
    fun `normalises an uppercase digest so a valid patch is not refused`() {

        val manifest = PatchManifestParser.parse(
            manifestJson(sha256 = "\"${VALID_DIGEST.uppercase()}\"")
        )

        assertEquals(VALID_DIGEST, manifest.sha256)
    }
}
