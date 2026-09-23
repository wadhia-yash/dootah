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
class CompilerBackendConsumerTest(private val version: String, private val compilerVersion: String) {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun `applying only dev dootah selects and runs the matching backend`() {
        // Both legal Kotlin/Dootah declaration orders must work.
        listOf(false, true).forEach { dootahFirst ->
            val root = temporary.newFolder()
            // The matrix loads many distinct KGP classloaders into the reused TestKit daemon.
            root.resolve("gradle.properties").writeText("org.gradle.jvmargs=-Xmx512m -XX:MaxMetaspaceSize=1024m")
            val repository = root.resolve("repo")
            val jars = System.getProperty("dootah.test.compiler.$compilerVersion").split(File.pathSeparator).map(::File)
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
            val declarations = mutableListOf("id 'org.jetbrains.kotlin.jvm'", "id 'dev.dootah'")
                .let { if (dootahFirst) it.reversed().toMutableList() else it }
            val compose = version == compilerVersion
            if (compose) declarations.add(
                if (!dootahFirst && selectCompilerBackend(compilerVersion).explicitOrdering) 1 else declarations.size,
                "id 'org.jetbrains.kotlin.plugin.compose'",
            )
            root.resolve("build.gradle").writeText("""
                plugins { ${declarations.joinToString("; ")} }
                ${if (version != compilerVersion) """
                    kotlin { compilerVersion.set('$compilerVersion') }
                    // KGP keeps its scripting plugin at the KGP version when overriding the compiler.
                    configurations.configureEach {
                        resolutionStrategy.eachDependency {
                            if (requested.group == 'org.jetbrains.kotlin' && requested.name.startsWith('kotlin-scripting-')) {
                                useVersion('$compilerVersion')
                            }
                        }
                    }
                """.trimIndent() else ""}
                repositories { maven { url = uri('repo') }; mavenCentral() }
                ${if (compose) "dependencies { implementation 'org.jetbrains.compose.runtime:runtime-desktop:1.7.3' }" else ""}
                // This JVM fixture has no Android renderer; Android resolution is tested by the real consumers.
                configurations.implementation.dependencies.removeAll { it.group == 'dev.dootah' }
                sourceSets { debug {} }
                tasks.register('verifyBackend') {
                    dependsOn('compileKotlin')
                    def selected = tasks.named('compileKotlin').map { it.pluginClasspath }
                    def extraction = configurations.named('dootahKotlinCompiler')
                    def bundle = configurations.named('dootahBundleCompiler')
                    def arguments = tasks.named('compileKotlin').flatMap { it.compilerOptions.freeCompilerArgs }
                    doLast {
                        assert selected.get().files.any { it.name == '${selectCompilerBackend(compilerVersion).artifactId}-$DOOTAH_VERSION.jar' }
                        assert extraction.get().files.any { it.name == 'kotlin-compiler-embeddable-$compilerVersion.jar' }
                        assert bundle.get().files.any { it.name == 'kotlin-compiler-embeddable-$BUNDLE_KOTLIN_VERSION.jar' }
                        assert arguments.get().contains('-Xcompiler-plugin-order=dev.dootah>androidx.compose.compiler.plugins.kotlin') == ${compose && selectCompilerBackend(compilerVersion).explicitOrdering}
                        println 'AUTOMATIC_BACKEND_OK $version'
                    }
                }
            """.trimIndent())
            val dootahClasspath = dootahPluginClasspath()
            val kgp = kotlinGradlePluginClasspath("dootah.test.kgp.$version")
            val result = GradleRunner.create().withProjectDir(root).withPluginClasspath(dootahClasspath + kgp)
                .withArguments("verifyBackend", "dootahDoctor", "--no-configuration-cache", "--stacktrace").build()
            assertTrue(result.output, result.output.contains("AUTOMATIC_BACKEND_OK $version"))
            assertTrue(result.output, result.output.contains("ABI compatibility: VERIFIED"))
            assertTrue(result.output, result.output.contains("Kotlin compiler: $compilerVersion"))
            assertTrue(result.output, result.output.contains("OTA readiness: NOT READY"))
        }
    }

    companion object {
        @JvmStatic @Parameterized.Parameters(name = "KGP {0}, compiler {1}")
        fun versions(): List<Array<String>> = compilerBackends.map { arrayOf(it.compilerVersion, it.compilerVersion) } +
            if (compilerBackends.any { it.compilerVersion == "2.4.20" }) listOf(arrayOf("2.4.20", "2.3.20")) else emptyList()
    }
}
