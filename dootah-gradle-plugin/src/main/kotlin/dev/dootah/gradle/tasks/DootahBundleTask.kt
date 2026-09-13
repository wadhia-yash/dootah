package dev.dootah.gradle.tasks

import dev.dootah.gradle.internal.compiledBundleFile
import dev.dootah.gradle.internal.compiledKlibFile
import dev.dootah.gradle.internal.klibArguments
import dev.dootah.gradle.internal.linkArguments
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Classpath
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.File
import java.security.MessageDigest
import javax.inject.Inject

/**
 * Compiles the generated bundle Kotlin and writes the artifacts to publish.
 *
 * Compilation lives here, in the same build as generation, because the two must
 * be ordered and Gradle cannot order a task in one build against a task in
 * another.
 *
 * The digest is taken over the bytes exactly as written, which is the rule the
 * installed app applies when it verifies a download. Computing it here rather
 * than asking a developer to run `shasum` removes the step most likely to be
 * done against the wrong file.
 */
abstract class DootahBundleTask @Inject constructor(
    private val execOperations: ExecOperations,
) : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val generatedSourceDirectory: DirectoryProperty

    @get:Classpath
    abstract val kotlinCompilerClasspath: ConfigurableFileCollection

    /** The Kotlin/JS libraries the bundle compiles against, as klibs. */
    @get:Classpath
    abstract val bundleRuntimeClasspath: ConfigurableFileCollection

    @get:Input
    abstract val runtimeVersion: Property<String>

    @get:Input
    abstract val bundleVersion: Property<Int>

    @get:Input
    abstract val bundleUrl: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun assemble() {

        val sources = generatedSourceDirectory.get().asFile
            .walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        if (sources.isEmpty()) {
            throw GradleException(
                "Dootah generated no bundle sources, so there is nothing to publish.\n" +
                    "Run dootahExtract first."
            )
        }

        val libraries = bundleRuntimeClasspath.files.toList()

        // Separate directories on purpose. The linker clears its output
        // directory, so pointing both steps at one directory deletes the klib
        // the linker is reading from.
        val klibDirectory = freshDirectory("klib")
        val bundleDirectory = freshDirectory("bundle")

        compile(klibArguments(sources, libraries, klibDirectory), step = "klib")
        compile(
            linkArguments(compiledKlibFile(klibDirectory), libraries, bundleDirectory),
            step = "link",
        )

        publish(compiledBundleFile(bundleDirectory))
    }

    private fun freshDirectory(name: String): File =
        temporaryDir.resolve(name).apply {
            deleteRecursively()
            mkdirs()
        }

    private fun compile(
        invocation: dev.dootah.gradle.internal.BundleCompilerInvocation,
        step: String,
    ) {

        // An argument file: a full library path list plus every source path
        // exceeds what a command line reliably accepts. One file per step keeps
        // a failed run's arguments readable.
        val argumentFile = temporaryDir.resolve("bundle-compiler-args-$step.txt")
        argumentFile.writeText(invocation.arguments.joinToString("\n"))

        execOperations.javaexec { spec ->
            // A separate JVM: the Kotlin CLI entry point terminates the process
            // when it finishes, which would take a Gradle worker with it.
            spec.classpath = kotlinCompilerClasspath
            spec.mainClass.set(invocation.mainClass)
            spec.args("@${argumentFile.absolutePath}")
        }
    }

    private fun publish(bundleFile: File) {

        if (!bundleFile.isFile) {
            throw GradleException(
                "The Kotlin/JS compiler produced no bundle at ${bundleFile.absolutePath}."
            )
        }

        val payload = bundleFile.readBytes()
        val digest = sha256Hex(payload)

        val output = outputDirectory.get().asFile.apply { mkdirs() }
        output.resolve(BUNDLE_FILE_NAME).writeBytes(payload)
        output.resolve(MANIFEST_FILE_NAME).writeText(manifest(digest))

        logger.lifecycle(
            "Dootah bundle ${bundleVersion.get()}: ${output.resolve(BUNDLE_FILE_NAME)} " +
                "(${payload.size} bytes, sha256 $digest)"
        )
    }

    /**
     * The manifest schema the installed app already validates. Every field is
     * required there, so every field is written here.
     */
    private fun manifest(digest: String): String = """
        {
          "schemaVersion": 1,
          "bundleVersion": ${bundleVersion.get()},
          "runtimeVersion": "${runtimeVersion.get()}",
          "enabled": true,
          "url": "${bundleUrl.get()}",
          "sha256": "$digest"
        }
    """.trimIndent() + "\n"

    private fun sha256Hex(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val BUNDLE_FILE_NAME = "bundle.js"
        const val MANIFEST_FILE_NAME = "manifest.json"
    }
}
