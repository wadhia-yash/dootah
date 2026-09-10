package dev.dootah.gradle.internal

import java.io.File

/**
 * The UMD global the Android runtime resolves the bundle through, and the base
 * name of the file produced.
 *
 * Must match `BUNDLE_MODULE_NAME` in the installed runtime. Changing one without
 * the other breaks the bundle protocol silently: the bundle loads and then
 * exports nothing the runtime can find.
 */
const val BUNDLE_MODULE_NAME: String = "dootah-bundle"

private const val KOTLIN_JS_CLI_MAIN_CLASS = "org.jetbrains.kotlin.cli.js.K2JSCompiler"

/**
 * The two Kotlin/JS compiler invocations that turn generated Kotlin into a
 * bundle.
 *
 * Two steps because the K2 compiler refuses to produce a klib and link
 * JavaScript in one pass: sources become a klib, then the klib is linked into a
 * single JavaScript file.
 *
 * This runs inside the host build rather than delegating to a separate
 * Kotlin/JS Gradle build. Generation and compilation have to be ordered, and
 * Gradle cannot order a task in one build against a task in another -- an
 * included build's compilation could, and did, run before the sources it was
 * meant to compile existed.
 */
internal data class BundleCompilerInvocation(
    val mainClass: String,
    val arguments: List<String>,
)

internal fun klibArguments(
    generatedSources: List<File>,
    libraries: List<File>,
    outputDirectory: File,
): BundleCompilerInvocation = BundleCompilerInvocation(
    mainClass = KOTLIN_JS_CLI_MAIN_CLASS,
    arguments = buildList {
        add("-Xir-produce-klib-file")
        add("-libraries")
        add(libraries.joinToString(File.pathSeparator) { it.absolutePath })
        add("-ir-output-dir")
        add(outputDirectory.absolutePath)
        add("-ir-output-name")
        add(BUNDLE_MODULE_NAME)
        // Sorted so the same sources always produce the same klib.
        addAll(generatedSources.map { it.absolutePath }.sorted())
    },
)

internal fun linkArguments(
    klib: File,
    libraries: List<File>,
    outputDirectory: File,
): BundleCompilerInvocation = BundleCompilerInvocation(
    mainClass = KOTLIN_JS_CLI_MAIN_CLASS,
    arguments = buildList {
        add("-Xir-produce-js")
        add("-Xinclude=${klib.absolutePath}")
        add("-libraries")
        add(libraries.joinToString(File.pathSeparator) { it.absolutePath })
        add("-ir-output-dir")
        add(outputDirectory.absolutePath)
        add("-ir-output-name")
        add(BUNDLE_MODULE_NAME)

        // UMD so the bundle installs itself on the global object. The Android
        // isolate has no module system, so the wrapper takes its global branch,
        // which is where the runtime looks.
        add("-module-kind")
        add("umd")

        // The bundle is loaded and called by the runtime; it has no entry point
        // of its own to run.
        add("-main")
        add("noCall")

        // Without dead code elimination the whole Kotlin standard library ships
        // in every bundle, which is a tenfold size increase on an artifact
        // downloaded over mobile networks.
        add("-Xir-dce")
        add("-Xir-minimized-member-names")
        add("-Xoptimize-generated-js")
    },
)

/** The compiled bundle, named after the module the runtime expects. */
internal fun compiledBundleFile(outputDirectory: File): File =
    File(outputDirectory, "$BUNDLE_MODULE_NAME.js")

internal fun compiledKlibFile(outputDirectory: File): File =
    File(outputDirectory, "$BUNDLE_MODULE_NAME.klib")
