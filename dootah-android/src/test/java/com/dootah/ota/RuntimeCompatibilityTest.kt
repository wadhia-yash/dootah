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
                manifest = testManifest(runtimeVersion = "3"),
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
                supportedVersion = "3",
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
    fun `declares runtime version 5`() {
        assertEquals("5", DOOTAH_RUNTIME_VERSION)
    }

    @Test
    fun `refuses a bundle built for the previous runtime`() {

        // The version gate is the only thing standing between a runtime "1"
        // bundle -- single-screen, no arguments, no command envelope -- and an
        // app that would call it with three arguments and fail at render time.
        assertFalse(isRuntimeCompatible(testManifest(runtimeVersion = "1")))
    }

    /**
     * The case runtime "4" was bumped for, from the installed app's side.
     *
     * A "3" app handed a "4" bundle is the dangerous direction: the parser
     * ignores keys it does not know, so nothing would fail. Every layout would
     * simply be drawn with Compose's default alignment and arrangement, and the
     * screen would be subtly not the one the bundle described. The gate refuses
     * it before the bundle is ever loaded.
     */
    @Test
    fun `a runtime 3 app refuses a runtime 4 bundle`() {

        assertFalse(
            isRuntimeCompatible(
                manifest = testManifest(runtimeVersion = "4"),
                supportedVersion = "3",
            )
        )
    }

    /**
     * And the other direction, which fails for the opposite reason.
     *
     * A "4" app is not entitled to assume a "3" bundle is merely a "4" bundle
     * that mentions no alignment. The gate is equality, so this is refused
     * without anyone having to reason about which fields happen to overlap.
     */
    @Test
    fun `a runtime 4 app refuses a runtime 3 bundle`() {

        assertFalse(
            isRuntimeCompatible(
                manifest = testManifest(runtimeVersion = "3"),
                supportedVersion = "4",
            )
        )

        assertFalse(isRuntimeCompatible(testManifest(runtimeVersion = "3")))
    }
    /**
     * The case runtime "5" was bumped for.
     *
     * A "4" renderer reads every length as a number. Handed a "5" bundle that
     * names one instead, it has no table to look the name up in and nothing to
     * lay out with -- so the screen would draw, at zero. A collapsed layout is
     * the worst of the failures available here, because it looks deliberate.
     */
    @Test
    fun `a runtime 4 app refuses a runtime 5 bundle`() {

        assertFalse(
            isRuntimeCompatible(
                manifest = testManifest(runtimeVersion = "5"),
                supportedVersion = "4",
            )
        )
    }

    @Test
    fun `a runtime 5 app refuses a runtime 4 bundle`() {

        assertFalse(
            isRuntimeCompatible(
                manifest = testManifest(runtimeVersion = "4"),
                supportedVersion = "5",
            )
        )

        assertFalse(isRuntimeCompatible(testManifest(runtimeVersion = "4")))
    }

    /**
     * Every runtime this app has ever implemented is refused but its own.
     *
     * Written as a sweep rather than one case per version, so that a future
     * bump cannot quietly leave a predecessor accepted.
     */
    @Test
    fun `refuses every earlier runtime`() {

        listOf("1", "2", "3", "4").forEach { version ->
            assertFalse(version, isRuntimeCompatible(testManifest(runtimeVersion = version)))
        }

        assertTrue(isRuntimeCompatible(testManifest(runtimeVersion = DOOTAH_RUNTIME_VERSION)))
    }

}
