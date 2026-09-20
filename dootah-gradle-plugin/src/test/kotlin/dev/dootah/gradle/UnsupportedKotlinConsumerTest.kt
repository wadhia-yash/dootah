package dev.dootah.gradle

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * What a project on an unverified Kotlin version is told.
 *
 * Automatic selection can only route to a backend the matrix verified, so every
 * other Kotlin version has to end in a refusal a reader can act on -- a named
 * version, the list of versions that would work -- and it has to arrive while
 * the build is still configuring, not as a stack trace from inside a compiler
 * the plugin was never built against. This runs a real Kotlin Gradle plugin, so
 * it fails the same way a consumer's build would.
 */
class UnsupportedKotlinConsumerTest {

    @get:Rule val temporary = TemporaryFolder()

    @Test fun `an unverified Kotlin version is refused by name`() {
        val version = System.getProperty("dootah.test.unsupportedKotlin")
        assertTrue(
            "Kotlin $version is in the backend matrix now, so it no longer tests a refusal. " +
                "Point this test at a release that is still outside it.",
            compilerBackends.none { it.compilerVersion == version },
        )

        val root = temporary.newFolder()
        root.resolve("settings.gradle").writeText("rootProject.name = 'unsupported-kotlin'")
        root.resolve("build.gradle").writeText(
            """
            plugins { id 'org.jetbrains.kotlin.jvm'; id 'dev.dootah' }
            repositories { mavenCentral() }
            """.trimIndent()
        )

        val dootahClasspath = dootahPluginClasspath()
        val kgp = kotlinGradlePluginClasspath("dootah.test.kgp.$version")

        val result = GradleRunner.create().withProjectDir(root).withPluginClasspath(dootahClasspath + kgp)
            .withArguments("tasks", "--no-configuration-cache").buildAndFail()

        assertTrue(result.output, result.output.contains("no verified compiler backend for Kotlin $version"))
        compilerBackends.forEach {
            assertTrue(result.output, result.output.contains(it.compilerVersion))
        }
        // A refusal, not a crash: no compiler was ever loaded to crash in.
        assertTrue(result.output, !result.output.contains("Internal compiler error"))
    }
}
