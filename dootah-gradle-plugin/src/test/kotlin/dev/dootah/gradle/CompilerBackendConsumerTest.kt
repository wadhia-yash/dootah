package dev.dootah.gradle

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/** Real isolated KGP consumers prove application order, automatic selection and compiler loading. */
@RunWith(Parameterized::class)
class CompilerBackendConsumerTest(private val version: String) {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `applying only dev dootah selects and runs the matching backend`() {
        // Both legal Kotlin/Dootah declaration orders must work.
        listOf(false, true).forEach { dootahFirst ->
            val root = temporary.newFolder()
            val repository = root.resolve("repo")
            val jars = System.getProperty("dootah.test.compiler.$version").split(File.pathSeparator).map(::File)
                .filter { it.name.startsWith("dootah-") }
            jars.forEach { jar ->
                val artifact = jar.name.removeSuffix("-$DOOTAH_VERSION.jar")
                val directory = repository.resolve("dev/dootah/$artifact/$DOOTAH_VERSION").apply { mkdirs() }
                jar.copyTo(directory.resolve(jar.name))
                val dependencies = when {
                    artifact.startsWith("dootah-compiler-plugin") -> listOf("dootah-compiler-core", "dootah-contract")
                    artifact == "dootah-compiler-core" -> listOf("dootah-contract")
                    else -> emptyList()
                }
                directory.resolve("$artifact-$DOOTAH_VERSION.pom").writeText("""
                    <project><modelVersion>4.0.0</modelVersion><groupId>dev.dootah</groupId>
                    <artifactId>$artifact</artifactId><version>$DOOTAH_VERSION</version><dependencies>
                    ${dependencies.joinToString("") { "<dependency><groupId>dev.dootah</groupId><artifactId>$it</artifactId><version>$DOOTAH_VERSION</version></dependency>" }}
                    </dependencies></project>
                """.trimIndent())
            }
            root.resolve("settings.gradle").writeText("rootProject.name = 'automatic-backend'")
            root.resolve("src/main/kotlin/Sample.kt").apply {
                parentFile.mkdirs(); writeText("fun answer(): Int = 42")
            }
            val declarations = listOf("id 'org.jetbrains.kotlin.jvm'", "id 'dev.dootah'")
                .let { if (dootahFirst) it.reversed() else it }
            root.resolve("build.gradle").writeText("""
                plugins { ${declarations.joinToString("; ")} }
                repositories { maven { url = uri('repo') }; mavenCentral() }
                // This JVM fixture has no Android renderer; Android resolution is tested by the real consumers.
                configurations.implementation.dependencies.removeAll { it.group == 'dev.dootah' }
                sourceSets { debug {} }
                tasks.register('verifyBackend') {
                    dependsOn('compileKotlin')
                    def selected = tasks.named('compileKotlin').map { it.pluginClasspath }
                    def extraction = configurations.named('dootahKotlinCompiler')
                    def bundle = configurations.named('dootahBundleCompiler')
                    doLast {
                        assert selected.get().files.any { it.name == '${selectCompilerBackend(version).artifactId}-$DOOTAH_VERSION.jar' }
                        assert extraction.get().files.any { it.name == 'kotlin-compiler-embeddable-$version.jar' }
                        assert bundle.get().files.any { it.name == 'kotlin-compiler-embeddable-$BUNDLE_KOTLIN_VERSION.jar' }
                        println 'AUTOMATIC_BACKEND_OK $version'
                    }
                }
            """.trimIndent())
            val dootahClasspath = dootahPluginClasspath()
            val kgp = kotlinGradlePluginClasspath("dootah.test.kgp.$version")
            val result = GradleRunner.create().withProjectDir(root).withPluginClasspath(dootahClasspath + kgp)
                .withArguments("verifyBackend", "--no-configuration-cache", "--stacktrace").build()
            assertTrue(result.output, result.output.contains("AUTOMATIC_BACKEND_OK $version"))
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "Kotlin {0}")
        fun versions(): List<Array<String>> = compilerBackends.map { arrayOf(it.compilerVersion) }
    }
}
