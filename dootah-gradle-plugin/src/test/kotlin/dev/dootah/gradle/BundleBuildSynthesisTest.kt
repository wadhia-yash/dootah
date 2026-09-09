package dev.dootah.gradle

import dev.dootah.gradle.internal.BUNDLE_MODULE_NAME
import dev.dootah.gradle.internal.GENERATED_SOURCES_PATH
import dev.dootah.gradle.internal.writeBundleBuild
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * The generated bundle build is where the bundle protocol is actually decided,
 * so the couplings that are easy to break silently are pinned here.
 */
class BundleBuildSynthesisTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    /**
     * The webpack output file and the UMD global both take their name from the
     * root project, and the installed app resolves the bundle through a global
     * of exactly this name. Letting it derive from a directory name is what
     * produced a bundle that loaded and then exported nothing findable.
     */
    @Test
    fun `pins the root project name to the module name the runtime expects`() {

        val generated = generate()

        assertTrue(
            generated.settings,
            generated.settings.contains("""rootProject.name = "$BUNDLE_MODULE_NAME""""),
        )
        assertEquals("dootah-bundle", BUNDLE_MODULE_NAME)
    }

    @Test
    fun `points the js source set at the generated sources only`() {

        val generated = generate()

        assertTrue(
            generated.buildScript,
            generated.buildScript.contains("""kotlin.setSrcDirs(listOf("$GENERATED_SOURCES_PATH"))"""),
        )
    }

    @Test
    fun `depends on the matching bundle runtime version`() {

        val generated = generate(dootahVersion = "9.9.9")

        assertTrue(
            generated.buildScript,
            generated.buildScript.contains("""implementation("dev.dootah:dootah-bundle-runtime:9.9.9")"""),
        )
    }

    @Test
    fun `builds an executable with the pinned kotlin version`() {

        val generated = generate(kotlinVersion = "2.3.20")

        assertTrue(
            generated.buildScript,
            generated.buildScript.contains("""kotlin("multiplatform") version "2.3.20""""),
        )
        assertTrue(generated.buildScript, generated.buildScript.contains("binaries.executable()"))
    }

    @Test
    fun `creates the directory generated sources are written to`() {

        val directory = temporaryFolder.newFolder("bundle-build")

        val generatedSources = writeBundleBuild(
            buildDirectory = directory,
            kotlinVersion = "2.3.20",
            dootahVersion = "0.1.0",
        )

        assertTrue(generatedSources.isDirectory)
        assertEquals(File(directory, GENERATED_SOURCES_PATH), generatedSources)
    }

    /**
     * Regenerated on every configuration, so it has to land on the same bytes
     * each time. A build script that churned would invalidate the bundle build
     * on every run and make the published bundle's digest unstable.
     */
    @Test
    fun `regenerating produces identical files`() {

        val directory = temporaryFolder.newFolder("stable")

        writeBundleBuild(directory, kotlinVersion = "2.3.20", dootahVersion = "0.1.0")
        val first = readGenerated(directory)

        writeBundleBuild(directory, kotlinVersion = "2.3.20", dootahVersion = "0.1.0")
        val second = readGenerated(directory)

        assertEquals(first.settings, second.settings)
        assertEquals(first.buildScript, second.buildScript)
    }

    private data class Generated(val settings: String, val buildScript: String)

    private fun generate(
        kotlinVersion: String = "2.3.20",
        dootahVersion: String = "0.1.0",
    ): Generated {

        val directory = temporaryFolder.newFolder(
            "generated-${kotlinVersion.filter { it.isLetterOrDigit() }}-" +
                dootahVersion.filter { it.isLetterOrDigit() }
        )

        writeBundleBuild(directory, kotlinVersion, dootahVersion)

        return readGenerated(directory)
    }

    private fun readGenerated(directory: File) = Generated(
        settings = File(directory, "settings.gradle.kts").readText(),
        buildScript = File(directory, "build.gradle.kts").readText(),
    )
}
