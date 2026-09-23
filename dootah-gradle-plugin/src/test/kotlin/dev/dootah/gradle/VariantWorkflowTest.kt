package dev.dootah.gradle

import java.io.File
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * The whole workflow, registered against one variant of a real Gradle build.
 *
 * The variant list is handed in rather than produced by the Android plugin, so
 * these run without an Android SDK; what they prove is the part that used to be
 * wrong -- that every task hangs off the variant that was selected, and that a
 * module with more than one candidate is asked rather than skipped.
 */
class VariantWorkflowTest {

    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(compilations: List<String>, variants: List<Pair<String, String>>): File {
        val root = temporary.newFolder()
        root.resolve("settings.gradle").writeText("rootProject.name = 'variant-workflow'")
        root.resolve("src/main/kotlin/Sample.kt").apply {
            parentFile.mkdirs()
            writeText("fun answer(): Int = 42")
        }
        val declared = variants.joinToString(",\n                ") { (name, buildType) ->
            "new dev.dootah.gradle.DootahVariant('$name', '$buildType', null)"
        }
        root.resolve("build.gradle").writeText(
            """
            plugins { id 'org.jetbrains.kotlin.jvm'; id 'dev.dootah' apply false }
            repositories { mavenCentral() }
            extensions.create('dootah', dev.dootah.gradle.DootahExtension)
            dootah { discovery.set('auto'); failOnUnsupportedScreen.set(false); appId.set('example') }
            sourceSets { ${compilations.joinToString("; ") { "$it {}" }} }
            afterEvaluate {
                dev.dootah.gradle.DootahVariantsKt.registerDootahWorkflow(project, [
                $declared
                ])
            }
            tasks.register('verifyWiring') {
                doLast {
                    def record = tasks.named('dootahRecordContract').get()
                    println 'FRAGMENTS=' + record.fragmentsDirectory.get().asFile.path
                    println 'RECORD_DEPENDS=' + record.taskDependencies
                        .getDependencies(record).collect { it.name }.sort()
                    def extract = tasks.named('dootahExtract').get()
                    println 'EXTRACT_DEPENDS=' + extract.taskDependencies
                        .getDependencies(extract).collect { it.name }.sort()
                }
            }
            """.trimIndent()
        )
        return root
    }

    private fun runner(root: File, vararg arguments: String): GradleRunner =
        GradleRunner.create().withProjectDir(root)
            .withPluginClasspath(dootahPluginClasspath() + kotlinGradlePluginClasspath("dootah.extraction.kotlinPlugin"))
            .withArguments(*arguments, "--no-configuration-cache", "--stacktrace")

    private fun BuildResult.wiring(key: String): String =
        output.lineSequence().first { it.startsWith("$key=") }.substringAfter("=")

    @Test fun `a module with one debug variant is wired without being asked`() {
        val root = fixture(compilations = listOf("debug"), variants = listOf("debug" to "debug"))
        val result = runner(root, "verifyWiring").build()

        assertTrue(result.output, result.wiring("RECORD_DEPENDS").contains("compileDebugKotlin"))
        assertTrue(result.output, result.wiring("EXTRACT_DEPENDS").contains("compileDebugKotlin"))
        assertTrue(result.output, result.wiring("FRAGMENTS").endsWith("dootah/reports/debug/contract"))
    }

    @Test fun `flavours with one debug variant wire every task to that variant`() {
        val root = fixture(
            compilations = listOf("genericDebug", "genericRelease", "fdroidRelease"),
            variants = listOf("genericDebug" to "debug", "genericRelease" to "release", "fdroidRelease" to "release"),
        )
        val result = runner(root, "verifyWiring").build()

        // Extraction and the contract read the same compilation; nothing reaches
        // for another flavour's, which is what makes the contract describe one app.
        assertTrue(result.output, result.wiring("EXTRACT_DEPENDS").contains("compileGenericDebugKotlin"))
        assertTrue(result.output, result.wiring("RECORD_DEPENDS").contains("compileGenericDebugKotlin"))
        assertFalse(result.output, result.wiring("RECORD_DEPENDS").contains("compileFdroidReleaseKotlin"))
        assertTrue(result.output, result.wiring("FRAGMENTS").endsWith("dootah/reports/genericDebug/contract"))
    }

