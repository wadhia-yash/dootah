package dev.dootah.gradle

import java.io.File
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * A real Android module, asked what it builds through the Android plugin's own variant API.
 *
 * The case the task-name check could never see: flavours put the build type in
 * the middle of the name, so `compileDebugKotlin` does not exist and Dootah used
 * to register nothing at all and say nothing about it.
 */
class AndroidVariantDiscoveryTest {

    @get:Rule val temporary = TemporaryFolder()

    private fun androidSdk(): File? =
        System.getenv("ANDROID_HOME")?.let(::File)
            ?: System.getenv("ANDROID_SDK_ROOT")?.let(::File)
            ?: File(System.getProperty("user.home"), "Library/Android/sdk").takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"), "Android/Sdk").takeIf { it.isDirectory }

    private fun fixture(sdk: File, flavours: String, dootahBlock: String): File {
        val root = temporary.newFolder()
        root.resolve("local.properties").writeText("sdk.dir=${sdk.absolutePath}")
        root.resolve("settings.gradle").writeText(
            """
            pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
            dependencyResolutionManagement { repositories { google(); mavenCentral() } }
            rootProject.name = 'android-variants'
            """.trimIndent()
        )
        root.resolve("src/main/kotlin/example/Screen.kt").apply {
            parentFile.mkdirs()
            writeText("package example\n\nfun screen(): String = \"ok\"")
        }
        root.resolve("build.gradle").writeText(
            """
            plugins { id 'com.android.application' }
            android {
                namespace = 'example'
                compileSdk { version = release(36) }
                defaultConfig { minSdk = 26 }
                $flavours
            }
            // The workflow itself is wired in VariantWorkflowTest; what this fixture
            // proves is the answer the Android plugin gives about what it builds.
            extensions.create('dootah', dev.dootah.gradle.DootahExtension)
            dootah { $dootahBlock }
            def discovered = []
            dev.dootah.gradle.DootahAndroidVariantsKt.collectAndroidVariants(project, discovered)
            afterEvaluate {
                println 'DISCOVERED=' + discovered.collect { it.name + ':' + it.buildType }.sort()
                println 'COMPILE_TASKS=' + discovered.collect {
                    it.name + '->' + dev.dootah.gradle.DootahVariantsKt.kotlinCompileTaskName(project, it.name)
                }.sort()
                try {
                    def selected = dev.dootah.gradle.DootahVariantsKt.selectDootahVariant(discovered,
                        dev.dootah.gradle.DootahVariantsKt.requestedVariant(project, dootah))
                    println 'SELECTED=' + selected.name
                } catch (Exception failure) {
                    println 'REFUSED=' + failure.message.replaceAll('\\n', ' | ')
                }
            }
            """.trimIndent()
        )
        return root
    }

    private fun runner(root: File, vararg arguments: String) =
        GradleRunner.create().withProjectDir(root)
            .withPluginClasspath(
                dootahPluginClasspath() +
                    kotlinGradlePluginClasspath("dootah.test.kgp.$BUNDLE_KOTLIN_VERSION") +
                    kotlinGradlePluginClasspath("dootah.test.agp")
            )
            .withArguments(*arguments, "--no-configuration-cache", "--stacktrace")

    @Test fun `an unflavoured module is wired to its debug variant, as it always was`() {
        val sdk = androidSdk()
        assumeTrue("No Android SDK on this machine", sdk != null)

        val root = fixture(sdk!!, flavours = "", dootahBlock = "appId = 'example'")
        val result = runner(root, "help").build()

        assertTrue(result.output, result.output.contains("DISCOVERED=[debug:debug, release:release]"))
        assertTrue(result.output, result.output.contains("debug->compileDebugKotlin"))
        assertTrue(result.output, result.output.contains("SELECTED=debug"))
    }

    @Test fun `a flavoured module registers the workflow against the variant it was given`() {
        val sdk = androidSdk()
        assumeTrue("No Android SDK on this machine", sdk != null)

        val root = fixture(
            sdk!!,
            flavours = """
                flavorDimensions += 'publishChannel'
                productFlavors {
                    generic { dimension = 'publishChannel' }
                    fdroid { dimension = 'publishChannel' }
                }
            """.trimIndent(),
            dootahBlock = "appId = 'example'; variant = 'genericDebug'",
        )
        val result = runner(root, "help").build()

        // The build type is in the middle of the name, so there is no
        // compileDebugKotlin here at all -- which is the case that used to
        // leave the module with no Dootah tasks and no explanation.
        assertTrue(result.output, result.output.contains("genericDebug:debug"))
        assertTrue(result.output, result.output.contains("fdroidDebug:debug"))
        assertTrue(result.output, result.output.contains("genericDebug->compileGenericDebugKotlin"))
        assertTrue(result.output, result.output.contains("SELECTED=genericDebug"))
    }

    @Test fun `a flavoured module with no variant named says which ones it builds`() {
        val sdk = androidSdk()
        assumeTrue("No Android SDK on this machine", sdk != null)

        val root = fixture(
            sdk!!,
            flavours = """
                flavorDimensions += 'publishChannel'
                productFlavors {
                    generic { dimension = 'publishChannel' }
                    fdroid { dimension = 'publishChannel' }
                }
            """.trimIndent(),
            dootahBlock = "appId = 'example'",
        )
        val result = runner(root, "help").build()

        assertTrue(result.output, result.output.contains("REFUSED="))
        assertTrue(result.output, result.output.contains("could not tell which variant"))
        assertTrue(result.output, result.output.contains("genericDebug"))
        assertTrue(result.output, result.output.contains("fdroidDebug"))
    }
}
