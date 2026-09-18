package dev.dootah.gradle.tasks

import dev.dootah.contract.SignedUpdate
import dev.dootah.contract.SignedImage
import dev.dootah.gradle.internal.PublisherSigning
import dev.dootah.contract.BundleImages
import dev.dootah.gradle.internal.BundleImagePackaging
import dev.dootah.gradle.internal.PackagedImage
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

    @get:org.gradle.api.tasks.InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val imageResources: ConfigurableFileCollection

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

    @get:Input
    abstract val appId: Property<String>

    @get:Input
    @get:org.gradle.api.tasks.Optional
    abstract val publishingServer: Property<String>

    init {
        // Never cache or skip signing based on an earlier key. Secret bytes are not task inputs.
        outputs.upToDateWhen { false }
        outputs.doNotCacheIf("Signing uses a private local key") { true }
    }

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @TaskAction
    fun assemble() {

        // Only the path enters the environment. Never log or put key bytes in Gradle inputs.
        val keyPath = System.getenv("DOOTAH_SIGNING_KEY_FILE")
            ?: throw GradleException("Set DOOTAH_SIGNING_KEY_FILE to an external PKCS#8 PEM file")
        val signingKey = File(keyPath).canonicalFile
        if (!signingKey.isFile || signingKey.toPath().startsWith(project.rootDir.canonicalFile.toPath())) {
            throw GradleException("Signing key must be an existing file outside the project")
        }
        require(appId.get().isNotBlank()) { "Dootah appId must not be blank" }
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

        val images = BundleImagePackaging.collect(sources, imageResources.files)
        val prepared = freshDirectory("image-sources")
        val imageSources = sources.mapIndexed { index, source ->
            File(prepared, "$index.kt").apply {
                writeText(BundleImagePackaging.rewrite(source.readText(), images))
            }
        }
        val libraries = bundleRuntimeClasspath.files.toList()

        // Separate directories on purpose. The linker clears its output
        // directory, so pointing both steps at one directory deletes the klib
        // the linker is reading from.
        val klibDirectory = freshDirectory("klib")
        val bundleDirectory = freshDirectory("bundle")

        compile(klibArguments(imageSources, libraries, klibDirectory), step = "klib")
        compile(
            linkArguments(compiledKlibFile(klibDirectory), libraries, bundleDirectory),
            step = "link",
        )

        publish(compiledBundleFile(bundleDirectory), images, signingKey)
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

    private fun publish(bundleFile: File, images: List<PackagedImage>, signingKey: File) {

        if (!bundleFile.isFile) {
            throw GradleException(
                "The Kotlin/JS compiler produced no bundle at ${bundleFile.absolutePath}."
            )
        }

        val payload = BundleImages.header(images.map { it.hash }).toByteArray() + bundleFile.readBytes()
        val digest = sha256Hex(payload)

        val update = SignedUpdate(
            1, appId.get(), runtimeVersion.get(), bundleVersion.get(), true,
            publishingServer.orNull?.let { "$it/artifacts/$digest" } ?: bundleUrl.get(), digest,
            images.distinctBy { it.hash }.map {
                SignedImage(it.hash, publishingServer.orNull?.let { server -> "$server/artifacts/${it.hash}" }
                    ?: BundleImagePackaging.imageUrl(bundleUrl.get(), it.hash), it.hash)
            },
        )
        val signedManifest = try {
            update.manifestJson(PublisherSigning.sign(update, signingKey))
        } catch (_: Exception) {
            // Key parser exceptions must never echo sensitive input.
            throw GradleException("Cannot sign update: expected a valid Ed25519 PKCS#8 PEM private key")
        }
        val output = outputDirectory.get().asFile.apply { mkdirs() }
        output.resolve(BUNDLE_FILE_NAME).writeBytes(payload)
        images.distinctBy { it.hash }.forEach { image ->
            output.resolve("images/${image.hash}").apply { parentFile.mkdirs() }.writeBytes(image.bytes)
        }
        output.resolve(MANIFEST_FILE_NAME).writeText(signedManifest)

        logger.lifecycle(
            "Dootah bundle ${bundleVersion.get()}: ${output.resolve(BUNDLE_FILE_NAME)} " +
                "(${payload.size} bytes, sha256 $digest)"
        )
    }

    private fun sha256Hex(payload: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(payload)
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val BUNDLE_FILE_NAME = "bundle.js"
        const val MANIFEST_FILE_NAME = "manifest.json"
    }
}
