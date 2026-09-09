package dev.dootah.gradle.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.security.MessageDigest

/**
 * Collects the compiled bundle and the digest a manifest has to declare.
 *
 * The digest is taken over the bytes exactly as written, which is the same rule
 * the installed app applies when it verifies a download. Computing it here
 * rather than asking a developer to run `shasum` removes the step most likely to
 * be done against the wrong file.
 */
abstract class DootahBundleTask : DefaultTask() {

    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val compiledBundle: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun assemble() {

        val payload = compiledBundle.get().asFile.readBytes()

        val output = outputDirectory.get().asFile.apply { mkdirs() }
        val bundle = output.resolve(BUNDLE_FILE_NAME)

        bundle.writeBytes(payload)

        val digest = sha256Hex(payload)
        output.resolve(DIGEST_FILE_NAME).writeText("$digest\n")

        logger.lifecycle(
            "Dootah bundle: ${bundle.absolutePath} (${payload.size} bytes, sha256 $digest)"
        )
    }

    private fun sha256Hex(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val BUNDLE_FILE_NAME = "bundle.js"
        const val DIGEST_FILE_NAME = "bundle.js.sha256"
    }
}
