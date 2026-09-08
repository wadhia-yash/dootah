package com.dootah.ota

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateDecisionTest {

    @Test
    fun `downloads a newer compatible bundle`() {

        val manifest = testManifest(bundleVersion = 4, runtimeVersion = "1")

        val decision = decideUpdate(
            manifest = manifest,
            installedBundleVersion = 3,
            supportedRuntimeVersion = "1",
        )

        assertEquals(UpdateDecision.Download(manifest), decision)
    }

    @Test
    fun `reports up to date when the server offers the same version`() {

        val decision = decideUpdate(
            manifest = testManifest(bundleVersion = 3),
            installedBundleVersion = 3,
            supportedRuntimeVersion = "1",
        )

        assertEquals(UpdateDecision.UpToDate, decision)
    }

    /** A replayed or rolled-back manifest must not walk the install backwards. */
    @Test
    fun `refuses to downgrade to an older bundle version`() {

        val decision = decideUpdate(
            manifest = testManifest(bundleVersion = 2),
            installedBundleVersion = 3,
            supportedRuntimeVersion = "1",
        )

        assertEquals(UpdateDecision.UpToDate, decision)
    }

    @Test
    fun `refuses a newer bundle that targets a different runtime`() {

        val manifest = testManifest(bundleVersion = 4, runtimeVersion = "2")

        val decision = decideUpdate(
            manifest = manifest,
            installedBundleVersion = 3,
            supportedRuntimeVersion = "1",
        )

        assertEquals(
            UpdateDecision.Incompatible(
                manifest = manifest,
                supportedRuntimeVersion = "1",
            ),
            decision,
        )
    }

    /**
     * Version is checked before compatibility, so a bundle we would not have
     * installed anyway does not log a migration warning on every check.
     */
    @Test
    fun `reports up to date rather than incompatible for an old incompatible bundle`() {

        val decision = decideUpdate(
            manifest = testManifest(bundleVersion = 2, runtimeVersion = "2"),
            installedBundleVersion = 3,
            supportedRuntimeVersion = "1",
        )

        assertEquals(UpdateDecision.UpToDate, decision)
    }

    // --- kill switch takes precedence over everything else -------------------

    @Test
    fun `refuses a newer compatible bundle when the kill switch is off`() {

        val manifest = testManifest(bundleVersion = 9, runtimeVersion = "1", enabled = false)

        val decision = decideUpdate(
            manifest = manifest,
            installedBundleVersion = 3,
            supportedRuntimeVersion = "1",
        )

        assertEquals(UpdateDecision.Disabled(manifest), decision)
    }

    /**
     * The switch is evaluated first, so being up to date or incompatible cannot
     * mask the fact that the publisher has turned Dootah off.
     */
    @Test
    fun `reports disabled even when there is nothing newer to install`() {

        val manifest = testManifest(bundleVersion = 3, enabled = false)

        val decision = decideUpdate(
            manifest = manifest,
            installedBundleVersion = 3,
            supportedRuntimeVersion = "1",
        )

        assertEquals(UpdateDecision.Disabled(manifest), decision)
    }

    @Test
    fun `reports disabled even when the bundle targets another runtime`() {

        val manifest = testManifest(bundleVersion = 9, runtimeVersion = "2", enabled = false)

        val decision = decideUpdate(
            manifest = manifest,
            installedBundleVersion = 3,
            supportedRuntimeVersion = "1",
        )

        assertEquals(UpdateDecision.Disabled(manifest), decision)
    }

    @Test
    fun `installs over the assumed asset version on a fresh install`() {

        val manifest = testManifest(bundleVersion = 3, runtimeVersion = "1")

        val decision = decideUpdate(
            manifest = manifest,
            installedBundleVersion = 1,
            supportedRuntimeVersion = "1",
        )

        assertEquals(UpdateDecision.Download(manifest), decision)
    }
}
