package com.pravah.ota

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateDecisionTest {

    @Test
    fun `downloads a newer compatible patch`() {

        val manifest = testManifest(patchVersion = 4, runtimeVersion = "1")

        val decision = decideUpdate(
            manifest = manifest,
            installedPatchVersion = 3,
            supportedRuntimeVersion = "1",
        )

        assertEquals(UpdateDecision.Download(manifest), decision)
    }

    @Test
    fun `reports up to date when the server offers the same version`() {

        val decision = decideUpdate(
            manifest = testManifest(patchVersion = 3),
            installedPatchVersion = 3,
            supportedRuntimeVersion = "1",
        )

        assertEquals(UpdateDecision.UpToDate, decision)
    }

    /** A replayed or rolled-back manifest must not walk the install backwards. */
    @Test
    fun `refuses to downgrade to an older patch version`() {

        val decision = decideUpdate(
            manifest = testManifest(patchVersion = 2),
            installedPatchVersion = 3,
            supportedRuntimeVersion = "1",
        )

        assertEquals(UpdateDecision.UpToDate, decision)
    }

    @Test
    fun `refuses a newer patch that targets a different runtime`() {

        val manifest = testManifest(patchVersion = 4, runtimeVersion = "2")

        val decision = decideUpdate(
            manifest = manifest,
            installedPatchVersion = 3,
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
     * Version is checked before compatibility, so a patch we would not have
     * installed anyway does not log a migration warning on every check.
     */
    @Test
    fun `reports up to date rather than incompatible for an old incompatible patch`() {

        val decision = decideUpdate(
            manifest = testManifest(patchVersion = 2, runtimeVersion = "2"),
            installedPatchVersion = 3,
            supportedRuntimeVersion = "1",
        )

        assertEquals(UpdateDecision.UpToDate, decision)
    }

    @Test
    fun `installs over the assumed bundled version on a fresh install`() {

        val manifest = testManifest(patchVersion = 3, runtimeVersion = "1")

        val decision = decideUpdate(
            manifest = manifest,
            installedPatchVersion = 1,
            supportedRuntimeVersion = "1",
        )

        assertEquals(UpdateDecision.Download(manifest), decision)
    }
}
