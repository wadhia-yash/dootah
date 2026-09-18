package dev.dootah.gradle.tasks

import dev.dootah.gradle.internal.describeRejections
import dev.dootah.gradle.internal.readDiscovery
import dev.dootah.gradle.internal.readLoweredScreens
import dev.dootah.gradle.internal.readRejectedConstructs
import dev.dootah.gradle.internal.summarise
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
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

    /** `auto` or `annotated`, matching what the app's own compilation was given. */
    @get:Input
    abstract val discovery: Property<String>

    /** The encoded include/exclude patterns, identical to the app's own. */
    @get:Input
    abstract val screenFilter: Property<String>

    /** Whether a screen Dootah could not describe fails the build. */
    @get:Input
    abstract val failOnUnsupportedScreen: Property<Boolean>

    /** Diagnostics and per-screen metadata. */
    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    /**
     * Generated bundle Kotlin.
     *
     * This is the bundle build's source directory, so extraction writes straight
     * into what the bundle compilation reads. Copying it afterwards would add a
     * step whose only job is to be forgotten.
     */
    @get:OutputDirectory
    abstract val generatedSourceDirectory: DirectoryProperty

    @TaskAction
    fun extract() {

        val output = outputDirectory.get().asFile
        output.deleteRecursively()
        output.mkdirs()

        // Cleared so a screen that is excluded, removed, or stops being
        // supported, cannot leave last run's generated source behind to be
        // compiled into the next bundle.
        val generated = generatedSourceDirectory.get().asFile
        generated.deleteRecursively()
        generated.mkdirs()

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
                generatedDirectory = generated.absolutePath,
            ).joinToString("\n")
        )

        execOperations.javaexec { spec ->
            // A separate JVM: the Kotlin CLI entry point terminates the process
            // when it finishes, which would take a Gradle worker with it.
            spec.classpath = kotlinCompilerClasspath
            spec.mainClass.set(KOTLIN_CLI_MAIN_CLASS)
            spec.args("@${argumentFile.absolutePath}")
        }

        reportOutcome(output)
    }

    /**
     * Turns what the compiler recorded into a build result.
     *
     * Dootah now looks at every Compose function in the module rather than at
     * the handful someone annotated, so most of what it reports is not a
     * problem: a screen it cannot describe is left out of the bundle and the app
     * renders the native body it always had. Failing the build over each one
     * would make an ordinary app impossible to build.
     *
     * Rejections fail the build when failOnUnsupportedScreen is enabled. A module
     * where nothing could be described also fails because it has nothing to publish.
     */
    private fun reportOutcome(reportDirectory: java.io.File) {

        val discovered = readDiscovery(reportDirectory)
        val rejections = readRejectedConstructs(reportDirectory)
        val screens = readLoweredScreens(reportDirectory)

        logger.lifecycle(summarise(discovered, screens))

        if (rejections.isNotEmpty() && failOn()) {
            throw GradleException(describeRejections(rejections))
        }

        if (rejections.isNotEmpty()) {
            // A warning rather than a failure, but still said out loud: these
            // are the screens an update cannot reach, and finding that out by
            // publishing one and watching nothing happen is a bad afternoon.
            logger.warn(describeRejections(rejections))
        }

        if (screens.isEmpty()) {
            throw GradleException(
                "Dootah could not describe any of this module's Compose functions, " +
                    "so there is nothing to publish.\n" +
                    "Run with --info to see what it found, or narrow Dootah to the " +
                    "part of the app you are updating with dootah { include(...) }."
            )
        }

        screens.forEach { screen ->
            // Said out loud because it is the difference between "my change did
            // not publish" and "that part of the screen is not part of what
            // publishes".
            if (screen.nativeComponents.isNotEmpty()) {
                logger.info(
                    "Dootah ${screen.screenId} kept native: " +
                        screen.nativeComponents.joinToString(", ")
                )
            }
        }
    }

    private fun failOn(): Boolean = failOnUnsupportedScreen.getOrElse(false)

    private fun buildCompilerArguments(
        sources: List<String>,
        classesOutput: String,
        reportDirectory: String,
        generatedDirectory: String,
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
        add("-P")
        add("plugin:dev.dootah:generatedDir=$generatedDirectory")
        add("-P")
        add("plugin:dev.dootah:discovery=${discovery.get()}")
        add("-P")
        add("plugin:dev.dootah:filter=${screenFilter.get()}")

        addAll(sources)
    }

    private companion object {
        const val KOTLIN_CLI_MAIN_CLASS = "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler"
    }
}