    @Test fun `several debug variants leave the workflow visible and say how to choose`() {
        val root = fixture(
            compilations = listOf("genericDebug", "fdroidDebug", "githubPreviewDebug"),
            variants = listOf(
                "genericDebug" to "debug",
                "fdroidDebug" to "debug",
                "githubPreviewDebug" to "debug",
            ),
        )
        val listed = runner(root, "tasks", "--group", "dootah", "--console=plain").build()
        assertTrue(listed.output, listed.output.contains("dootahRecordContract"))
        assertTrue(listed.output, listed.output.contains("dootahPublish"))

        val failure = runner(root, "dootahRecordContract").buildAndFail()
        assertTrue(failure.output, failure.output.contains("could not tell which variant"))
        assertTrue(failure.output, failure.output.contains("fdroidDebug"))
    }

    @Test fun `naming a variant resolves the choice for the whole workflow`() {
        val root = fixture(
            compilations = listOf("genericDebug", "fdroidDebug"),
            variants = listOf("genericDebug" to "debug", "fdroidDebug" to "debug"),
        )
        val result = runner(root, "verifyWiring", "-P$VARIANT_PROPERTY=fdroidDebug").build()

        assertTrue(result.output, result.wiring("EXTRACT_DEPENDS").contains("compileFdroidDebugKotlin"))
        assertTrue(result.output, result.wiring("RECORD_DEPENDS").contains("compileFdroidDebugKotlin"))
        assertTrue(result.output, result.wiring("FRAGMENTS").endsWith("dootah/reports/fdroidDebug/contract"))
    }

    @Test fun `a variant this module does not build is refused, not silently ignored`() {
        val root = fixture(
            compilations = listOf("genericDebug", "fdroidDebug"),
            variants = listOf("genericDebug" to "debug", "fdroidDebug" to "debug"),
        )
        val failure = runner(root, "dootahRecordContract", "-P$VARIANT_PROPERTY=genericdebug").buildAndFail()

        assertTrue(failure.output, failure.output.contains("which this module does not build"))
        assertTrue(failure.output, failure.output.contains("genericDebug"))
    }

    @Test fun `doctor readiness requires a compatible backend and baseline contract`() {
        val root = fixture(listOf("debug"), listOf("debug" to "debug"))
        val backend = root.resolve("backend.jar")
        java.util.zip.ZipOutputStream(backend.outputStream()).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("dev/dootah/backend-versions.txt"))
            zip.write(BUNDLE_KOTLIN_VERSION.toByteArray())
            zip.closeEntry()
        }
        root.resolve("build.gradle").appendText("""

            afterEvaluate {
                tasks.named('dootahDoctor') {
                    backendFiles.setFrom(files('backend.jar'))
                    composeDetected.set(true)
                    runtimeVersion.set('${dev.dootah.contract.RuntimeVersion.CURRENT}')
                }
            }
        """.trimIndent())
        val contract = root.resolve("dootah/contract.json").apply { parentFile.mkdirs() }
        fun baseline(runtime: String) = dev.dootah.contract.ContractJson.write(
            dev.dootah.contract.InstalledContract(runtimeVersion = runtime, screens = listOf(
                dev.dootah.contract.ScreenContract("example.Screen()", emptyList(), emptyList(), emptyList(), emptyList())
            )))
        contract.writeText(baseline(dev.dootah.contract.RuntimeVersion.CURRENT))
        val ready = runner(root, "dootahDoctor").build()
        assertTrue(ready.output, ready.output.contains("OTA readiness: READY"))
        assertFalse(ready.output, ready.tasks.any { it.path.contains("compile") })
        contract.writeText(baseline("incompatible"))
        val refused = runner(root, "dootahDoctor").build()
        assertTrue(refused.output, refused.output.contains("OTA readiness: NOT READY"))
        assertTrue(refused.output, refused.output.contains("different runtimeVersion"))
    }
}
