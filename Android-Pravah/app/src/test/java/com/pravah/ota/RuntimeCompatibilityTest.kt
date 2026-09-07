package com.pravah.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun testManifest(
    patchVersion: Int = 4,
    runtimeVersion: String = "1",
): PatchManifest = PatchManifest(
    schemaVersion = MANIFEST_SCHEMA_VERSION,
    patchVersion = patchVersion,
    runtimeVersion = runtimeVersion,
    url = "https://example.test/patch.js",
    sha256 = "ba409aa79ea36249d61490b613164cc202365ea5e796037f1ed8766ef54d62e4",
)

class RuntimeCompatibilityTest {

    @Test
    fun `accepts a patch targeting the supported runtime`() {

        assertTrue(
            isRuntimeCompatible(
                manifest = testManifest(runtimeVersion = "1"),
                supportedVersion = "1",
            )
        )
    }

    @Test
    fun `rejects a patch built for a newer runtime`() {

        assertFalse(
            isRuntimeCompatible(
                manifest = testManifest(runtimeVersion = "2"),
                supportedVersion = "1",
            )
        )
    }

    /**
     * The gate is an equality test, not a lower bound: a patch built for an
     * older runtime is not guaranteed to survive a breaking change either.
     */
    @Test
    fun `rejects a patch built for an older runtime`() {

        assertFalse(
            isRuntimeCompatible(
                manifest = testManifest(runtimeVersion = "1"),
                supportedVersion = "2",
            )
        )
    }

    @Test
    fun `treats the runtime version as an opaque token rather than a number`() {

        assertFalse(
            isRuntimeCompatible(
                manifest = testManifest(runtimeVersion = "01"),
                supportedVersion = "1",
            )
        )
    }

    @Test
    fun `defaults to the runtime version this build implements`() {

        assertTrue(
            isRuntimeCompatible(
                testManifest(runtimeVersion = PRAVAH_RUNTIME_VERSION)
            )
        )
    }

    @Test
    fun `declares runtime version 1`() {
        assertEquals("1", PRAVAH_RUNTIME_VERSION)
    }
}
