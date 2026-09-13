package dev.dootah.gradle.coverage

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import javax.inject.Inject

/**
 * Runs Dootah's analysis pass over an app that knows nothing about Dootah.
 *
 * The same pass that produces a bundle, with nowhere to generate into. It walks
 * every Compose function in the module and records what Dootah made of it, which
 * is the whole measurement.
 *
 * Failures are expected and are data. An app Dootah cannot read at all is a
 * result worth reporting, not a reason to stop -- so the compiler's exit code is
 * recorded rather than thrown, and the report says how much of the module was
 * analysable.
 */
abstract class DootahCoverageTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:Classpath
    abstract val compileClasspath: ConfigurableFileCollection

    @get:Classpath
    abstract val kotlinCompilerClasspath: ConfigurableFileCollection

    /** The Compose compiler plugin, so the analysis sees what a real build sees. */
    @get:Classpath
    abstract val composePluginClasspath: ConfigurableFileCollection

    /** Dootah's own plugin jars, as a path list from the init script. */
    @get:Input
    abstract val dootahPluginJars: Property<String>

    @get:Input
    abstract val moduleName: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun measure() {

        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        val kotlinSources = sources.files
            .filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.absolutePath }

        if (kotlinSources.isEmpty()) {
            logger.lifecycle("Dootah coverage: ${moduleName.get()} has no Kotlin sources")
            return
        }

        val jars = dootahPluginJars.get().split(File.pathSeparator).filter { it.isNotBlank() }
        require(jars.isNotEmpty()) {
            "Dootah coverage needs -Pdootah.plugin.jars=<compiler-plugin.jar>:<contract.jar>"
        }

        val argumentFile = temporaryDir.resolve("dootah-coverage-args.txt")
        argumentFile.writeText(
            arguments(kotlinSources, jars, output).joinToString("\n")
        )

        val result = execOperations.javaexec { spec ->
            spec.classpath = kotlinCompilerClasspath
            spec.mainClass.set(KOTLIN_CLI_MAIN_CLASS)
            spec.args("@${argumentFile.absolutePath}")
            // An app Dootah cannot fully resolve still yields records for every
            // file it did resolve, and that partial answer is the honest one.
            spec.isIgnoreExitValue = true
        }

        output.resolve("module.properties").writeText(
            listOf(
                "module=${moduleName.get()}",
                "kotlinFiles=${kotlinSources.size}",
                "sourceLines=${kotlinSources.sumOf { file ->
                    runCatching { file.readLines().size }.getOrDefault(0)
                }}",
                "analysisExitCode=${result.exitValue}",
            ).joinToString("\n", postfix = "\n")
        )

        logger.lifecycle(
            "Dootah coverage: ${moduleName.get()} -- ${kotlinSources.size} Kotlin files, " +
                "analysis exit ${result.exitValue}"
        )
    }

    private fun arguments(
        kotlinSources: List<File>,
        jars: List<String>,
        output: File,
    ): List<String> = buildList {

        add("-no-stdlib")
        add("-no-reflect")

        add("-jvm-target")
        add(JVM_TARGET)

        add("-classpath")
        add(compileClasspath.files.joinToString(File.pathSeparator) { it.absolutePath })

        add("-d")
        add(temporaryDir.resolve("classes").absolutePath)

        // Compose first, Dootah second. Order is irrelevant to an analysis pass
        // -- every registered frontend checker runs regardless -- and is fixed
        // only so that two runs over the same app produce the same arguments.
        composePluginClasspath.files
            .sortedBy { it.absolutePath }
            .forEach { add("-Xplugin=${it.absolutePath}") }

        jars.sorted().forEach { add("-Xplugin=$it") }

        // `extract` with no generatedDir: the analysis runs and records what it
        // found, and nothing is written that anyone could mistake for a bundle.
        add("-P")
        add("plugin:dev.dootah:mode=extract")
        add("-P")
        add("plugin:dev.dootah:reportDir=${output.absolutePath}")
        add("-P")
        add("plugin:dev.dootah:discovery=auto")
        add("-P")
        add("plugin:dev.dootah:filter=")

        addAll(kotlinSources.map { it.absolutePath })
    }

    private companion object {
        const val KOTLIN_CLI_MAIN_CLASS = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"

        /** Every app in the corpus targets this or lower. */
        const val JVM_TARGET = "11"
    }
}
