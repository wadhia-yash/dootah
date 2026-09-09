package dev.dootah.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import javax.inject.Inject

/**
 * Runs Dootah's extraction pass over the host app's own sources.
 *
 * A separate compiler invocation rather than a hook on the app's compilation.
 * Kotlin's incremental compilation does not track files a plugin writes as a
 * side effect, so folding extraction into the app build would let a stale bundle
 * survive an incremental compile -- the failure mode being a published bundle
 * that silently disagrees with the source it came from.
 *
 * Its inputs are taken from the app's real Kotlin compilation, so extraction
 * resolves against exactly the classpath and sources the app itself compiles
 * with. Reconstructing an approximation of that classpath would let the two
 * disagree about what a name means.
 */
@CacheableTask
abstract class DootahExtractTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Classpath
    abstract val compileClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val compilerPluginClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val kotlinCompilerClasspath: ConfigurableFileCollection

    @get:Input
    abstract val jvmTarget: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun extract() {

        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        val throwawayClasses = temporaryDir.resolve("classes").apply {
            deleteRecursively()
            mkdirs()
        }

        val kotlinSources = sources.files
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            // Sorted so the same inputs always produce the same compilation.
            .sortedBy { it.absolutePath }

        if (kotlinSources.none { it.extension == "kt" }) {
            logger.lifecycle("Dootah: no Kotlin sources to extract from")
            return
        }

        val argumentFile = temporaryDir.resolve("dootah-extract-args.txt")
        argumentFile.writeText(
            buildCompilerArguments(
                sources = kotlinSources.map { it.absolutePath },
                classesOutput = throwawayClasses.absolutePath,
                reportDirectory = output.absolutePath,
            ).joinToString("\n")
        )

        execOperations.javaexec { spec ->
            // A separate JVM: the Kotlin CLI entry point terminates the process
            // when it finishes, which would take a Gradle worker with it.
            spec.classpath = kotlinCompilerClasspath
            spec.mainClass.set(KOTLIN_CLI_MAIN_CLASS)
            spec.args("@${argumentFile.absolutePath}")
        }
    }

    private fun buildCompilerArguments(
        sources: List<String>,
        classesOutput: String,
        reportDirectory: String,
    ): List<String> = buildList {

        add("-no-stdlib")
        add("-no-reflect")

        add("-jvm-target")
        add(jvmTarget.get())

        add("-classpath")
        add(compileClasspath.files.joinToString(java.io.File.pathSeparator) { it.absolutePath })

        add("-d")
        add(classesOutput)

        // Sorted only for determinism. Unlike the interception pass, order is
        // irrelevant here: extraction is a frontend checker, and every
        // registered checker runs regardless of plugin order.
        compilerPluginClasspath.files
            .sortedBy { it.absolutePath }
            .forEach { add("-Xplugin=${it.absolutePath}") }

        add("-P")
        add("plugin:dev.dootah:mode=extract")
        add("-P")
        add("plugin:dev.dootah:reportDir=$reportDirectory")

        addAll(sources)
    }

    private companion object {
        const val KOTLIN_CLI_MAIN_CLASS = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
    }
}
