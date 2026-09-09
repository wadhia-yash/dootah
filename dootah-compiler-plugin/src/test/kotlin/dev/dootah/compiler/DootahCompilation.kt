package dev.dootah.compiler

import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.common.arguments.K2JVMCompilerArguments
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSeverity
import org.jetbrains.kotlin.cli.common.messages.CompilerMessageSourceLocation
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import org.jetbrains.kotlin.config.Services
import java.io.File

/** One source file in a test fixture. */
data class SourceFile(val name: String, val contents: String)

/** What a fixture compilation produced. */
class CompilationResult(
    val exitCode: ExitCode,
    val messages: List<String>,
    private val reportDirectory: File,
) {

    val succeeded: Boolean get() = exitCode == ExitCode.OK

    /** The ordering guard's report, or null when the plugin wrote none. */
    fun orderingReport(): String? =
        File(reportDirectory, "dootah-ordering.txt")
            .takeIf { it.exists() }
            ?.readText()

    fun messagesContaining(fragment: String): List<String> =
        messages.filter { it.contains(fragment) }
}

/**
 * Runs the real Kotlin compiler over [sources] with the Dootah plugin attached.
 *
 * A real compilation rather than a mocked one: the whole point of these tests is
 * that the plugin behaves correctly inside the compiler, which a hand-built IR
 * fixture cannot establish.
 */
fun compileWithDootah(
    workingDirectory: File,
    sources: List<SourceFile>,
    mode: String = "intercept",
    extraPluginClasspath: List<File> = emptyList(),
    dootahFirst: Boolean = true,
): CompilationResult {

    val sourceDirectory = File(workingDirectory, "src").apply { mkdirs() }
    val outputDirectory = File(workingDirectory, "out").apply { mkdirs() }
    val reportDirectory = File(workingDirectory, "reports").apply { mkdirs() }

    val sourceFiles = (composeStubs() + sources).map { source ->
        File(sourceDirectory, source.name).apply {
            parentFile.mkdirs()
            writeText(source.contents)
        }
    }

    // Plugin order on the command line is the order the compiler registers
    // extensions in, which is exactly what these tests need to control.
    val dootahJar = requiredJar("dootah.plugin.jar")
    val pluginClasspath =
        if (dootahFirst) listOf(dootahJar) + extraPluginClasspath
        else extraPluginClasspath + listOf(dootahJar)

    val arguments = K2JVMCompilerArguments().apply {
        freeArgs = sourceFiles.map { it.absolutePath }
        destination = outputDirectory.absolutePath
        classpath = testCompileClasspath()
        noStdlib = true
        noReflect = true
        moduleName = "dootah-fixture"
        pluginClasspaths = pluginClasspath.map { it.absolutePath }.toTypedArray()
        pluginOptions = arrayOf(
            "plugin:dev.dootah:mode=$mode",
            "plugin:dev.dootah:reportDir=${reportDirectory.absolutePath}",
        )
    }

    val collected = mutableListOf<String>()

    val collector = object : MessageCollector {

        private var sawError = false

        override fun clear() = Unit

        override fun hasErrors(): Boolean = sawError

        override fun report(
            severity: CompilerMessageSeverity,
            message: String,
            location: CompilerMessageSourceLocation?,
        ) {
            if (severity.isError) sawError = true
            collected += "$severity: $message"
        }
    }

    val exitCode = K2JVMCompiler().exec(collector, Services.EMPTY, arguments)

    return CompilationResult(
        exitCode = exitCode,
        messages = collected.toList(),
        reportDirectory = reportDirectory,
    )
}

/**
 * Minimal stand-ins for the Compose declarations the plugin reasons about.
 *
 * Stubs rather than the real artifacts so these tests stay independent of a
 * Compose release: the plugin identifies both by fully qualified name, which is
 * precisely what is being tested.
 */
private fun composeStubs(): List<SourceFile> = listOf(
    SourceFile(
        name = "ComposeStubs.kt",
        contents = """
            package androidx.compose.runtime

            @Target(
                AnnotationTarget.FUNCTION,
                AnnotationTarget.TYPE,
                AnnotationTarget.TYPE_PARAMETER,
                AnnotationTarget.PROPERTY_GETTER,
            )
            annotation class Composable

            interface Composer
        """.trimIndent(),
    ),
)

private fun requiredJar(property: String): File {

    val path = System.getProperty(property)
        ?: error(
            "System property '$property' is not set. The Gradle test task supplies " +
                "it; running these tests outside Gradle is not supported."
        )

    return File(path).also {
        require(it.exists()) { "Jar named by '$property' does not exist: $path" }
    }
}

/**
 * The fixture's compile classpath.
 *
 * Reuses the test runtime classpath so the fixtures see the same kotlin-stdlib
 * and the real `@Bundlable` annotation this module already depends on.
 */
private fun testCompileClasspath(): String =
    System.getProperty("java.class.path")
