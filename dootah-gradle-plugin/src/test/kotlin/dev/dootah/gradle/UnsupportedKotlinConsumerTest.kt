package dev.dootah.gradle

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Real KGP consumer: an unknown compiler remains usable for native builds.
 * Explicit OTA operations refuse it before loading any Dootah compiler code.
 */
class UnsupportedKotlinConsumerTest {

    @get:Rule val temporary = TemporaryFolder()

    @Test fun `unverified Kotlin builds natively but explicit OTA is refused by name`() {
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
            configurations.implementation.dependencies.removeAll { it.group == 'dev.dootah' }
            tasks.register('verifyNative') {
                dependsOn('compileKotlin')
                def selected = tasks.named('compileKotlin').map { it.pluginClasspath }
                doLast { assert !selected.get().files.any { it.name.startsWith('dootah-compiler') } }
            }
            """.trimIndent()
        )

        val dootahClasspath = dootahPluginClasspath()
        val kgp = kotlinGradlePluginClasspath("dootah.test.kgp.$version")

        root.resolve("src/main/kotlin/Native.kt").apply { parentFile.mkdirs(); writeText("fun answer() = 42") }
        fun runner(vararg tasks: String) = GradleRunner.create().withProjectDir(root).withPluginClasspath(dootahClasspath + kgp)
            .withArguments(*tasks, "--no-configuration-cache")
        runner("verifyNative").build()
        val doctor = runner("dootahDoctor").build()
        assertTrue(doctor.output, doctor.output.contains("OTA readiness: NOT READY"))
        val result = runner("dootahExtract").buildAndFail()

        assertTrue(result.output, result.output.contains("Detected Kotlin $version"))
        assertTrue(result.output, result.output.contains("No verified Dootah compiler ABI is available"))
        compilerBackends.forEach {
            assertTrue(result.output, result.output.contains(it.compilerVersion))
        }
        // A refusal, not a crash: no compiler was ever loaded to crash in.
        assertTrue(result.output, !result.output.contains("Internal compiler error"))
    }
}
