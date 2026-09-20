package dev.dootah.gradle

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** A separate consumer build: no compiled app classes can mask missing source inputs. */
class ExtractionInputsTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun `extraction resolves Java and generated sources with the real compile classpath`() {
        val root = temporary.root
        fun write(path: String, text: String) {
            root.resolve(path).apply { parentFile.mkdirs(); writeText(text.trimIndent()) }
        }
        write("settings.gradle", "rootProject.name = 'external-extraction'")
        write("src/main/java/example/model/JavaModel.java", """
            package example.model;
            public class JavaModel { public String getLabel() { return "local Java"; } }
        """)
        write("src/main/kotlin/example/model/KotlinModel.kt", """
            package example.model
            class KotlinModel(val label: String)
        """)
        write("src/main/kotlin/androidx/compose/runtime/Composable.kt", """
            package androidx.compose.runtime
            @Target(AnnotationTarget.FUNCTION, AnnotationTarget.TYPE)
            annotation class Composable
        """)
        write("src/main/kotlin/androidx/compose/material3/Text.kt", """
            package androidx.compose.material3
            import androidx.compose.runtime.Composable
            @Composable fun Text(text: String) {}
        """)
        write("src/main/kotlin/example/ui/Screen.kt", """
            package example.ui
            import androidx.compose.runtime.Composable
            import androidx.compose.material3.Text
            import example.model.JavaModel
            import example.model.KotlinModel
            import generated.GeneratedJava
            import generated.GeneratedKotlin
            import org.apache.commons.lang3.StringUtils
            fun businessLogic(): String = StringUtils.trim(
                JavaModel().label + KotlinModel("Kotlin").label +
                    GeneratedJava().label + GeneratedKotlin().label
            )
            @Composable fun Screen() { Text("External source graph") }
        """)
        // Custom output locations deliberately differ from KSP's conventional paths.
        // Like KSP/AGP, the producer registers sources and an optional classes directory.
        write("build.gradle", """
            import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
            import org.jetbrains.kotlin.gradle.dsl.JvmTarget
            plugins { id 'org.jetbrains.kotlin.jvm'; id 'dev.dootah' apply false }
            repositories { mavenCentral() }
            extensions.create('dootah', dev.dootah.gradle.DootahExtension)
            dootah { discovery.set('auto'); failOnUnsupportedScreen.set(false) }
            sourceSets { debug {} }
            dependencies { implementation 'org.apache.commons:commons-lang3:3.17.0' }
            abstract class GenerateModels extends DefaultTask {
                @OutputDirectory abstract DirectoryProperty getOutputDirectory()
                @TaskAction void generate() {
                    def dir = outputDirectory.get().asFile
                    new File(dir, 'java/generated').mkdirs()
                    new File(dir, 'kotlin/generated').mkdirs()
                    new File(dir, 'java/generated/GeneratedJava.java').text =
                        'package generated; public class GeneratedJava { public String getLabel() { return "generated Java"; } }'
                    new File(dir, 'kotlin/generated/GeneratedKotlin.kt').text =
                        'package generated; class GeneratedKotlin(val label: String = "generated Kotlin")'
                }
            }
            def generated = tasks.register('generateModels', GenerateModels) {
                outputDirectory.set(layout.buildDirectory.dir('custom-processor-output'))
            }
            tasks.named('compileDebugKotlin', KotlinCompile) {
                source(fileTree('src/main'), generated.flatMap { it.outputDirectory })
                libraries.from(configurations.compileClasspath)
                libraries.from(generated.flatMap { it.outputDirectory.dir('optional-classes') })
                destinationDirectory.set(layout.buildDirectory.dir('fixture-classes'))
                compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
                compilerOptions.moduleName.set('external-extraction')
            }
            dev.dootah.gradle.DootahExtractWiringKt.registerExtractTask(project, 'compileDebugKotlin',
                layout.buildDirectory.dir('dootah/generated/jsMain/kotlin').get())
            afterEvaluate {
                tasks.named('dootahExtract') {
                    compilerPluginClasspath.from(files(providers.gradleProperty('testCompiler').get().split(File.pathSeparator)))
                }
            }
        """)
        val classpath = dootahPluginClasspath() + kotlinGradlePluginClasspath("dootah.extraction.kotlinPlugin")
        val result = GradleRunner.create().withProjectDir(root).withPluginClasspath(classpath)
            .withArguments("dootahExtract", "--no-configuration-cache", "--stacktrace",
                "-PtestCompiler=${System.getProperty("dootah.extraction.compiler")}")
            .build()
        assertEquals(result.output, TaskOutcome.SUCCESS, result.task(":generateModels")?.outcome)
        assertEquals(result.output, TaskOutcome.SUCCESS, result.task(":compileDebugKotlin")?.outcome)
        assertEquals(result.output, TaskOutcome.SUCCESS, result.task(":dootahExtract")?.outcome)
        assertFalse(result.output, result.output.contains("classpath entry points to a non-existent location"))
        assertTrue(result.output, root.resolve("build/dootah/generated/jsMain/kotlin")
            .walkTopDown().any { it.isFile && it.readText().contains("External source graph") })
    }
}
