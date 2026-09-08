package com.dootah.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun testManifest(
    bundleVersion: Int = 4,
    runtimeVersion: String = "1",
    enabled: Boolean = true,
): BundleManifest = BundleManifest(
    schemaVersion = MANIFEST_SCHEMA_VERSION,
    bundleVersion = bundleVersion,
    runtimeVersion = runtimeVersion,
    enabled = enabled,
    url = "https://example.test/bundle.js",
    sha256 = "ba409aa79ea36249d61490b613164cc202365ea5e796037f1ed8766ef54d62e4",
)

class RuntimeCompatibilityTest {

    @Test
    fun `accepts a bundle targeting the supported runtime`() {

        assertTrue(
            isRuntimeCompatible(
                manifest = testManifest(runtimeVersion = "1"),
                supportedVersion = "1",
            )
        )
    }

    @Test
    fun `rejects a bundle built for a newer runtime`() {

        assertFalse(
            isRuntimeCompatible(
                manifest = testManifest(runtimeVersion = "2"),
                supportedVersion = "1",
            )
        )
    }

    /**
     * The gate is an equality test, not a lower bound: a bundle built for an
     * older runtime is not guaranteed to survive a breaking change either.
     */
    @Test
    fun `rejects a bundle built for an older runtime`() {

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
                testManifest(runtimeVersion = DOOTAH_RUNTIME_VERSION)
            )
        )
    }

    @Test
    fun `declares runtime version 1`() {
        assertEquals("1", DOOTAH_RUNTIME_VERSION)
    }
}
